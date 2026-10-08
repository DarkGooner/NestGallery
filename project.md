# NestGallery: Technical Architecture & File Directory Guide

This document provides a comprehensive, file-by-file technical breakdown of the **NestGallery** codebase. It is designed to allow any Android or Kotlin developer to understand the application architecture, trace data and execution flows, and safely modify or extend features.

---

## 1. High-Level Architecture Overview

NestGallery is a high-performance, privacy-focused, offline-first media gallery and video viewer for Android with built-in on-device facial recognition and clustering.

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Jetpack Compose UI                            │
│  MainActivity  ·  GalleryScreen  ·  ExploreScreen  ·  FolderFaceScreen │
│  SearchScreen  ·  ImageViewerScreen  ·  PersonDetailScreen             │
└─────────────────────────────────┬──────────────────────────────────────┘
                                  │
         ┌────────────────────────┴────────────────────────┐
         ▼                                                 ▼
┌─────────────────────────────────┐       ┌──────────────────────────────┐
│       Data & Storage Layer      │       │     On-Device ML Engine      │
│  FsDirectory (Direct File IO)   │       │  ScrfdDetector (ONNX)        │
│  GalleryCache (Process Memory)  │       │  ArcFaceEmbedder (ONNX)      │
│  VlcPlayerController (LibVLC)   │       │  PeopleClusterer + FaceStore │
│                                 │       │  FaceScannerManager (Scope)  │
│                                 │       │  FaceDatabase (SQLite)       │
└─────────────────────────────────┘       └──────────────────────────────┘
```

### Key Architectural Tenets
1. **Zero-SAF Direct Filesystem Access**: Rather than slow cross-process Storage Access Framework (`SAF`) queries, NestGallery requests `MANAGE_EXTERNAL_STORAGE` (All Files Access) on Android 11+ (API 30+) to read the storage directly using standard `java.io.File`.
2. **On-Device Facial Recognition**: InsightFace **SCRFD-500MF** (detector, 5 landmarks) + **AdaFace IR-101 / WebFace12M, int8-quantised** (512-d embeddings) executed by ONNX Runtime. Faces are aligned to the ArcFace 112x112 template from the landmarks. All processing runs 100% locally. *Model licences: InsightFace's pretrained models (SCRFD) are non-commercial research only; AdaFace is trained on WebFace12M, whose licence is also non-commercial.*
3. **Folder-Scoped Processing**: Heavy operations like recursive exploration, media indexing, and facial clustering are strictly scoped to user-selected directory trees rather than locking up the entire device storage.
4. **Resilient Video Playback**: Uses native **LibVLC** (`libvlc-all:3.7.6`) instead of ExoPlayer/Media3 to guarantee playback of legacy and esoteric video containers and codecs (e.g. AVI, MKV, legacy DivX/Xvid, 10-bit H.264).

---

## 2. Complete File Directory & Role Breakdown

### Root Configuration & Build Scripts

#### [`.github/workflows/build.yml`](file:///d:/Projects/NestGallery/.github/workflows/build.yml)
- **Role**: Continuous Integration & Automated Release pipeline using GitHub Actions.
- **Functionality**:
  - Automatically triggers on `push` to `main` and `face` branches or via manual `workflow_dispatch`.
  - Sets up Temurin JDK 17 and Gradle 8.9.
  - Builds the signed release APK using `:app:assembleRelease`.
  - Uploads the resulting APK artifact (`app-release.apk`) and publishes GitHub releases when tags are created on `main`.
- **How to Modify**: To add CI unit testing, add `./gradlew test` before `:app:assembleRelease`. To add more branch triggers, edit `branches: [ "main", "face", "your-branch" ]`.

#### [`.gitignore`](file:///d:/Projects/NestGallery/.gitignore)
- **Role**: Version control exclusion rules.
- **Functionality**: Filters out Gradle build outputs (`build/`, `.gradle/`), IDE metadata (`.idea/`, `*.iml`), local environment overrides (`local.properties`), and OS artifacts (`.DS_Store`, `Thumbs.db`).

#### [`build.gradle.kts`](file:///d:/Projects/NestGallery/build.gradle.kts)
- **Role**: Top-level project Gradle build configuration.
- **Functionality**: Declares core plugin versions across modules (Android Application plugin `8.9.0`, Kotlin Android plugin `2.0.21`, Jetpack Compose compiler plugin `2.0.21`).

#### [`settings.gradle.kts`](file:///d:/Projects/NestGallery/settings.gradle.kts)
- **Role**: Gradle repository and multi-project settings.
- **Functionality**: Configures plugin and dependency repositories (`google()`, `mavenCentral()`) and includes the `:app` module.

#### [`gradle.properties`](file:///d:/Projects/NestGallery/gradle.properties)
- **Role**: JVM memory and Gradle daemon configuration.
- **Functionality**: Sets JVM memory limits (`-Xmx2048m`), enables AndroidX (`android.useAndroidX=true`), and activates non-transitive R classes for build speed.

#### [`gradlew` / `gradlew.bat`](file:///d:/Projects/NestGallery/gradlew)
- **Role**: Gradle Wrapper scripts (Unix shell script and Windows command script).
- **Functionality**: Allows deterministic compilation without requiring a pre-installed Gradle binary on the host developer machine.

---

### App Module Build & Manifest

#### [`app/build.gradle.kts`](file:///d:/Projects/NestGallery/app/build.gradle.kts)
- **Role**: Build configuration, compiler flags, and dependencies for the Android app.
- **Functionality**:
  - `namespace`: `com.nestgallery.viewer`, `minSdk`: 26, `targetSdk`: 34, `compileSdk`: 36.
  - Native ABI filters: `arm64-v8a`, `armeabi-v7a` (ensures optimal APK size for mobile architectures).
  - `aaptOptions { noCompress += listOf("onnx") }` so the models can be copied out of the APK cheaply (they are materialised once into `filesDir/models` and memory-mapped by ONNX Runtime).
  - Signing configurations for release builds pointing to `app/keystore/release.keystore`.
  - Dependencies: Jetpack Compose BOM, Coil (`coil-compose`, `coil-gif`, `coil-video`), LibVLC (`org.videolan.android:libvlc-all:3.7.6`), ONNX Runtime (`onnxruntime-android:1.22.0`).
- **How to Modify**: To upgrade dependencies, add new ML models, or change SDK target levels.

#### [`app/src/main/AndroidManifest.xml`](file:///d:/Projects/NestGallery/app/src/main/AndroidManifest.xml)
- **Role**: Android application manifest.
- **Functionality**:
  - Requests `android.permission.MANAGE_EXTERNAL_STORAGE` (All Files Access) for direct disk access and `READ_EXTERNAL_STORAGE` for Android 10 (API 29) and lower.
  - Defines the single-activity entry point `MainActivity` with configuration changes handled (`orientation|screenSize|keyboardHidden|uiMode`) to prevent activity recreations during rotation.

#### [`app/proguard-rules.pro`](file:///d:/Projects/NestGallery/app/proguard-rules.pro)
- **Role**: Code obfuscation and R8 shrinking rules.
- **Functionality**: Protects ONNX Runtime and LibVLC native JNI methods from being stripped out during release builds.

#### [`app/keystore/release.keystore`](file:///d:/Projects/NestGallery/app/keystore/release.keystore)
- **Role**: Pre-configured keystore for signing release APKs.

#### `app/src/main/assets/nudenet_320n.onnx` (12 MB), `nudenet_320n_qdq.onnx` (3.3 MB) - Git LFS
- **Role**: NudeNet v3 320n NSFW detector (YOLOv8n, AGPL-3.0) and its quantised NPU copy (`tools/nsfw-eval/quantize_qnn.py`). Input `[1,3,H,W]` RGB `/255`, dynamic H/W. Cut before box decoding (`tools/nsfw-eval/split_head.py`, the Snapdragon NPU / GPU reject those ops): outputs are the 3 head maps `[1, 64+18, H/s, W/s]`, decoded by `YoloHeadDecoder`. Stored with Git LFS. See the NSFW section.

#### `app/src/main/assets/scrfd_500m.onnx` (2.4 MB), `adaface_ir101_int8.onnx` (63 MB), `face_knn.onnx` (<1 KB)
- **Role**: SCRFD-500MF face detector (InsightFace `buffalo_s`); AdaFace IR-101 recogniser (CVLFace `cvlface_adaface_ir101_webface12m`, exported with `tools/face-eval/export_adaface.py`, statically quantised to int8 QDQ with percentile calibration by `tools/face-eval/quant.py`; within ~1 point of fp32 TAR); the Gemm+TopK kNN kernel the clusterer runs on ONNX Runtime (`tools/face-eval/make_knn_model.py`). The class is still called `ArcFaceEmbedder` (same 112 px RGB `[-1,1]` interface). It replaced ArcFace ResNet-50 int8 (42 MB) on 2026-10-08: better on CGI expression/lighting changes, occlusion and pose, at ~2.5x the compute.
- **Contract**: detector input `[1,3,H,W]` (RGB, `(x-127.5)/128`, H/W = photo size rounded up to 32), 9 outputs (score/box/5-landmark per stride 8/16/32). Recogniser input `[N,3,112,112]` (RGB, `(x-127.5)/127.5`), output 512-d.
- **Swapping the recogniser**: any ArcFace-style 112x112 model with the same contract works. Replace the asset, change `ArcFaceEmbedder.ASSET`, bump `MODEL_ID` (the DB drops incompatible embeddings automatically) and re-measure the thresholds with `tools/face-eval` - they depend on the model.

---

### Core Lifecycle & Application Root

#### [`app/src/main/java/com/nestgallery/viewer/MainActivity.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/MainActivity.kt)
- **Role**: Main activity, root composable, navigation state manager, and storage permission coordinator.
- **Functionality**:
  - Initializes Coil image loaders with GIF and video-frame decoding plugins.
  - Checks storage permissions (`Environment.isExternalStorageManager()` on Android 11+; runtime permission on Android 10-). Launches intent `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` when required.
  - Controls back stack navigation using a sealed class `Screen`:
    - `Screen.NeedsPermission`: Storage permission grant prompt.
    - `Screen.Browser`: Folder browser hierarchy.
    - `Screen.Explore(root)`: Recursive flat explorer for all nested files.
    - `Screen.Search(root, returnTo)`: Filename and path search.
    - `Screen.Viewer(images, startIndex, returnTo)`: Fullscreen zoomable photo & LibVLC video viewer.
    - `Screen.FolderFaces(root, files, returnTo)`: Scoped face detection, people clustering, and reverse face search.
    - `Screen.PersonDetail(personId, folderPath, returnTo)`: Photos matching a specific person cluster.
- **How to Modify**: To introduce new screens or global overlays, add a new subclass to `Screen` and handle its rendering in `NestGalleryApp`.

---

### Data Layer (`com.nestgallery.viewer.data`)

#### [`app/src/main/java/com/nestgallery/viewer/data/FsDirectory.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/FsDirectory.kt)
- **Role**: Filesystem listing, natural alphanumeric sorting, and recursive streaming flow.
- **Functionality**:
  - Defines `DocEntry(file: File, name: String, isDirectory: Boolean, size: Long, isVideo: Boolean)`.
  - `naturalCompare(a, b)`: Implements human-friendly natural sorting (e.g. `day2` sorts before `day11` instead of standard alphabetical order) handling arbitrarily long numbers without integer overflow.
  - `listFolderFast(dir, hideHidden)`: Directly reads directory entries with `dir.listFiles()`, filters media extensions (`jpg`, `png`, `webp`, `mp4`, `mkv`, `avi`, etc.), and sorts folders first followed by media files.
  - `exploreMediaFlow(root, hideHidden)`: Iterative Breadth-First Search (BFS) using an `ArrayDeque` that yields media files in batches of 300 over a Kotlin coroutine `Flow`. Prevents UI stalls on directories with 50,000+ files.
- **How to Modify**: To add support for new file formats (e.g. RAW, DNG, AVIF), add their extensions to `imageExtensions` or `videoExtensions`.

#### [`app/src/main/java/com/nestgallery/viewer/data/GalleryCache.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/GalleryCache.kt)
- **Role**: In-memory process cache for folder listings, file counts, and scroll positions.
- **Functionality**:
  - Caches `DocEntry` lists and scroll states keyed by directory paths.
  - Prevents reloading or re-sorting folder contents when navigating back and forth between the browser and fullscreen viewer.
  - Supports invalidation (`invalidate(key)` and `clearAll()`) when files are modified, rescanned, or deleted.

#### [`app/src/main/java/com/nestgallery/viewer/data/VlcPlayerController.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/VlcPlayerController.kt)
- **Role**: LibVLC lifecycle controller and playback coordinator.
- **Functionality**:
  - Wraps `org.videolan.libvlc.LibVLC` and `org.videolan.libvlc.MediaPlayer`.
  - Configured with `--no-video-title-show` and `--avcodec-hw=none` (software decoding enabled for maximum compatibility with legacy AVI codecs without crashing hardware decoders).
  - Renders to an Android `TextureView` (enables real-time frame capture `textureView.getBitmap()` for scrubbing thumbnails).
  - Handles size changes, looping, volume muting, seeking, and event propagation to the main thread.

#### [`app/src/main/java/com/nestgallery/viewer/data/PlayerFactory.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/PlayerFactory.kt)
- **Role**: Architectural documentation marker for video engine selection.
- **Functionality**: Explains why LibVLC was chosen over ExoPlayer/Media3 (AVI/MKV container and legacy codec coverage).

---

### Face Recognition & Machine Learning Engine (`com.nestgallery.viewer.data.face`)

Pipeline: `decode (EXIF-correct, >=800px)` -> `SCRFD detect` -> `5-point similarity alignment to 112x112` -> `ArcFace R50 embed (512-d)` -> `quality score` -> `int8 store + SQLite` -> `kNN graph (ONNX)` -> `average-linkage clustering`.

| File | Android-free? | Role |
|---|---|---|
| `FaceMath.kt` | yes | closed-form similarity transform, SCRFD head decoding + NMS, quality score, calibrated match bands (`MATCH_STRONG/LIKELY/POSSIBLE`) |
| `FaceStore.kt` | yes | `Int8Matrix` (chunked int8 embeddings) and `FaceStore`: thread-safe in-memory index with exact folder-scoped cosine search; `BruteForceNeighborFinder` (Kotlin kNN, tests + fallback) |
| `PeopleClusterer.kt` | yes | constrained average-linkage clustering + margin-tested attach (see below) |
| `FaceScanService.kt` | no | foreground service (data-sync) + wake lock + progress notification so scans survive minimising / screen-off |
| `OnnxFaceModels.kt` | no | `ScrfdDetector`, `ArcFaceEmbedder`, `OnnxKnn` (ONNX Runtime) |
| `FaceImage.kt` | no | `FaceImageLoader` (sampled decode, all 8 EXIF orientations), `FaceAligner` (Skia matrix warp), `FaceAnalyzer`, `FaceThumbnails` |
| `FaceDatabase.kt` | no | SQLite v5 (WAL), batched writes, int8 embedding blobs, folder range queries, `face_rejections` ("not this person"), `people.locked` (curated by the user), `person_not_same` ("different people" answers), merge |
| `FaceScannerManager.kt` | no | parallel scan pipeline, grouping, corrections (rename / merge / remove / hide / "same person?" answers - always DB **and** in-memory index), cover thumbnails, search + person suggestions, merge suggestions |

**Scan pipeline** (`FaceScannerManager.runPipeline`): 1-2 decoder coroutines (IO) feed a bounded channel; 1-4 analysis workers (CPU) run detect+align+embed; one writer commits batches of up to 32 photos in a single transaction and appends to the in-memory index. Already-indexed files are skipped using one bulk query. Pause stops new decodes; cancel still groups what was saved. If a scan indexed nothing new, grouping is skipped.

**Clustering** (`PeopleClusterer`, measured in `tools/face-eval/README.md`):
1. *Neighbours*: every face being (re)grouped gets its 24 nearest faces through `OnnxKnn` (`face_knn.onnx`: Gemm + TopK in blocks of 16k rows, ~4x faster than Kotlin because ART does not vectorise). A full rebuild of 40k faces is one 40k x 40k pass.
2. *Merge*: **kept** people (named, or `locked` = curated by the user) start as groups, every other face as a singleton. The pair of groups with the highest **average** cosine over all their face pairs is merged while it is >= `linkThreshold` (0.42). For unit vectors that average is `sumA . sumB / (|A||B|)`, so a group is just a running sum. Average linkage never increases on a merge, so stale heap entries are upper bounds and are re-scored lazily (no neighbour lists to maintain).
3. *Attach*: a face still alone joins the person it matches best on average if that is >= `attachThreshold` (0.33) **and** beats the runner-up by `attachMargin` (0.06); ambiguous faces stay ungrouped instead of being guessed.
4. *Ids*: a regrouped person takes back the old id it overlaps most (largest overlaps first), so "Person 12" stays Person 12.
5. *Constraints* (every merge and attach): faces of one photo are different people; two named people never merge; people answered "different" (`person_not_same`) never merge; a face the user removed from a person never returns to it (`face_rejections`).
6. *"Same person?"* (`PeopleClusterer.suggestMerges`, card at the top of the People tab): pairs of people with average linkage in [`askThreshold` 0.36, 0.42), best first. In that band the score cannot tell one character split by lighting/expression from two look-alike characters, so the user decides: *Same person* merges (target locked), *Different* records the pair and locks both, *Skip* hides it until the user leaves the folder's face screen. All pairs are listed (no cap), and the card shows `n / total`; progress and skips live in `ReviewSession` so opening a person from the card and coming back keeps them.

Why not the previous Immich-style DBSCAN: it is single-link, so one look-alike face chains two people together, and its "reconcile" step merged any groups that touched. On CGI renders (where different characters look much more alike than real people) its pairwise precision was 0.035 - a few groups swallowed many characters. Average linkage: 0.996.

Every grouping run regroups all people the user has not curated (that costs a neighbour search over those faces per scan that found new faces). Freezing groups scan by scan locked in early mistakes both ways: in tools/face-eval, scanning a mixed library in 4 passes tripled the impure groups, and a character split early stayed split because two existing people were never compared again. Curated people only grow. **Regroup people** (Faces screen menu) also drops the locks, merges and "different" answers on unnamed people. Person ids only grow (`max_person_id` meta key), so a stored rejection can never point at a later, unrelated person. On first run after an algorithm change (`cluster_algo` meta key) unnamed people are rebuilt automatically.

**Corrections** (Google Photos style): long-press people to multi-select and **Merge**; in a person, **Select** photos -> **Not this person** (removed + remembered); rename; hide (faces marked `person_id = -1`, never regrouped).

**Background scanning**: `FaceScannerManager` starts `FaceScanService` (foreground, `dataSync`) when a scan starts. The service holds a partial wake lock, shows a progress notification (photos/s, Pause / Resume / Stop) and stops itself when the scan ends. Status is published before the service starts so it can't see a stale Idle. Progress is committed in batches, so if Android still kills the process the next scan resumes (indexed photos are skipped). Android 13+ asks for notification permission before the first scan; Android 15 limits dataSync services to ~6h/day, handled in `onTimeout`.

**Scan speed**: detector input is the photo's own aspect ratio rounded up to a multiple of 32 instead of a fixed 640x640 square, detection score floor 0.7 and a minimum face size skip junk faces, faces of a photo are embedded in one batched call, the recogniser is int8.

**Search** ("Find by face"): exact brute-force cosine over the int8 index, scoped to the folder by path prefix, best face per photo. Sensitivity chips map to calibrated bands - Strict `MATCH_STRONG` (~1 in 100k different-person pairs pass), Balanced `MATCH_LIKELY` (~1 in 10k), Broad `MATCH_POSSIBLE` (~1 in 1k) - and result badges are coloured by band. "Looks like" lists the people whose faces match the query on average (same score as the clusterer's attach step). `FaceMath.matchProbability` only converts the raw cosine into the displayed percentage.

**Cover thumbnails** are rendered lazily, only for the face chosen as each (visible) person's cover (high quality and close to the person's average face), into `filesDir/face_thumbs/f<faceId>.jpg`.

**Verification tooling**: `tools/face-eval/` (Python harness on LFW + DigiFace-1M) and `app/src/test/.../PeopleClustererTest.kt` (`./gradlew :app:testDebugUnitTest`; set `FACE_EVAL_FIXTURE` to also run on real exported embeddings).

---

### Presentation & UI Layer (`com.nestgallery.viewer.ui`)

#### [`app/src/main/java/com/nestgallery/viewer/ui/GalleryScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/GalleryScreen.kt)
- **Role**: Primary folder browser interface.
- **Functionality**:
  - Displays contents of current folder in a grid or staggered layout.
  - Supports navigation into subfolders and navigating up the directory stack.
  - Uses `NestTopBar` (see below). Pinned dock: search pill, recursive-explorer button, grid/list switch. Header tier: back, title, path chips, Home, overflow (filenames / hidden items).
  - Long-press preview: Renders animated live preview using `MediaImageTile`.

#### [`app/src/main/java/com/nestgallery/viewer/ui/NestTopBar.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/NestTopBar.kt)
- **Role**: Shared floating, two-tier "glass capsule" top bar for the browser, recursive explorer and search.
- **Header tier** (folds away while scrolling down via `CollapsingBarState`, a nested-scroll connection that never consumes scroll and snaps open/closed after a fling): back or folder badge, title + subtitle, tappable path chips, extra actions, overflow menu.
- **Dock tier** (always visible): `SearchPill` (or the typeable `SearchField` on the search screen), `RecursiveAction` button (tinted when recursive mode is active, progress ring while scanning), animated sliding grid/list switch.
- **Usage**: host the content and bar in one `Box` with `Modifier.nestedScroll(bar.connection)`; give lists `contentPadding(top = bar.contentTopPadding())` so they scroll underneath the capsule.

#### [`app/src/main/java/com/nestgallery/viewer/ui/ExploreScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/ExploreScreen.kt)
- **Role**: Recursive flattened explorer interface.
- **Functionality**:
  - Recursively discovers all media nested in subdirectories using `exploreMediaFlow`.
  - Displays media in a unified grid with sub-labels indicating relative folder paths.
  - `NestTopBar`: the recursive button doubles as **rescan** (active tint + progress ring while walking the tree); **Faces in folder** sits in the header tier; filenames / hidden items in the overflow menu.
  - **NSFW** (shield icon) next to Faces opens `FolderNsfwScreen`; **Settings** in the overflow menu. See the NSFW section below.

#### [`app/src/main/java/com/nestgallery/viewer/ui/ImageViewerScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/ImageViewerScreen.kt)
- **Role**: Fullscreen photo viewer and video player.
- **Functionality**:
  - Horizontal swipe pager (`HorizontalPager`) across the media set.
  - `ZoomableImage`: Pinch-to-zoom, double-tap zoom, and panning using Compose pointer gestures.
  - `VideoPlayer`: Custom LibVLC-backed playback controls with scrubbing bar, play/pause, timecode, and scrubbing preview window.
  - **"i" button** (top right, photos and videos) opens `MediaInfoSheet`.

#### [`app/src/main/java/com/nestgallery/viewer/ui/MediaInfoSheet.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/MediaInfoSheet.kt)
- **Role**: The viewer's details bottom sheet (text is selectable).
- **Functionality**: name, full path, size, modified date for every file; photos add displayed resolution / megapixels,
  type, rotation and EXIF (taken, camera, aperture, exposure, ISO, focal length, software, GPS) via the platform
  `ExifInterface`; videos add resolution, duration, bitrate, type, recorded date and location via `MediaMetadataRetriever`.

#### [`app/src/main/java/com/nestgallery/viewer/ui/Insets.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/Insets.kt)
- **Role**: Window insets for the edge-to-edge app. `ScreenInsets` (= `safeDrawing`) is every `Scaffold`'s
  `contentWindowInsets`, `TopBarInsets` every `TopAppBar`'s `windowInsets`; full-screen viewer chrome uses
  `safeDrawingPadding()`. Material's defaults leave out display cutouts, which the activity draws into in landscape, and
  the 3-button navigation bar moves to the side in landscape - so new screens should use these, not `statusBarsPadding()`
  / `navigationBarsPadding()` alone.

#### [`app/src/main/java/com/nestgallery/viewer/ui/SearchScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/SearchScreen.kt)
- **Role**: Filename and path search interface.
- **Functionality**:
  - Real-time debounced search bar.
  - Toggle for recursive searching (entire tree) vs local searching (immediate directory only).
  - Grid/list view mode switching.

#### [`app/src/main/java/com/nestgallery/viewer/ui/MediaGridTile.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/MediaGridTile.kt)
- **Role**: Reusable media tile component for all grids.
- **Functionality**:
  - Displays Coil-cached image or video thumbnail.
  - Renders video duration badge or play icon.
  - Shows file name and optional relative path subtitle.
  - Handles tap and long-press hover preview interactions.

#### [`app/src/main/java/com/nestgallery/viewer/ui/FastScrollbar.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/FastScrollbar.kt)
- **Role**: High-performance draggable scrollbar for massive lists (10k+ items).
- **Functionality**:
  - Draggable thumb overlay with smooth enter/exit animations.
  - Calculates proportional jump offsets across thousands of items without freezing the Compose render thread.

#### [`app/src/main/java/com/nestgallery/viewer/ui/KeptScroll.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/KeptScroll.kt)
- **Role**: scroll positions that survive opening the viewer (or a person) and coming back. Navigation swaps whole
  screens, so `remember` / `rememberSaveable` state is lost; `rememberKeptGridState` / `rememberKeptListState` keep the
  position in `GalleryCache` and re-apply it once the (asynchronously loaded) content is long enough.
- **Used by**: a person's photos (grid and list separately), the Faces screen's people grid, the NSFW screen's Photos
  grid and Filters list (its selected tab is kept in `NsfwFilterState`). `MainActivity` calls
  `GalleryCache.forgetScrolls(ScrollKeys.x(...))` when one of these screens is opened afresh, so only returns keep the
  position. `ExploreScreen` has its own equivalent.

---

### NSFW Scan & Filters (`com.nestgallery.viewer.data.nsfw`, `ui.nsfw`)

Pipeline: `decode (EXIF-upright, long side >= model size)` -> `NudeNet 320n @ 320` (ONNX Runtime;
640m was dropped on 2026-10-09) -> `YoloDecoder` (boxes in original-photo pixels, NMS within label groups) -> SQLite (per model) +
in-memory map (current model) -> per-folder counts -> range filter. Checked against nudenet.py and timed in
`tools/nsfw-eval/README.md`.

| File | Android-free? | Role |
|---|---|---|
| `NsfwMath.kt` | yes | `NsfwDetection` / `NsfwResult` (tag string, JSON), `NsfwLabels` (model class order, screen order + groups, NMS groups), `YoloHeadDecoder` (DFL + dist2bbox + sigmoid on the cut models' head maps -> the full model's output), `YoloDecoder` (Ultralytics `[4+C, anchors]` -> boxes + NMS), `NsfwFilter` (counts at a threshold, AND-of-ranges match), `NsfwFolderIndex` (per-photo counts, slider maxima, per-label histograms) |
| `NsfwDetector.kt` | no | `NsfwModels` (320n: id, asset, NPU asset, input size, title), `YoloDetector` (aspect-ratio input padded to /32 on the CPU, the full square on NPU / GPU; runs take turns on an accelerator; reuses the face package's `ModelFiles` / `Ort`; refuses an LFS pointer with a clear message), `NsfwAnalyzer` (decode + detect for one model on one accelerator) |
| `NsfwBackend.kt` | no | `NsfwAccelerator` (Auto / NPU / GPU / CPU), `NsfwSessions`: QNN session options (HTP `libQnnHtp.so` at fp16 with `ADSP_LIBRARY_PATH` set, or `libQnnGpu.so`; height/width pinned with `setSymbolicDimensionValue`; compiled HTP graph cached in `filesDir/qnn_ctx` via `ep.context_*`), photos-at-once per backend (CPU: by cores and RAM) |
| `NsfwDatabase.kt` | no | `nest_nsfw.db` v2: `nsfw_files` (model, path, mtime, size, width, height, ms; width 0 = undecodable) and `nsfw_detections` (model, path, every box with score >= 0.25). Every row carries the model id, so each model keeps its own results |
| `NsfwScannerManager.kt` | no | selected hardware (SharedPreferences `nsfw`; refused while scanning, closes the old session); opens the model along the hardware's fallback chain, running it once per attempt, with a committed marker so a native crash is remembered and that accelerator skipped; `speedTest` (every option at scan parallelism on a synthetic 640x480 picture); same decode -> analyse -> batched-writer pipeline as the face scan (640m: fewer workers, 2 threads each), status with rate + ETA, `revision` flow (~1/s while scanning), pause / resume / stop, `clearFolder` |
| `NsfwScanService.kt` | no | foreground (data-sync) service + wake lock + progress notification (own channel), like `FaceScanService` |
| `ui/nsfw/FolderNsfwScreen.kt` | no | the NSFW screen (structure of `FolderFaceScreen`): top bar (scan, overflow: Settings / forget results), progress card, tabs **Filters** and **Photos**, empty state |
| `ui/nsfw/NsfwFilters.kt` | no | `NsfwFilterState` (threshold + label ranges, per folder for the process lifetime), confidence card, label card (histogram with tap-to-pick, slider, quick chips), active-filter chips, `rangeText`. `StepTrack` is a custom slider instead of Material's (which jumps on touch-down, so scrolling the list changed filters): it moves only when a thumb is dragged sideways past the touch slop; `TouchGuard` drops taps/drags while the list scrolls and 350 ms after; bars, labels and stops share one `StopGeometry`, so each bar sits over its stop |
| `ui/SettingsScreen.kt` | no | Settings (from the 3-dot menus of the browser, the recursive view and the NSFW screen): NSFW hardware choice and speed test |

- **Entry point**: shield icon in `ExploreScreen`'s header (next to Faces) -> `Screen.FolderNsfw(root, files)`.
- **Filter semantics**: a range equal to `0..max` is inactive; the top end stored as `NsfwFilter.NO_MAX` means "and more",
  so it keeps up with a scan still raising the maximum. The Photos tab lists scanned photos that pass every active range.
- **Threshold** is applied at count time, so the confidence slider never rescans.
- **Models are Git LFS objects** (`.gitattributes`: `app/src/main/assets/nudenet_*.onnx`); CI pulls them with a cache.
- **ONNX Runtime is `onnxruntime-android-qnn:1.22.0`** (the face models run on its CPU provider exactly as before) plus
  `com.qualcomm.qti:qnn-runtime` (QNN 2.33, Qualcomm AI Hub licence): arm64-only, so the app is arm64-v8a only;
  `jniLibs.useLegacyPackaging = true` so the DSP can load `libQnnHtpV*Skel.so` from the extracted library directory;
  the old DSP (V66) libraries are excluded. Bump `NsfwSessions.CACHE_VERSION` with either version.
- **Changing models**: add the asset (LFS-tracked if named `nudenet_*.onnx`), add an `NsfwModel` with a new id to
  `NsfwModels`; labels go in `NsfwLabels` (`ALL` order = screen order, `nmsGroup` for mutually exclusive variants);
  re-run `tools/nsfw-eval/compare.py` / `make_fixture.py`.

---

### Face Recognition UI (`com.nestgallery.viewer.ui.face`)

#### [`app/src/main/java/com/nestgallery/viewer/ui/face/FolderFaceScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/face/FolderFaceScreen.kt)
- **Role**: Scoped facial recognition hub for the active recursive exploration.
- **Functionality**:
  - **Tabs**:
    1. **People**: Detected people with photo counts and circular cover thumbnails. Long-press to multi-select, then **Merge**. A **"Same person?"** card on top asks about borderline pairs (see the face section above).
    2. **Find by Face**: Reverse image search. Pick a photo, choose one of its faces; shows "Looks like" people suggestions and ranked matching photos in the folder, badges coloured by confidence band (strong / likely / possible).
  - **Control Bar**: Real-time progress bar, item counters, Pause/Resume, and Rescan buttons. Overflow menu: **Regroup people**.
  - **Dialogs**: rename, merge confirmation, regroup confirmation.

#### [`app/src/main/java/com/nestgallery/viewer/ui/face/PersonDetailScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/face/PersonDetailScreen.kt)
- **Role**: Detailed gallery of photos containing a specific person cluster.
- **Functionality**:
  - Displays circular hero cover and cluster statistics (first item of the list / grid, so it scrolls away with the photos).
  - All photos in which this person's face was identified, as a square grid or a full-width list (toggle in the top
    bar; the choice is held in `MainActivity` so it sticks across people), with the shared `FastScrollbar`.
  - **Select** -> **Not this person**: removes the selected photos from the person and remembers it (they are never grouped back into this person).
  - Rename and hide-person actions (all routed through `FaceScannerManager` so the in-memory index stays in sync).

---

### Theme & Styling (`com.nestgallery.viewer.ui.theme`)

#### [`app/src/main/java/com/nestgallery/viewer/ui/theme/Theme.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/theme/Theme.kt)
- **Role**: Material 3 dark-mode color scheme and dynamic system theming.
- **Functionality**:
  - Custom dark palette with slate surfaces (`#14181D`), deep ink background (`#0B0E11`), and mint-teal accent (`#6FD3C7`).
  - Supports Android 12+ dynamic theming (`dynamicDarkColorScheme`).
  - Sets edge-to-edge transparent system bars.

#### [`app/src/main/java/com/nestgallery/viewer/ui/theme/Type.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/theme/Type.kt)
- **Role**: Typography definition.
- **Functionality**: Defines `titleLarge`, `titleMedium`, `bodyMedium`, and `labelSmall` styles for consistent typographic hierarchy.

---

## 3. Developer Guide & Extension Workflows

### How to Add a New Screen
1. Define a new entry in `Screen` in [`MainActivity.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/MainActivity.kt#L54-L62).
2. Add a `when (val s = screen)` branch inside `NestGalleryApp`.
3. Provide an `onBack: () -> Unit` callback that updates `screen = s.returnTo`.

### How to Adjust Face Recognition Precision
- **Grouping**: `ClusterConfig` in `PeopleClusterer.kt`. `linkThreshold` higher (e.g. `0.45f`) = fewer wrong merges, more people split in two; lower (`0.40f`) = the reverse; `askThreshold` sets how far below it "Same person?" questions go. `attachThreshold` / `attachMargin` control how readily leftover faces join a person. Bump `CLUSTER_ALGO` in `FaceScannerManager` to regroup existing libraries, and measure with `tools/face-eval/cluster.py` first.
- **Find by face bands**: `MATCH_STRONG / MATCH_LIKELY / MATCH_POSSIBLE` in `FaceMath.kt` (raw cosine, model specific).
- **Detection**: `FaceAnalyzer.MIN_DET_SCORE` and `FaceQuality.MIN_EYE_DIST`.

### How to Build & Run
- **Debug build**: `./gradlew assembleDebug`
- **Release build**: `./gradlew assembleRelease` (outputs signed APK to `app/build/outputs/apk/release/app-release.apk`)
