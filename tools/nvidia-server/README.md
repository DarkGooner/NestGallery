# NestGallery face endpoint (NVIDIA PC)

Runs the face scan on your own Windows PC's NVIDIA GPU. The phone sends each photo; the PC finds the faces (SCRFD),
aligns them and computes their embeddings (AdaFace), exactly the steps the phone runs itself, and sends back only the
face boxes, landmarks and embeddings. Small JPEG / PNG / WebP files are sent as they are; bigger photos are decoded on
the phone at reduced size (long side 800-1600 px, as the phone's own scan does) and sent as a JPEG.

Why whole photos and not face crops (the first version): with detection on the phone, the phone's CPU (decode +
SCRFD, ~90 ms per photo) held a scan at about 30 photos/s however fast the PC was.

## Setup (once)

1. Double-click `setup.cmd`. It downloads into **this folder only** (about 1.5 GB):
   `runtime\` (uv, a private Python 3.12, download cache), `.venv\` (ONNX Runtime GPU 1.22 + CUDA 12 / cuDNN 9.10
   runtime libraries from pip, Pillow) and `models\` (the detector and recogniser, checked by SHA-256). No CUDA
   toolkit, no system-wide Python, no PATH or registry changes. Only the normal NVIDIA driver is needed. To uninstall,
   delete the folder. Updating from the first version: copy the new folder over the old one and run `setup.cmd` again.
2. It ends with a GPU check that must print `OK: CUDAExecutionProvider`.

Pinned for a CUDA 12.x driver (tested combination on driver 566 / CUDA 12.7): newer onnxruntime-gpu wheels need
CUDA 13 and newer cuDNN versions failed on that driver.

## Use

* **USB:** plug the phone in (USB debugging on), double-click `start.cmd`. It runs `adb reverse tcp:8765 tcp:8765`
  if the Android SDK is in the usual place, otherwise run that yourself.
  In the app: Settings > Face scan hardware > **NVIDIA PC**, endpoint `http://localhost:8765`.
* **Wi-Fi:** `start.cmd -Lan`, allow Python in the Windows firewall prompt (private networks only), and enter the
  `http://192.168.x.x:8765` address the window shows. Big photos are shrunk to a few hundred KB first, but Wi-Fi can
  still be the limit on a slow network; USB avoids that.

Press **Test connection** in the app. The window prints photos and faces per second while a scan runs.

## Privacy

* Nothing is written to disk: no photos, no embeddings, no logs (the window only shows photos per second). No Python
  bytecode cache either. Each photo is in memory only while its request runs.
* There is no password / token. Without `-Lan` the endpoint only listens on the PC itself (127.0.0.1), reachable from
  the phone only through USB. With `-Lan` it is plain HTTP on your local network: anyone on that network could send it
  photos or watch the traffic (which now contains your photos), so use `-Lan` only on a network you trust, such as
  your home Wi-Fi.

## Protocol

See the docstring of `server.py` (`GET /v1/health`, `POST /v1/analyze`). The model id
(`adaface_ir101_gpu_from_int8:v1`) and `api` 2 must match the app (`RemoteFaceAnalyzer`); the recogniser is the
same float graph as the phone's GPU option, so embeddings from the PC, the phone's CPU and its GPU are comparable
(LFW, 600 faces: cos 0.984 to the phone's int8 model, same accuracy).
