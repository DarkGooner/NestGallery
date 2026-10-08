# NSFW detector checks

Scripts behind the choices in `app/src/main/java/com/nestgallery/viewer/data/nsfw/`.

| File | What it does |
|---|---|
| `compare.py` | Runs NudeNet's own pre/post-processing (copied from `nudenet` 3.4.2) and a line-for-line port of the app's pipeline on the same photos; reports matching detections, identical per-label counts and time per image. `--size 640` for 640m; `--extra model.onnx:size` times more models. |
| `make_fixture.py` | Writes `app/src/test/resources/nsfw/` (raw NudeNet 320n outputs + the port's detections) for `NsfwMathTest.matchesThePythonPipelineOnRealOutputs`, which makes the Kotlin decoder agree with the port exactly. |

Environment used: `%USERPROFILE%\nsfw-export-venv` = Python 3.13, onnxruntime 1.22, opencv. Run scripts with `python -I`.

## Models (both bundled, Git LFS, picked in Settings)

- **NudeNet v3 `320n.onnx`** (YOLOv8n, 18 classes, 12 MB, AGPL-3.0): from the `nudenet` 3.4.2 PyPI wheel
  (`pip download nudenet --no-deps`; it is inside the wheel). sha256 `c15d8273...911f0f`.
- **NudeNet v3 `640m.onnx`** (YOLOv8m, same classes, 104 MB): the GitHub release assets now ask for a login, so it came
  from the Hugging Face mirror `SimonJoz/nudenet`, whose 320n is byte-identical to the PyPI one and whose 640m carries
  the same Ultralytics export metadata (8.2.46, exported 1.5 min after 320n). sha256 `04fe3d77...e634eb`.

Both are Ultralytics exports with output `[1, 22, anchors]` and dynamic input size.

(EraX-NSFW-V1.0 YOLO11n was bundled for a day for its `make_love` class and removed on request on 2026-10-09. On the
same photos it found nothing at all, i.e. no false positives on clean pictures, at 111 ms per photo @ 640.)

## Results (100 ordinary photos: 60 COCO val2017 people photos + 40 LFW faces; no explicit images were used)

Pipeline check at counting threshold 0.45:

| | 320n reference | 320n app | 640m reference | 640m app |
|---|---|---|---|---|
| detections >= 0.45 | 74 | 71 (all match a reference box) | 103 | 104 (103 match) |
| score difference of matched boxes | | mean 0.008, max 0.07 | | mean 0.003, max 0.04 |
| identical per-label counts | | 97 / 100 photos | | 99 / 100 photos |

"Match" = same label and IoU >= 0.7. The differences are small regions whose score sits next to 0.45 and flips either
way. They come from two deliberate changes:

1. **Aspect-ratio input instead of a padded square.** The long side is scaled to the model size and the short side
   padded to a multiple of 32 (what Ultralytics' own `predict()` does); nudenet.py pads every photo to a square. Same
   detections, 6-18% less time on these photos, up to ~45% on 16:9 photos.
2. **NMS within region groups instead of across all classes.** nudenet.py suppresses any overlapping box, whatever
   the class, so overlapping male and female genitalia (common in explicit photos) would count as one. Pure per-class
   NMS goes too far the other way: one face came out as both FACE_MALE and FACE_FEMALE. The app suppresses within
   groups of mutually exclusive labels (`NsfwLabels.nmsGroup`: face male/female, X exposed/covered, female/male
   breast) and never across different body parts.

640m finds 45% more regions at 0.45 than 320n on the same photos (104 vs 71), mostly smaller faces and feet.

Speed, one CPU thread on the dev PC (Intel i7-12700H, onnxruntime 1.22, pre- and post-processing included):

| model @ long side | ms / photo |
|---|---|
| NudeNet 320n @ 320 | 26-33 |
| NudeNet 640m @ 640 | 870 |

On a phone 640m is run with fewer photos in parallel (2 threads each) to bound memory. **Not measured:** phone speed
(expect a phone core 2-4x slower than the PC one) and recall/precision on explicit images (no labelled set was used).
