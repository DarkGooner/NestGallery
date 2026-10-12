"""NestGallery face endpoint: runs the face recogniser on an NVIDIA GPU for the phone's face scan.

The phone still decodes photos, finds faces (SCRFD) and aligns them; it sends only the 112x112 aligned face crops here
and gets back one 512-d embedding per face. The model is the same float AdaFace graph the phone's GPU option runs
(app/src/main/assets/adaface_ir101_gpu.onnx, weights read from adaface_ir101_int8.onnx), so embeddings land in the
phone's vector space (LFW: cos 0.984 to the phone's CPU model, same accuracy).

Privacy: nothing is written to disk. Face crops live in memory only while their batch runs, and nothing about them
is logged (only a faces-per-second counter). No Python bytecode cache is written either (start.ps1 sets
PYTHONDONTWRITEBYTECODE).

    python server.py [--host 127.0.0.1] [--port 8765] [--token TOKEN] [--allow-cpu]

Protocol (HTTP/1.1, keep-alive):
    GET  /v1/health  -> JSON {"app", "model_id", "provider", "max_faces"}       (header X-Nest-Token required)
    POST /v1/embed   body = N x 112 x 112 x 3 bytes (RGB, row-major), header X-Faces: N
                     -> N x 512 little-endian float32, L2-normalised
"""
import argparse, hmac, http.server, json, os, queue, secrets, socket, sys, threading, time
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
GRAPH = "adaface_ir101_gpu.onnx"
# What the app checks before using this endpoint: same graph as ArcFaceEmbedder.GPU_ASSET.
MODEL_ID = "adaface_ir101_gpu_from_int8:v1"
SIZE, DIM = 112, 512
FACE_BYTES = SIZE * SIZE * 3
MAX_FACES = 64            # per request; the phone sends one photo's faces (it keeps at most 40)
BATCH = 256               # faces per GPU run; requests arriving together are batched
WAIT_S = 0.004            # how long the batcher waits for more requests once one is queued


def load_session(allow_cpu):
    import onnxruntime as ort
    if hasattr(ort, "preload_dlls"):
        ort.preload_dlls()          # CUDA / cuDNN from the nvidia-* pip packages inside this folder's .venv
    o = ort.SessionOptions()
    o.log_severity_level = 3
    # folds the int8 weights' DequantizeLinear nodes into float weights: a plain float network for CUDA
    o.add_session_config_entry("session.disable_quant_qdq", "1")
    cuda = ("CUDAExecutionProvider", {"cudnn_conv_algo_search": "EXHAUSTIVE"})
    providers = [cuda] + (["CPUExecutionProvider"] if allow_cpu else [])
    try:
        s = ort.InferenceSession(os.path.join(MODELS, GRAPH), o, providers=providers)
    except Exception as e:
        if not allow_cpu: raise SystemExit(f"Could not start on the GPU: {e}")
        s = ort.InferenceSession(os.path.join(MODELS, GRAPH), o, providers=["CPUExecutionProvider"])
    provider = s.get_providers()[0]
    if provider != "CUDAExecutionProvider" and not allow_cpu:
        # ONNX Runtime silently falls back to the CPU when CUDA / cuDNN don't load; never serve slowly by surprise
        raise SystemExit("CUDA did not load (running on the CPU instead). Run setup.ps1 again; see README.")
    return s, provider


class Batcher:
    """Collects the faces of concurrent requests into one GPU run."""

    def __init__(self, session):
        self.session = session
        self.input = session.get_inputs()[0].name
        self.q = queue.Queue()
        self.faces_done = 0
        threading.Thread(target=self._loop, daemon=True).start()

    def embed(self, faces_u8):
        job = {"x": faces_u8, "done": threading.Event()}
        self.q.put(job)
        job["done"].wait()
        if "error" in job: raise job["error"]
        return job["y"]

    def _loop(self):
        while True:
            jobs = [self.q.get()]
            n = len(jobs[0]["x"])
            deadline = time.perf_counter() + WAIT_S
            while n < BATCH:
                left = deadline - time.perf_counter()
                if left <= 0: break
                try: j = self.q.get(timeout=left)
                except queue.Empty: break
                jobs.append(j); n += len(j["x"])
            try:
                x = np.concatenate([j["x"] for j in jobs])
                x = ((x.astype(np.float32) - 127.5) / 127.5).transpose(0, 3, 1, 2)
                y = self.session.run(None, {self.input: np.ascontiguousarray(x)})[0].astype(np.float32)
                y /= np.maximum(np.linalg.norm(y, axis=1, keepdims=True), 1e-12)
                k = 0
                for j in jobs:
                    m = len(j["x"]); j["y"] = y[k:k + m]; k += m
                self.faces_done += n
            except Exception as e:      # e.g. GPU out of memory: fail these requests, keep serving
                for j in jobs: j["error"] = e
            del x
            for j in jobs:
                j["x"] = None; j["done"].set()


def make_handler(batcher, token, provider):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args): pass          # no request logs: nothing about the phone or its photos

        def _send(self, code, body, ctype="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if code != 200:             # the request body may be unread: don't reuse this connection
                self.send_header("Connection", "close"); self.close_connection = True
            self.end_headers()
            self.wfile.write(body)

        def _authorised(self):
            if hmac.compare_digest(self.headers.get("X-Nest-Token", ""), token): return True
            self._send(401, b'{"error":"wrong or missing token"}')
            return False

        def do_GET(self):
            if self.path != "/v1/health": return self._send(404, b'{"error":"not found"}')
            if not self._authorised(): return
            self._send(200, json.dumps({"app": "nestgallery-face", "model_id": MODEL_ID, "provider": provider,
                                        "max_faces": MAX_FACES}).encode())

        def do_POST(self):
            if self.path != "/v1/embed": return self._send(404, b'{"error":"not found"}')
            if not self._authorised(): return
            try:
                n = int(self.headers.get("X-Faces", "-1")); length = int(self.headers.get("Content-Length", "-1"))
            except ValueError:
                n, length = -1, -1
            if not (1 <= n <= MAX_FACES) or length != n * FACE_BYTES:
                return self._send(400, b'{"error":"body must be X-Faces x 112 x 112 x 3 bytes"}')
            body = self.rfile.read(length)
            if len(body) != length: return
            faces = np.frombuffer(body, np.uint8).reshape(n, SIZE, SIZE, 3)
            try:
                y = batcher.embed(faces)
            except Exception as e:
                return self._send(500, json.dumps({"error": str(e)[:200]}).encode())
            finally:
                del body, faces
            self._send(200, y.astype("<f4").tobytes(), "application/octet-stream")

    return Handler


def lan_addresses():
    out = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if not info[4][0].startswith("127."): out.add(info[4][0])
    except OSError: pass
    return sorted(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1", help="127.0.0.1 = USB only (adb reverse); 0.0.0.0 = also Wi-Fi")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--token", default=None, help="default: a new random token each start (never saved)")
    ap.add_argument("--allow-cpu", action="store_true", help="testing only: serve on the CPU if CUDA is missing")
    a = ap.parse_args()
    token = a.token or secrets.token_hex(4)
    session, provider = load_session(a.allow_cpu)
    batcher = Batcher(session)
    batcher.embed(np.zeros((1, SIZE, SIZE, 3), np.uint8))           # warm-up (cuDNN picks its kernels)
    batcher.embed(np.zeros((32, SIZE, SIZE, 3), np.uint8))
    server = http.server.ThreadingHTTPServer((a.host, a.port), make_handler(batcher, token, provider))
    server.daemon_threads = True

    print(f"\nNestGallery face endpoint running on {provider}.")
    print("In the app: Settings > Face scan hardware > NVIDIA PC, then enter:")
    print(f"   Endpoint:  http://localhost:{a.port}    (phone on USB; run once per connection:  adb reverse tcp:{a.port} tcp:{a.port})")
    if a.host != "127.0.0.1":
        for ip in lan_addresses(): print(f"   or         http://{ip}:{a.port}    (same Wi-Fi)")
    print(f"   Token:     {token}")
    print("Nothing is saved on this PC. Close this window (or Ctrl+C) to stop.\n")

    def meter():
        last = 0
        while True:
            time.sleep(10)
            d = batcher.faces_done - last; last = batcher.faces_done
            if d: print(f"{time.strftime('%H:%M:%S')}  {d / 10:.0f} faces/s", flush=True)
    threading.Thread(target=meter, daemon=True).start()
    try: server.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    main()
