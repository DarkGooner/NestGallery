# CLAUDE.md

NestGallery: offline Android gallery (Kotlin + Jetpack Compose, LibVLC video) with on-device face recognition
and people grouping. `project.md` is the file-by-file architecture guide; read its face section before touching
`data/face/`. `tools/face-eval/README.md` has the measured results that justify every face threshold.

## Build & test (Windows 11 since 2026-10-08; was WSL before)

- JDK 21 (`JAVA_HOME` = Eclipse Adoptium 21; CI uses 17), Android SDK at `%LOCALAPPDATA%\Android\Sdk` (platform 36,
  build-tools 34 auto-installed), `local.properties` points there. AGP 8.7.3, Kotlin 2.2.0, minSdk 26, targetSdk 34.
- The committed wrapper says Gradle 9.3.0; CI and these builds use **Gradle 8.9**, already in the wrapper cache:
  `& "$HOME\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" <task> --console=plain`
- `:app:compileDebugKotlin` is the fast check, `:app:testDebugUnitTest` the JVM tests (CI runs them too),
  `:app:assembleRelease` the signed APK (keystore is committed on purpose).
- The unit tests run the real `face_knn.onnx` and recogniser asset through desktop ONNX Runtime 1.22 (Java).
  **Known local issue:** since mid-session 2026-10-08, ORT Java on this PC fails with `UnsatisfiedLinkError: DLL
  initialization routine failed` (even a 2-line program, both JDK 21s; JDK bundles msvcp140 14.36, preloading
  System32's 14.50 didn't help). The two `OnnxKnnTest` tests fail for that reason only; the asset itself was checked
  with Python onnxruntime 1.22. Unresolved.
- `FACE_EVAL_FIXTURE=<file.bin>` also runs `PeopleClustererTest.realEmbeddingsFixture` on real embeddings exported with
  `tools/face-eval/export_fixture.py` (skipped when unset; ~1.5 min for 21k faces, brute-force kNN).
- No device/emulator is available: nothing in the face pipeline has been verified on a phone except by the user.
  Say so when reporting.

## Face pipeline (data/face)

decode -> SCRFD-500M detect (5 landmarks) -> similarity-align to 112x112 -> AdaFace IR-101 WebFace12M int8
(`adaface_ir101_int8.onnx`, percentile-calibrated Conv/Gemm-only QDQ; class still named `ArcFaceEmbedder`)
-> int8 `FaceStore` + SQLite (`FaceDatabase`, schema v5) -> `PeopleClusterer`.

- `PeopleClusterer`: constrained **average-linkage** agglomerative clustering over a kNN graph (`OnnxKnn` = Gemm+TopK
  ONNX model; `BruteForceNeighborFinder` is the Kotlin fallback/test path). `linkThreshold 0.42`, then a margin-tested
  attach step (`attachThreshold 0.33`, `attachMargin 0.06`). **Every run regroups all people not in
  `keptPersonIds()`** (named or `people.locked`), and regrouped people get their old ids back by overlap.
  Constraints: same-photo faces never one person, named people never merged, `face_rejections` ("Not this person")
  and `person_not_same` ("Different") respected. Bump `CLUSTER_ALGO` in `FaceScannerManager` when the algorithm changes.
- "Same person?" card (People tab): `PeopleClusterer.suggestMerges`, pairs with average linkage in
  [`askThreshold` 0.36, 0.42). Same -> merge (locks target), Different -> `person_not_same` + lock both.
- Changing the recogniser: change `ArcFaceEmbedder.ASSET` + `MODEL_ID` (DB wipes embeddings on mismatch) and
  re-measure `FaceMath.MATCH_*` bands and the cluster thresholds with tools/face-eval - they are model-specific.
- All user corrections (rename/merge/remove/hide/answers) must go through `FaceScannerManager` so SQLite and the
  in-memory `FaceStore` stay in sync (writing only the DB caused deleted people to reappear before).

## Status (2026-10-08)

- Fixed in code, not yet tried on a phone: the "same character split into several people" report
  (`Screenshot_20261008-114812_com.nestgallery.viewer.png`, Person 88 / 90). On DigiFace-72 (400 CGI characters x 72
  renders): old setup split 80% of characters (2.94 people each); now 50% automatically and ~19% after answering
  "Same person?" questions, at ~0.994-0.999 precision. Cover thumbnails no longer show black wedges (`FaceThumbnails`
  zooms in until the rotated crop fits the photo).
- Cost to watch on the phone: AdaFace is ~2.5x ResNet-50 compute per face, and every scan that finds new faces
  now regroups all uncurated faces (a full kNN pass). Speed work (caching kNN lists in the DB, so a scan only searches
  new x all) is the planned follow-up; the user said accuracy first, speed later.
- Scan speed (2026-10-09, user's SM7550, 80 library photos, one thread): decode 35 ms/photo, SCRFD 53 ms/photo,
  AdaFace 193 ms/face (batching faces doesn't help). Decoding was the pipeline's bottleneck: 2 decoders / 4 workers
  20.5 photos/s -> now 4 / 5 (scaled by core count), ~20-30% faster. Larger gains need the GPU (QNN only runs float
  models; the user declined a fp16 recogniser copy) or a smaller detector input (accuracy cost). This ORT QNN
  build has no XNNPACK.
- Not done: real Daz3D / Blender / Honey Select test images (DigiFace is the stand-in), learned clustering, anime.

## NSFW scan (data/nsfw, ui/nsfw; branch `NSFW`)

Own screen (`FolderNsfwScreen`, opened from the recursive view's shield icon, built like `FolderFaceScreen`): NudeNet
320n @ 320 (640m dropped 2026-10-09: ~2 photos/s vs ~40) -> `YoloDecoder` -> `nest_nsfw.db` (rows keyed by model id) +
in-memory map -> photo grid with a filter chip bar + filter sheet (label rows with faceted counts, AND; hide-empty;
confidence presets). EraX was removed on 2026-10-09 at
the user's request. Mirrors the face scan (manager + foreground service), not Room/WorkManager. NMS is within
`NsfwLabels.nmsGroup`; the counting threshold (default 0.45) is applied at load time, every box >= 0.25 is stored.
**Models are Git LFS** (`nudenet_*.onnx`; `git lfs install --local` done here
2026-10-09); CI pulls them with an actions/cache. `tools/nsfw-eval/README.md` has the checks. Not verified on a phone;
no explicit-image accuracy measured. Eval venv: `%USERPROFILE%\nsfw-export-venv` (torch CPU + ultralytics).
**Hardware (2026-10-09):** ORT is now `onnxruntime-android-qnn:1.22.0` (+ Qualcomm `qnn-runtime` 2.33), app arm64-only.
NSFW models can run on the Snapdragon NPU (QNN HTP, fp32 model at fp16, static 640/320 square, compiled graph cached)
or GPU (QNN, experimental), with CPU fallback and a native-crash marker; Settings has a speed test. User's phone:
Snapdragon 7 Gen 3, 12 GB. **Untested on any device**: the x64 `onnxruntime-qnn` 1.22 wheel (HTP simulator) segfaults
on import on this PC (same native-DLL problem as ORT Java), so not even partitioning was checked offline. If the NPU
rejects fp16, next step is a QDQ (int8 / a16w8) export for the HTP. First device run (SM7550): QNN loaded only after
declaring `libcdsprpc.so` / `libOpenCL.so` as `uses-native-library`; HTP rejected the YOLO box-decoding ops, GPU the
DFL Softmax, so the bundled models are now cut before decoding (`split_head.py`, Kotlin `YoloHeadDecoder`). Speed
test "Copy details" gives the QNN log lines. Second run: GPU worked (640m 2.10 photos/s vs CPU 1.27), HTP rejected every
float op even at fp16 (error 3110), so the NPU now loads QDQ copies (`nudenet_*_qdq.onnx`, A16W8, `quantize_qnn.py`;
identical counts to float on 45 held-out photos). Then the DSP could not load the skel's libc++.so.1 /
libc++abi.so.1 (no qnn-runtime / QAIRT ships them; /vendor/dsp/cdsp is closed to apps and even to adb on this Motorola):
NPU unusable there without root. The Secure Folder was not a bottleneck (that comparison mixed 320n and 640m).

## Gallery view (ui/gallery, data/MediaLibrary.kt; added 2026-10-09)

The app opens in the gallery view by default, on the Albums tab; three-dot menu "Gallery view" / "File explorer
view" switches the whole UI (remembered in `UiPrefs`). Gallery view = MediaStore-backed Albums (one per folder) +
Photos timeline tabs, selection with share / permanent delete, pinch for columns. It shows what MediaStore indexes
(no `.nomedia` folders, no WMV etc.; rows whose file is gone are dropped and rescanned); the explorer still shows
all. Grid thumbnails use `ui/gallery/Thumbnail.kt` (MediaStore thumbnails, own LRU), not Coil: Coil's per-image
setup was ~2.5 ms of main thread per cell and made flings drop frames. Judge smoothness on `:app:assembleBenchmark`
(release speed, debug-signed: installs over a debug build keeping data), not a debug build. On the user's phone
(12 fast swipes) janky frames went 1.4-1.9% -> 1.0-1.5%, 99th percentile 25-32 -> 19-25 ms.

**NSFW scan is off by default** (Settings switch, `UiPrefs.nsfwEnabled`): while off it is hidden everywhere
(explorer shield button, gallery menus, Settings hardware section, info-sheet tags); switching off stops a running
scan; saved results are kept.

## Eval tooling (tools/face-eval)

See its README. Data in `$FACE_EVAL_DATA` = `%USERPROFILE%\face-eval-data`; venvs `%USERPROFILE%\face-eval-venv`
(CPU, onnxruntime 1.30) and `%USERPROFILE%\face-eval-gpu` (onnxruntime-gpu 1.22 + CUDA 12 / cuDNN 9.10 pip libs,
torch CPU for the AdaFace export). Embedding caches in `tools/face-eval/emb` (gitignored, several GB).

## Environment gotchas (Windows)

- PowerShell piping into native programs adds a BOM (breaks `json.load(sys.stdin)`); write a small .py instead.
- GitHub downloads: `curl.exe --ssl-no-revoke -C - --retry 5` (TLS revocation check fails; big downloads get reset).
- RTX 4060, driver 566 = CUDA 12.7: current onnxruntime-gpu wheels need CUDA 13 (error 801) and cuDNN 9.27 fails;
  use onnxruntime-gpu 1.22 + nvidia-cudnn-cu12 9.10. ORT silently falls back to CPU otherwise.
- onnxruntime's `quant_pre_process` failed to delete temp files inside `app/src/main/assets` (file in use);
  `quant.py` now works in a temp dir.
- `Select-String ... | Select -First N` on a running Gradle build closes the pipe and kills the build.
- 16 GB RAM: percentile calibration and big embedding jobs together made the shell unresponsive for minutes.
- Commit only when the user asks.
