"""Recogniser comparison table: TAR at FAR 1e-3 / 1e-4 / 1e-5 for every (set, model) whose embeddings are cached.
usage: python compare.py digi72,digi72occ,cplfw,calfw,mix r50s8,r50,ada101,lvb[,a+b ensembles]"""
import os, sys, numpy as np
from collections import defaultdict
from cluster import load, E_DIR

def tar(E, y, rng):
    by = defaultdict(list)
    for i, t in enumerate(y): by[t].append(i)
    pos = np.array([(a, b) for idx in by.values() for i, a in enumerate(idx) for b in idx[i + 1:]])
    if len(pos) > 2_000_000: pos = pos[rng.choice(len(pos), 2_000_000, replace=False)]
    ps = np.einsum("ij,ij->i", E[pos[:, 0]], E[pos[:, 1]])
    ns = []
    for _ in range(15):
        a = rng.integers(0, len(y), 200_000); b = rng.integers(0, len(y), 200_000); m = y[a] != y[b]
        ns.append(np.einsum("ij,ij->i", E[a[m]], E[b[m]]))
    ns = np.sort(np.concatenate(ns))
    return [(ps >= ns[int(len(ns) * (1 - f))]).mean() for f in (1e-3, 1e-4, 1e-5)]

sets, models = sys.argv[1].split(","), sys.argv[2].split(",")
print("| set | " + " | ".join(models) + " |"); print("|---" * (len(models) + 1) + "|")
for s in sets:
    row = []
    for m in models:
        try:
            E, y, *_ = load(s, m); t = tar(E, y, np.random.default_rng(0))
            row.append(" / ".join(f"{v:.3f}" for v in t))
        except FileNotFoundError:
            row.append("-")
    print(f"| {s} | " + " | ".join(row) + " |", flush=True)
