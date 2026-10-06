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
│  FsDirectory (Direct File IO)   │       │  FaceDetectorHelper (ML Kit) │
│  GalleryCache (Process Memory)  │       │  FaceEmbeddingHelper (TFLite)│
│  VlcPlayerController (LibVLC)   │       │  FaceClusterer (HAC 512D)    │
│                                 │       │  FaceScannerManager (Scope)  │
│                                 │       │  FaceDatabase (SQLite)       │
└─────────────────────────────────┘       └──────────────────────────────┘
```

### Key Architectural Tenets
1. **Zero-SAF Direct Filesystem Access**: Rather than slow cross-process Storage Access Framework (`SAF`) queries, NestGallery requests `MANAGE_EXTERNAL_STORAGE` (All Files Access) on Android 11+ (API 30+) to read the storage directly using standard `java.io.File`.
2. **On-Device Facial Recognition**: Uses Google ML Kit's face detector coupled with a 512-dimensional TensorFlow Lite model (**Google FaceNet-512**). All processing runs 100% locally on the device with zero cloud dependency.
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
  - TFLite compression rule: `aaptOptions { noCompress += listOf("tflite") }` to allow zero-copy memory mapping (`FileChannel.MapMode.READ_ONLY`) of the FaceNet-512 model directly from the APK assets.
  - Signing configurations for release builds pointing to `app/keystore/release.keystore`.
  - Dependencies: Jetpack Compose BOM, Coil (`coil-compose`, `coil-gif`, `coil-video`), LibVLC (`org.videolan.android:libvlc-all:3.7.6`), Google ML Kit (`face-detection:16.1.7`), TensorFlow Lite (`tensorflow-lite:2.16.1`, `tensorflow-lite-support:0.4.4`).
- **How to Modify**: To upgrade dependencies, add new ML models, or change SDK target levels.

#### [`app/src/main/AndroidManifest.xml`](file:///d:/Projects/NestGallery/app/src/main/AndroidManifest.xml)
- **Role**: Android application manifest.
- **Functionality**:
  - Requests `android.permission.MANAGE_EXTERNAL_STORAGE` (All Files Access) for direct disk access and `READ_EXTERNAL_STORAGE` for Android 10 (API 29) and lower.
  - Defines the single-activity entry point `MainActivity` with configuration changes handled (`orientation|screenSize|keyboardHidden|uiMode`) to prevent activity recreations during rotation.

#### [`app/proguard-rules.pro`](file:///d:/Projects/NestGallery/app/proguard-rules.pro)
- **Role**: Code obfuscation and R8 shrinking rules.
- **Functionality**: Protects TFLite, ML Kit, and LibVLC native JNI methods from being stripped out during release builds.

#### [`app/keystore/release.keystore`](file:///d:/Projects/NestGallery/app/keystore/release.keystore)
- **Role**: Pre-configured keystore for signing release APKs.

#### [`app/src/main/assets/facenet_512.tflite`](file:///d:/Projects/NestGallery/app/src/main/assets/facenet_512.tflite)
- **Role**: Pre-trained Google FaceNet-512 deep neural network in TensorFlow Lite format (24.3 MB).
- **Functionality**: Takes a 160×160 normalized RGB face crop and outputs a 512-dimensional feature embedding vector.

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

#### [`app/src/main/java/com/nestgallery/viewer/data/face/FaceDatabase.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceDatabase.kt)
- **Role**: SQLite persistence layer for face recognition metadata, embeddings, and cluster associations.
- **Functionality**:
  - Manages three indexed tables:
    - `indexed_files`: File path, last modified timestamp, size, and face count. Avoids redundant re-processing of unchanged files.
    - `people`: Cluster records (`id`, `name`, `cover_face_id`, `face_count`, `updated_at`).
    - `faces`: Detected face crops (`file_path`, bounding box `rect_left..rect_bottom`, `person_id`, 512D float array serialized as binary `BLOB`, `thumbnail_path`).
  - Scoped Query Functions:
    - `getPeopleInFolder(folderPath)`: Returns only clusters representing faces appearing in that folder.
    - `searchByEmbeddingInFolder(queryEmbedding, folderPath, threshold, limit)`: Performs vectorized cosine similarity search against faces in the specified folder.
    - `getFacesForClustering(folderPath)`: Fetches detected embeddings within the folder for agglomerative clustering.
    - `renamePerson(id, name)` & `deletePerson(id)`: Cluster management utilities.

#### [`app/src/main/java/com/nestgallery/viewer/data/face/FaceDetectorHelper.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceDetectorHelper.kt)
- **Role**: Fast on-device face detection with Google ML Kit.
- **Functionality**:
  - Configured with `PERFORMANCE_MODE_FAST` and `minFaceSize = 0.08f` (~15ms detection per image).
  - `decodeSampledBitmap(file, maxDimension = 512)`: Decodes images with downsampling to 512px and applies EXIF orientation rotation matrix.
  - `detectFacesInBitmap(bitmap)`: Detects faces, crops them with padding, aligns rotation along Euler angle Z (tilted heads), and scales crops to 160×160 for FaceNet.
  - Edge Case Handling: Includes boundary clamping when a face is partially cut off at image margins, and center-crop fallback when pre-cropped faces are provided.

#### [`app/src/main/java/com/nestgallery/viewer/data/face/FaceEmbeddingHelper.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceEmbeddingHelper.kt)
- **Role**: Feature extraction and embedding generation using TensorFlow Lite.
- **Functionality**:
  - Memory-maps `facenet_512.tflite` with 4-thread XNNPACK acceleration.
  - Input: 160×160 RGB bitmap.
  - Preprocessing: Standard pixel whitening `x' = (x - mean) / max(std, 1/sqrt(N))` across all channels.
  - Output: 512-dimensional float vector, normalized to unit length via L2 norm.
  - `cosineSimilarity(a, b)`: Vector dot-product calculation for unit vectors ($A \cdot B$).

#### [`app/src/main/java/com/nestgallery/viewer/data/face/FaceClusterer.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceClusterer.kt)
- **Role**: Agglomerative Hierarchical Clustering (HAC) algorithm for face grouping.
- **Functionality**:
  - Solves cluster fragmentation (e.g. stops the same person from being split into `Person 1`, `Person 55`, `Person 66`).
  - Metric: Blended link similarity:
    $$\text{Sim}(C_1, C_2) = 0.5 \times \text{CentroidSim} + 0.3 \times \text{AverageSim} + 0.2 \times \text{MaxPairSim}$$
  - Calibrated similarity threshold: `0.62f` for normalized FaceNet-512 embeddings.
  - Updates running cluster centroids and automatically selects the face closest to the centroid as the cover thumbnail.
  - Preserves existing user-assigned person names across incremental scans.

#### [`app/src/main/java/com/nestgallery/viewer/data/face/FaceScannerManager.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/data/face/FaceScannerManager.kt)
- **Role**: Background scanning coordinator, lifecycle manager, and progress state dispatcher.
- **Functionality**:
  - Exposes `status: StateFlow<ScanStatus>` (`Idle`, `Scanning`, `Paused`, `Completed`).
  - `startScanForFiles(files, folderPath)`: Takes candidate files from recursive scan, checks `isFileIndexedAndCurrent`, detects faces, extracts embeddings, saves cropped thumbnail images to disk, inserts DB records, and triggers `FaceClusterer`.
  - Supports pause/resume/cancellation without UI blocking.
  - `searchFaceInFolder(queryBitmap, folderPath)`: Extracts embedding from an input image and performs reverse face search in the folder.

---

### Presentation & UI Layer (`com.nestgallery.viewer.ui`)

#### [`app/src/main/java/com/nestgallery/viewer/ui/GalleryScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/GalleryScreen.kt)
- **Role**: Primary folder browser interface.
- **Functionality**:
  - Displays contents of current folder in a grid or staggered layout.
  - Supports navigation into subfolders and navigating up the directory stack.
  - Top Bar actions: Search folder, Recursive Explorer (`AccountTree`), filename toggle, hidden files toggle, and view mode switch.
  - Long-press preview: Renders animated live preview using `MediaImageTile`.

#### [`app/src/main/java/com/nestgallery/viewer/ui/ExploreScreen.kt`](file:///d:/Projects/NestGallery/app/src/main/java/com/nestgallery/viewer/ui/ExploreScreen.kt)
- **Role**: Recursive flattened explorer interface.
- **Functionality**:
  - Recursively discovers all media nested in subdirectories using `exploreMediaFlow`.
  - Displays media in a unified grid with sub-labels indicating relative folder paths.
  - Material 3 Redesigned Top Bar:
    - Direct actions: **Faces in folder** (launches `FolderFaceScreen`), **Search in folder**, **View mode toggle**.
    - Overflow Menu (`MoreVert`): Rescan folder, Show/hide filenames, Show/hide hidden items.

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
