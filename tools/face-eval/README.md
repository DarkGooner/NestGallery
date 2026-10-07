# face-eval

Measurement tooling behind the on-device face pipeline. None of this ships in the APK.

| file | what it does |
|---|---|
| `embed.py` | Runs the app's pipeline (SCRFD detect -> 5-point align -> recogniser) over two labelled sets and caches embeddings in `emb/`. |
| `cluster.py` | Verification stats (TAR at FAR 1e-3/1e-4/1e-5) and clustering quality (BCubed / pairwise precision and recall) for the old Immich-style clusterer and the new average-linkage one. |
| `quant.py` | Static int8 (QDQ, per-channel) quantisation of a recogniser, calibrated on faces from both sets. |
| `make_knn_model.py` | Builds `app/src/main/assets/face_knn.onnx` (Gemm + TopK) used by `OnnxKnn`. |
| `export_fixture.py` | Writes cached embeddings for `PeopleClustererTest.realEmbeddingsFixture`. |

## Data

* **Real people** - LFW, 13,233 photos of 5,749 people (4,069 of them appear once, i.e. strangers): `https://huggingface.co/datasets/logasja/lfw` -> `data/train-00000-of-00001.parquet`, saved as `lfw.parquet`.
* **CGI renders** - DigiFace-1M subset, 8,000 renders of 1,600 synthetic identities (stand-in for Daz3D / Blender / game renders): `https://huggingface.co/datasets/34data/human-digiface1m-synthetic` -> `data.parquet`, saved as `digi.parquet`.
* `mix` in `cluster.py` = both sets in one library (CGI + real + strangers): the closest thing to a real NestGallery folder.
* Models (InsightFace, non-commercial research licence) from `https://github.com/deepinsight/insightface/releases/tag/v0.7`, unpacked into `$FACE_EVAL_DATA/models/`: `buffalo_l.zip` (`w600k_r50.onnx`, the fp32 source of the shipped int8 model), `buffalo_s.zip` (`w600k_mbf.onnx`, the old recogniser, for comparison) and `buffalo_m.zip` (`det_2.5g.onnx`). The shipped int8 model (`r50s8`) is read straight from the app's assets.

```bash
pip install onnxruntime onnx numpy pillow pyarrow
export FACE_EVAL_DATA=~/face-eval-data          # lfw.parquet, digi.parquet, models/
python embed.py digi,lfw mbf,r50s8              # DET=2.5g to use the SCRFD-2.5G detector instead
python cluster.py mix r50s8 immich hac:link=0.45:attach=0.35
```

## Results (2026-10-08)

Verification on the `mix` set (CGI + real), raw cosine:

| recogniser | TAR @ FAR 1e-3 | @ 1e-4 | @ 1e-5 | different-person 99.9th pct |
|---|---|---|---|---|
| MobileFaceNet (`w600k_mbf`, old) | 0.9948 | 0.9821 | 0.9356 | 0.339 |
| MobileFaceNet + flip | 0.9953 | 0.9837 | 0.9463 | 0.343 |
| ResNet-50 (`w600k_r50`) fp32 | 0.9982 | 0.9948 | 0.9865 | 0.317 |
| **ResNet-50 int8, conv-only (shipped)** | 0.9980 | 0.9943 | 0.9863 | 0.315 |

CGI faces look much more alike than real ones: 99.9% of different-person pairs score below 0.21-0.23 on LFW but below 0.38-0.40 on the renders. Thresholds tuned on real photos alone are far too loose for 3D renders.

Clustering (BCubed precision / recall; "pairP" = pairwise precision):

| set | recogniser | old clusterer (Immich port + reconcile) | new: avg-linkage 0.45, attach 0.35 |
|---|---|---|---|
| CGI | MobileFaceNet | P 0.763 / R 0.889, **pairP 0.035** | P 0.997 / R 0.930 |
| CGI | ResNet-50 | P 0.891 / R 0.912, pairP 0.524 | P 0.998 / R 0.950 |
| mix | MobileFaceNet | P 0.890 / R 0.943, pairP 0.405 | P 0.995 / R 0.963 |
| mix | ResNet-50 | P 0.946 / R 0.963, pairP 0.949 | P 0.996 / R 0.979 |
| CGI | **ResNet-50 int8 (shipped)** | | P 0.998 / R 0.941 |
| mix | **ResNet-50 int8 (shipped)** | | P 0.996 / R 0.975 |

The old clusterer's pairwise precision of 0.035 on CGI means a few giant groups each swallowed many different
characters: single-link chaining (one look-alike face bridges two people) plus the "reconcile" step merging any
two groups that touch. Average linkage needs the *whole* groups to match on average, so one bridge face can't merge them.

Remaining errors on LFW (~50 impure groups, mostly pairs) appear with every model and threshold, which points at
LFW's known labelling mistakes rather than the clusterer.

### Model choice

Timings are single-thread x86 and approximate (other jobs were running); only the ratios matter.

| recogniser | size | ms/face (x86, 1 thread) | notes |
|---|---|---|---|
| MobileFaceNet fp32 | 13 MB | 15 | old model |
| ResNet-50 fp32 | 166 MB | ~400 | best accuracy, too heavy for 20k photos on a phone |
| ResNet-50 int8, everything quantised | 42 MB | 31 | cos(fp32, int8) 0.980: loses about half of ResNet-50's gain on CGI |
| **ResNet-50 int8, Conv/Gemm only** | 42 MB | 32 | cos 0.987; matches fp32 on the mixed set (table above) |

Detector: SCRFD-2.5G (`buffalo_m`) gave the same verification accuracy as the bundled SCRFD-500M (TAR@1e-4 on CGI
0.800 vs 0.798; landmarks are equally good) and found 0.3% more faces, at 5x the detection cost - not worth it.

Flip test-time augmentation (embed the mirrored face too) barely helps MobileFaceNet (CGI recall 0.930 -> 0.935)
and doubles the cost; not used.
