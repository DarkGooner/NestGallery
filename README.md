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
- **Breadcrumb bar** under the top app bar, always showing the full path;
  tap any segment to jump back. System back button also walks up one
  folder at a time.
- **List or grid view**, toggle in the top bar. List view renders each
  image full width with the filename underneath it.
- **Filename toggle** in the top bar — hide filenames entirely for a
  cleaner, image-only look, in both list and grid view.
- Optional filter to hide `*_thumb.*` / `*_locked.*` variant files.
- **Videos and animated GIFs**: thumbnails show a play badge on videos;
  tap one to play with standard Media3 controls, plus **double-tap the
  left/right half of the screen to seek back/forward 10 seconds** (with a
  brief on-screen confirmation), same as most video apps.
- **Full-screen image viewer** with swipe between images, pinch-to-zoom,
  and double-tap to zoom.
- Built for scale: a single batched directory read per folder (not one
  query per file), an in-memory cache so revisiting a folder or backing
  out of the viewer is instant, and lazy lists/grids that only decode
  on-screen thumbnails — comfortably handles folders with 5,000+ files.
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

