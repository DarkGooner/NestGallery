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
    val description: String,
    /**
     * The NPU's copy: the same cut model with its input fixed to [inputSize] square and QDQ-quantised (16-bit
     * activations, 8-bit weights) by tools/nsfw-eval/quantize_qnn.py - the Hexagon HTP runs no float ops.
     */
    val npuAsset: String
)

/** The NudeNet v3 variants (YOLOv8, 18 body-part classes, AGPL-3.0), both bundled; Settings picks one. */
object NsfwModels {
    val N320 = NsfwModel(
        "nudenet-320n:v1", "nudenet_320n.onnx", 320, NsfwLabels.NUDENET,
        "NudeNet 320n (fast)",
        "YOLOv8n at 320 px, 12 MB. About 30 ms per photo per CPU core on a PC. Good for big libraries; small or " +
            "distant regions are missed more often.",
        npuAsset = "nudenet_320n_qdq.onnx"
    )
    val M640 = NsfwModel(
        "nudenet-640m:v1", "nudenet_640m.onnx", 640, NsfwLabels.NUDENET,
        "NudeNet 640m (accurate)",
        "YOLOv8m at 640 px, 104 MB. Finds smaller regions and is more reliable, but about 30x slower on a CPU: " +
            "a 20,000-photo library takes hours unless the NPU runs it (Settings > hardware).",
        npuAsset = "nudenet_640m_qdq.onnx"
    )
    val ALL = listOf(N320, M640)
    val DEFAULT = N320

    fun byId(id: String?): NsfwModel = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/**
 * Runs one YOLO model with ONNX Runtime. The photo's long side is scaled to [NsfwModel.inputSize] and the short side
 * padded (black, like nudenet.py) only up to a multiple of 32, not to a square: the exported models have dynamic H/W,
 * and for a 16:9 photo this is ~45% fewer pixels. On the NPU / GPU ([accelerator]) the shape must be fixed, so the
 * photo is padded to the full square there. Thread-safe: one session, run from several workers (on an accelerator the
 * runs themselves take turns; preparing the input still overlaps).
 */
class YoloDetector(context: Context, val model: NsfwModel, val accelerator: NsfwAccelerator = NsfwAccelerator.CPU) {
    private val session: OrtSession
    /** NPU / GPU: "whole model" or "partly, rest on the CPU". Empty on the CPU. */
    var placement: String = ""
        private set
    private val inputName: String
    private val size = model.inputSize
    private val square = accelerator == NsfwAccelerator.NPU || accelerator == NsfwAccelerator.GPU
    private val runLock = Any()
    private val scratch = object : ThreadLocal<FloatArray>() { override fun initialValue() = FloatArray(3 * size * size) }
    private val pixelScratch = object : ThreadLocal<IntArray>() { override fun initialValue() = IntArray(size * size) }

    init {
        val file = ModelFiles.materialize(context, model.asset)
        // A clone without `git lfs pull` ships the ~130-byte pointer instead of the model; say so instead of an ORT error.
        check(file.length() > 4096) { "${model.asset} is a Git LFS pointer, not the model. Run `git lfs pull` and rebuild." }
        session = when (accelerator) {
            NsfwAccelerator.NPU -> openNpu(context)
            NsfwAccelerator.GPU -> {
                // Strict: either QNN's GPU backend runs the whole model or this fails (and the scanner uses the CPU).
                Ort.env.createSession(file.absolutePath, NsfwSessions.options(context, model, accelerator, null, strict = true))
                    .also { placement = "whole model" }
            }
            else -> Ort.env.createSession(file.absolutePath, NsfwSessions.options(context, model, accelerator, null))
        }
        inputName = session.inputNames.first()
        // The bundled models end at the Detect head's three per-stride maps (box decoding is YoloHeadDecoder's job)
        require(session.numOutputs == 3L) { "${model.asset}: ${session.numOutputs} outputs, expected the 3 head maps (tools/nsfw-eval/split_head.py)" }
        val classes = (session.outputInfo.values.first().info as TensorInfo).shape[1] - 4 * YoloHeadDecoder.REG_MAX
        require(classes == model.labels.size.toLong()) { "${model.asset}: $classes classes, expected ${model.labels.size}" }
    }

    /**
     * The HTP compiles the model once and the compiled graph is cached (a stale or broken cache is rebuilt). First the
     * whole model is required on the NPU; if QNN rejects some nodes, a split NPU + CPU session is accepted only if the
     * compiled graph really contains an NPU part - otherwise everything would silently run on the CPU.
     */
    private fun openNpu(context: Context): OrtSession {
        val file = ModelFiles.materialize(context, model.npuAsset)
        check(file.length() > 4096) { "${model.npuAsset} is a Git LFS pointer, not the model. Run `git lfs pull` and rebuild." }
        val ctx = NsfwSessions.contextFile(context, model)
        if (ctx.exists() && !NsfwSessions.hasQnnPartition(ctx)) ctx.delete()   // a cache without an NPU part is useless
        if (ctx.exists()) {
            try {
                return Ort.env.createSession(ctx.absolutePath, NsfwSessions.options(context, model, accelerator, null))
                    .also { placement = "compiled graph from cache" }
            } catch (e: Exception) { ctx.delete() }
        }
        val strictError = try {
            return Ort.env.createSession(file.absolutePath, NsfwSessions.options(context, model, accelerator, ctx, strict = true))
                .also { placement = "whole model" }
        } catch (e: Exception) { ctx.delete(); e }
        val s = Ort.env.createSession(file.absolutePath, NsfwSessions.options(context, model, accelerator, ctx))
        if (!NsfwSessions.hasQnnPartition(ctx)) {
            s.close(); ctx.delete()
            throw IllegalStateException(
                "QNN ran none of the model on the NPU (${strictError.message?.lineSequence()?.firstOrNull()?.take(100)})"
            )
        }
        placement = "partly, the rest on the CPU"
        return s
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
        val cw = if (square) size else (w + 31) / 32 * 32
        val ch = if (square) size else (h + 31) / 32 * 32
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
        val maps = OnnxTensor.createTensor(Ort.env, input, longArrayOf(1, 3, ch.toLong(), cw.toLong())).use { tensor ->
            if (square) synchronized(runLock) { run(tensor) } else run(tensor)
        }
        val (out, anchors) = YoloHeadDecoder.decode(
            maps.map { it.first }, IntArray(3) { maps[it].second }, IntArray(3) { maps[it].third }, model.labels.size, ch
        )
        return YoloDecoder.decode(out, anchors, model.labels, w, h, width, height)
    }

    /** @return the three head maps as (values, height, width), stride 8 first */
    private fun run(tensor: OnnxTensor): List<Triple<FloatArray, Int, Int>> = session.run(mapOf(inputName to tensor)).use { result ->
        List(3) { i ->
            val t = result.get(i) as OnnxTensor
            val shape = t.info.shape                                    // [1, 4*16 + classes, h, w]
            val fb = t.floatBuffer
            Triple(FloatArray(fb.remaining()).also { fb.get(it) }, shape[2].toInt(), shape[3].toInt())
        }.sortedByDescending { it.second }                              // largest map = stride 8
    }

    fun close() = session.close()
}

/** Decode + detect for one [model] on one [accelerator] (never AUTO). Thread-safe. */
class NsfwAnalyzer(context: Context, val model: NsfwModel, val accelerator: NsfwAccelerator) {
    private val detector = YoloDetector(context, model, accelerator)
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

    /** Where the model's nodes ended up (see [YoloDetector.placement]). */
    val placement: String get() = detector.placement

    fun close() = detector.close()

    private companion object {
        val SIDEWAYS = setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
        )
    }
}
