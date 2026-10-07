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
 * ArcFace MobileFaceNet (InsightFace "w600k_mbf", trained on WebFace600K). 112x112 aligned RGB in,
 * L2-normalised 512-d embedding out. Same input/output contract as the larger w600k_r50 model, so
 * swapping in the more accurate (but ~8x heavier) ResNet50 only means changing [ASSET] and [MODEL_ID].
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
        const val ASSET = "arcface_mbf.onnx"
        /** Stored in the DB; if it changes, old embeddings are discarded (they live in a different vector space). */
        const val MODEL_ID = "scrfd500m+arcface_mbf_w600k:v1"
        const val SIZE = FaceMath.ALIGN_SIZE
    }
}
