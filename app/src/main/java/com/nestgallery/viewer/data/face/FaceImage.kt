package com.nestgallery.viewer.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Decodes photos at a size that is cheap to decode but still has enough pixels for small faces. */
object FaceImageLoader {
    /** The decoded bitmap's long side lands in [MIN_LONG_SIDE, 2*MIN_LONG_SIDE). */
    const val MIN_LONG_SIDE = 800

    fun decodeFile(file: File): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            val orientation = try {
                ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
            upright(bmp, orientation)
        } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
    }

    /** For photos picked through the system picker (query image of "find by face"). */
    fun decodeUri(context: Context, uri: Uri): Bitmap? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        bmp?.let { upright(it, orientation) }
    } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        val longSide = max(w, h)
        while (longSide / (s * 2) >= MIN_LONG_SIDE) s *= 2
        return s
    }

    /** Applies all eight EXIF orientations (the old code only handled the three pure rotations). */
    private fun upright(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out != src) src.recycle()
        return out
    }
}

/** Warps a face to the ArcFace 112x112 template using the native canvas (bilinear, no Kotlin pixel loops). */
object FaceAligner {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * @param landmarks 5 points (x,y interleaved) in [source] pixel coordinates
     * @param template  target template; defaults to the ArcFace one (112 px). Thumbnails pass a zoomed-out copy.
     */
    fun align(source: Bitmap, landmarks: FloatArray, size: Int = FaceMath.ALIGN_SIZE, template: FloatArray = FaceMath.ARCFACE_TEMPLATE): Bitmap {
        val t = FaceMath.estimateSimilarity(landmarks, template)
        val m = Matrix().apply { setValues(floatArrayOf(t.a, -t.b, t.tx, t.b, t.a, t.ty, 0f, 0f, 1f)) }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, m, paint)
        return out
    }
}

class AnalyzedFace(
    /** 0..1 box relative to the analysed image. */
    val bounds: RectF,
    /** 5 landmarks, x,y interleaved, 0..1 relative to the analysed image (lets thumbnails be re-rendered later). */
    val landmarksNorm: FloatArray,
    val embedding: FloatArray,
    /** 0..1, see [FaceQuality]. */
    val quality: Float,
    val detScore: Float,
    /** Aligned 112x112 crop; only filled when requested (query images). Caller owns/recycles it. */
    val aligned: Bitmap?
)

/** Detect -> align -> embed -> quality, for one decoded photo. Thread-safe (sessions are shared). */
class FaceAnalyzer(private val detector: ScrfdDetector, private val embedder: ArcFaceEmbedder) {

    private class Pending(val det: RawDetection, val aligned: Bitmap, val quality: Float, val detW: Float, val detH: Float)

    fun analyze(src: Bitmap, keepAligned: Boolean = false, maxFaces: Int = 40): List<AnalyzedFace> {
        val longSide = max(src.width, src.height)
        val scale = ScrfdDetector.INPUT_SIZE.toFloat() / longSide
        val detBmp = if (longSide == ScrfdDetector.INPUT_SIZE) src else Bitmap.createScaledBitmap(
            src, max(1, (src.width * scale).toInt()), max(1, (src.height * scale).toInt()), true
        )
        val pending = ArrayList<Pending>()
        try {
            val dets = detector.detect(detBmp, MIN_DET_SCORE)
                .sortedByDescending { (it.x2 - it.x1) * (it.y2 - it.y1) }
                .take(maxFaces)
            val hiRes = src.width.toFloat() / detBmp.width
            for (d in dets) {
                val eyeNorm = FaceQuality.eyeDistance(d.landmarks) * 800f / ScrfdDetector.INPUT_SIZE
                if (eyeNorm < FaceQuality.MIN_EYE_DIST) continue       // too small to embed reliably
                val yaw = FaceQuality.yawProxy(d.landmarks)
                val q = FaceQuality.score(d.score, eyeNorm, yaw)

                // Small faces are aligned from the larger decode (more real pixels); big faces from the detector image.
                val useSrc = (d.x2 - d.x1) < FaceMath.ALIGN_SIZE && src !== detBmp
                val source = if (useSrc) src else detBmp
                val lm = if (useSrc) FloatArray(10) { d.landmarks[it] * hiRes } else d.landmarks
                pending.add(Pending(d, FaceAligner.align(source, lm), q, detBmp.width.toFloat(), detBmp.height.toFloat()))
            }
            if (pending.isEmpty()) return emptyList()

            val embeddings = try { embedder.embedBatch(pending.map { it.aligned }) } catch (e: Exception) { return emptyList() }
            return pending.mapIndexed { i, p ->
                val d = p.det
                val bounds = RectF(
                    (d.x1 / p.detW).coerceIn(0f, 1f), (d.y1 / p.detH).coerceIn(0f, 1f),
                    (d.x2 / p.detW).coerceIn(0f, 1f), (d.y2 / p.detH).coerceIn(0f, 1f)
                )
                val lmNorm = FloatArray(10) { if (it % 2 == 0) d.landmarks[it] / p.detW else d.landmarks[it] / p.detH }
                AnalyzedFace(bounds, lmNorm, embeddings[i], p.quality, d.score, if (keepAligned) p.aligned else null)
            }
        } finally {
            if (detBmp !== src) detBmp.recycle()
            if (!keepAligned) for (p in pending) p.aligned.recycle()
        }
    }

    companion object {
        /** Immich's default minimum detection score: biased towards precision (fewer junk "faces" polluting clusters). */
        const val MIN_DET_SCORE = 0.7f
    }
}

/** Renders the round cover images for people - only for the faces that are actually used as covers. */
object FaceThumbnails {
    private const val SIZE = 128
    /**
     * ArcFace template scaled by [zoom] around the centre of a 128 canvas. 0.72 = a little head-room around the face;
     * larger values crop tighter (1.14 is about the 112 px recognition crop).
     */
    private fun template(zoom: Float) = FloatArray(10) { i ->
        (FaceMath.ARCFACE_TEMPLATE[i] - FaceMath.ALIGN_SIZE / 2f) * zoom + SIZE / 2f
    }
    private val ZOOMS = floatArrayOf(0.72f, 0.8f, 0.88f, 0.96f, 1.05f, 1.14f)

    /**
     * Least zoom whose crop lies inside the photo. A tilted face near the photo edge otherwise samples outside it,
     * which showed as black wedges on cover thumbnails.
     */
    private fun fittingTemplate(lm: FloatArray, w: Int, h: Int): FloatArray {
        for (z in ZOOMS) {
            val tpl = template(z)
            val inv = FaceMath.estimateSimilarity(lm, tpl).inverse()      // thumbnail pixel -> photo pixel
            val fits = listOf(0f to 0f, SIZE.toFloat() to 0f, 0f to SIZE.toFloat(), SIZE.toFloat() to SIZE.toFloat()).all { (x, y) ->
                val sx = inv.a * x - inv.b * y + inv.tx; val sy = inv.b * x + inv.a * y + inv.ty
                sx >= -1f && sy >= -1f && sx <= w + 1f && sy <= h + 1f
            }
            if (fits) return tpl
        }
        return template(ZOOMS.last())                                     // still clipped: the smallest wedge
    }

    fun render(file: File, landmarksNorm: FloatArray, out: File): Boolean {
        val bmp = FaceImageLoader.decodeFile(file) ?: return false
        return try {
            val lm = FloatArray(10) { if (it % 2 == 0) landmarksNorm[it] * bmp.width else landmarksNorm[it] * bmp.height }
            val thumb = FaceAligner.align(bmp, lm, SIZE, fittingTemplate(lm, bmp.width, bmp.height))
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            thumb.recycle()
            true
        } catch (e: Exception) { false } finally { bmp.recycle() }
    }
}
