"""Checks the app's NSFW detector pipeline against NudeNet's reference code, and times the candidate models.

The app (data/nsfw/NsfwDetector.kt + NsfwMath.kt) does NOT pad every photo to a square like nudenet.py: it scales the
long side to the model size and pads the short side only up to a multiple of 32 (the model has dynamic H/W, and this
is what Ultralytics' own predict() does), which costs up to ~45% less for 16:9 photos. It also runs NMS per class
instead of across classes, so a male and a female region that overlap (common in explicit photos) are both counted.
This script runs both on the same images and reports how close they are.

usage: python -I compare.py <nudenet_model.onnx> <image or folder>... [--size 320] [--erax erax.onnx --erax-size 640]
"""
import argparse
import math
import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort

NUDENET_LABELS = [
    "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED", "BELLY_COVERED",
    "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED",
    "ANUS_COVERED", "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED",
]
CANDIDATE_FLOOR = 0.2   # nudenet.py: rows below this never enter NMS
NMS_SCORE = 0.25        # nudenet.py: NMSBoxes score threshold (= the lowest score the app stores)
NMS_IOU = 0.45


def session(path, threads=1):
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.inter_op_num_threads = 1
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])


# ---- reference: nudenet 3.4.2 _read_image / _postprocess, verbatim maths ------------------------------------------

def reference(sess, mat, size):
    h0, w0 = mat.shape[:2]
    m = max(h0, w0)
    pad = cv2.copyMakeBorder(mat, 0, m - h0, 0, m - w0, cv2.BORDER_CONSTANT)
    blob = cv2.dnn.blobFromImage(pad, 1 / 255.0, (size, size), (0, 0, 0), swapRB=True, crop=False)
    out = sess.run(None, {sess.get_inputs()[0].name: blob})[0]
    rows = np.transpose(np.squeeze(out))
    boxes, scores, ids = [], [], []
    for r in rows:
        cs = r[4:]
        s = float(np.amax(cs))
        if s < CANDIDATE_FLOOR:
            continue
        x, y, w, h = r[:4]
        x, y = x - w / 2, y - h / 2
        x, y, w, h = (v * m / size for v in (x, y, w, h))
        x = max(0, min(x, w0)); y = max(0, min(y, h0))
        w = min(w, w0 - x); h = min(h, h0 - y)
        boxes.append([x, y, w, h]); scores.append(s); ids.append(int(np.argmax(cs)))
    keep = cv2.dnn.NMSBoxes(boxes, scores, NMS_SCORE, NMS_IOU)
    return [(ids[i], scores[i], boxes[i]) for i in np.array(keep).flatten()]


# ---- the app's pipeline (keep in step with NsfwDetector.kt / YoloDecoder in NsfwMath.kt) ------------------------------

def app_preprocess(mat_bgr, size):
    h0, w0 = mat_bgr.shape[:2]
    scale = size / max(h0, w0)
    w1, h1 = max(1, round(w0 * scale)), max(1, round(h0 * scale))
    small = cv2.resize(mat_bgr, (w1, h1), interpolation=cv2.INTER_LINEAR)
    cw, ch = (w1 + 31) // 32 * 32, (h1 + 31) // 32 * 32
    canvas = np.zeros((ch, cw, 3), np.float32)
    canvas[:h1, :w1] = small[:, :, ::-1] / 255.0
    return canvas.transpose(2, 0, 1)[None], w1, h1


def nms_group(label):
    """Keep in step with NsfwLabels.nmsGroup: mutually exclusive variants of one region suppress each other."""
    if label.startswith("FACE_"):
        return "FACE"
    if label.endswith(("_BREAST_EXPOSED", "_BREAST_COVERED")):
        return "BREAST"
    for suffix in ("_EXPOSED", "_COVERED"):
        if label.endswith(suffix):
            return label[: -len(suffix)]
    return label


def iou(a, b):
    ax2, ay2, bx2, by2 = a[0] + a[2], a[1] + a[3], b[0] + b[2], b[1] + b[3]
    iw = max(0.0, min(ax2, bx2) - max(a[0], b[0])); ih = max(0.0, min(ay2, by2) - max(a[1], b[1]))
    inter = iw * ih
    u = a[2] * a[3] + b[2] * b[3] - inter
    return 0.0 if u <= 0 else inter / u


def app_decode(out, nc, w1, h1, w0, h0, labels=NUDENET_LABELS):
    rows = np.transpose(np.squeeze(out, 0))           # [N, 4+nc]
    groups = [nms_group(l) for l in labels[:nc]] if labels and len(labels) == nc else list(range(nc))
    fx, fy = w0 / w1, h0 / h1
    cands = []
    for r in rows:
        cs = r[4:4 + nc]
        c = int(np.argmax(cs)); s = float(cs[c])
        if s < NMS_SCORE:
            continue
        cx, cy, w, h = (float(v) for v in r[:4])
        x1 = min(max((cx - w / 2) * fx, 0.0), w0); y1 = min(max((cy - h / 2) * fy, 0.0), h0)
        x2 = min(max((cx + w / 2) * fx, 0.0), w0); y2 = min(max((cy + h / 2) * fy, 0.0), h0)
        if x2 - x1 < 1 or y2 - y1 < 1:
            continue
        cands.append((c, s, [x1, y1, x2 - x1, y2 - y1]))
    cands.sort(key=lambda t: -t[1])
    keep = []
    for d in cands:
        if all(not (groups[k[0]] == groups[d[0]] and iou(k[2], d[2]) > NMS_IOU) for k in keep):
            keep.append(d)
    return keep


def app_detect(sess, mat, size, nc, labels=NUDENET_LABELS):
    blob, w1, h1 = app_preprocess(mat, size)
    out = sess.run(None, {sess.get_inputs()[0].name: blob})[0]
    return app_decode(out, nc, w1, h1, mat.shape[1], mat.shape[0], labels)


# ---- comparison ----------------------------------------------------------------------------------------------------

def list_images(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in sorted(files):
                    if f.lower().endswith((".jpg", ".jpeg", ".png", ".webp", ".bmp")):
                        yield os.path.join(root, f)
        else:
            yield p


def counts(dets, threshold):
    c = {}
    for cls, s, _ in dets:
        if s >= threshold:
            c[cls] = c.get(cls, 0) + 1
    return c


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("images", nargs="+")
    ap.add_argument("--size", type=int, default=320)
    ap.add_argument("--threshold", type=float, default=0.45, help="the app's default counting threshold")
    ap.add_argument("--erax", help="optional EraX YOLO11 ONNX, timed on the same images")
    ap.add_argument("--erax-size", type=int, default=640)
    ap.add_argument("--extra", nargs="*", default=[], help="more model.onnx:size pairs to time only")
    a = ap.parse_args()

    sess = session(a.model)
    imgs = [p for p in list_images(a.images)]
    matched = ref_total = app_total = 0
    same_counts = 0
    score_diffs = []
    t_ref = t_app = 0.0
    for p in imgs:
        mat = cv2.imread(p)
        if mat is None:
            continue
        t = time.perf_counter(); ref = reference(sess, mat, a.size); t_ref += time.perf_counter() - t
        t = time.perf_counter(); app = app_detect(sess, mat, a.size, len(NUDENET_LABELS)); t_app += time.perf_counter() - t
        ref_hi = [d for d in ref if d[1] >= a.threshold]; app_hi = [d for d in app if d[1] >= a.threshold]
        ref_total += len(ref_hi); app_total += len(app_hi)
        used = set()
        for r in ref_hi:
            best, bi = 0.0, -1
            for j, d in enumerate(app_hi):
                if j in used or d[0] != r[0]:
                    continue
                v = iou(r[2], d[2])
                if v > best:
                    best, bi = v, j
            if best >= 0.7:
                used.add(bi); matched += 1; score_diffs.append(abs(r[1] - app_hi[bi][1]))
        same_counts += counts(ref, a.threshold) == counts(app, a.threshold)
        if os.environ.get("VERBOSE"):
            name = os.path.basename(p)
            print(name, "ref", {NUDENET_LABELS[k]: v for k, v in counts(ref, a.threshold).items()},
                  "app", {NUDENET_LABELS[k]: v for k, v in counts(app, a.threshold).items()})
    n = len(imgs)
    print(f"{n} images, detections >= {a.threshold}: reference {ref_total}, app {app_total}, matched (same label, IoU>=0.7) {matched}")
    if score_diffs:
        print(f"  score |diff| mean {np.mean(score_diffs):.4f} max {np.max(score_diffs):.4f}")
    print(f"  identical per-label counts on {same_counts}/{n} images")
    print(f"  time per image (1 thread, incl. pre/post): reference square {1000 * t_ref / n:.1f} ms, app rect {1000 * t_app / n:.1f} ms")

    timed = [(a.erax, a.erax_size)] if a.erax else []
    for e in a.extra:
        m, s = e.rsplit(":", 1); timed.append((m, int(s)))
    for path, size in timed:
        s2 = session(path)
        nc = s2.get_outputs()[0].shape[1] - 4
        t = time.perf_counter(); hits = 0
        for p in imgs:
            mat = cv2.imread(p)
            if mat is not None:
                hits += len(app_detect(s2, mat, size, nc, None))
        print(f"{os.path.basename(path)} @ {size}: {1000 * (time.perf_counter() - t) / n:.1f} ms/image, {hits} detections >= {NMS_SCORE}")


if __name__ == "__main__":
    sys.exit(main())
