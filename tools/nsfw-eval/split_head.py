"""Cuts a NudeNet (Ultralytics YOLOv8) ONNX model before its box decoding, for the Snapdragon NPU / GPU.

QNN rejects the decoding tail of the Detect head on this hardware (HTP: the Slice / Add / Sub / Div / Concat / Mul /
Sigmoid of dist2bbox; GPU: the DFL Softmax), and a partly-supported graph either fails or hits an ONNX Runtime layout
bug. So the app's models end at the three per-scale head outputs (strides 8, 16, 32), each [1, 4*16 + classes, H/s,
W/s] = box distance logits (4 sides x 16 bins, side-major) + class logits, and the app decodes them itself
(YoloHeadDecoder in NsfwMath.kt): softmax over the 16 bins, expected distance, anchor centre (x + 0.5, y + 0.5) -/+
distance, times the stride; sigmoid on the classes. That is Ultralytics' DFL + dist2bbox + sigmoid.

This script writes the cut model and checks that decoding it gives the full model's output.

usage: python -I split_head.py full.onnx cut.onnx <image or folder>... [--size 320]
"""
import argparse
import os
import sys

import cv2
import numpy as np
import onnx
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))     # python -I leaves the script's folder out
from compare import REG_MAX, app_preprocess, decode_heads, list_images, session

HEAD_OUTPUTS = ["/model.22/Concat_output_0", "/model.22/Concat_1_output_0", "/model.22/Concat_2_output_0"]


def cut(src, dst):
    model = onnx.load(src)
    names = {o for n in model.graph.node for o in n.output}
    missing = [o for o in HEAD_OUTPUTS if o not in names]
    if missing:
        raise SystemExit(f"not an Ultralytics YOLOv8 detect head: {missing} not found")
    e = onnx.utils.Extractor(model)
    sub = e.extract_model([i.name for i in model.graph.input], HEAD_OUTPUTS)
    # keep the export metadata (class names etc.) and say what changed
    del sub.metadata_props[:]
    for p in model.metadata_props:
        sub.metadata_props.add(key=p.key, value=p.value)
    sub.metadata_props.add(key="nestgallery", value="cut before box decoding: outputs = per-stride head maps (strides 8, 16, 32)")
    onnx.checker.check_model(sub)
    onnx.save(sub, dst)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("full")
    ap.add_argument("cut")
    ap.add_argument("images", nargs="+")
    ap.add_argument("--size", type=int, default=320)
    a = ap.parse_args()
    cut(a.full, a.cut)
    full, part = session(a.full), session(a.cut)
    worst_box = worst_cls = 0.0
    n = 0
    for p in list_images(a.images):
        mat = cv2.imread(p)
        if mat is None:
            continue
        blob, _, _ = app_preprocess(mat, a.size)
        ref = full.run(None, {"images": blob})[0][0]                                 # [4+nc, A]
        maps = part.run(None, {"images": blob})
        got = decode_heads(maps, ref.shape[0] - 4, blob.shape[2])
        worst_box = max(worst_box, float(np.abs(got[:4] - ref[:4]).max()))
        worst_cls = max(worst_cls, float(np.abs(got[4:] - ref[4:]).max()))
        n += 1
    print(f"{os.path.basename(a.cut)}: {os.path.getsize(a.cut) / 1e6:.1f} MB, outputs {[o.name for o in part.get_outputs()]}")
    print(f"{n} images: decoded vs full model max |diff| boxes {worst_box:.5f} px, scores {worst_cls:.7f}")


if __name__ == "__main__":
    main()
