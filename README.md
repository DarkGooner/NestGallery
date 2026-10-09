# NestGallery

A minimal, fast image/video browser for Android built for deep, nested
folder trees with thousands of files. Jetpack Compose + Material 3,
breadcrumb navigation, and a full-screen viewer with pinch-zoom and
double-tap video seeking. Browses your whole device directly, like a
regular file explorer (Z-Archiver / MiXplorer style) — no folder picker.

## Features

- **All Files Access** — grant it once, then it opens straight into
  device storage. No SAF folder-tree picker, no re-picking a folder every
  time you reinstall. A "Home" button in the top bar jumps back to the
  storage root from anywhere.
- **Floating top capsule** — search, recursive scan and a grid/list switch stay pinned
  while the title/path header folds away as you scroll. The path is shown as tappable
  chips; tap any segment to jump back. System back button also walks up one
  folder at a time.
- **List or grid view**, toggle in the top bar. List view renders each
  image full width with the filename underneath it.
- **Hidden items toggle** (eye icon) — hides dotfiles/dotfolders (the
  standard "hidden" convention), same as any file explorer.
- **Fast-scroll bar** on the right edge of long lists/grids — drag it to
  jump through thousands of items quickly. Fades out automatically when
  idle.
- **Recursive explorer** — the tree icon (on the top bar, or on any
  individual folder row/tile) scans that folder and every folder beneath
  it and flattens all the media into one continuous, infinitely-scrolling
  view. Results stream in as they're found rather than waiting for the
  whole tree to finish, so it stays responsive even on folders with tens
  of thousands of nested files. With filenames toggled on, each item also
  shows its path relative to the folder you started the scan from.
- **Hold to preview** (grid view) — press and hold any image or video tile
  to pop it up full-size over a dimmed, blurred background, Instagram-reel
  style. Videos auto-play muted and looped while held. Release to dismiss.
  (The background blur needs Android 12+; on older versions it falls back
  to just the dim scrim.)
- **Filename toggle** in the top bar — hide filenames (and, in the
  recursive explorer, relative paths) entirely for a cleaner, image-only
  look, in both list and grid view.
- **Video player (LibVLC)**: plays what VLC plays - MP4/MOV, MKV/WebM, AVI/DivX/Xvid, WMV/ASF, FLV, MPEG-TS/M2TS
  (camcorder and TV recordings), MPG/VOB, OGV, RM/RMVB, DV/MXF and more, with their audio and subtitle tracks.
  - **Scrub bar**: tap to jump, drag to scrub with a frame preview above the thumb; elapsed and remaining time (tap
    for the total).
  - **Gestures**: tap shows the controls; double-tap left / right seeks -/+10 s (keep tapping for 20, 30 s ...); hold
    for 2x speed; swipe up / down on the left for brightness, on the right for volume. Swiping sideways still moves
    to the next photo or video.
  - **Controls**: -10 s / play / +10 s, lock (ignores touches until unlocked), picture fit (Fit, Fill, Stretch,
    16:9, 4:3, 21:9, 100%), speed (0.25x-4x), loop, rotate. The top bar names the file with its resolution, codec and
    frame rate, and has audio-track and subtitle pickers when the file has a choice (subtitle files with the same
    name next to the video are picked up).
  - **Remembers where you stopped** in each video ("Resumed at 12:34 · Start over"); keeps the screen on while
    playing; pauses when you leave the app or swipe to another item.
  - **Decoding**: software by default (most compatible with old AVI / DivX / WMV); hardware decoding is a switch in
    the player's settings for heavy 4K / HEVC files, and falls back to software by itself if a file fails.
  - Animated GIFs play in the grid and viewer; tiles of videos Android can't thumbnail show their file type.
- **Full-screen image viewer** with swipe between images, pinch-to-zoom,
  and double-tap to zoom. The **"i" button** shows the file's details: full path, size, dates,
  resolution, and camera EXIF / GPS for photos or duration / bitrate for videos.
- **Works with 3-button and gesture navigation**, portrait and landscape: controls stay clear of the
  navigation bar, the status bar and camera cutouts.
- **On-Device Facial Recognition (Google Photos style)** — Discover and group
  people in your photos completely offline with zero server calls.
  - **People & Pets view**: circular face avatar previews, editable names
    (e.g. "Mom", "Alice"), and instant photo count. Tap any person to view
    all their photos, in a grid or full-width list with the fast-scroll bar. Fix mistakes like in Google Photos: long-press people to
    **merge** them, or select photos in a person and mark them **Not this person**
    (remembered, so they are never grouped back). **Regroup people** rebuilds
    everything you haven't named.
  - **Find / Search by Face**: pick any photo (from storage, camera, or
    tap a face directly in the full-screen photo viewer) to instantly search
    the entire gallery for that person, ranked by match percentage (e.g. 98% Match).
  - **Edge-case resilience**: safely handles cut/cropped faces near photo borders
    without crashes, aligns tilted/rotated faces via Euler angle correction,
    detects partial profiles, and includes fallback center cropping for pre-cropped face inputs.
  - **Real people and 3D renders** (Daz3D, Blender, game characters): AdaFace IR-101
    (int8) embeddings and average-linkage clustering, measured on real photos (LFW, cross-pose
    and cross-age LFW) and CGI renders (DigiFace-1M, including 72 renders per character with
    varied expression, lighting and synthetic hand/food occlusion) at 99%+ grouping precision
    (BCubed). Borderline pairs are asked as **"Same person?"** (with an `n / total` counter) instead of guessed - details in
    `tools/face-eval/README.md`.
  - **Engineered for 10,000-20,000+ photos**: SCRFD detector + AdaFace run through ONNX
    Runtime; a parallel decode/analyse/write pipeline; clustering on an ONNX
    k-nearest-neighbour graph that keeps people you named or corrected exactly as you left them; an int8
    in-memory index that searches 50k faces in tens of milliseconds. Everything is local.
- **NSFW scan and filters** (own screen from the recursive view, fully on-device): see below.
- Dark theme with Material You dynamic color on Android 12+.

## NSFW scan

In the recursive view (tree icon), the **shield** button opens the folder's NSFW screen (like **Faces**). It scans
every photo in the folder and its subfolders with NudeNet, on the device, and lets you filter them by what was found.

- **What is detected** (NudeNet v3): `FACE_FEMALE`, `FACE_MALE`, `FEMALE_BREAST_EXPOSED` / `_COVERED`,
  `FEMALE_GENITALIA_EXPOSED` / `_COVERED`, `MALE_GENITALIA_EXPOSED`, `MALE_BREAST_EXPOSED`, `BUTTOCKS_*`, `ANUS_*`,
  `BELLY_*`, `ARMPITS_*`, `FEET_*`. Each photo's result has the shape `{"width", "height", "labels", "detections":
  [{"label", "score", "box": [x, y, w, h]}], "ms"}` (`NsfwResult.toJson()`), and the viewer's **i** sheet shows its tag
  string, e.g. `2FACE_FEMALE, 1MALE_GENITALIA_EXPOSED, 1FEMALE_BREAST_COVERED`.
- **Scanning** (refresh button) runs in the background like the face scan: a notification with progress, photos/s and
  time left, Pause / Resume / Stop, and the same progress card on the screen. Results are saved as they come in, so the
  filters work while the scan runs, a stopped scan resumes, and later scans only look at new or changed photos. Videos
  are skipped.
- **Photos and filters**: the screen shows the matching photos, with a chip row above them: **Filters** (opens the
  filter sheet), **Something detected** (hides photos where nothing was found), one removable chip per active filter
  (tap to edit it), and while nothing is filtered a few one-tap suggestions ("+ Buttocks · exposed"). Under it,
  "N of M photos match" and **Clear all**. Tapping a photo opens the viewer on that set.
- **Filter sheet**: the hide-nothing-detected switch; **detection confidence** as 30 / 45 / 60 / 75% (default 45%;
  changing it never rescans, because every detection down to 25% is stored); then one row per label found, grouped
  Faces / Exposed / Covered, saying how many photos have it among those the other filters leave. Expanding a row offers
  Any / None / 1+ / 2+ / 3+, each with the number of photos it would leave (choices that leave none are greyed out),
  and **Custom** for any from-to range with +/- buttons. A photo passes only if it fits **every** filter (e.g. Female
  face "Exactly 2" = two women's faces). Reset at the top, **Show N photos** at the bottom.
- **Model**: NudeNet 320n (YOLOv8n, 12 MB). About 40 photos/s on a Snapdragon 7 Gen 3, so 20,000 photos take under
  10 minutes. (The larger 640m variant was tried and dropped: about 2 photos/s on the same phone.)
- **Hardware** (Settings, from the 3-dot menu here, in the folder browser and in the recursive view): *Auto* (default:
  NPU, then GPU, then CPU), *NPU* (Snapdragon Hexagon through Qualcomm QNN, running a quantised copy of the model),
  *GPU* (Adreno, experimental) or *CPU*. Whatever fails on a phone falls back to the CPU; one that crashes the app is
  skipped from then on (Settings can retry it). The first NPU run compiles the model for the chip and caches it.
  **Speed test** measures every option on the phone the way a scan runs (photos/s and the time for 20,000 photos) and
  offers to switch to the fastest. The app is 64-bit only (arm64) because the QNN build of ONNX Runtime is.
- **NPU on phones that hide the DSP's C++ runtime** (the speed test's details say "Failed to initialize
  qnn_model_wrapper" and "libc++.so.1 (No such file)"): Qualcomm's NPU code needs the DSP's own `libc++.so.1` and
  `libc++abi.so.1`, which live in `/vendor/dsp/cdsp/` where apps may not read them. If adb can read that folder, copy
  them off the phone once and the app uses them:

  ```
  adb shell mkdir -p /sdcard/NestGallery/dsp
  adb pull /vendor/dsp/cdsp/libc++.so.1
  adb pull /vendor/dsp/cdsp/libc++abi.so.1
  adb push libc++.so.1 libc++abi.so.1 /sdcard/NestGallery/dsp/
  ```

  On the motorola edge 50 pro even adb gets "Permission denied" there, so its NPU is out of reach without root;
  320n on the CPU or GPU is fast enough anyway.
- **Models** are bundled in `app/src/main/assets` (no download, no network) and stored with **Git LFS**. After
  cloning, run `git lfs install` once and `git lfs pull` before building; an APK built from a clone without them shows
  "...is a Git LFS pointer" when a scan starts. CI fetches them itself. Where the models come from and how they were
  checked: `tools/nsfw-eval/README.md`. Licence: NudeNet is AGPL-3.0.
- **Accuracy**: no detector is perfect, especially on drawings and 3D renders; expect misses and false hits around the
  threshold. The pipeline was checked against NudeNet's reference code, but recall on explicit images has not been
  measured.

## Setup: just push

There's no manual signing setup. A release keystore is already generated
and committed at `app/keystore/release.keystore`, and
`app/build.gradle.kts` points straight at it. Your only step:

1. Create a new GitHub repository.
2. Push the contents of this folder to its `main` branch.

That's it — the push itself triggers `.github/workflows/build.yml`, which
builds and signs the APK automatically. When the run finishes:
- a signed `app-release.apk` is attached as a build artifact on that run, and
- a new entry appears on the repo's **Releases** page with the APK attached.

You can also trigger a build without pushing new code: **Actions** tab →
**Build and Sign APK** → **Run workflow**.

> **Security note:** the keystore is committed in plain sight so the
> workflow needs zero configuration. That's fine for a personal app you
> sideload yourself, but anyone with read access to the repo could rebuild
> and resign an app with the same identity. If that matters to you, keep
> the repo **private** (free on GitHub for personal repos).

## Install on your phone

Download the APK from the Release (easiest on mobile), then open it.
Android will prompt you to allow installs from that source the first
time — allow it, then tap install. Because the signing key stays the same
across every CI build, you can just install future releases over the old
one without uninstalling first.

## First launch

You'll see a one-time **"Grant access"** prompt. On Android 11+, this
opens the system's All Files Access settings screen for the app — flip
the toggle on and go back. On Android 10 and below, it's a normal runtime
permission dialog. After that, NestGallery opens straight into device
storage every time; nothing to pick or remember.

## Notes / things you may want to tweak

- Supported image types: jpg, jpeg, png, webp, gif, bmp, heic. Video types: mp4, m4v, mov, qt, 3gp, 3g2, f4v, mkv,
  mk3d, webm, avi, divx, xvid, wmv, asf, flv, ts, m2ts, mts, m2t, tp, trp, mpg, mpeg, mpe, m1v, m2v, mpv, vob, dat,
  ogv, ogm, rm, rmvb, dv, mxf, nut, y4m, ivf, amv and raw h264 / h265 / hevc (`videoExtensions` in `FsDirectory.kt`).
- Folder item counts are computed with one batched directory read per
  visible folder row and cached afterward, so revisiting a folder is free.
- If Android Studio warns about a missing Gradle wrapper when you open the
  project locally, either let Android Studio generate one for you, or
  ignore it — the GitHub Actions workflow installs Gradle directly and
  doesn't need it.

