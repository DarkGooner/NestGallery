# face-eval

Reference + verification tooling for the on-device face pipeline. None of this ships in the APK.

* `pipeline.py` – Python reference of the exact pipeline (SCRFD letterbox 640 → NMS → 5-point similarity align → ArcFace-MBF). The Kotlin code is a port of this.
* `evalpairs.py` – compares the old FaceNet-512 pipeline and the new one on labeled same/different pairs. Point `D` at your own folder + `master.csv` (`file_x,file_y,Decision`) to tune thresholds on *your* kind of photos.
* `fixtures.py` – dumps embeddings / raw SCRFD outputs / alignment transforms for the JVM tests.
* `jvm/Verify.kt` – compiles against the Android-free Kotlin core (`FaceMath.kt`, `FaceStore.kt`, `PersonClusterer.kt`) and checks: transform == reference, SCRFD decode == reference, int8 search == float cosine, folder scoping, cannot-link, named-person stability, clustering precision/recall on real embeddings, scaling vs the old hierarchical clusterer, and search latency.

```bash
pip install onnxruntime numpy pillow
# models: https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip  (det_500m.onnx, w600k_mbf.onnx)
python fixtures.py                       # writes emb.txt, scrfd_raw.txt, ... next to Verify.kt
D=../../app/src/main/java/com/nestgallery/viewer/data/face
kotlinc $D/FaceMath.kt $D/FaceStore.kt $D/PersonClusterer.kt -d core.jar
kotlinc -cp core.jar jvm/Verify.kt -d verify.jar
java -cp core.jar:verify.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar VerifyKt
```
