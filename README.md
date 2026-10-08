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
- **Videos and animated GIFs**: thumbnails show a play badge on videos;
  tap one to play with standard Media3 controls, plus **double-tap the
  left/right half of the screen to seek back/forward 10 seconds** (with a
  brief on-screen confirmation), same as most video apps.
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
- Dark theme with Material You dynamic color on Android 12+.

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

- Supported image types: jpg, jpeg, png, webp, gif, bmp, heic. Supported
  video types: mp4, mkv, webm, mov, 3gp, m4v, avi.
- Folder item counts are computed with one batched directory read per
  visible folder row and cached afterward, so revisiting a folder is free.
- If Android Studio warns about a missing Gradle wrapper when you open the
  project locally, either let Android Studio generate one for you, or
  ignore it — the GitHub Actions workflow installs Gradle directly and
  doesn't need it.

