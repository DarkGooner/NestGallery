package com.nestgallery.viewer.data.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.nio.FloatBuffer

/** Copies a bundled model out of the APK once so ONNX Runtime can memory-map it from a real file. */
internal object ModelFiles {
    fun materialize(context: Context, assetName: String): File {
        val out = File(context.filesDir, "models/$assetName").apply { parentFile?.mkdirs() }
        val size = context.assets.openFd(assetName).use { it.length }
        if (!out.exists() || out.length() != size) {
            val tmp = File(out.path + ".tmp")
            context.assets.open(assetName).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(out)
            // Copies of models an older version shipped (e.g. the 42 MB ResNet-50) would otherwise stay forever.
            val shipped = context.assets.list("")?.toSet() ?: emptySet()
            out.parentFile?.listFiles()?.forEach { if (it.name !in shipped && !it.name.endsWith(".tmp")) it.delete() }
        }
        return out
    }
}

internal object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * One thread per session on purpose: the scanner runs several images in parallel (one per worker), which
     * keeps every core busy without the cross-thread overhead of splitting tiny conv layers further.
     */
    fun options(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
}

/**
 * SCRFD-500MF face detector (InsightFace). Unlike the old ML Kit detector it returns five real facial
 * landmarks per face, which is what makes proper ArcFace alignment possible.
 * Input: a bitmap whose longest side is <= [INPUT_SIZE]; it is placed top-left on a zero (grey) canvas.
 */
class ScrfdDetector(context: Context) {
    private val session: OrtSession
    private val inputName: String
    private val scratch = object : ThreadLocal<FloatArray>() {
        override fun initialValue() = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    }
    private val pixelScratch = object : ThreadLocal<IntArray>() {
        override fun initialValue() = IntArray(INPUT_SIZE * INPUT_SIZE)
    }

    // output index (0..8) -> (kind: 0 score / 1 box / 2 kps, stride slot 0..2), resolved once from the model
    private val outputKind = IntArray(9)
    private val outputSlot = IntArray(9)

    init {
        val file = ModelFiles.materialize(context, "scrfd_500m.onnx")
        session = Ort.env.createSession(file.absolutePath, Ort.options())
        inputName = session.inputNames.first()
        // The head emits 3 scores [N,1], 3 boxes [N,4], 3 keypoints [N,10]; N shrinks 4x per stride.
        val infos = session.outputInfo.values.map { (it.info as ai.onnxruntime.TensorInfo).shape }
        val order = (0 until 9).sortedWith(compareBy({ infos[it].last() }, { -infos[it].first() }))
        // sorted by last dim (1,4,10) then by count desc (stride 8,16,32)
        for ((rank, idx) in order.withIndex()) { outputKind[idx] = rank / 3; outputSlot[idx] = rank % 3 }
    }

    /**
     * @param bitmap longest side <= [INPUT_SIZE]. It is placed top-left on a canvas of its own size rounded up to
     * a multiple of 32 (the model accepts any such size), so a 4:3 or 16:9 photo costs 25-45% less than the old
     * fixed 640x640 square. Measured on sample photos: same faces, same boxes.
     * @return detections in [bitmap] pixel coordinates
     */
    fun detect(bitmap: Bitmap, scoreThreshold: Float = 0.5f): List<RawDetection> {
        val w = bitmap.width; val h = bitmap.height
        require(w <= INPUT_SIZE && h <= INPUT_SIZE) { "detector input too large: ${w}x$h" }
        val cw = (w + 31) / 32 * 32
        val ch = (h + 31) / 32 * 32
        val data = scratch.get()!!
        java.util.Arrays.fill(data, 0, 3 * cw * ch, 0f)
        val px = pixelScratch.get()!!
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val plane = cw * ch
        for (y in 0 until h) {
            val rowOut = y * cw
            val rowIn = y * w
            for (x in 0 until w) {
                val p = px[rowIn + x]
                data[rowOut + x] = (((p shr 16) and 0xFF) - 127.5f) / 128f
                data[plane + rowOut + x] = (((p shr 8) and 0xFF) - 127.5f) / 128f
                data[2 * plane + rowOut + x] = ((p and 0xFF) - 127.5f) / 128f
            }
        }
        val scores = arrayOfNulls<FloatArray>(3)
        val boxes = arrayOfNulls<FloatArray>(3)
        val kps = arrayOfNulls<FloatArray>(3)
        val input = FloatBuffer.wrap(data, 0, 3 * plane).slice()
        OnnxTensor.createTensor(Ort.env, input, longArrayOf(1, 3, ch.toLong(), cw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                for (i in 0 until 9) {
                    val fb = (result.get(i) as OnnxTensor).floatBuffer
                    val arr = FloatArray(fb.remaining()); fb.get(arr)
                    when (outputKind[i]) {
                        0 -> scores[outputSlot[i]] = arr
                        1 -> boxes[outputSlot[i]] = arr
                        else -> kps[outputSlot[i]] = arr
                    }
                }
            }
        }
        return ScrfdDecoder.decode(
            Array(3) { scores[it]!! }, Array(3) { boxes[it]!! }, Array(3) { kps[it]!! },
            cw, ch, scoreThreshold
        )
    }

    fun close() = session.close()

    companion object { const val INPUT_SIZE = 640 }
}

/**
 * Face recogniser: AdaFace IR-101 trained on WebFace12M (CVLFace release), int8-quantised (convolutions only,
 * percentile calibration, see tools/face-eval/quant.py). 112x112 aligned RGB in, L2-normalised 512-d embedding out.
 * The class keeps its old name; it is the ArcFace-style 112px pipeline either way.
 *
 * Chosen over the previous ArcFace ResNet-50 (w600k_r50) on measurements (tools/face-eval/README.md): at a
 * 1-in-10,000 false-match rate it accepts 77.5% vs 73.2% of same-character pairs on CGI renders with varied
 * expression / lighting / accessories, and is ahead on synthetic hand/food occlusion and cross-pose photos too.
 * It costs about 2.5x the compute of ResNet-50 per face.
 */
class ArcFaceEmbedder(context: Context) {
    private val session: OrtSession
    private val inputName: String
    private val pixelScratch = object : ThreadLocal<IntArray>() {
        override fun initialValue() = IntArray(SIZE * SIZE)
    }

    init {
        val file = ModelFiles.materialize(context, ASSET)
        session = Ort.env.createSession(file.absolutePath, Ort.options())
        inputName = session.inputNames.first()
    }

    /** @param aligned a 112x112 ARGB bitmap produced by [FaceAligner]. */
    fun embed(aligned: Bitmap): FloatArray = embedBatch(listOf(aligned))[0]

    /**
     * Embeds several aligned faces in one model call (about 18% less time per face than one call each,
     * measured on the same CPU). Returns L2-normalised vectors in the input order.
     */
    fun embedBatch(faces: List<Bitmap>): List<FloatArray> {
        if (faces.isEmpty()) return emptyList()
        val n = faces.size
        val plane = SIZE * SIZE
        val data = FloatArray(n * 3 * plane)
        val px = pixelScratch.get()!!
        for ((k, f) in faces.withIndex()) {
            require(f.width == SIZE && f.height == SIZE)
            f.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            val base = k * 3 * plane
            for (i in 0 until plane) {
                val p = px[i]
                data[base + i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f
                data[base + plane + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f
                data[base + 2 * plane + i] = ((p and 0xFF) - 127.5f) / 127.5f
            }
        }
        val raw = OnnxTensor.createTensor(Ort.env, FloatBuffer.wrap(data), longArrayOf(n.toLong(), 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val fb = (result.get(0) as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        val dim = raw.size / n
        return List(n) { k -> FaceMath.l2Normalize(raw.copyOfRange(k * dim, (k + 1) * dim)) }
    }

    fun close() = session.close()

    companion object {
        const val ASSET = "adaface_ir101_int8.onnx"
        /** Stored in the DB; if it changes, old embeddings are discarded (they live in a different vector space). */
        const val MODEL_ID = "scrfd500m+adaface_ir101_webface12m_int8:v1"
        const val SIZE = FaceMath.ALIGN_SIZE
    }
}

/**
 * k-nearest-neighbour search on ONNX Runtime (`face_knn.onnx` = Gemm + TopK, built by tools/face-eval/make_knn_model.py).
 * Its matrix kernels are vectorised, unlike ART-compiled Kotlin, so this is ~4x faster than [BruteForceNeighborFinder]:
 * a full rebuild of 40k faces is 40k x 40k x 512 multiply-adds.
 *
 * The base set is processed in blocks of [BLOCK] rows (32 MB as float32) and the per-block top-k lists are merged,
 * so memory stays bounded however large the library gets.
 */
class OnnxKnn(modelFile: File) : NeighborFinder {
    private val session: OrtSession

    constructor(context: Context) : this(ModelFiles.materialize(context, ASSET))

    init {
        val file = modelFile
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 6))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = Ort.env.createSession(file.absolutePath, opts)
    }

    override fun find(store: FaceStore, queries: IntArray, base: IntArray, k: Int, onProgress: ((Int, Int) -> Unit)?): KnnResult {
        val dim = store.dim
        val rows = IntArray(queries.size * k) { -1 }
        val sims = FloatArray(queries.size * k) { Float.NEGATIVE_INFINITY }
        if (queries.isEmpty() || base.isEmpty()) return KnnResult(k, rows, sims)
        val blocks = (base.size + BLOCK - 1) / BLOCK
        val qBatches = (queries.size + QUERY_BATCH - 1) / QUERY_BATCH
        val baseBuf = java.nio.ByteBuffer.allocateDirect(minOf(BLOCK, base.size) * dim * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        val qBuf = java.nio.ByteBuffer.allocateDirect(minOf(QUERY_BATCH, queries.size) * dim * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        var step = 0
        for (blk in 0 until blocks) {
            val b0 = blk * BLOCK; val b1 = minOf(base.size, b0 + BLOCK)
            baseBuf.clear(); store.copyEmbeddings(base, b0, b1, baseBuf); baseBuf.flip()
            // +1: a query that is itself in the base set finds itself first; it is skipped below.
            val kk = minOf(k + 1, b1 - b0).toLong()
            OnnxTensor.createTensor(Ort.env, baseBuf, longArrayOf((b1 - b0).toLong(), dim.toLong())).use { baseT ->
                OnnxTensor.createTensor(Ort.env, longArrayOf(kk)).use { kT ->
                    for (qb in 0 until qBatches) {
                        val q0 = qb * QUERY_BATCH; val q1 = minOf(queries.size, q0 + QUERY_BATCH)
                        qBuf.clear(); store.copyEmbeddings(queries, q0, q1, qBuf); qBuf.flip()
                        OnnxTensor.createTensor(Ort.env, qBuf, longArrayOf((q1 - q0).toLong(), dim.toLong())).use { qT ->
                            session.run(mapOf("queries" to qT, "base" to baseT, "k" to kT)).use { out ->
                                val vals = (out.get(0) as OnnxTensor).floatBuffer
                                val idx = (out.get(1) as OnnxTensor).longBuffer
                                val w = kk.toInt()
                                for (i in 0 until q1 - q0) {
                                    val qi = q0 + i
                                    for (j in 0 until w) {
                                        val r = base[b0 + idx.get(i * w + j).toInt()]
                                        if (r == queries[qi]) continue
                                        insert(rows, sims, qi * k, k, r, vals.get(i * w + j))
                                    }
                                }
                            }
                        }
                        onProgress?.invoke(++step, blocks * qBatches)
                    }
                }
            }
        }
        for (i in sims.indices) if (rows[i] < 0) sims[i] = 0f
        return KnnResult(k, rows, sims)
    }

    /** Inserts into the sorted (descending) slice [off, off+k); returns early once the candidate can't qualify. */
    private fun insert(rows: IntArray, sims: FloatArray, off: Int, k: Int, row: Int, sim: Float) {
        if (sim <= sims[off + k - 1]) return
        var j = k - 1
        while (j > 0 && sims[off + j - 1] < sim) { rows[off + j] = rows[off + j - 1]; sims[off + j] = sims[off + j - 1]; j-- }
        rows[off + j] = row; sims[off + j] = sim
    }

    fun close() = session.close()

    companion object {
        const val ASSET = "face_knn.onnx"
        private const val BLOCK = 16384
        private const val QUERY_BATCH = 256
    }
}
