"""Embeds LFW (real) and DigiFace (CGI renders) with the recognisers under test. Caches to emb/<set>_<model>.npz.
Mirrors the app pipeline: SCRFD-500M (score>=0.5) -> 5-pt similarity align 112 -> recogniser, optional flip TTA."""
import io, os, sys, time, numpy as np, onnxruntime as ort, pyarrow.parquet as pq
from PIL import Image, ImageOps
from concurrent.futures import ThreadPoolExecutor

# FACE_EVAL_DATA holds lfw.parquet, digi.parquet and models/ (see README); embeddings are cached in ./emb
DATA = os.environ.get("FACE_EVAL_DATA", os.path.expanduser("~/face-eval-data"))
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "emb"); os.makedirs(OUT, exist_ok=True)
REPO_ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/assets/")
ARC = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]], np.float32)

def sess(path, threads=1, gpu=False):
    o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.inter_op_num_threads = 1
    o.log_severity_level = 3   # SCRFD's declared output shapes don't match the dynamic canvas; ORT warns per call
    if gpu and os.environ.get("EP") == "cuda":   # onnxruntime-gpu[cuda,cudnn] venv; only for recogniser comparisons
        ort.preload_dlls()   # Windows + driver 566 (CUDA 12.7): onnxruntime-gpu 1.22 + nvidia-cudnn-cu12 9.10
        s = ort.InferenceSession(path, o, providers=["CUDAExecutionProvider", "CPUExecutionProvider"])
        if "CUDAExecutionProvider" not in s.get_providers(): raise RuntimeError("EP=cuda but CUDA did not load")
        return s
    return ort.InferenceSession(path, o, providers=["CPUExecutionProvider"])

def nms(b, s, thr=0.4):
    idx = s.argsort()[::-1]; keep = []
    while idx.size:
        i = idx[0]; keep.append(i)
        xx1 = np.maximum(b[i, 0], b[idx[1:], 0]); yy1 = np.maximum(b[i, 1], b[idx[1:], 1])
        xx2 = np.minimum(b[i, 2], b[idx[1:], 2]); yy2 = np.minimum(b[i, 3], b[idx[1:], 3])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        a = (b[i, 2] - b[i, 0]) * (b[i, 3] - b[i, 1]); a2 = (b[idx[1:], 2] - b[idx[1:], 0]) * (b[idx[1:], 3] - b[idx[1:], 1])
        idx = idx[1:][inter / (a + a2 - inter) <= thr]
    return keep

def detect(det, img, thr=0.5):
    """img HxWx3 uint8 with long side <= 640. Canvas = own size rounded up to 32 (as the app does)."""
    h, w = img.shape[:2]; ch, cw = (h + 31) // 32 * 32, (w + 31) // 32 * 32
    canvas = np.zeros((ch, cw, 3), np.float32); canvas[:h, :w] = (img.astype(np.float32) - 127.5) / 128.0
    out = det.run(None, {det.get_inputs()[0].name: canvas.transpose(2, 0, 1)[None]})
    B, S_, L = [], [], []
    for k, st in enumerate((8, 16, 32)):
        sc = out[k][:, 0]; bb = out[k + 3] * st; kp = out[k + 6] * st
        gh, gw = ch // st, cw // st
        ys, xs = np.mgrid[0:gh, 0:gw]; ctr = np.repeat(np.stack([xs, ys], -1).reshape(-1, 2).astype(np.float32) * st, 2, axis=0)
        m = sc >= thr
        if not m.any(): continue
        c = ctr[m]; b = bb[m]; p = kp[m]
        B.append(np.stack([c[:, 0] - b[:, 0], c[:, 1] - b[:, 1], c[:, 0] + b[:, 2], c[:, 1] + b[:, 3]], 1))
        L.append(p.reshape(-1, 5, 2) + c[:, None, :]); S_.append(sc[m])
    if not B: return []
    B = np.concatenate(B); S_ = np.concatenate(S_); L = np.concatenate(L)
    return [(B[i], float(S_[i]), L[i]) for i in nms(B, S_)]

def similarity(src, dst):
    ms, md = src.mean(0), dst.mean(0); a = src - ms; b = dst - md
    den = (a ** 2).sum(); re = (b[:, 0] * a[:, 0] + b[:, 1] * a[:, 1]).sum() / den; im = (b[:, 1] * a[:, 0] - b[:, 0] * a[:, 1]).sum() / den
    M = np.array([[re, -im, 0], [im, re, 0]], np.float64); M[:, 2] = md - M[:, :2] @ ms
    return M

def align(img, lm, size=112):
    M = similarity(lm.astype(np.float64), ARC.astype(np.float64))
    Mi = np.linalg.inv(np.vstack([M, [0, 0, 1]]))
    pil = Image.fromarray(img)
    return np.asarray(pil.transform((size, size), Image.AFFINE, tuple(Mi[:2].reshape(-1)), Image.BILINEAR))

def prep(faces):  # list of 112x112x3 uint8 -> NCHW float
    x = (np.stack(faces).astype(np.float32) - 127.5) / 127.5
    return x.transpose(0, 3, 1, 2)

PRE_ALIGNED = {"digi", "digi72", "cplfw"}    # 112x112 crops already on the ArcFace template

def load_set(name):
    if name == "lfw":
        t = pq.read_table(DATA + "/lfw.parquet").to_pydict()
        return [r["bytes"] for r in t["image"]], np.array(t["label"])
    if name == "digi72":   # fetch_digi72.py: 72 renders per identity (expression / lighting / accessories / pose)
        t = pq.read_table(DATA + "/digi72.parquet").to_pydict()
        return t["image"], np.array(t["label"])
    if name == "cplfw":    # cross-pose LFW (LSIbabnikz/cplfw parquet): identity = key minus the trailing _<n>
        t = pq.read_table(DATA + "/cplfw.parquet").to_pydict()
        ids = [k.rsplit("_", 1)[0] for k in t["__key__"]]; u = {s: i for i, s in enumerate(sorted(set(ids)))}
        return [r["bytes"] for r in t["jpg"]], np.array([u[s] for s in ids])
    if name == "calfw":    # cross-age LFW (marcelohaps/calfw, raw 250x250)
        t = pq.read_table(DATA + "/calfw_raw.parquet").to_pydict()
        return [r["bytes"] for r in t["image"]], np.array(t["label"])
    t = pq.read_table(DATA + "/digi.parquet").to_pydict()
    return t["image"], np.array([int(f.split("_")[0]) for f in t["filename"]])

def occlude(faces, ok, frac=0.4, seed=0):
    """Synthetic lower-face occlusion on aligned 112px faces (hand / food / cup / mask-like blobs over mouth and chin,
    sometimes reaching the nose), applied to `frac` of the faces. Stand-in for eating / hand-over-mouth photos."""
    rng = np.random.default_rng(seed); out = faces.copy(); hit = np.zeros(len(faces), bool)
    yy, xx = np.mgrid[0:112, 0:112]
    for i in np.where(ok)[0]:
        if rng.random() >= frac: continue
        hit[i] = True; f = out[i]
        kind = rng.integers(0, 3)
        top = rng.uniform(72, 88)                                  # nose tip ~71, mouth ~92 on the template
        if kind == 0:   # skin-ish hand: ellipse from one side
            cx = rng.choice([rng.uniform(20, 45), rng.uniform(67, 92)]); cy = rng.uniform(top + 10, 112)
            m = ((xx - cx) / rng.uniform(28, 45)) ** 2 + ((yy - cy) / rng.uniform(16, 26)) ** 2 <= 1
            col = np.array([rng.uniform(150, 235), rng.uniform(110, 180), rng.uniform(90, 150)])
        elif kind == 1:  # object (cup / food / phone): rectangle with a random colour
            x0 = rng.uniform(15, 60); w = rng.uniform(35, 70)
            m = (xx >= x0) & (xx <= x0 + w) & (yy >= top)
            col = rng.uniform(0, 255, 3)
        else:            # mask-like band across the whole lower face
            m = yy >= top + 4 * np.sin((xx - 56) / 30)
            col = rng.uniform(30, 230, 3)
        shade = 1 + 0.15 * (yy - top) / 40
        f[m] = np.clip(col[None, :] * shade[m][:, None] + rng.normal(0, 6, (m.sum(), 3)), 0, 255).astype(np.uint8)
    return out, hit

def faces_for(name, det):
    """Aligned face per image (centre-most face; None if not detected) + quality info."""
    cache = f"{OUT}/{name}{DETTAG}_faces.npz"
    if os.path.exists(cache):
        z = np.load(cache); return z["faces"], z["ok"], z["labels"], z["eye"], z["yaw"], z["score"]
    if name.endswith("occ"):    # e.g. digi72occ: same faces with synthetic lower-face occluders on 40% of them
        faces, ok, labels, eye, yaw, score = faces_for(name[:-3], det)
        faces, hit = occlude(faces, ok)
        np.savez(cache, faces=faces, ok=ok, labels=labels, eye=eye, yaw=yaw, score=score, occluded=hit)
        return faces, ok, labels, eye, yaw, score
    imgs, labels = load_set(name)
    def one(b):
        img = np.asarray(ImageOps.exif_transpose(Image.open(io.BytesIO(b))).convert("RGB"))
        crop = img
        if name in PRE_ALIGNED:   # 112px aligned crops: pad so the detector sees context like a real photo, then upscale
            pad = np.zeros((224, 224, 3), np.uint8); pad[56:168, 56:168] = img
            img = np.asarray(Image.fromarray(pad).resize((448, 448), Image.BILINEAR))
        ds = detect(det, img)
        if not ds:
            # Profiles the 500M detector misses would bias cplfw towards easy poses; their crops are already aligned
            if name == "cplfw": return crop, 0.0, 9.0, 0.0
            return None
        cx, cy = img.shape[1] / 2, img.shape[0] / 2
        b, s, l = min(ds, key=lambda d: ((d[0][0] + d[0][2]) / 2 - cx) ** 2 + ((d[0][1] + d[0][3]) / 2 - cy) ** 2)
        eye = float(np.hypot(*(l[1] - l[0]))); yaw = float(abs(l[2, 0] - (l[0, 0] + l[1, 0]) / 2) / max(eye, 1e-3))
        return align(img, l), eye * 800 / max(img.shape[:2]), yaw, s
    with ThreadPoolExecutor(16) as ex: res = list(ex.map(one, imgs))
    ok = np.array([r is not None for r in res])
    faces = np.stack([r[0] if r is not None else np.zeros((112, 112, 3), np.uint8) for r in res])
    eye = np.array([r[1] if r else 0 for r in res], np.float32); yaw = np.array([r[2] if r else 9 for r in res], np.float32)
    score = np.array([r[3] if r else 0 for r in res], np.float32)
    np.savez(cache, faces=faces, ok=ok, labels=labels, eye=eye, yaw=yaw, score=score)
    return faces, ok, labels, eye, yaw, score

def embed_all(rec_path, faces, flip=False, batch=32):
    s = sess(rec_path, threads=os.cpu_count(), gpu=True)
    if isinstance(s.get_inputs()[0].shape[0], int): batch = s.get_inputs()[0].shape[0]   # fixed-batch exports
    out = []
    t0 = time.time()
    for i in range(0, len(faces), batch):
        x = prep(list(faces[i:i + batch]))
        e = s.run(None, {s.get_inputs()[0].name: x})[0]
        if flip: e = e + s.run(None, {s.get_inputs()[0].name: x[:, :, :, ::-1].copy()})[0]
        out.append(e / np.linalg.norm(e, axis=1, keepdims=True))
    dt = time.time() - t0
    return np.concatenate(out).astype(np.float32), dt / len(faces) * 1000

DETTAG = "_d25" if os.environ.get("DET") == "2.5g" else ""
MODELS = {"mbf": DATA + "/models/w600k_mbf.onnx", "r50": DATA + "/models/w600k_r50.onnx",
          # previously shipped ResNet-50 int8 (git show 9250b1c:app/src/main/assets/arcface_r50_int8.onnx)
          "r50s8": DATA + "/models/arcface_r50_int8.onnx",
          "ada8s": REPO_ASSETS + "adaface_ir101_int8.onnx",          # shipped
          # candidates (all RGB, (x - 127.5) / 127.5, 112x112, like ArcFace)
          "lvt": DATA + "/models/LVFace-T_Glint360K.onnx", "lvs": DATA + "/models/LVFace-S_Glint360K.onnx",
          "lvb": DATA + "/models/LVFace-B_Glint360K.onnx", "ada101": DATA + "/models/adaface_ir101_webface12m.onnx",
          "ada8": DATA + "/models/adaface_ir101_int8.onnx"}     # quant.py output (run on CPU, like the phone)

if __name__ == "__main__":
    det = sess(DATA + "/models/det_2.5g.onnx" if DETTAG else REPO_ASSETS + "scrfd_500m.onnx", 1)
    sets = sys.argv[1].split(",") if len(sys.argv) > 1 else ["digi", "lfw"]
    models = sys.argv[2].split(",") if len(sys.argv) > 2 else list(MODELS)
    for name in sets:
        faces, ok, labels, eye, yaw, score = faces_for(name, det)
        print(f"{name}: {len(ok)} images, detected {ok.sum()}", flush=True)
        for m in models:
            for flip in ((False, True) if os.environ.get("FLIP") else (False,)):     # FLIP=1: also mirrored-face TTA
                tag = m + ("_flip" if flip else "")
                path = f"{OUT}/{name}{DETTAG}_{tag}.npy"
                if os.path.exists(path): continue
                E, ms = embed_all(MODELS.get(m, m), faces, flip)
                np.save(path, E); print(f"  {tag}: {ms:.2f} ms/face (x86, {os.cpu_count()} threads)", flush=True)
