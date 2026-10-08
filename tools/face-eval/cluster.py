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
    ok = z["ok"]
    # "a+b": ensemble of two recognisers = concatenated unit vectors (cosine = mean of the two cosines)
    E = np.concatenate([np.load(f"{E_DIR}/{name}_{t}.npy") for t in tag.split("+")], 1)
    E = E / np.linalg.norm(E, axis=1, keepdims=True)
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

def split_stats(assign, truth):
    """The user-visible split problem: for identities with >= 4 faces, how many shown people (groups of >= 2) hold
    at least 2 of their faces, the share of identities spread over more than one person, and the share of faces left
    outside the identity's main person (in another person or ungrouped)."""
    by = defaultdict(list)
    for i, t in enumerate(truth): by[t].append(i)
    frags, split, outside, tot = [], 0, 0, 0
    for t, idx in by.items():
        if len(idx) < 4: continue
        a = assign[idx]; u, c = np.unique(a[a >= 0], return_counts=True)
        f = int((c >= 2).sum()); frags.append(f); split += f > 1
        outside += len(idx) - (c.max() if len(c) else 0); tot += len(idx)
    return np.mean(frags) if frags else 0, split / max(len(frags), 1), outside / max(tot, 1), len(frags)

def report(name, assign, truth, dt):
    P, R = bcubed(assign, truth); pp, pr, k, imp = pair_stats(assign, truth)
    multi = len([t for t in set(truth) if (truth == t).sum() >= 2])
    fr, sp, out, nid = split_stats(assign, truth)
    print(f"{name:34s} bcubedP {P:.4f} R {R:.4f} | pairP {pp:.4f} pairR {pr:.4f} | people {k} (true multi {multi}) impure {imp} ({imp / max(k, 1) * 100:.1f}%)"
          f" | split {sp * 100:.1f}% of {nid} ids, {fr:.2f} people/id, {out * 100:.1f}% faces outside main | {dt:.1f}s", flush=True)

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

# ---------------------------------------------------------------- app port: PeopleClusterer.run (incremental scans)
def app_run(E, files, person, link=0.45, attach=0.35, margin=0.06, k=24, group_link=None, group_min=2, group_fn=None,
            next_id=None, floor=None):
    """Port of PeopleClusterer.kt. person[i] > 0: existing person (fixed group), 0: new face to cluster.
    group_link: optional extra pass after merging - merge whole groups (>= group_min faces each, existing people
    included) while group_fn(a, b) >= group_link; default group_fn is average linkage (exact, from running sums)."""
    n = len(E); E64 = E.astype(np.float64)
    parent = np.arange(n); size = np.zeros(n, np.int64); pid = np.zeros(n, np.int64)
    sums = {}; fset = {}; ver = np.zeros(n, np.int64)
    def find(x):
        while parent[x] != x: parent[x] = parent[parent[x]]; x = parent[x]
        return x
    root_of = {}
    for r in range(n):
        p = person[r]; size[r] = 1
        if p == 0: continue
        if p not in root_of: root_of[p] = r; pid[r] = p; sums[r] = np.zeros(E.shape[1]); fset[r] = set()
        else: parent[r] = root_of[p]; size[root_of[p]] += 1
        sums[root_of[p]] += E64[r]; fset[root_of[p]].add(files[r])
    S_ = lambda a: sums[a] if a in sums else E64[a]
    F_ = lambda a: fset[a] if a in fset else {files[a]}
    q = np.where(person == 0)[0]
    if len(q):
        sim = None; I = np.zeros((len(q), k), np.int64); Sv = np.zeros((len(q), k), np.float32)
        for s in range(0, len(q), 2048):
            sim = E[q[s:s + 2048]] @ E.T
            sim[np.arange(sim.shape[0]), q[s:s + 2048]] = -9
            idx = np.argpartition(-sim, k, axis=1)[:, :k]; v = np.take_along_axis(sim, idx, 1); o = np.argsort(-v, 1)
            I[s:s + 2048] = np.take_along_axis(idx, o, 1); Sv[s:s + 2048] = np.take_along_axis(v, o, 1)
    def lk(a, b): return S_(a) @ S_(b) / (size[a] * size[b])
    def allowed(a, b): return not (F_(a) & F_(b))
    def merge(x, y):
        a, b = (x, y) if size[x] >= size[y] else (y, x)
        parent[b] = a; sums[a] = S_(a) + S_(b); sums.pop(b, None); size[a] += size[b]
        fset[a] = F_(a) | F_(b); fset.pop(b, None)
        pid[a] = pid[a] if pid[a] > 0 else pid[b]; pid[b] = 0; ver[a] += 1
        return a
    # Size-aware threshold: two lone faces need `link`; as both groups grow, their average is a steadier estimate
    # and the bar eases towards `floor`: thr = floor + (link - floor) / sqrt(min(|A|, |B|)).
    lo = link if floor is None else floor
    def thr(a, b): return lo + (link - lo) / np.sqrt(min(size[a], size[b]))
    heap = []
    for i, r in enumerate(q):
        for j in range(k):
            s = Sv[i, j]
            if s < lo: break
            if files[I[i, j]] != files[r]: heap.append((-float(s), int(r), int(I[i, j]), 0, 0))
    heapq.heapify(heap)
    while heap:
        ns_, a0, b0, va, vb = heapq.heappop(heap)
        a, b = find(a0), find(b0)
        if a == b: continue
        if a != a0 or b != b0 or ver[a] != va or ver[b] != vb:
            f = lk(a, b)
            if f >= lo: heapq.heappush(heap, (-f, a, b, ver[a], ver[b]))
            continue
        if -ns_ < thr(a, b) or not allowed(a, b): continue
        merge(a, b)
    if group_link is not None:
        fn = None; gmerge = merge
        if group_fn and group_fn.startswith("top"):
            m = int(group_fn[3:])
            memb = defaultdict(list)
            for i in range(n): memb[find(i)].append(i)
            def gmerge(x, y):
                a = merge(x, y); b = y if a == x else x
                memb[a] += memb.pop(b); return a
            def fn(a, b):   # each face's m best matches in the other group, averaged; the weaker direction counts
                M = E64[memb[a]] @ E64[memb[b]].T
                mm = min(m, M.shape[1]); ab = np.sort(M, 1)[:, -mm:].mean()
                mm = min(m, M.shape[0]); ba = np.sort(M, 0)[-mm:, :].mean()
                return min(ab, ba)
        group_pass(E64, find, size, S_, F_, gmerge, lk, group_link, group_min, fn, parent)
    # attach (frozen snapshot)
    att = {}
    for i, r in enumerate(q):
        if size[find(r)] != 1: continue
        cand = {}
        for j in range(k):
            c = find(I[i, j])
            if size[c] < 2 or c in cand: continue
            cand[c] = E64[r] @ S_(c) / size[c]
        best, bs, sec = -1, -2, -2
        for c, s in cand.items():
            if files[r] not in F_(c) and s > bs:
                if best >= 0: sec = max(sec, bs)
                best, bs = c, s
            else: sec = max(sec, s)
        if best >= 0 and bs >= attach and bs - sec >= margin: att[r] = best
    for r, c in att.items():
        c = find(c)
        if files[r] not in F_(c): merge(c, find(r))
    nid = (person.max() + 1) if next_id is None else next_id
    out = np.zeros(n, np.int64); given = {}
    for r in range(n):
        R = find(r)
        if R not in given:
            if pid[R] > 0: given[R] = pid[R]
            elif size[R] >= 2: given[R] = nid; nid += 1
            else: given[R] = 0
        out[r] = given[R]
    return out

def group_pass(E64, find, size, S_, F_, merge, lk, thr, gmin, fn, parent):
    """Merge whole groups: all pairs of groups with >= gmin faces, best first, re-scored lazily (exact)."""
    roots = sorted({find(i) for i in range(len(E64)) if size[find(i)] >= gmin})
    if len(roots) < 2: return
    fn = fn or (lambda a, b: lk(a, b))
    C = np.stack([S_(r) / size[r] for r in roots]); A = C @ C.T     # avg linkage = mean_a . mean_b
    ver = {r: 0 for r in roots}; heap = []
    for x in range(len(roots)):
        for y in np.where(A[x, x + 1:] >= thr - 0.15)[0] + x + 1:        # prefilter on avg linkage
            a, b = roots[x], roots[y]; s = fn(a, b)
            if s >= thr: heap.append((-s, a, b, 0, 0))
    heapq.heapify(heap)
    while heap:
        ns_, a0, b0, va, vb = heapq.heappop(heap)
        a, b = find(a0), find(b0)
        if a == b: continue
        if a != a0 or b != b0 or ver.get(a, 0) != va or ver.get(b, 0) != vb:
            s = fn(a, b)
            if s >= thr: heapq.heappush(heap, (-s, a, b, ver.get(a, 0), ver.get(b, 0)))
            continue
        if F_(a) & F_(b): continue
        m = merge(a, b); ver[m] = ver.get(m, 0) + 1

def suggestions(E, files, assign, truth, low=0.25, rounds=3, max_per_round=None):
    """"Same person?" merge suggestions: for each shown person (>= 2 faces), the other person with the highest average
    linkage, if >= low (no shared photo). Each pair is asked once; a simulated user answers from the ground truth
    (yes = merge). Reports how many questions are asked, how many are 'yes', and the split rate after answering."""
    assign = assign.copy(); asked = set()
    for rnd in range(rounds):
        groups = defaultdict(list)
        for i, a in enumerate(assign):
            if a >= 0: groups[a].append(i)
        ids = [g for g in groups if len(groups[g]) >= 2]
        if len(ids) < 2: break
        C = np.stack([E[groups[g]].astype(np.float64).mean(0) for g in ids]); A = C @ C.T; np.fill_diagonal(A, -9)
        F = [set(files[groups[g]]) for g in ids]
        maj = {g: np.bincount(np.searchsorted(np.unique(truth), truth[groups[g]])).argmax() for g in ids}
        qs = []; queued = set()
        for x in np.argsort(-A.max(1)):
            for y in np.argsort(-A[x])[:3]:
                if A[x, y] < low: break
                pair = (min(ids[x], ids[y]), max(ids[x], ids[y]))
                if pair in asked or pair in queued or F[x] & F[y]: continue
                queued.add(pair)
                qs.append((A[x, y], pair, maj[ids[x]] == maj[ids[y]], min(len(groups[ids[x]]), len(groups[ids[y]])))); break
        if max_per_round: qs = sorted(qs, reverse=True)[:max_per_round]
        if not qs: break
        yes = 0
        if rnd == 0:     # how often a suggestion is right, by score band and by the smaller group's size
            for lo_s, hi_s in ((2, 3), (3, 5), (5, 10), (10, 10**9)):
                cells = []
                for lo_, hi_ in ((0.25, 0.30), (0.30, 0.33), (0.33, 0.36), (0.36, 0.40), (0.40, 9)):
                    sel = [t for s, _, t, m in qs if lo_ <= s < hi_ and lo_s <= m < hi_s]
                    cells.append(f"{lo_:.2f}+ {sum(sel)}/{len(sel)}")
                print(f"   min size {lo_s}-{hi_s - 1 if hi_s < 10**9 else '...'}: " + "  ".join(cells))
        for s, (a, b), same, _ in qs:
            asked.add((a, b))
            if same and (assign == a).any() and (assign == b).any(): assign[assign == b] = a; yes += 1
        fr, sp, out, nid = split_stats(assign, truth)
        print(f"   round {rnd + 1}: asked {len(qs)}, yes {yes} ({yes / len(qs) * 100:.0f}%) -> split {sp * 100:.1f}%, "
              f"{fr:.2f} people/id, {out * 100:.1f}% faces outside main", flush=True)
    return assign

def incremental(E, files, batches, seed=0, **kw):
    """Simulates scanning a library in several passes (new folders / new photos): random order, `batches` chunks."""
    rng = np.random.default_rng(seed); order = rng.permutation(len(E)); person = np.zeros(len(E), np.int64)
    chunks = np.array_split(order, batches)
    for b in range(batches):
        idx = np.sort(np.concatenate(chunks[:b + 1]))      # everything scanned so far; earlier faces keep their person
        person[idx] = app_run(E[idx], files[idx], person[idx], next_id=person.max() + 1, **kw)
    return np.where(person > 0, person, -1)

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
        elif a.startswith("app") or a.startswith("inc"):
            # app[:link=..:attach=..:group=..:gmin=..]  = one full scan with the Kotlin algorithm
            # inc<N>[:...]                              = the same library scanned in N passes
            kw = dict(x.split("=") for x in a.split(":")[1:]) if ":" in a else {}
            ask = float(kw.pop("ask")) if "ask" in kw else None      # ask=<low>: simulate "Same person?" suggestions
            kw = {{"group": "group_link", "gmin": "group_min", "gfn": "group_fn"}.get(k_, k_): v if k_ == "gfn" else float(v)
                  for k_, v in kw.items()}
            for key in ("k", "group_min"):
                if key in kw: kw[key] = int(kw[key])
            head = a.split(":")[0]
            if head.startswith("inc"): assign = incremental(E, files, int(head[3:] or 4), **kw)
            else:
                out = app_run(E, files, np.zeros(len(E), np.int64), next_id=1, **kw); assign = np.where(out > 0, out, -1)
            report(a, assign, y, time.time() - t)
            if ask is not None: report("  after answering", suggestions(E, files, assign, y, low=ask), y, 0)
        elif a.startswith("hac"):
            kw = dict(x.split("=") for x in a.split(":")[1:]) if ":" in a else {}
            kw = {k_: (v == "1") if k_ == "centroid" else float(v) for k_, v in kw.items()}
            if "k" in kw: kw["k"] = int(kw["k"])
            report(a, hac(E, q, files, **kw), y, time.time() - t)
