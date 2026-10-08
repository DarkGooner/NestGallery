"""Pulls N identities x 72 renders out of the DigiFace-1M "72 images per subject" zips (2.9 GB each) with HTTP range
reads, so only the needed members are downloaded. Writes $FACE_EVAL_DATA/digi72.parquet (image bytes, label).
usage: python fetch_digi72.py [identities=400] [zip_index=0]
These renders vary expression, lighting, accessories and pose per identity much more than the 5-per-identity `digi`
set, which is what the "same character split into several people" problem needs."""
import io, os, sys, zipfile, urllib.request, pyarrow as pa, pyarrow.parquet as pq
from concurrent.futures import ThreadPoolExecutor

DATA = os.environ.get("FACE_EVAL_DATA", os.path.expanduser("~/face-eval-data"))
ZIPS = ["subjects_0-1999_72_imgs.zip", "subjects_2000-3999_72_imgs.zip", "subjects_4000-5999_72_imgs.zip",
        "subjects_6000-7999_72_imgs.zip", "subjects_8000-9999_72_imgs.zip"]
BASE = "https://huggingface.co/datasets/lhoestq/digiface1m_720k/resolve/main/"

def get_range(url, a, b):
    for attempt in range(6):
        try:
            req = urllib.request.Request(url, headers={"Range": f"bytes={a}-{b}", "User-Agent": "face-eval"})
            return urllib.request.urlopen(req, timeout=120).read()
        except Exception:
            if attempt == 5: raise

class HttpFile(io.RawIOBase):
    """Seekable read-only file over HTTP range requests (enough for zipfile)."""
    def __init__(self, url):
        self.url = urllib.request.urlopen(urllib.request.Request(url, method="HEAD", headers={"User-Agent": "face-eval"})).url
        self.size = int(urllib.request.urlopen(urllib.request.Request(self.url, method="HEAD")).headers["Content-Length"])
        self.pos = 0
    def seekable(self): return True
    def readable(self): return True
    def tell(self): return self.pos
    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else self.pos + off if whence == 1 else self.size + off
        return self.pos
    def read(self, n=-1):
        if n < 0: n = self.size - self.pos
        n = min(n, self.size - self.pos)
        if n <= 0: return b""
        d = get_range(self.url, self.pos, self.pos + n - 1); self.pos += len(d); return d
    def readinto(self, b):
        d = self.read(len(b)); b[:len(d)] = d; return len(d)

if __name__ == "__main__":
    n_id = int(sys.argv[1]) if len(sys.argv) > 1 else 400
    zi = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    hf = HttpFile(BASE + ZIPS[zi])
    z = zipfile.ZipFile(io.BufferedReader(hf, buffer_size=1 << 16))
    members = [m for m in z.infolist() if not m.is_dir() and m.filename.lower().endswith((".png", ".jpg"))]
    ident = lambda m: m.filename.replace("\\", "/").split("/")[-2]
    ids = sorted({ident(m) for m in members}, key=lambda s: int(s) if s.isdigit() else s)[:n_id]
    keep = set(ids)
    members = [m for m in members if ident(m) in keep]
    print(f"{ZIPS[zi]}: {len(ids)} identities, {len(members)} images", flush=True)
    url = hf.url

    def fetch(m):   # local header (30 bytes + name + extra) precedes the data; read both in one range
        raw = get_range(url, m.header_offset, m.header_offset + 30 + len(m.filename.encode()) + 1024 + m.compress_size)
        nlen = int.from_bytes(raw[26:28], "little"); xlen = int.from_bytes(raw[28:30], "little")
        data = raw[30 + nlen + xlen: 30 + nlen + xlen + m.compress_size]
        if m.compress_type == zipfile.ZIP_DEFLATED:
            import zlib; data = zlib.decompress(data, -15)
        return data
    with ThreadPoolExecutor(16) as ex: imgs = list(ex.map(fetch, members))
    labels = [int(ident(m)) if ident(m).isdigit() else ids.index(ident(m)) for m in members]
    out = os.path.join(DATA, "digi72.parquet")
    pq.write_table(pa.table({"image": imgs, "label": labels, "filename": [m.filename for m in members]}), out)
    print("wrote", out, sum(map(len, imgs)) >> 20, "MB")
