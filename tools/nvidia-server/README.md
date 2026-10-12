# NestGallery face endpoint (NVIDIA PC)

Runs the face scan's recogniser on your own Windows PC's NVIDIA GPU. The phone still decodes photos, finds and aligns
faces; it sends only the 112x112 face crops and gets their embeddings back. Photos never leave the phone.

## Setup (once)

1. Double-click `setup.cmd`. It downloads into **this folder only** (about 1.5 GB):
   `runtime\` (uv, a private Python 3.12, download cache), `.venv\` (ONNX Runtime GPU 1.22 + CUDA 12 / cuDNN 9.10
   runtime libraries from pip) and `models\` (the face model, checked by SHA-256). No CUDA toolkit, no system-wide
   Python, no PATH or registry changes. Only the normal NVIDIA driver is needed. To uninstall, delete the folder.
2. It ends with a GPU check that must print `OK: CUDAExecutionProvider`.

Pinned for a CUDA 12.x driver (tested combination on driver 566 / CUDA 12.7): newer onnxruntime-gpu wheels need
CUDA 13 and newer cuDNN versions failed on that driver.

## Use

* **USB (most private):** plug the phone in (USB debugging on), double-click `start.cmd`. It runs
  `adb reverse tcp:8765 tcp:8765` if the Android SDK is in the usual place, otherwise run that yourself.
  In the app: Settings > Face scan hardware > **NVIDIA PC**, endpoint `http://localhost:8765`.
* **Wi-Fi:** `start.cmd -Lan`, allow Python in the Windows firewall prompt (private networks only), and enter the
  `http://192.168.x.x:8765` address the window shows.

Enter the token from the window and press **Test connection**. A new random token is made at every start and never
saved; `start.cmd -Token yourtoken` keeps a fixed one.

## Privacy

* Nothing is written to disk: no images, no embeddings, no logs (the window only shows faces per second). No Python
  bytecode cache either.
* Face crops are in memory only while their batch runs on the GPU.
* Without `-Lan` the endpoint only listens on the PC itself (127.0.0.1), reachable from the phone only through USB.
  With `-Lan` it is plain HTTP on your local network, protected by the token; use it on networks you trust.

## Protocol

See the docstring of `server.py`. The model id (`adaface_ir101_gpu_from_int8:v1`) must match the app's
`RemoteEmbedder.MODEL_ID`; it is the same float graph as the phone's GPU option, so embeddings from the PC, the
phone's CPU and its GPU are comparable (LFW, 600 faces: cos 0.984 to the phone's int8 model, same accuracy).
