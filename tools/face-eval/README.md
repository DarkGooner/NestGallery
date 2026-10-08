# face-eval

Measurement tooling behind the on-device face pipeline. None of this ships in the APK.

| file | what it does |
|---|---|
| `embed.py` | Runs the app's pipeline (SCRFD detect -> 5-point align -> recogniser) over labelled sets and caches embeddings in `emb/`. `EP=cuda` runs recognisers on an NVIDIA GPU, `FLIP=1` also embeds mirrored faces. |
| `cluster.py` | Verification stats (TAR at FAR 1e-3/1e-4/1e-5) and clustering quality: BCubed / pairwise precision and recall plus **split stats** (share of identities spread over several people). `app` = Python port of `PeopleClusterer.kt`, `inc<N>` = the library scanned in N passes, `ask=<t>` = simulated "Same person?" answers. |
| `compare.py` | TAR table for several sets x recognisers (`a+b` = ensemble). |
| `fetch_digi72.py` | Range-reads N identities x 72 renders out of the 2.9 GB DigiFace-1M zips. |
| `export_adaface.py` | Exports AdaFace IR-101 (CVLFace) to ONNX. |
| `quant.py` | Static int8 (QDQ, per-channel, Conv/Gemm/MatMul only) quantisation; MinMax or percentile calibration. |
| `make_knn_model.py` | Builds `app/src/main/assets/face_knn.onnx` (Gemm + TopK) used by `OnnxKnn`. |
| `export_fixture.py` | Writes cached embeddings for `PeopleClustererTest.realEmbeddingsFixture`. |

## Setup (Windows 11; WSL works the same with `~`)

```powershell
python -m venv $HOME\face-eval-venv
& $HOME\face-eval-venv\Scripts\pip install onnxruntime onnx numpy pillow pyarrow sympy
# optional GPU venv for big fp32 models (driver 566 = CUDA 12.7; newer onnxruntime-gpu wheels want CUDA 13):
python -m venv $HOME\face-eval-gpu
& $HOME\face-eval-gpu\Scripts\pip install "onnxruntime-gpu[cuda,cudnn]==1.22.0" "nvidia-cudnn-cu12==9.10.2.21" onnx numpy pillow pyarrow
& $HOME\face-eval-gpu\Scripts\pip install torch --index-url https://download.pytorch.org/whl/cpu   # + omegaconf safetensors torchvision, for export_adaface.py
$env:FACE_EVAL_DATA = "$HOME\face-eval-data"
```

Windows notes: GitHub downloads with `curl.exe` need `--ssl-no-revoke` (and `-C -` to resume); ORT silently falls back
to CPU when CUDA fails to load, so `embed.py` raises instead.

## Data (`$FACE_EVAL_DATA`)

* `lfw.parquet` - LFW, 13,233 photos of 5,749 people (4,069 appear once = strangers): `huggingface.co/datasets/logasja/lfw` `data/train-00000-of-00001.parquet`.
* `digi.parquet` - DigiFace-1M subset, 8,000 renders of 1,600 CGI identities, 5 each (dense look-alikes): `huggingface.co/datasets/34data/human-digiface1m-synthetic` `data.parquet`.
* `digi72.parquet` - `python fetch_digi72.py 400`: 400 identities x 72 renders from `huggingface.co/datasets/lhoestq/digiface1m_720k` - big changes of expression, lighting, accessories and pose per character. **This is the set that reproduces the "same character split into several people" bug.**
* `digi72occ` - the same faces with synthetic lower-face occluders (hand / food / cup / mask-like band) on 40% of them (`embed.py occlude`).
* `cplfw.parquet` - cross-pose LFW, 11,652 aligned crops: `huggingface.co/api/datasets/LSIbabnikz/cplfw/parquet/default/train/0.parquet`.
* `calfw_raw.parquet` - cross-age LFW, 12,174 photos: `huggingface.co/api/datasets/marcelohaps/calfw/parquet/default/raw/0.parquet`.
* `mix` in `cluster.py` = digi + lfw in one library (CGI + real + strangers).
* `models/`: `buffalo_l.zip` from `github.com/deepinsight/insightface/releases/tag/v0.7` (`w600k_r50.onnx`), LVFace T/S/B (`huggingface.co/bytedance-research/LVFace`), `adaface_src/` (`huggingface.co/minchul/cvlface_adaface_ir101_webface12m`: `models/`, `pretrained_model/`), `arcface_r50_int8.onnx` (the previously shipped model, `git show 9250b1c:app/src/main/assets/arcface_r50_int8.onnx`).

```powershell
python embed.py digi,lfw,digi72,digi72occ,cplfw,calfw r50s8,ada8s
python compare.py digi72,digi72occ,cplfw,digi,calfw,mix r50s8,ada8s
python cluster.py digi72 ada8s app:link=0.42:attach=0.33:ask=0.36
```

## Results (2026-10-08)

### Recogniser: AdaFace IR-101 int8 (shipped) vs ArcFace ResNet-50 int8 (before)

TAR at FAR 1e-3 / 1e-4 / 1e-5. `ada8` = AdaFace int8 as shipped (identical file to `ada8s`).

| set | ResNet-50 int8 (before) | ResNet-50 fp32 | AdaFace IR-101 fp32 | **AdaFace IR-101 int8** | LVFace-B fp32 |
|---|---|---|---|---|---|
| CGI, 72 renders/character | 0.852 / 0.732 / 0.579 | 0.862 / 0.748 / 0.590 | 0.892 / 0.784 / 0.650 | **0.883 / 0.775 / 0.647** | 0.839 / 0.712 / 0.585 |
| same + occlusion | 0.777 / 0.631 / 0.483 | 0.790 / 0.652 / 0.482 | 0.832 / 0.701 / 0.543 | **0.820 / 0.685 / 0.525** | 0.755 / 0.607 / 0.448 |
| cross-pose (CPLFW) | 0.900 / 0.862 / 0.468 | 0.903 / 0.866 / 0.524 | 0.912 / 0.889 / 0.744 | **0.907 / 0.876 / 0.649** | 0.895 / 0.877 / 0.834 |
| CGI look-alikes (digi) | 0.941 / 0.859 / 0.758 | 0.946 / 0.870 / 0.767 | 0.957 / 0.892 / 0.793 | **0.961 / 0.896 / 0.806** | 0.934 / 0.860 / 0.739 |
| cross-age (CALFW) | 0.953 / 0.947 / 0.932 | 0.953 / 0.947 / 0.932 | 0.953 / 0.949 / 0.939 | **0.953 / 0.948 / 0.935** | 0.948 / 0.944 / 0.936 |
| LFW | 1.000 / 0.999 / 0.999 | 1.000 / 1.000 / 0.999 | 1.000 / 1.000 / 0.999 | **1.000 / 1.000 / 0.999** | 1.000 / 0.999 / 0.999 |
| mix | 0.998 / 0.994 / 0.986 | 0.998 / 0.995 / 0.987 | 0.999 / 0.996 / 0.989 | **0.999 / 0.996 / 0.989** | 0.998 / 0.994 / 0.959 |

* LVFace T / S were below ResNet-50 on CGI as well (digi72 TAR@1e-4 0.618 / 0.682). Ensembles (r50+AdaFace, AdaFace+LVFace-B) added <= 1.6 points over AdaFace alone - not worth two big models on a phone. Flip TTA: +1.3 points on AdaFace at 2x cost; not used.
* Quantisation: MinMax calibration (enough for ResNet-50, cos(fp32, int8) 0.987) gave AdaFace only 0.960, because IR blocks start with a BatchNorm that can't be folded into a conv. Percentile 99.99 calibration: cos 0.975 and TAR within ~1 point of fp32 (table).
* Cost: ~2.5x ResNet-50 per face (x86 single thread int8: 53 vs ~134 ms, measured while other jobs ran). 63 MB vs 42 MB.

### Clustering: why people were split

On `digi72` with the old setup (ResNet-50 int8, link 0.45): **80% of characters were split, 2.94 people per character** (92% / 4.15 with occlusion), at 0.9996 precision. Three causes, three fixes:

1. **Recogniser** - see above.
2. **Frozen groups.** The app grouped scan by scan and never re-examined existing people, so (a) two people that were really one character were never compared again, and (b) early wrong pairings stuck: scanning `mix` in 4 passes gave 4.9% impure groups vs 1.6% in one pass (`inc4` vs `app`; digi 5.9% vs 0.6%). Fix: every run regroups all people the user has not curated (named / merged / corrected / answered), and they keep their ids by overlap.
3. **Thresholds can't separate the rest.** At group level, look-alike CGI characters stay look-alikes: the 99.99th-percentile average linkage between *different* characters only falls from 0.36 to 0.31 as groups grow from 2 to 35 faces, while 1% of same-character halves score below 0.33-0.40. Size-aware thresholds, a group-merge pass and top-k linkage were all no better than a uniform threshold at equal precision. So the band just below the merge threshold is **asked**, not guessed.

AdaFace int8, average linkage (one full run = what every scan now does):

| setting | digi72 split / people per char. | digi72 P | digi P | mix P / R |
|---|---|---|---|---|
| link 0.45, attach 0.35 | 69.5% / 2.37 | 0.9997 | 0.9987 | 0.9960 / 0.9829 |
| **link 0.42, attach 0.33 (shipped)** | **49.8% / 1.80** | 0.9996 | 0.9933 | 0.9936 / 0.9886 |
| link 0.40 | 41.5% / 1.59 | 0.9996 | 0.9877 | 0.9909 / 0.9891 |
| with occlusion, shipped | 75.0% / 2.56 | 0.9986 | | |

"Same person?" questions (`ask=`; pairs with average linkage in [ask, 0.42), best first, simulated user):

| ask from | digi72: questions (yes) -> split | mix: questions (yes) | digi: questions (yes) |
|---|---|---|---|
| **0.36 (shipped)** | 266 (86%) -> **19.2%, 1.23 people/char.** | 149 (15%) | 148 (14%) |
| 0.38 | 198 (90%) -> 29.5% | 61 (31%) | 60 (30%) |
| 0.40 | <= 181 -> 40.5% | <= 39 | |

Below 0.42 the score cannot tell a split from a look-alike: in the 0.36-0.40 band, 97% of pairs were one character on digi72 (fragments of a big group) but ~0% between complete 3-9 face groups on digi/mix (look-alike characters). Questions are shown best-first, so most of the early ones are "yes".

The Kotlin port reproduces this: `realEmbeddingsFixture` on the AdaFace-int8 `mix` embeddings gives BCubed precision 0.9936 with 3,312 people (Python: 0.9936, 3,311).

## Earlier results (ResNet-50 vs MobileFaceNet, Immich-style vs average-linkage clusterer)

Verification on `mix`, raw cosine:

| recogniser | TAR @ FAR 1e-3 | @ 1e-4 | @ 1e-5 | different-person 99.9th pct |
|---|---|---|---|---|
| MobileFaceNet (`w600k_mbf`) | 0.9948 | 0.9821 | 0.9356 | 0.339 |
| ResNet-50 (`w600k_r50`) fp32 | 0.9982 | 0.9948 | 0.9865 | 0.317 |
| ResNet-50 int8, conv-only | 0.9980 | 0.9943 | 0.9863 | 0.315 |

| set | recogniser | old clusterer (Immich port + reconcile) | avg-linkage 0.45, attach 0.35 |
|---|---|---|---|
| CGI | MobileFaceNet | P 0.763 / R 0.889, **pairP 0.035** | P 0.997 / R 0.930 |
| mix | ResNet-50 | P 0.946 / R 0.963, pairP 0.949 | P 0.996 / R 0.979 |

The old clusterer's pairwise precision of 0.035 on CGI means a few giant groups each swallowed many characters
(single-link chaining plus a "reconcile" step merging any groups that touch). Average linkage needs the *whole* groups
to match on average, so one bridge face can't merge them. SCRFD-2.5G gave the same accuracy as the bundled SCRFD-500M
at 5x the cost. Remaining LFW errors (~50 impure groups, mostly pairs) appear with every model and threshold, which
points at LFW's known labelling mistakes.
