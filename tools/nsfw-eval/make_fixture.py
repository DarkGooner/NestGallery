"""Writes the golden fixture for app/src/test/.../NsfwMathTest.kt: the raw NudeNet output for a few photos plus the
detections compare.py's port of the app pipeline makes from it. The Kotlin decoder must reproduce them exactly.

usage: python -I make_fixture.py <nudenet_320n.onnx> <out_dir> <image>...
Fixture format (little endian): per image: int32 anchors, int32 rows, int32 contentW, int32 contentH, int32 width,
int32 height, float32[rows*anchors] output; expected.txt: one line per detection "image label score x y w h".
"""
import os
import struct
import sys

import cv2

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))     # python -I leaves the script's folder out
from compare import NUDENET_LABELS, app_decode, app_preprocess, session


def main():
    model, out_dir, images = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out_dir, exist_ok=True)
    sess = session(model)
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
