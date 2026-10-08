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

/** A bundled Ultralytics YOLO detector: asset name, the long side it runs at, and its class names in output order. */
class NsfwModel(val asset: String, val inputSize: Int, val labels: List<String>)

object NsfwModels {
    /** NudeNet v3 320n (YOLOv8n, 18 body-part classes, AGPL-3.0), at its training size. */
    val NUDENET = NsfwModel("nudenet_320n.onnx", 320, NsfwLabels.NUDENET)

    /** EraX-NSFW-V1.0 YOLO11n (anus / make_love / nipple / penis / vagina, Apache-2.0), at its training size. */
    val ERAX = NsfwModel("erax_nsfw_yolo11n.onnx", 640, NsfwLabels.ERAX)

    val ALL = listOf(NUDENET, ERAX)

    /** Stored with the results; scans made with other models (or settings) are redone. */
    const val MODEL_ID = "nudenet320n+erax-yolo11n@640:v1"
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
        // One thread per run (Ort.options), like the face models: the scanner keeps every core busy with several photos.
        session = Ort.env.createSession(ModelFiles.materialize(context, model.asset).absolutePath, Ort.options())
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

/** Decode once, run every model. Thread-safe. */
class NsfwAnalyzer(context: Context) {
    private val detectors = NsfwModels.ALL.map { YoloDetector(context, it) }
    private val decodeSide = NsfwModels.ALL.maxOf { it.inputSize }

    /** Upright photo decoded small (long side in [640, 1280) for big photos) plus its original upright size. */
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

    fun analyze(d: Decoded): List<NsfwDetection> = detectors.flatMap { it.detect(d.bitmap, d.width, d.height) }

    private companion object {
        val SIDEWAYS = setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE
        )
    }
}
