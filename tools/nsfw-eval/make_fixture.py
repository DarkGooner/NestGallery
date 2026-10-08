"""Writes the golden fixtures for app/src/test/.../NsfwMathTest.kt from a few photos, with compare.py's port of the
app pipeline (checked against nudenet.py). The Kotlin decoders must reproduce the detections exactly.

usage: python -I make_fixture.py <full nudenet_320n.onnx> <cut nudenet_320n.onnx> <out_dir> <image>...
nudenet_outputs.bin (little endian), from the full model: per image: int32 anchors, int32 rows, int32 contentW,
int32 contentH, int32 width, int32 height, float32[rows*anchors]; expected.txt: "image label score x y w h" lines.
nudenet_heads.bin, from the cut model (split_head.py): int32 images, per image: int32 contentW, contentH, width,
height, canvasH, then 3 x (int32 channels, h, w, float32[channels*h*w]); expected_heads.txt like expected.txt.
"""
import os
import struct
import sys

import cv2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))     # python -I leaves the script's folder out
from compare import NUDENET_LABELS, REG_MAX, app_decode, app_preprocess, decode_heads, session


def main():
    model, cut_model, out_dir, images = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:]
    os.makedirs(out_dir, exist_ok=True)
    sess = session(model)
    cut = session(cut_model)
    with open(os.path.join(out_dir, "nudenet_heads.bin"), "wb") as b, open(os.path.join(out_dir, "expected_heads.txt"), "w") as t:
        b.write(struct.pack("<i", len(images)))
        for k, p in enumerate(images):
            mat = cv2.imread(p)
            blob, w1, h1 = app_preprocess(mat, 320)
            maps = cut.run(None, {"images": blob})
            b.write(struct.pack("<5i", w1, h1, mat.shape[1], mat.shape[0], blob.shape[2]))
            for m in maps:
                b.write(struct.pack("<3i", m.shape[1], m.shape[2], m.shape[3]))
                b.write(m[0].astype("<f4").tobytes())
            out = decode_heads(maps, maps[0].shape[1] - 4 * REG_MAX, blob.shape[2])[None]
            for c, s, (x, y, w, h) in app_decode(out, out.shape[1] - 4, w1, h1, mat.shape[1], mat.shape[0]):
                xi, yi = round(x), round(y)
                t.write(f"{k} {NUDENET_LABELS[c]} {s:.6f} {xi} {yi} {round(x + w) - xi} {round(y + h) - yi}\n")
    with open(os.path.join(out_dir, "nudenet_outputs.bin"), "wb") as b, open(os.path.join(out_dir, "expected.txt"), "w") as t:
        b.write(struct.pack("<i", len(images)))
        for k, p in enumerate(images):
            mat = cv2.imread(p)
            blob, w1, h1 = app_preprocess(mat, 320)
            out = sess.run(None, {sess.get_inputs()[0].name: blob})[0]          # [1, 22, anchors]
            rows, anchors = out.shape[1], out.shape[2]
            b.write(struct.pack("<6i", anchors, rows, w1, h1, mat.shape[1], mat.shape[0]))
            b.write(out[0].astype("<f4").tobytes())
            for c, s, (x, y, w, h) in app_decode(out, rows - 4, w1, h1, mat.shape[1], mat.shape[0]):
                xi, yi = round(x), round(y)
                t.write(f"{k} {NUDENET_LABELS[c]} {s:.6f} {xi} {yi} {round(x + w) - xi} {round(y + h) - yi}\n")
    print("wrote", out_dir)


if __name__ == "__main__":
    main()
