"""NestGallery face endpoint: runs the face scan on an NVIDIA GPU for the phone.

The phone sends each photo (the original file when it is a small JPEG / PNG / WebP, else a downsized JPEG it decoded
itself) and gets back the faces: box, 5 landmarks, quality and the 512-d embedding. Everything after decoding runs
here, exactly as the app's FaceAnalyzer does it on the phone: SCRFD-500M at 640 (score >= 0.7) -> size / quality
filter -> 5-point similarity alignment to 112x112 (small faces from the larger decode) -> AdaFace IR-101, the same
float graph the phone's GPU option runs (app/src/main/assets/adaface_ir101_gpu.onnx, weights read from
adaface_ir101_int8.onnx), here in float16 (make_fp16.py; cos >= 0.99997 to float32 on 360 faces), so embeddings
land in the phone's vector space (LFW: cos 0.984 to the phone's CPU model).

Privacy: nothing is written to disk. Photos and faces live in memory only while their request runs, and nothing about
them is logged (only a photos-per-second counter). No Python bytecode cache is written either (start.ps1 sets
PYTHONDONTWRITEBYTECODE). There is no token: run it only on networks you trust (see README).

    python server.py [--host 127.0.0.1] [--port 8765] [--allow-cpu]

Protocol (HTTP/1.1, keep-alive):
    GET  /v1/health   -> JSON {"app", "api": 2, "model_id", "provider"}
    POST /v1/analyze  body = one image file (JPEG / PNG / WebP / BMP), header X-Max-Faces (default 40)
                      -> int32 n, then n x (4 box + 10 landmarks + quality + det score + 512 embedding) float32,
                         little-endian; box and landmarks are 0..1 of the upright photo, embeddings L2-normalised
"""
import argparse, http.server, io, json, os, queue, socket, struct, sys, threading, time
import numpy as np
from PIL import Image, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, "models")
GRAPH = "adaface_ir101_gpu.onnx"
FP16_GRAPH = "adaface_ir101_fp16.onnx"     # made by make_fp16.py (setup.ps1): same weights in float16, ~1.9x faster
DETECTOR = "scrfd_500m_640.onnx"
# What the app checks before using this endpoint: same graph as ArcFaceEmbedder.GPU_ASSET.
MODEL_ID = "adaface_ir101_gpu_from_int8:v1"
API = 2
SIZE, DIM = 112, 512
DET_SIZE = 640            # ScrfdDetector.INPUT_SIZE
DET_SESSIONS = int(os.environ.get("NEST_DET_SESSIONS", "2"))
MIN_LONG_SIDE = 800       # FaceImageLoader.MIN_LONG_SIDE
MIN_DET_SCORE = 0.7       # FaceAnalyzer.MIN_DET_SCORE
MIN_EYE_DIST = 12.0       # FaceQuality.MIN_EYE_DIST (on an 800 px long side)
MAX_BODY = 40 << 20
BATCH = 64                # faces per recogniser run (256 overflowed an 8 GB laptop GPU: 50 faces/s instead of 700)
# Runs are padded to these sizes, each with its own session: ONNX Runtime's CUDA convolutions redo their setup
# (~200-600 ms on an RTX 4060 laptop) whenever one session sees a different batch size, which happened on almost every
# run. One session per size keeps each on its shape: 8 / 16 / 32 / 64 faces in ~15 / 26 / 49 / 93 ms (float16).
BUCKETS = (8, 16, 32, 64)
WAIT_S = 0.004            # how long the batcher waits for more faces once some are queued
# Python work per photo (decode, resize, align) runs on at most this many request threads at once. With every request
# thread busy the two GPU threads waited for the GIL after each model run and the GPU sat idle (~45 photos/s).
CPU_SLOTS = threading.BoundedSemaphore(max(2, min(8, (os.cpu_count() or 4) // 2)))
ARC = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]])


def session_options():
    import onnxruntime as ort
    o = ort.SessionOptions()
    o.log_severity_level = 3
    return o


def load_sessions(allow_cpu):
    """-> ({batch size: recogniser session}, detector, provider, precision). The float16 recogniser is used when it is
    there and agrees with the float32 graph (cosine >= 0.999 on test inputs; measured 0.99997+ on real faces)."""
    import onnxruntime as ort
    nvrtc = os.path.join(sys.prefix, "Lib", "site-packages", "nvidia", "cuda_nvrtc", "bin")
    if os.name == "nt" and os.path.isdir(nvrtc):
        os.add_dll_directory(nvrtc)  # cuDNN looks for nvrtc64_120_0.dll; preload_dlls doesn't put it on the path
    if hasattr(ort, "preload_dlls"):
        ort.preload_dlls()          # CUDA / cuDNN from the nvidia-* pip packages inside this folder's .venv
    # The recogniser runs at 4 fixed batch sizes and the detector at one fixed shape (640 x 640, as the phone's GPU
    # option), so an exhaustive cuDNN search pays off.
    cuda = ("CUDAExecutionProvider", {"cudnn_conv_algo_search": "EXHAUSTIVE"})
    providers = [cuda] + (["CPUExecutionProvider"] if allow_cpu else [])

    def make(name, qdq=False):
        o = session_options()
        # folds the int8 weights' DequantizeLinear nodes into float weights: a plain float network for CUDA
        if qdq: o.add_session_config_entry("session.disable_quant_qdq", "1")
        try:
            return ort.InferenceSession(os.path.join(MODELS, name), o, providers=providers)
        except Exception as e:
            if not allow_cpu: raise SystemExit(f"Could not start on the GPU: {e}")
            return ort.InferenceSession(os.path.join(MODELS, name), o, providers=["CPUExecutionProvider"])

    rec = make(GRAPH, qdq=True)
    det = [make(DETECTOR) for _ in range(DET_SESSIONS)]
    provider = rec.get_providers()[0]
    if (provider != "CUDAExecutionProvider" or det[0].get_providers()[0] != "CUDAExecutionProvider") and not allow_cpu:
        # ONNX Runtime silently falls back to the CPU when CUDA / cuDNN don't load; never serve slowly by surprise
        raise SystemExit("CUDA did not load (running on the CPU instead). Run setup.ps1 again; see README.")
    graph, qdq, precision = GRAPH, True, "float32"
    if provider == "CUDAExecutionProvider" and os.path.exists(os.path.join(MODELS, FP16_GRAPH)):
        try:
            half = make(FP16_GRAPH)
            x = np.random.default_rng(0).uniform(-1, 1, (8, 3, SIZE, SIZE)).astype(np.float32)
            ya = rec.run(None, {rec.get_inputs()[0].name: x})[0]
            yb = half.run(None, {half.get_inputs()[0].name: x})[0]
            cos = (ya * yb).sum(1) / np.linalg.norm(ya, axis=1) / np.linalg.norm(yb, axis=1)
            if cos.min() >= 0.999: graph, qdq, precision = FP16_GRAPH, False, "float16"
            else: print(f"float16 recogniser disagrees with float32 (cos {cos.min():.4f}); using float32")
            del half
        except Exception as e:
            print(f"float16 recogniser not usable ({e}); using float32")
    del rec
    recs = {b: make(graph, qdq) for b in BUCKETS}
    return recs, det, provider, precision


def load_session(allow_cpu):
    """setup.ps1's GPU check."""
    recs, _, provider, _ = load_sessions(allow_cpu)
    return recs[BUCKETS[0]], provider


class Batcher:
    """Collects the faces of concurrent requests into one recogniser run."""

    def __init__(self, sessions):
        self.sessions = sessions
        self.input = sessions[BUCKETS[0]].get_inputs()[0].name
        self.q = queue.Queue()
        threading.Thread(target=self._loop, daemon=True).start()

    def embed(self, faces_u8):
        if len(faces_u8) == 0: return np.zeros((0, DIM), np.float32)
        job = {"x": faces_u8, "done": threading.Event()}
        self.q.put(job)
        job["done"].wait()
        if "error" in job: raise job["error"]
        return job["y"]

    def _run(self, faces):
        n = len(faces)
        size = next(b for b in BUCKETS if b >= n)
        x = np.zeros((size, 3, SIZE, SIZE), np.float32)
        x[:n] = ((faces.astype(np.float32) - 127.5) / 127.5).transpose(0, 3, 1, 2)
        return self.sessions[size].run(None, {self.input: x})[0][:n].astype(np.float32)

    def warm_up(self):
        for b in BUCKETS: self._run(np.zeros((b, SIZE, SIZE, 3), np.uint8))

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
            x = None
            try:
                x = np.concatenate([j["x"] for j in jobs])
                y = np.concatenate([self._run(x[i:i + BATCH]) for i in range(0, len(x), BATCH)])
                y /= np.maximum(np.linalg.norm(y, axis=1, keepdims=True), 1e-12)
                k = 0
                for j in jobs:
                    m = len(j["x"]); j["y"] = y[k:k + m]; k += m
            except Exception as e:      # e.g. GPU out of memory: fail these requests, keep serving
                for j in jobs: j["error"] = e
            del x
            for j in jobs:
                j["x"] = None; j["done"].set()


class Detector:
    """SCRFD-500M on a fixed 640 x 640 canvas (photo top-left, zero padding), decoded like ScrfdDecoder.

    A few sessions, each on its own thread, run one photo at a time (~3.6 ms each on an RTX 4060 laptop, mostly kernel
    launches, so concurrent runs overlap well). One thread was the limit at ~110 photos/s once the recogniser shared
    the GPU. (Batching photos is not possible: the graph's output reshape interleaves the batch.)"""

    def __init__(self, sessions):
        self.sessions = sessions
        session = sessions[0]
        self.input = session.get_inputs()[0].name
        self.q = queue.Queue()
        # 3 scores [N,1], 3 boxes [N,4], 3 keypoints [N,10]; sorted by last dim, then by N descending (stride 8,16,32)
        x = np.zeros((1, 3, DET_SIZE, DET_SIZE), np.float32)
        outs = session.run(None, {self.input: x})
        self.order = sorted(range(9), key=lambda i: (outs[i].shape[-1], -outs[i].size))
        for s_ in sessions: threading.Thread(target=self._loop, args=(s_,), daemon=True).start()

    def _loop(self, session):
        while True:
            job = self.q.get()
            try: job["y"] = session.run(None, {self.input: job["x"]})
            except Exception as e: job["error"] = e
            job["x"] = None; job["done"].set()

    def _run(self, canvas):
        job = {"x": canvas, "done": threading.Event()}
        self.q.put(job)
        job["done"].wait()
        if "error" in job: raise job["error"]
        return job["y"]

    @staticmethod
    def canvas(img):
        """The photo (long side <= 640) normalised, top-left on a zero 640 x 640 canvas."""
        h, w = img.shape[:2]
        canvas = np.zeros((1, 3, DET_SIZE, DET_SIZE), np.float32)
        canvas[0, :, :h, :w] = img.transpose(2, 0, 1)
        canvas[0, :, :h, :w] -= 127.5
        canvas[0, :, :h, :w] /= 128.0
        return canvas

    def detect(self, canvas, thr):
        outs = self._run(canvas)
        outs = [outs[i] for i in self.order]
        boxes, scores, lms = [], [], []
        for k, st in enumerate((8, 16, 32)):
            sc = outs[k].reshape(-1)
            m = sc >= thr
            if not m.any(): continue
            g = DET_SIZE // st
            idx = np.nonzero(m)[0]
            cell = idx // 2
            cx = (cell % g) * st; cy = (cell // g) * st
            b = outs[3 + k].reshape(-1, 4)[idx] * st
            p = outs[6 + k].reshape(-1, 10)[idx] * st
            boxes.append(np.stack([cx - b[:, 0], cy - b[:, 1], cx + b[:, 2], cy + b[:, 3]], 1))
            p[:, 0::2] += cx[:, None]; p[:, 1::2] += cy[:, None]
            lms.append(p); scores.append(sc[idx])
        if not boxes: return []
        boxes = np.concatenate(boxes); scores = np.concatenate(scores); lms = np.concatenate(lms)
        return [(boxes[i], float(scores[i]), lms[i]) for i in nms(boxes, scores, 0.4)]


def nms(b, s, thr):
    """Greedy NMS by score, IoU > thr suppressed (ScrfdDecoder.nms)."""
    idx = s.argsort(kind="stable")[::-1]; keep = []
    area = (b[:, 2] - b[:, 0]) * (b[:, 3] - b[:, 1])
    while idx.size:
        i = idx[0]; keep.append(i); r = idx[1:]
        xx1 = np.maximum(b[i, 0], b[r, 0]); yy1 = np.maximum(b[i, 1], b[r, 1])
        xx2 = np.minimum(b[i, 2], b[r, 2]); yy2 = np.minimum(b[i, 3], b[r, 3])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        idx = r[inter / np.maximum(area[i] + area[r] - inter, 1e-6) <= thr]
    return keep


def similarity(src, dst):
    """FaceMath.estimateSimilarity: least-squares rotation + uniform scale + translation."""
    ms, md = src.mean(0), dst.mean(0); a = src - ms; b = dst - md
    den = (a ** 2).sum()
    re = (b[:, 0] * a[:, 0] + b[:, 1] * a[:, 1]).sum() / den
    im = (b[:, 1] * a[:, 0] - b[:, 0] * a[:, 1]).sum() / den
    M = np.array([[re, -im, 0], [im, re, 0]], np.float64); M[:, 2] = md - M[:, :2] @ ms
    return M


def align(pil, lm):
    M = similarity(lm.reshape(5, 2).astype(np.float64), ARC)
    Mi = np.linalg.inv(np.vstack([M, [0, 0, 1]]))
    return np.asarray(pil.transform((SIZE, SIZE), Image.AFFINE, tuple(Mi[:2].reshape(-1)), Image.BILINEAR))


def decode(body):
    """The photo upright, decoded like FaceImageLoader: long side in [800, 1600) (or smaller if the photo is)."""
    im = Image.open(io.BytesIO(body))
    w, h = im.size
    s = 1
    while max(w, h) // (s * 2) >= MIN_LONG_SIDE: s *= 2
    if s > 1 and im.format == "JPEG":
        im.draft("RGB", (w // s, h // s))         # libjpeg's DCT scaling, like Android's inSampleSize
    im = ImageOps.exif_transpose(im).convert("RGB")
    w, h = im.size
    s = 1
    while max(w, h) // (s * 2) >= MIN_LONG_SIDE: s *= 2
    if s > 1: im = im.reduce(s)                  # PNG / WebP (no DCT scaling): box-filter down
    return im


def analyze(body, max_faces, detector, batcher):
    """FaceAnalyzer.analyze. -> (meta [n, 16] float32, embeddings [n, 512])."""
    with CPU_SLOTS:
        src = decode(body)
        long_side = max(src.size)
        if long_side == DET_SIZE:
            det_im = src
        else:
            scale = DET_SIZE / long_side
            det_im = src.resize((max(1, int(src.width * scale)), max(1, int(src.height * scale))), Image.BILINEAR)
        dw, dh = det_im.size
        canvas = detector.canvas(np.asarray(det_im))
    dets = detector.detect(canvas, MIN_DET_SCORE)
    dets.sort(key=lambda d: -float((d[0][2] - d[0][0]) * (d[0][3] - d[0][1])))
    hi_res = src.width / dw
    meta, crops = [], []
    with CPU_SLOTS:
        faces = prepare_faces(src, det_im, dets[:max_faces], hi_res, dw, dh, meta, crops)
    if not faces: return np.zeros((0, 16), np.float32), np.zeros((0, DIM), np.float32)
    emb = batcher.embed(np.stack(crops))
    return np.asarray(meta, np.float32), emb


def prepare_faces(src, det_im, dets, hi_res, dw, dh, meta, crops):
    """Size / quality filter and alignment (FaceAnalyzer); fills meta and crops, returns how many faces."""
    for box, score, lm in dets:
        eye = float(np.hypot(lm[2] - lm[0], lm[3] - lm[1]))
        eye_norm = eye * 800.0 / DET_SIZE
        if eye_norm < MIN_EYE_DIST: continue
        yaw = 1.0 if eye <= 1e-3 else abs(lm[4] - (lm[0] + lm[2]) / 2) / eye
        q = (0.45 * min(max(eye_norm / 60.0, 0), 1) + 0.35 * min(max(1 - yaw / 0.6, 0), 1)
             + 0.20 * min(max((score - 0.5) / 0.4, 0), 1))
        # small faces are aligned from the larger decode (more real pixels); big faces from the detector image
        use_src = (box[2] - box[0]) < SIZE and src is not det_im
        crops.append(align(src, lm * hi_res) if use_src else align(det_im, lm))
        bounds = np.clip([box[0] / dw, box[1] / dh, box[2] / dw, box[3] / dh], 0, 1)
        lm_norm = lm.astype(np.float64).copy(); lm_norm[0::2] /= dw; lm_norm[1::2] /= dh
        meta.append(np.concatenate([bounds, lm_norm, [q, score]]))
    return len(crops)


class Stats:
    def __init__(self): self.photos = 0; self.faces = 0; self.first = self.last = 0.0; self.lock = threading.Lock()

    def started(self):
        """A request arrived; marks the start of a scan if the server was idle."""
        with self.lock:
            now = time.perf_counter()
            if now - self.last > 3: self.first = now

    def add(self, faces):
        with self.lock: self.photos += 1; self.faces += faces; self.last = time.perf_counter()


def make_handler(detector, batcher, provider, precision, stats):
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

        def do_GET(self):
            if self.path != "/v1/health": return self._send(404, b'{"error":"not found"}')
            self._send(200, json.dumps({"app": "nestgallery-face", "api": API, "model_id": MODEL_ID,
                                        "provider": provider, "precision": precision}).encode())

        def do_POST(self):
            if self.path != "/v1/analyze": return self._send(404, b'{"error":"not found"}')
            try:
                length = int(self.headers.get("Content-Length", "-1"))
                max_faces = int(self.headers.get("X-Max-Faces", "40"))
            except ValueError:
                length, max_faces = -1, 40
            if not (0 < length <= MAX_BODY): return self._send(400, b'{"error":"body must be one image file"}')
            stats.started()
            body = self.rfile.read(length)
            if len(body) != length: return
            try:
                meta, emb = analyze(body, max(1, min(max_faces, 100)), detector, batcher)
            except Exception as e:
                return self._send(422 if isinstance(e, (OSError, ValueError)) else 500,
                                  json.dumps({"error": f"{type(e).__name__}: {str(e)[:160]}"}).encode())
            finally:
                del body
            stats.add(len(meta))
            out = struct.pack("<i", len(meta)) + np.concatenate([meta, emb], 1).astype("<f4").tobytes()
            self._send(200, out, "application/octet-stream")

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
    ap.add_argument("--allow-cpu", action="store_true", help="testing only: serve on the CPU if CUDA is missing")
    a = ap.parse_args()
    recs, det, provider, precision = load_sessions(a.allow_cpu)
    batcher = Batcher(recs)
    detector = Detector(det)
    batcher.warm_up()                                                     # cuDNN picks its kernels once per shape
    stats = Stats()
    server = http.server.ThreadingHTTPServer((a.host, a.port), make_handler(detector, batcher, provider, precision, stats))
    server.daemon_threads = True
    server.request_queue_size = 64

    print(f"\nNestGallery face endpoint running on {provider} (recogniser in {precision}).")
    print("In the app: Settings > Face scan hardware > NVIDIA PC, then enter:")
    print(f"   http://localhost:{a.port}    (phone on USB; run once per connection:  adb reverse tcp:{a.port} tcp:{a.port})")
    if a.host != "127.0.0.1":
        for ip in lan_addresses(): print(f"   http://{ip}:{a.port}    (same Wi-Fi)")
    print("Nothing is saved on this PC. Close this window (or Ctrl+C) to stop.\n")

    def meter():
        """Every 10 s while photos arrive, and a summary once a scan has been idle for 3 s."""
        last = (0, 0); burst = None; tick = time.perf_counter()
        while True:
            time.sleep(0.5)
            p, f, t = stats.photos, stats.faces, stats.last
            now = time.perf_counter()
            if burst is None and p != last[0]: burst = (last[0], last[1], stats.first)
            if now - tick >= 10:
                if p != last[0]:
                    print(f"{time.strftime('%H:%M:%S')}  {(p - last[0]) / (now - tick):.0f} photos/s, "
                          f"{(f - last[1]) / (now - tick):.0f} faces/s", flush=True)
                last = (p, f); tick = now
            if burst is not None and now - t > 3:
                n, nf, secs = p - burst[0], f - burst[1], max(t - burst[2], 1e-3)
                print(f"{time.strftime('%H:%M:%S')}  scan: {n} photos, {nf} faces in {secs:.1f} s = "
                      f"{n / secs:.0f} photos/s", flush=True)
                burst = None; last = (p, f); tick = now
    threading.Thread(target=meter, daemon=True).start()
    try: server.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    sys.setswitchinterval(0.001)       # hand the GIL back to the GPU threads sooner (default 5 ms)
    main()
