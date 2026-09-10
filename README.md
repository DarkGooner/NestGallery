# NestGallery

A minimal, fast image browser for Android built for deep, nested folder trees.
Jetpack Compose + Material 3, no thumbnail cache files, breadcrumb navigation,
and a full-screen pinch-zoom viewer.

## Features

- **Storage Access Framework** folder picking — point it at any root folder
  (e.g. `Download/1D...`) once; it remembers the choice.
- **Breadcrumb bar** under the top app bar, always showing the full path from
  the root; tap any segment to jump back.
- **List or grid view**, toggle in the top bar. List view renders each image
  full width with the filename underneath it — no thumbnail cropping.
- Optional filter to hide `*_thumb.*` / `*_locked.*` variant files.
- **Full-screen viewer** with swipe between images, pinch-to-zoom, and
  double-tap to zoom.
- Dark theme with Material You dynamic color on Android 12+.
- Images are decoded directly by Coil — nothing is written to a hidden
  thumbnail cache folder on disk.

## Setup: just push

There's no manual signing setup. A release keystore is already generated and
committed at `app/keystore/release.keystore`, and `app/build.gradle.kts`
points straight at it. Your only step:

1. Create a new GitHub repository.
2. Push the contents of this folder to its `main` branch.

That's it — the push itself triggers `.github/workflows/build.yml`, which
builds and signs the APK automatically. When the run finishes:
- a signed `app-release.apk` is attached as a build artifact on that run, and
- a new entry appears on the repo's **Releases** page with the APK attached.

You can also trigger a build without pushing new code: **Actions** tab →
**Build and Sign APK** → **Run workflow**.

> **Security note:** the keystore is committed in plain sight so the workflow
> needs zero configuration. That's fine for a personal app you sideload
> yourself, but anyone with read access to the repo could rebuild and
> resign an app with the same identity. If that matters to you, keep the
> repo **private** (free on GitHub for personal repos).

## Install on your phone

Download the APK from the Release (easiest on mobile), then open it. Android
will prompt you to allow installs from that source (Files/Chrome/whatever
app you downloaded it with) the first time — allow it, then tap install.
Because the signing key stays the same across every CI build, you can just
install future releases over the old one without uninstalling first.

## First launch

Tap **Choose folder**, grant access to your root folder. NestGallery
remembers it for next time, so you land straight in the browser on
future launches.

## Notes / things you may want to tweak

- `minSdk` is 26 (Android 8.0+) to keep the launcher icon setup simple
  (adaptive icons only, no legacy PNG mipmaps to generate).
- Folder image counts are computed by scanning each folder's children, so
  very large folders (thousands of files) may make the folder row for that
  particular directory take a moment to compute the count. Browsing and
  image loading itself stays fast either way.
- Supported image types: jpg, jpeg, png, webp, gif, bmp, heic.
- If Android Studio warns about a missing Gradle wrapper when you open the
  project locally, either let Android Studio generate one for you
  (File → "Create Gradle Wrapper" prompt), or ignore it — the GitHub Actions
  workflow doesn't need it since it installs Gradle directly.


## Performance improvements for very large folders

This version is optimized for folders containing thousands to tens of thousands of media files:

- Uses a direct `DocumentsContract` child-document query instead of `DocumentFile.listFiles()` plus per-file `length()` calls. This drastically reduces SAF provider/Binder work.
- Avoids calculating media counts for every visible folder row. Those counts caused additional directory scans and could make large folder screens much slower.
- Uses a URI → image-index map instead of calling `imagesOnly.indexOf()` for every composed item (which was O(n) per item).
- Keeps folder listings in the process cache so returning to a folder does not rescan it.
- Adds Coil memory/disk caching and disables crossfade for faster scrolling.
- Thumbnail decoding remains constrained by the Compose grid item's size, so the gallery does not need to decode every original-resolution image just to display a thumbnail.

## 50K-image performance

The fast build is designed for very large Android SAF folders (10,000-50,000+ files):
- enumerates children with one `DocumentsContract` query instead of `DocumentFile.listFiles()` metadata calls;
- keeps only `Uri` + primitive metadata for each child and creates `DocumentFile` only when a folder is opened;
- uses lazy Compose lists/grids so only visible thumbnails are composed;
- uses a process-lifetime LRU cache for recently visited folders;
- uses Coil memory/disk caching with crossfade disabled for fast scrolling;
- avoids per-folder media-count scans.
