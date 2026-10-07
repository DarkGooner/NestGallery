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
│  VlcPlayerController (LibVLC)   │       │  PersonClusterer + FaceStore │
│                                 │       │  FaceScannerManager (Scope)  │
│                                 │       │  FaceDatabase (SQLite)       │
└─────────────────────────────────┘       └──────────────────────────────┘
```

### Key Architectural Tenets
1. **Zero-SAF Direct Filesystem Access**: Rather than slow cross-process Storage Access Framework (`SAF`) queries, NestGallery requests `MANAGE_EXTERNAL_STORAGE` (All Files Access) on Android 11+ (API 30+) to read the storage directly using standard `java.io.File`.
2. **On-Device Facial Recognition**: InsightFace **SCRFD-500MF** (detector, 5 landmarks) + **ArcFace MobileFaceNet / WebFace600K** (512-d embeddings) executed by ONNX Runtime. Faces are aligned to the ArcFace 112x112 template from the landmarks. All processing runs 100% locally. *Model licence: InsightFace's pretrained models are non-commercial research only.*
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

#### `app/src/main/assets/scrfd_500m.onnx` (2.4 MB) and `arcface_mbf.onnx` (13 MB)
- **Role**: SCRFD-500MF face detector and ArcFace MobileFaceNet (`w600k_mbf`) recogniser from InsightFace's `buffalo_s` pack.
- **Contract**: detector input `[1,3,H,W]` (RGB, `(x-127.5)/128`, we use 640x640 letterboxed top-left), 9 outputs (score/box/5-landmark per stride 8/16/32). Recogniser input `[1,3,112,112]` (RGB, `(x-127.5)/127.5`), output 512-d.
- **Swapping the recogniser**: `w600k_r50.onnx` (buffalo_l/m) has the identical interface and is more accurate on hard/demographically balanced benchmarks, but ~8x heavier. Replace the asset, change `ArcFaceEmbedder.ASSET` and bump `MODEL_ID` (the DB drops incompatible embeddings automatically).

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

Pipeline: `decode (EXIF-correct, >=800px)` -> `SCRFD detect @640` -> `5-point similarity alignment to 112x112` -> `ArcFace embed (512-d)` -> `quality score` -> `int8 store + SQLite` -> `incremental clustering`.

| File | Android-free? | Role |
|---|---|---|
| `FaceMath.kt` | yes | closed-form similarity transform, SCRFD head decoding + NMS, quality score, match-% calibration |
| `FaceStore.kt` | yes | `Int8Matrix` (chunked int8 embeddings, ~4x smaller than float32) and `FaceStore`: thread-safe in-memory index with exact folder-scoped cosine search |
| `ImmichClusterer.kt` | yes | port of Immich's DBSCAN-style recognition clustering + duplicate-person reconciliation (see below) |
| `FaceScanService.kt` | no | foreground service (data-sync) + wake lock + progress notification so scans survive minimising / screen-off |
| `OnnxFaceModels.kt` | no | `ScrfdDetector`, `ArcFaceEmbedder` (ONNX Runtime, 1 intra-op thread per session so several images run in parallel) |
| `FaceImage.kt` | no | `FaceImageLoader` (sampled decode, all 8 EXIF orientations), `FaceAligner` (Skia matrix warp), `FaceAnalyzer`, `FaceThumbnails` |
| `FaceDatabase.kt` | no | SQLite v3 (WAL), batched writes, int8 embedding blobs, index-friendly folder range queries |
| `FaceScannerManager.kt` | no | parallel scan pipeline, grouping, cover thumbnails, query analysis + search |

**Scan pipeline** (`FaceScannerManager.runPipeline`): 1-2 decoder coroutines (IO) feed a bounded channel; 1-4 analysis workers (CPU) run detect+align+embed; one writer commits batches of up to 32 photos in a single transaction and appends to the in-memory index. Already-indexed files are skipped using one bulk query. Pause stops new decodes; cancel still groups what was saved.

**Clustering** (`ImmichClusterer`): a port of Immich's `handleRecognizeFaces`. For each unassigned face, find the nearest faces within cosine distance 0.5 (exact int8 scan, multi-threaded). Faces with no neighbour are noise; a face with >= `minFaces` (2; Immich uses 3) faces in range is a *core* point; non-core faces are deferred until all others are processed. A face joins the person of its nearest assigned neighbour; a core face with none starts a new person. Two additions to Immich: (1) **reconcile** - Immich never merges people it has split, so when a core face bridges two people they are merged (this is what fixes "same person shows up as two"); (2) **same-photo rule** - faces in one photo are never the same person, never neighbours, and people sharing a photo are never merged. Named people are never merged with each other and always survive. `person_id = -1` is a dismissed face. On first run after upgrading, unnamed groups made by the old algorithm are rebuilt (`cluster_algo` meta key). Config in `ImmichConfig`.

**Background scanning**: `FaceScannerManager` starts `FaceScanService` (foreground, `dataSync`) when a scan starts. The service holds a partial wake lock, shows a progress notification (photos/s, Pause / Resume / Stop) and stops itself when the scan ends. Status is published before the service starts so it can't see a stale Idle. Progress is committed in batches, so if Android still kills the process the next scan resumes (indexed photos are skipped). Android 13+ asks for notification permission before the first scan; Android 15 limits dataSync services to ~6h/day, handled in `onTimeout`.

**Scan speed**: detector input is the photo's own aspect ratio rounded up to a multiple of 32 instead of a fixed 640x640 square (about 40-55% less detector time on 4:3 / 16:9 photos in tests, same detections on 4 of 5 sample photos), detection score floor 0.7 and a minimum face size skip junk faces, faces of a photo are embedded in one batched model call (about 18% faster per face).

**Search**: exact brute-force cosine over the int8 index, scoped to the folder by path prefix. Raw cosine is stored/compared; `FaceMath.matchProbability` only converts it to the displayed percentage. Sensitivity chips: Strict 0.52 / Balanced 0.42 / Broad 0.34. Long-press on a result (or on a person's photo) shows the same hold-to-preview as the gallery (`Modifier.holdPreviewGestures`).

**Cover thumbnails** are rendered lazily, only for the face chosen as each (visible) person's cover, into `filesDir/face_thumbs/f<faceId>.jpg`.

**Verification tooling**: `tools/face-eval/` holds the Python reference pipeline, the labeled-pair evaluation and the JVM test harness used to validate the Kotlin core.

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

#### [`app/src/main/java/com/nestgallery/viewer/ui/ImageViewerScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/ImageViewerScreen.kt)
- **Role**: Fullscreen photo viewer and video player.
- **Functionality**:
  - Horizontal swipe pager (`HorizontalPager`) across the media set.
  - `ZoomableImage`: Pinch-to-zoom, double-tap zoom, and panning using Compose pointer gestures.
  - `VideoPlayer`: Custom LibVLC-backed playback controls with scrubbing bar, play/pause, timecode, and scrubbing preview window.

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

---

### Face Recognition UI (`com.nestgallery.viewer.ui.face`)

#### [`app/src/main/java/com/nestgallery/viewer/ui/face/FolderFaceScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/face/FolderFaceScreen.kt)
- **Role**: Scoped facial recognition hub for the active recursive exploration.
- **Functionality**:
  - **Tabs**:
    1. **People**: Displays detected person clusters with face count badges and circular cover thumbnails.
    2. **Find by Face**: Reverse image search. Allows picking an input photo (from device or gallery), detects query faces, and returns ranked matching photos in the current folder with similarity percentages.
  - **Control Bar**: Real-time progress bar, item counters, Pause/Resume, and Rescan buttons.
  - **Dialogs**: Inline person renaming dialog.

#### [`app/src/main/java/com/nestgallery/viewer/ui/face/PersonDetailScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/face/PersonDetailScreen.kt)
- **Role**: Detailed gallery of photos containing a specific person cluster.
- **Functionality**:
  - Displays circular hero cover and cluster statistics.
  - Grid of all photos in which this person's face was identified.
  - Renaming and cluster deletion actions.

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
- **Sensitivity / Similarity Threshold**: Edit `similarityThreshold` in [`FaceClusterer.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceClusterer.kt#L14). Lower values (e.g. `0.58f`) group more aggressively across large age gaps; higher values (e.g. `0.68f`) ensure stricter separation.
- **Detection Sensitivity**: Adjust `minFaceSize` in [`FaceDetectorHelper.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceDetectorHelper.kt#L47).

### How to Build & Run
- **Debug build**: `./gradlew assembleDebug`
- **Release build**: `./gradlew assembleRelease` (outputs signed APK to `app/build/outputs/apk/release/app-release.apk`)
