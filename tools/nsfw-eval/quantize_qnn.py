"""Builds the NPU (Qualcomm HTP) version of a cut NudeNet model: fixed square input, QDQ-quantised for QNN.

The Snapdragon 7 Gen 3's HTP rejects every float op even with fp16 precision requested (QNN error 3110 on Conv2d,
Sigmoid, Mul, Concat), so the NPU needs a quantised model: 16-bit activations (uint16) and 8-bit per-channel
symmetric weights ("A16W8", ORT's get_qnn_qdq_config), min/max calibrated. Input is pinned to [1, 3, S, S]: the app
pads photos into that square on the NPU anyway, and the HTP wants static shapes.

The CPU and GPU keep the float model. Accuracy is checked here against that float model on photos left out of the
calibration (same square input).

usage: python -I quantize_qnn.py cut.onnx out_qdq.onnx --size 640 --calib <dir>... --holdout <dir>...
"""
import argparse
import os
import sys
import tempfile
import time

import cv2
import numpy as np
import onnx
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
from onnxruntime.tools.onnx_model_utils import fix_output_shapes, make_input_shape_fixed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))     # python -I leaves the script's folder out
from compare import NUDENET_LABELS, REG_MAX, app_decode, decode_heads, iou, list_images, session


def square_blob(mat_bgr, size):
    """The NPU path of YoloDetector: long side -> size, top-left in a size x size black square, RGB / 255."""
    h0, w0 = mat_bgr.shape[:2]
    scale = size / max(h0, w0)
    w1, h1 = max(1, round(w0 * scale)), max(1, round(h0 * scale))
    canvas = np.zeros((size, size, 3), np.float32)
    canvas[:h1, :w1] = cv2.resize(mat_bgr, (w1, h1), interpolation=cv2.INTER_LINEAR)[:, :, ::-1] / 255.0
    return canvas.transpose(2, 0, 1)[None].copy(), w1, h1


class Reader(CalibrationDataReader):
    def __init__(self, paths, size, input_name):
        self.paths, self.size, self.name, self.i = paths, size, input_name, 0

    def get_next(self):
        while self.i < len(self.paths):
            mat = cv2.imread(self.paths[self.i]); self.i += 1
            if mat is not None:
                return {self.name: square_blob(mat, self.size)[0]}
        return None

    def rewind(self):
        self.i = 0


def detections(sess, mat, size):
    blob, w1, h1 = square_blob(mat, size)
    maps = sess.run(None, {sess.get_inputs()[0].name: blob})
    out = decode_heads(maps, maps[0].shape[1] - 4 * REG_MAX, size)[None]
    return app_decode(out, out.shape[1] - 4, w1, h1, mat.shape[1], mat.shape[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cut")
    ap.add_argument("out")
    ap.add_argument("--size", type=int, required=True)
    ap.add_argument("--calib", nargs="+", required=True)
    ap.add_argument("--holdout", nargs="+", required=True)
    ap.add_argument("--threshold", type=float, default=0.45)
    a = ap.parse_args()

    with tempfile.TemporaryDirectory() as tmp:          # quant tools leave files next to their inputs
        m = onnx.load(a.cut)
        make_input_shape_fixed(m.graph, "images", [1, 3, a.size, a.size])
        fix_output_shapes(m)
        fixed = os.path.join(tmp, "fixed.onnx"); onnx.save(m, fixed)
        pre = os.path.join(tmp, "pre.onnx")
        if not qnn_preprocess_model(fixed, pre):
            pre = fixed
        calib = list(list_images(a.calib))
        t = time.time()
        cfg = get_qnn_qdq_config(
            pre, Reader(calib, a.size, "images"),
            activation_type=QuantType.QUInt16, weight_type=QuantType.QInt8, per_channel=True, weight_symmetric=True,
        )
        # (no CalibMaxIntermediateOutputs: in ORT 1.22 it discards the data before computing ranges. Not needed either:
        # MinMax keeps only a ReduceMin / ReduceMax per tensor and photo.)
        cfg.calibration_data_reader = Reader(calib, a.size, "images")  # the config step may have read the first one
        quantize(pre, a.out, cfg)
        print(f"quantised with {len(calib)} calibration photos in {time.time() - t:.0f} s -> {os.path.getsize(a.out) / 1e6:.1f} MB")

    ref, q = session(a.cut), session(a.out)
    hold = list(list_images(a.holdout))
    ref_n = q_n = matched = same = 0
    diffs = []
    for p in hold:
        mat = cv2.imread(p)
        if mat is None:
            continue
        r = [d for d in detections(ref, mat, a.size) if d[1] >= a.threshold]
        g = [d for d in detections(q, mat, a.size) if d[1] >= a.threshold]
        ref_n += len(r); q_n += len(g)
        used = set()
        for d in r:
            best, bi = 0.0, -1
            for j, e in enumerate(g):
                if j not in used and e[0] == d[0] and iou(d[2], e[2]) > best:
                    best, bi = iou(d[2], e[2]), j
            if best >= 0.7:
                used.add(bi); matched += 1; diffs.append(abs(d[1] - g[bi][1]))
        cr = {}; cg = {}
        for d in r: cr[d[0]] = cr.get(d[0], 0) + 1
        for d in g: cg[d[0]] = cg.get(d[0], 0) + 1
        same += cr == cg
        if os.environ.get("VERBOSE") and cr != cg:
            print(" ", os.path.basename(p), {NUDENET_LABELS[k]: v for k, v in cr.items()}, "->", {NUDENET_LABELS[k]: v for k, v in cg.items()})
    print(f"held-out {len(hold)} photos, >= {a.threshold}: float {ref_n}, quantised {q_n}, matched {matched} "
          f"(same label, IoU >= 0.7); identical counts on {same}/{len(hold)}; score |diff| mean {np.mean(diffs) if diffs else 0:.4f} max {max(diffs) if diffs else 0:.4f}")


if __name__ == "__main__":
    main()
