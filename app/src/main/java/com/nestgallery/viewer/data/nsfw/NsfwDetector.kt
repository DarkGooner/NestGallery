package com.nestgallery.viewer.data.nsfw

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.nestgallery.viewer.data.face.FaceImageLoader
import com.nestgallery.viewer.data.face.ModelFiles
import com.nestgallery.viewer.data.face.Ort
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A bundled Ultralytics YOLO detector. [id] is stored with every result (each model keeps its own results, so
 * switching back and forth never loses a scan); change it if the model or its pre/post-processing changes.
 */
class NsfwModel(
    val id: String,
    val asset: String,
    /** Long side the photo is scaled to (the model's training size). */
    val inputSize: Int,
    val labels: List<String>,
    val title: String,
    val description: String
)

/** The NudeNet v3 variants (YOLOv8, 18 body-part classes, AGPL-3.0), both bundled; Settings picks one. */
object NsfwModels {
    val N320 = NsfwModel(
        "nudenet-320n:v1", "nudenet_320n.onnx", 320, NsfwLabels.NUDENET,
        "NudeNet 320n (fast)",
        "YOLOv8n at 320 px, 12 MB. About 30 ms per photo per CPU core on a PC. Good for big libraries; small or " +
            "distant regions are missed more often."
    )
    val M640 = NsfwModel(
        "nudenet-640m:v1", "nudenet_640m.onnx", 640, NsfwLabels.NUDENET,
        "NudeNet 640m (accurate)",
        "YOLOv8m at 640 px, 104 MB. Finds smaller regions and is more reliable, but about 30x slower: a 20,000-photo " +
            "library can take many hours on a phone."
    )
    val ALL = listOf(N320, M640)
    val DEFAULT = N320

    fun byId(id: String?): NsfwModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * Runs one YOLO model with ONNX Runtime. The photo's long side is scaled to [NsfwModel.inputSize] and the short side
 * padded (black, like nudenet.py) only up to a multiple of 32, not to a square: the exported models have dynamic H/W,
 * and for a 16:9 photo this is ~45% fewer pixels. Thread-safe: one session, run from several workers.
 */
class YoloDetector(context: Context, val model: NsfwModel) {
    private val session: OrtSession
    private val inputName: String
    private val size = model.inputSize
    private val scratch = object : ThreadLocal<FloatArray>() { override fun initialValue() = FloatArray(3 * size * size) }
    private val pixelScratch = object : ThreadLocal<IntArray>() { override fun initialValue() = IntArray(size * size) }

    init {
        val file = ModelFiles.materialize(context, model.asset)
        // A clone without `git lfs pull` ships the ~130-byte pointer instead of the model; say so instead of an ORT error.
        check(file.length() > 4096) { "${model.asset} is a Git LFS pointer, not the model. Run `git lfs pull` and rebuild." }
        // The small model: one thread per run (Ort.options) like the face models, the scanner runs several photos at
        // once. The big one runs fewer photos at a time (memory), so it gets two threads each.
        val options = if (model.inputSize > 320) Ort.options().apply { setIntraOpNumThreads(2) } else Ort.options()
        session = Ort.env.createSession(file.absolutePath, options)
        inputName = session.inputNames.first()
        val classes = (session.outputInfo.values.first().info as TensorInfo).shape[1] - 4
        require(classes == model.labels.size.toLong()) { "${model.asset}: $classes classes, expected ${model.labels.size}" }
    }

    /**
     * @param src    the upright photo, any size (it is scaled here)
     * @param width  original upright width the boxes are reported in
     * @param height original upright height
     */
    fun detect(src: Bitmap, width: Int, height: Int): List<NsfwDetection> {
        val scale = size.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceIn(1, size)
        val h = (src.height * scale).roundToInt().coerceIn(1, size)
        val bmp = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        val cw = (w + 31) / 32 * 32
        val ch = (h + 31) / 32 * 32
        val plane = cw * ch
        val data = scratch.get()!!
        java.util.Arrays.fill(data, 0, 3 * plane, 0f)
        val px = pixelScratch.get()!!
        try {
            bmp.getPixels(px, 0, w, 0, 0, w, h)
        } finally {
            if (bmp !== src) bmp.recycle()
        }
        for (y in 0 until h) {
            val rowOut = y * cw
            val rowIn = y * w
            for (x in 0 until w) {
                val p = px[rowIn + x]
                data[rowOut + x] = ((p shr 16) and 0xFF) / 255f
                data[plane + rowOut + x] = ((p shr 8) and 0xFF) / 255f
                data[2 * plane + rowOut + x] = (p and 0xFF) / 255f
            }
        }
        val input = FloatBuffer.wrap(data, 0, 3 * plane).slice()
        return OnnxTensor.createTensor(Ort.env, input, longArrayOf(1, 3, ch.toLong(), cw.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val t = result.get(0) as OnnxTensor
                val anchors = t.info.shape[2].toInt()
                val fb = t.floatBuffer
                val out = FloatArray(fb.remaining()).also { fb.get(it) }
                YoloDecoder.decode(out, anchors, model.labels, w, h, width, height)
            }
        }
    }

    fun close() = session.close()
}

/** Decode + detect for one [model]. Thread-safe. */
class NsfwAnalyzer(context: Context, val model: NsfwModel) {
    private val detector = YoloDetector(context, model)
    private val decodeSide = model.inputSize

    /** Upright photo decoded small (long side in [inputSize, 2*inputSize) for big photos) plus its original upright size. */
    class Decoded(val bitmap: Bitmap, val width: Int, val height: Int)

    fun decode(file: File): Decoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bmp = FaceImageLoader.decodeFile(file, decodeSide) ?: return null
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val sideways = orientation in SIDEWAYS
        return Decoded(bmp, if (sideways) bounds.outHeight else bounds.outWidth, if (sideways) bounds.outWidth else bounds.outHeight)
    }

    fun analyze(d: Decoded): List<NsfwDetection> = detector.detect(d.bitmap, d.width, d.height)

    fun close() = detector.close()

    private companion object {
        val SIDEWAYS = setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
        )
    }
}
