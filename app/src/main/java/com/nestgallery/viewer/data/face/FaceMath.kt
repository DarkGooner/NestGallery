package com.nestgallery.viewer.data.face

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Android-free numeric building blocks of the face pipeline. Nothing in this file touches
 * Bitmap / ONNX / SQLite, so it is unit-testable on a plain JVM (and was: see tools/face-eval).
 */
object FaceMath {
    const val EMBED_DIM = 512
    const val ALIGN_SIZE = 112

    /** InsightFace / ArcFace 112x112 five-point template: L-eye, R-eye, nose, L-mouth, R-mouth (x,y pairs). */
    val ARCFACE_TEMPLATE = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * 2D similarity transform (rotation + uniform scale + translation):
     *   x' = a*x - b*y + tx
     *   y' = b*x + a*y + ty
     */
    class Similarity(val a: Float, val b: Float, val tx: Float, val ty: Float) {
        val scale: Float get() = sqrt(a * a + b * b)

        fun inverse(): Similarity {
            val d = a * a + b * b
            val ia = a / d
            val ib = -b / d
            return Similarity(ia, ib, -(ia * tx - ib * ty), -(ib * tx + ia * ty))
        }
    }

    /**
     * Least-squares similarity transform mapping [src] points onto [dst] points (both x,y interleaved).
     * Closed form using complex numbers - equivalent to Umeyama's SVD solution when no reflection is
     * allowed, but needs no SVD (verified against the SVD reference to 1.5e-5).
     */
    fun estimateSimilarity(src: FloatArray, dst: FloatArray): Similarity {
        val n = src.size / 2
        var msx = 0.0; var msy = 0.0; var mdx = 0.0; var mdy = 0.0
        for (i in 0 until n) {
            msx += src[2 * i]; msy += src[2 * i + 1]
            mdx += dst[2 * i]; mdy += dst[2 * i + 1]
        }
        msx /= n; msy /= n; mdx /= n; mdy /= n
        var den = 0.0; var re = 0.0; var im = 0.0
        for (i in 0 until n) {
            val ax = src[2 * i] - msx; val ay = src[2 * i + 1] - msy
            val bx = dst[2 * i] - mdx; val by = dst[2 * i + 1] - mdy
            den += ax * ax + ay * ay
            re += bx * ax + by * ay
            im += by * ax - bx * ay
        }
        if (den < 1e-9) return Similarity(1f, 0f, (mdx - msx).toFloat(), (mdy - msy).toFloat())
        val a = re / den
        val b = im / den
        val tx = mdx - (a * msx - b * msy)
        val ty = mdy - (b * msx + a * msy)
        return Similarity(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        if (n <= 0f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / n
        return out
    }

    /**
     * Maps a raw cosine similarity of the ArcFace embedding to a human-friendly 0..1 "match" score.
     * Centre 0.38 / slope 14 chosen from the genuine/impostor distributions we measured
     * (same-person median ~0.74, different-person median ~0.0, impostor tail ~0.25).
     * Purely cosmetic - all decisions use the raw cosine.
     */
    fun matchProbability(cosine: Float): Float = (1.0 / (1.0 + exp(-14.0 * (cosine - 0.38)))).toFloat()
}

/** One raw SCRFD detection in the coordinate space of the (letterboxed) detector input. */
class RawDetection(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val score: Float,
    /** 5 landmarks, x,y interleaved: L-eye, R-eye, nose, L-mouth, R-mouth. */
    val landmarks: FloatArray
)

/** Decoder for the InsightFace SCRFD-500M ONNX head (strides 8/16/32, 2 anchors per cell). */
object ScrfdDecoder {
    val STRIDES = intArrayOf(8, 16, 32)
    private const val ANCHORS = 2

    /**
     * @param scores per stride: g*g*2 values
     * @param boxes  per stride: g*g*2*4 values (l,t,r,b distances in stride units)
     * @param kps    per stride: g*g*2*10 values (landmark offsets in stride units)
     * @param inputSize side of the square detector input (e.g. 640)
     */
    fun decode(
        scores: Array<FloatArray>,
        boxes: Array<FloatArray>,
        kps: Array<FloatArray>,
        inputSize: Int,
        scoreThreshold: Float = 0.5f,
        nmsIou: Float = 0.4f
    ): List<RawDetection> {
        val candidates = ArrayList<RawDetection>()
        for (k in STRIDES.indices) {
            val stride = STRIDES[k]
            val g = inputSize / stride
            val sc = scores[k]
            val count = g * g * ANCHORS
            for (i in 0 until count) {
                val s = sc[i]
                if (s < scoreThreshold) continue
                val cell = i / ANCHORS
                val cx = ((cell % g) * stride).toFloat()
                val cy = ((cell / g) * stride).toFloat()
                val b = boxes[k]; val p = kps[k]
                val lm = FloatArray(10)
                for (j in 0 until 5) {
                    lm[2 * j] = cx + p[i * 10 + 2 * j] * stride
                    lm[2 * j + 1] = cy + p[i * 10 + 2 * j + 1] * stride
                }
                candidates.add(
                    RawDetection(
                        cx - b[i * 4] * stride, cy - b[i * 4 + 1] * stride,
                        cx + b[i * 4 + 2] * stride, cy + b[i * 4 + 3] * stride,
                        s, lm
                    )
                )
            }
        }
        return nms(candidates, nmsIou)
    }

    fun nms(dets: List<RawDetection>, iouThreshold: Float): List<RawDetection> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<RawDetection>()
        for (d in sorted) {
            var ok = true
            for (k in keep) {
                if (iou(d, k) > iouThreshold) { ok = false; break }
            }
            if (ok) keep.add(d)
        }
        return keep
    }

    private fun iou(a: RawDetection, b: RawDetection): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * Cheap, resolution-independent face quality estimate. Used so that only good faces can *start* a new
 * person; poor ones (tiny, blurry-sized, strong profile) may only join an existing person.
 */
object FaceQuality {
    /** Distance between eye centres. */
    fun eyeDistance(lm: FloatArray): Float {
        val dx = lm[2] - lm[0]; val dy = lm[3] - lm[1]
        return sqrt(dx * dx + dy * dy)
    }

    /** |nose offset from eye midpoint| / eye distance: ~0 frontal, >0.45 strong profile. */
    fun yawProxy(lm: FloatArray): Float {
        val d = eyeDistance(lm)
        if (d <= 1e-3f) return 1f
        val midX = (lm[0] + lm[2]) / 2f
        return kotlin.math.abs(lm[4] - midX) / d
    }

    /**
     * @param eyeDistNorm eye distance expressed on an 800px-long-side rendering of the photo
     * @return 0..1
     */
    fun score(detScore: Float, eyeDistNorm: Float, yaw: Float): Float {
        val size = (eyeDistNorm / 60f).coerceIn(0f, 1f)
        val pose = (1f - yaw / 0.6f).coerceIn(0f, 1f)
        val det = ((detScore - 0.5f) / 0.4f).coerceIn(0f, 1f)
        return 0.45f * size + 0.35f * pose + 0.20f * det
    }

    const val SEED_MIN_QUALITY = 0.50f
    const val SEED_MAX_YAW = 0.45f
    const val SEED_MIN_EYE_DIST = 22f

    fun canSeed(quality: Float, eyeDistNorm: Float, yaw: Float): Boolean =
        quality >= SEED_MIN_QUALITY && yaw <= SEED_MAX_YAW && eyeDistNorm >= SEED_MIN_EYE_DIST
}
