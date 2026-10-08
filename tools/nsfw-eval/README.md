# NSFW detector checks

Scripts behind the choices in `app/src/main/java/com/nestgallery/viewer/data/nsfw/`.

| File | What it does |
|---|---|
| `compare.py` | Runs NudeNet's own pre/post-processing (copied from `nudenet` 3.4.2) and a line-for-line port of the app's pipeline on the same photos; reports matching detections, identical per-label counts and time per image. `--extra model.onnx:size` times more models. |
| `make_fixture.py` | Writes `app/src/test/resources/nsfw/` (raw NudeNet outputs + the port's detections) for `NsfwMathTest.matchesThePythonPipelineOnRealOutputs`, which makes the Kotlin decoder agree with the port exactly. |
| `export_erax.py` | Exports an EraX-NSFW-V1.0 YOLO11 checkpoint to ONNX with dynamic height/width (needs `ultralytics`). |

Environment used (2026-10-08): `%USERPROFILE%\nsfw-export-venv` = Python 3.13, torch 2.14 CPU, ultralytics 8.4, onnxruntime 1.22.
Run scripts with `python -I`.

## Models

- **NudeNet v3 `320n.onnx`** (YOLOv8n, 18 classes, 12 MB, AGPL-3.0): from the `nudenet` 3.4.2 PyPI wheel
  (`pip download nudenet --no-deps`, it is inside the wheel). sha256 `c15d8273...911f0f`. The GitHub release assets now
  ask for a login; the Hugging Face mirror `SimonJoz/nudenet` has a byte-identical 320n and the 640m.
- **EraX-NSFW-V1.0 `erax_nsfw_yolo11n.pt`** (YOLO11n; anus, make_love, nipple, penis, vagina; Apache-2.0) from
  `huggingface.co/erax-ai/EraX-NSFW-V1.0`, exported with `export_erax.py` (11 MB). The `.pt` files are pickles: before
  loading them their pickle globals were listed statically - only torch / ultralytics / `builtins.set`.

Both are Ultralytics exports with output `[1, 4 + classes, anchors]` and dynamic input size, so one decoder
(`YoloDecoder`) serves both.

## Results (100 ordinary photos: 60 COCO val2017 people photos + 40 LFW faces; no explicit images were used)

Pipeline check, NudeNet 320n, counting threshold 0.45:

| | reference (nudenet.py) | app |
|---|---|---|
| detections >= 0.45 | 74 | 71 (all 71 match a reference box: same label, IoU >= 0.7) |
| score difference of matched boxes | | mean 0.008, max 0.07 |
| identical per-label counts | | 97 / 100 photos |

The differences are small faces whose score sits next to 0.45 and flips either way. They come from two deliberate changes:

1. **Aspect-ratio input instead of a padded square.** The long side is scaled to 320 and the short side padded to a
   multiple of 32 (what Ultralytics' own `predict()` does); nudenet.py pads every photo to a square. Same detections,
   18% less time on these photos, up to ~45% on 16:9 photos.
2. **NMS within region groups instead of across all classes.** nudenet.py suppresses any overlapping box, whatever
   the class, so overlapping male and female genitalia (common in explicit photos) would count as one. Pure per-class
   NMS goes too far the other way: one face came out as both FACE_MALE and FACE_FEMALE. The app suppresses within
   groups of mutually exclusive labels (`NsfwLabels.nmsGroup`: face male/female, X exposed/covered, female/male
   breast) and never across different body parts.

Speed, one CPU thread on the dev PC (Intel i7-12700H, onnxruntime 1.22, pre- and post-processing included):

| model @ long side | ms / photo | notes |
|---|---|---|
| NudeNet 320n @ 320 | 26-32 | bundled |
| NudeNet 640m @ 640 | 875 | ~30x slower, 104 MB: not bundled (20k photos would take ~5 phone-CPU days) |
| EraX yolo11n @ 640 | 111 | bundled, its training size |
| EraX yolo11n @ 480 / 320 | 58 / 28 | faster, but below the size it was trained at |
| EraX yolo11s @ 640 / 480 | 272 / 151 | |

EraX found nothing (0 boxes even at score 0.25) on the 100 ordinary photos, so it does not add false positives on
clean pictures. **Not measured:** recall/precision on explicit images (no labelled set was used); EraX's model card
reports mAP50-95 0.356 (n) / 0.408 (s) / 0.433 (m) on its own validation set.

Phone speed is not measured either; expect a phone core to be 2-4x slower than the PC one. The scanner runs up to 4
photos in parallel.
