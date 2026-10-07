"""Clustering / verification evaluation on cached embeddings.
usage: python3 cluster.py <set> <model-tag> [algo ...]"""
import heapq, os, sys, time, numpy as np
from collections import defaultdict

E_DIR = os.path.dirname(os.path.abspath(__file__)) + "/emb"

def load(name, tag):
    if name.startswith("mix"):          # CGI renders + real people + one-off strangers, in one library
        sfx = name[3:]
        a = load("digi" + sfx, tag); b = load("lfw" + sfx, tag)
        return tuple(np.concatenate([x, y + (10_000_000 if i == 1 else 0)]) if i == 1 else np.concatenate([x, y])
                     for i, (x, y) in enumerate(zip(a, b)))
    z = np.load(f"{E_DIR}/{name}_faces.npz")    # lazy: the 'faces' array is never read here
    ok = z["ok"]; E = np.load(f"{E_DIR}/{name}_{tag}.npy")
    q = quality(z["score"], z["eye"], z["yaw"])
    return E[ok], z["labels"][ok], q[ok], z["yaw"][ok], z["eye"][ok]

def quality(det, eye, yaw):
    size = np.clip(eye / 60, 0, 1); pose = np.clip(1 - yaw / 0.6, 0, 1); d = np.clip((det - 0.5) / 0.4, 0, 1)
    return 0.45 * size + 0.35 * pose + 0.2 * d

# ---------------------------------------------------------------- metrics
def bcubed(assign, truth):
    """assign: -1 = unassigned (counted as its own singleton for recall; excluded from precision)."""
    n = len(truth); P = R = 0.0; m = 0
    cl = defaultdict(list)
    for i, a in enumerate(assign):
        if a >= 0: cl[a].append(i)
    tcount = defaultdict(int)
    for t in truth: tcount[t] += 1
    for a, idx in cl.items():
        lab = truth[idx]; u, c = np.unique(lab, return_counts=True); cc = dict(zip(u, c))
        for i in idx:
            P += cc[truth[i]] / len(idx); R += cc[truth[i]] / tcount[truth[i]]; m += 1
    for i, a in enumerate(assign):
        if a < 0: R += 1 / tcount[truth[i]]
    return P / max(m, 1), R / n

def pair_stats(assign, truth):
    cl = defaultdict(list)
    for i, a in enumerate(assign):
        if a >= 0: cl[a].append(i)
    tp = fp = 0; impure = 0; big = 0
    for idx in cl.values():
        if len(idx) < 2: continue
        big += 1
        u, c = np.unique(truth[idx], return_counts=True)
        tp += (c * (c - 1) // 2).sum(); tot = len(idx) * (len(idx) - 1) // 2; fp += tot - (c * (c - 1) // 2).sum()
        if len(u) > 1: impure += 1
    tc = np.unique(truth, return_counts=True)[1]; allpos = (tc * (tc - 1) // 2).sum()
    return tp / max(tp + fp, 1), tp / max(allpos, 1), big, impure

def report(name, assign, truth, dt):
    P, R = bcubed(assign, truth); pp, pr, k, imp = pair_stats(assign, truth)
    shown = sum(1 for a in set(assign) if a >= 0 and (assign == a).sum() >= 2)
    multi = len([t for t in set(truth) if (truth == t).sum() >= 2])
    print(f"{name:34s} bcubedP {P:.4f} R {R:.4f} | pairP {pp:.4f} pairR {pr:.4f} | people {k} (true multi {multi}) impure {imp} ({imp / max(k, 1) * 100:.1f}%) | {dt:.1f}s", flush=True)

def verification(E, y, rng=np.random.default_rng(0)):
    pos = []
    by = defaultdict(list)
    for i, t in enumerate(y): by[t].append(i)
    for idx in by.values():
        for a in range(len(idx)):
            for b in range(a + 1, len(idx)): pos.append((idx[a], idx[b]))
    pos = np.array(pos); ps = np.einsum("ij,ij->i", E[pos[:, 0]], E[pos[:, 1]])
    parts = []
    for _ in range(15):
        a = rng.integers(0, len(y), 200_000); b = rng.integers(0, len(y), 200_000); m = y[a] != y[b]
        parts.append(np.einsum("ij,ij->i", E[a[m]], E[b[m]]))
    ns = np.concatenate(parts); ns.sort()
    out = []
    for far in (1e-3, 1e-4, 1e-5):
        thr = ns[int(len(ns) * (1 - far))]
        out.append(f"FAR {far:g}: thr {thr:.3f} TAR {(ps >= thr).mean():.4f}")
    print(f"  pos median {np.median(ps):.3f} p5 {np.percentile(ps, 5):.3f} | neg p99.9 {np.percentile(ns, 99.9):.3f} max {ns[-1]:.3f}")
    print("  " + " | ".join(out))

# ---------------------------------------------------------------- kNN
def knn(E, k):
    n = len(E); I = np.zeros((n, k), np.int64); S = np.zeros((n, k), np.float32)
    for s in range(0, n, 2048):
        sim = E[s:s + 2048] @ E.T
        for r in range(sim.shape[0]): sim[r, s + r] = -9
        idx = np.argpartition(-sim, k, axis=1)[:, :k]
        v = np.take_along_axis(sim, idx, 1); o = np.argsort(-v, 1)
        I[s:s + 2048] = np.take_along_axis(idx, o, 1); S[s:s + 2048] = np.take_along_axis(v, o, 1)
    return I, S

# ---------------------------------------------------------------- baseline: current app (Immich port + reconcile, minFaces 2, maxDist .5)
def immich(E, q, files, max_dist=0.5, min_faces=2, cap=48):
    n = len(E); minsim = 1 - max_dist
    I, S = knn(E, cap)
    person = np.zeros(n, np.int64); nxt = 1
    members = defaultdict(set); pfiles = defaultdict(set)
    def usable(r):
        return [(int(j), s) for j, s in zip(I[r], S[r]) if s >= minsim and files[j] != files[r]]
    core = {}
    def is_core(r):
        if r not in core: core[r] = 1 + len(usable(r)) >= min_faces
        return core[r]
    def merge(a, b):
        keep, drop = (a, b) if len(members[a]) >= len(members[b]) else (b, a)
        for r in members[drop]: person[r] = keep
        members[keep] |= members.pop(drop); pfiles[keep] |= pfiles.pop(drop); return keep
    todo = sorted(range(n), key=lambda r: -q[r]); deferred = []
    def process(r, dpass):
        nonlocal nxt
        use = usable(r)
        if not use: return
        c = 1 + len(use) >= min_faces; core[r] = c
        if not c and not dpass: deferred.append(r); return
        pid = 0
        for j, _ in use:
            p = person[j]
            if p > 0 and files[r] not in pfiles[p]: pid = p; break
        if pid == 0 and c: pid = nxt; nxt += 1
        if pid == 0: return
        person[r] = pid; members[pid].add(r); pfiles[pid].add(files[r])
        if c:
            cur = pid
            for j, _ in use:
                qq = person[j]
                if qq > 0 and qq != cur and not (pfiles[cur] & pfiles[qq]) and is_core(j): cur = merge(cur, qq)
    for r in todo: process(r, False)
    for r in deferred: process(r, True)
    return np.where(person > 0, person, -1)

# ---------------------------------------------------------------- proposed: average-linkage HAC on a kNN candidate graph + margin attach
def hac(E, q, files, link=0.40, k=24, seed_q=0.0, attach=0.30, margin=0.06, edge_floor=0.25, centroid=False, min_size=1, pair_bonus=0.0):
    n = len(E)
    I, S = knn(E, k)
    seeds = q >= seed_q
    parent = np.arange(n); size = np.ones(n, np.int64)
    sums = E.astype(np.float64).copy()
    fset = {i: {files[i]} for i in range(n)}
    nbrs = defaultdict(set)
    for i in range(n):
        if not seeds[i]: continue
        for j, s in zip(I[i], S[i]):
            if s >= edge_floor and seeds[j] and files[j] != files[i]: nbrs[i].add(int(j)); nbrs[int(j)].add(i)
    def L(a, b):
        d = sums[a] @ sums[b]
        return d / (np.linalg.norm(sums[a]) * np.linalg.norm(sums[b])) if centroid else d / (size[a] * size[b])
    ver = np.zeros(n, np.int64); heap = []
    for a in nbrs:
        for b in nbrs[a]:
            if a < b:
                s = L(a, b)
                if s >= link: heap.append((-s, a, b, 0, 0))
    heapq.heapify(heap)
    alive = np.ones(n, bool)
    while heap:
        ns_, a, b, va, vb = heapq.heappop(heap)
        if not (alive[a] and alive[b]) or ver[a] != va or ver[b] != vb: continue
        if fset[a] & fset[b]: continue                                    # same-photo cannot-link
        if size[a] == 1 and size[b] == 1 and -ns_ < link + pair_bonus: continue
        if size[a] < size[b]: a, b = b, a
        alive[b] = False; parent[b] = a; size[a] += size[b]; sums[a] += sums[b]; fset[a] |= fset.pop(b)
        nbrs[a] |= nbrs.pop(b); nbrs[a].discard(a); nbrs[a].discard(b); ver[a] += 1
        for c in list(nbrs[a]):
            if not alive[c]: nbrs[a].discard(c); continue
            nbrs[c].discard(b); nbrs[c].add(a)
            s = L(a, c)
            if s >= link: heapq.heappush(heap, (-s, a, c, ver[a], ver[c]) if a < c else (-s, c, a, ver[c], ver[a]))
    def root(i):
        while parent[i] != i: i = parent[i]
        return i
    lab = np.array([root(i) for i in range(n)])
    # clusters smaller than 2 are "unassigned"; attach leftovers to a clear winner by average similarity to its members
    cnt = defaultdict(int)
    for l in lab: cnt[l] += 1
    assign = np.array([l if cnt[l] >= max(2, min_size) else -1 for l in lab])
    if attach is not None:
        cl = [c for c in cnt if cnt[c] >= max(2, min_size)]
        if cl:
            C = np.stack([sums[c] for c in cl]); csize = np.array([size[c] for c in cl], np.float64)
            cf = [fset[c] for c in cl]
            todo = np.where(assign < 0)[0]
            if len(todo):
                A = (E[todo].astype(np.float64) @ C.T) / csize                  # mean similarity to each person
                for row, i in enumerate(todo):
                    o = np.argsort(-A[row])[:3]
                    best = next((x for x in o if files[i] not in cf[x]), None)
                    if best is None: continue
                    second = max([A[row][x] for x in o if x != best] + [-1])
                    if A[row][best] >= attach and A[row][best] - second >= margin: assign[i] = cl[best]
    return assign

if __name__ == "__main__":
    name, tag = sys.argv[1], sys.argv[2]
    E, y, q, yaw, eye = load(name, tag)
    files = np.arange(len(y))       # one face per image in these sets
    print(f"== {name} / {tag}: {len(y)} faces, {len(set(y))} identities")
    verification(E, y)
    algos = sys.argv[3:] or ["immich", "hac"]
    for a in algos:
        t = time.time()
        if a == "immich": report("current (immich+reconcile)", immich(E, q, files), y, time.time() - t)
        elif a == "immich-strict": report("immich maxDist .35", immich(E, q, files, max_dist=0.35), y, time.time() - t)
        elif a.startswith("hac"):
            kw = dict(x.split("=") for x in a.split(":")[1:]) if ":" in a else {}
            kw = {k_: (v == "1") if k_ == "centroid" else float(v) for k_, v in kw.items()}
            if "k" in kw: kw["k"] = int(kw["k"])
            report(a, hac(E, q, files, **kw), y, time.time() - t)
