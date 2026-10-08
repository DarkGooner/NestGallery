"""Exports an EraX-NSFW-V1.0 YOLO11 checkpoint (https://huggingface.co/erax-ai/EraX-NSFW-V1.0, Apache-2.0) to ONNX
with dynamic height/width, like NudeNet's exports, so the app can feed aspect-ratio-sized inputs.

usage: python -I export_erax.py erax_nsfw_yolo11n.pt [--imgsz 640]
Needs: torch (CPU is fine), ultralytics, onnx, onnxslim. Writes <name>.onnx next to the checkpoint.
The .pt files are pickles; check them first (they only reference torch / ultralytics / builtins.set).
"""
import argparse

from ultralytics import YOLO


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights")
    ap.add_argument("--imgsz", type=int, default=640)
    a = ap.parse_args()
    model = YOLO(a.weights)
    print("classes:", model.names)
    # dynamic=True: batch, height and width are symbolic. simplify folds constants (onnxslim). opset 17 runs on
    # onnxruntime-android 1.22.
    out = model.export(format="onnx", imgsz=a.imgsz, dynamic=True, simplify=True, opset=17, half=False)
    print("wrote", out)


if __name__ == "__main__":
    main()
