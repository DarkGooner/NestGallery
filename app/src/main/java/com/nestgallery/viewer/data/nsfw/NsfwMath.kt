package com.nestgallery.viewer.data.nsfw

import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One region a detector found, in pixels of the upright original photo. [x],[y] = top-left corner. */
class NsfwDetection(val label: String, val score: Float, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Everything detected in one photo. [width] x [height] is the upright (EXIF-rotated) original size; 0 x 0 means the
 * file could not be decoded (kept so it is not retried every scan, but it never matches a filter).
 */
class NsfwResult(val width: Int, val height: Int, val detections: List<NsfwDetection>, val ms: Long) {
    val decoded: Boolean get() = width > 0 && height > 0

    /** Distinct labels at or above [threshold], in [NsfwLabels.ALL] order. */
    fun labels(threshold: Float): List<String> {
        val present = detections.filter { it.score >= threshold }.mapTo(HashSet()) { it.label }
        return NsfwLabels.ALL.filter { it in present }
    }

    /** Compact tag string, most frequent first: "2FACE_FEMALE, 1MALE_GENITALIA_EXPOSED". Empty if nothing qualifies. */
    fun tagString(threshold: Float): String =
        detections.filter { it.score >= threshold }.groupingBy { it.label }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { NsfwLabels.indexOf(it.key) })
            .joinToString(", ") { "${it.value}${it.key}" }

    /** The detector's full output in the documented JSON shape (labels / detections / box = [x, y, w, h] / ms). */
    fun toJson(threshold: Float = 0f): String {
        val dets = detections.filter { it.score >= threshold }
        val sb = StringBuilder()
        sb.append("{\"width\":").append(width).append(",\"height\":").append(height).append(",\"labels\":[")
        labels(threshold).joinTo(sb, ",") { "\"$it\"" }
        sb.append("],\"detections\":[")
        dets.joinTo(sb, ",") {
            "{\"label\":\"${it.label}\",\"score\":${"%.4f".format(Locale.ROOT, it.score)},\"box\":[${it.x},${it.y},${it.w},${it.h}]}"
        }
        sb.append("],\"ms\":").append(ms).append('}')
        return sb.toString()
    }
}

object NsfwLabels {
    /** NudeNet v3 class order, from the model's own metadata. */
    val NUDENET = listOf(
        "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
        "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED", "BELLY_COVERED",
        "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE", "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED", "FEMALE_BREAST_COVERED", "BUTTOCKS_COVERED"
    )

    /** Every label, in the order the filter screen lists them (faces, exposed, covered). */
    val ALL = listOf(
        "FACE_FEMALE", "FACE_MALE",
        "FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED",
        "ANUS_EXPOSED", "MALE_BREAST_EXPOSED", "BELLY_EXPOSED", "ARMPITS_EXPOSED", "FEET_EXPOSED",
        "FEMALE_BREAST_COVERED", "FEMALE_GENITALIA_COVERED", "BUTTOCKS_COVERED", "ANUS_COVERED",
        "BELLY_COVERED", "ARMPITS_COVERED", "FEET_COVERED"
    )
    private val INDEX = ALL.withIndex().associate { it.value to it.index }

    fun indexOf(label: String): Int = INDEX[label] ?: -1

    /** Section heading the filter screen groups [label] under. */
    fun group(label: String): String = when {
        label.startsWith("FACE_") -> "Faces"
        label.endsWith("_EXPOSED") -> "Exposed"
        else -> "Covered"
    }

    /**
     * Labels in one group describe the same kind of region in mutually exclusive ways (a face is male OR female, a
     * body part exposed OR covered, a breast female OR male), so NMS lets one region keep only the best of them.
     * Different body parts are never suppressed by each other: overlapping male and female genitalia both count.
     */
    fun nmsGroup(label: String): String = when {
        label.startsWith("FACE_") -> "FACE"
        label.endsWith("_BREAST_EXPOSED") || label.endsWith("_BREAST_COVERED") -> "BREAST"
        label.endsWith("_EXPOSED") -> label.removeSuffix("_EXPOSED")
        label.endsWith("_COVERED") -> label.removeSuffix("_COVERED")
        else -> label
    }

    /** "FEMALE_BREAST_EXPOSED" -> "Female breast exposed". */
    fun display(label: String): String = label.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * Decoder for Ultralytics YOLOv8 / YOLO11 detection heads exported to ONNX (NudeNet is one).
 *
 * Output layout [1, 4 + classes, anchors]: per anchor cx, cy, w, h in model-input pixels, then one sigmoid score per
 * class. Matches nudenet.py's postprocessing (argmax class per anchor, NMS at IoU 0.45) except that NMS runs within
 * [NsfwLabels.nmsGroup]s instead of across all classes: overlapping regions of different body parts (e.g. male and
 * female genitalia during sex) are both kept, which per-label counts need, while one face still can't count as both
 * male and female. Measured against nudenet.py in tools/nsfw-eval/compare.py.
 */
object YoloDecoder {
    /** Lowest score kept (and stored). nudenet.py's NMS also drops everything below 0.25. */
    const val MIN_SCORE = 0.25f
    const val NMS_IOU = 0.45f

    /**
     * @param out      the raw output, [4 + labels.size] rows of [anchors] values each
     * @param contentW width of the photo inside the model input (before padding); boxes beyond it are clipped
     * @param contentH height of the photo inside the model input
     * @param width    original photo width the boxes are mapped to
     * @param height   original photo height
     */
    fun decode(
        out: FloatArray, anchors: Int, labels: List<String>,
        contentW: Int, contentH: Int, width: Int, height: Int,
        minScore: Float = MIN_SCORE, iouThreshold: Float = NMS_IOU
    ): List<NsfwDetection> {
        val nc = labels.size
        val groupNames = labels.map { NsfwLabels.nmsGroup(it) }
        val group = IntArray(nc) { groupNames.indexOf(groupNames[it]) }
        require(out.size >= (4 + nc) * anchors) { "output has ${out.size} values, expected ${(4 + nc) * anchors}" }
        val fx = width.toFloat() / contentW
        val fy = height.toFloat() / contentH
        val cands = ArrayList<Cand>()
        for (a in 0 until anchors) {
            var best = 0; var bestScore = out[4 * anchors + a]
            for (c in 1 until nc) {
                val s = out[(4 + c) * anchors + a]
                if (s > bestScore) { best = c; bestScore = s }
            }
            if (bestScore < minScore) continue
            val cx = out[a]; val cy = out[anchors + a]; val w = out[2 * anchors + a]; val h = out[3 * anchors + a]
            val x1 = ((cx - w / 2) * fx).coerceIn(0f, width.toFloat())
            val y1 = ((cy - h / 2) * fy).coerceIn(0f, height.toFloat())
            val x2 = ((cx + w / 2) * fx).coerceIn(0f, width.toFloat())
            val y2 = ((cy + h / 2) * fy).coerceIn(0f, height.toFloat())
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue
            cands.add(Cand(best, group[best], bestScore, x1, y1, x2, y2))
        }
        return nms(cands, iouThreshold).map {
            val x = it.x1.roundToInt(); val y = it.y1.roundToInt()
            NsfwDetection(labels[it.cls], it.score, x, y, it.x2.roundToInt() - x, it.y2.roundToInt() - y)
        }
    }

    internal class Cand(val cls: Int, val group: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    /** Greedy NMS within each group, highest score first. */
    internal fun nms(cands: List<Cand>, iouThreshold: Float): List<Cand> {
        val keep = ArrayList<Cand>()
        for (d in cands.sortedByDescending { it.score }) {
            if (keep.none { it.group == d.group && iou(it, d) > iouThreshold }) keep.add(d)
        }
        return keep
    }

    private fun iou(a: Cand, b: Cand): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * The box decoding the bundled models no longer contain (tools/nsfw-eval/split_head.py cuts it off, because the
 * Snapdragon NPU / GPU reject it): turns the Detect head's per-stride maps into the full model's [4 + classes, anchors]
 * output for [YoloDecoder]. That is Ultralytics' DFL (softmax over 16 distance bins, expected value) + dist2bbox
 * (anchor at the cell centre, minus left/top, plus right/bottom, times the stride) + sigmoid on the classes.
 * Matches the full model to ~0.0002 px / 2e-7 (split_head.py).
 */
object YoloHeadDecoder {
    const val REG_MAX = 16

    /**
     * @param maps    per stride (8, 16, 32), [4 * REG_MAX + classes, h, w] row-major: box logits side-major (l, t, r, b
     *                x 16 bins), then class logits
     * @param inputHeight model input height; stride = inputHeight / h
     * @param minScore anchors whose best class is below this get a zero box ([YoloDecoder] drops them anyway)
     * @return (output in [YoloDecoder.decode]'s layout, anchor count)
     */
    fun decode(
        maps: List<FloatArray>, heights: IntArray, widths: IntArray, classes: Int, inputHeight: Int,
        minScore: Float = YoloDecoder.MIN_SCORE
    ): Pair<FloatArray, Int> {
        val total = maps.indices.sumOf { heights[it] * widths[it] }
        val out = FloatArray((4 + classes) * total)
        val dist = FloatArray(4)
        var base = 0
        for (k in maps.indices) {
            val m = maps[k]; val h = heights[k]; val w = widths[k]; val hw = h * w
            require(m.size >= (4 * REG_MAX + classes) * hw) { "head map $k has ${m.size} values" }
            val stride = inputHeight.toFloat() / h
            for (p in 0 until hw) {
                val a = base + p
                var best = 0f
                for (c in 0 until classes) {
                    val s = 1f / (1f + exp(-m[(4 * REG_MAX + c) * hw + p]))
                    out[(4 + c) * total + a] = s
                    if (s > best) best = s
                }
                if (best < minScore) continue
                for (side in 0 until 4) {
                    val off = side * REG_MAX * hw + p
                    var mx = Float.NEGATIVE_INFINITY
                    for (b in 0 until REG_MAX) mx = max(mx, m[off + b * hw])
                    var sum = 0f; var acc = 0f
                    for (b in 0 until REG_MAX) { val e = exp(m[off + b * hw] - mx); sum += e; acc += e * b }
                    dist[side] = acc / sum
                }
                val ax = p % w + 0.5f; val ay = p / w + 0.5f
                val x1 = ax - dist[0]; val y1 = ay - dist[1]; val x2 = ax + dist[2]; val y2 = ay + dist[3]
                out[a] = (x1 + x2) / 2 * stride
                out[total + a] = (y1 + y2) / 2 * stride
                out[2 * total + a] = (x2 - x1) * stride
                out[3 * total + a] = (y2 - y1) * stride
            }
            base += hw
        }
        return out to total
    }
}

/**
 * Per-label counts for a set of photos and the range filter over them.
 *
 * A filter is a map label -> [min, max] (inclusive). A photo passes when, for EVERY range, its count of that label is
 * inside it (AND). [max] = [NO_MAX] means "no upper limit", so a slider dragged to the top keeps including photos with
 * more regions as the scan finds them.
 */
object NsfwFilter {
    const val NO_MAX = Int.MAX_VALUE

    /** Counts of each [NsfwLabels.ALL] label at or above [threshold]. */
    fun counts(result: NsfwResult, threshold: Float): IntArray {
        val c = IntArray(NsfwLabels.ALL.size)
        for (d in result.detections) {
            if (d.score < threshold) continue
            val i = NsfwLabels.indexOf(d.label)
            if (i >= 0) c[i]++
        }
        return c
    }

    /** Highest count of each label over [counts] (null entries = photos not scanned). */
    fun maxCounts(counts: List<IntArray?>): IntArray {
        val m = IntArray(NsfwLabels.ALL.size)
        for (c in counts) if (c != null) for (i in m.indices) if (c[i] > m[i]) m[i] = c[i]
        return m
    }

    /** True if [range] narrows anything for a label whose highest count is [max] (the default is the full 0..max). */
    fun isActive(range: IntRange, max: Int): Boolean = range.first > 0 || range.last < max

    /**
     * @param counts null = not scanned (or not decodable): never passes an active filter
     * @param ranges label index -> range; only the active ones need to be passed
     */
    fun matches(counts: IntArray?, ranges: Map<Int, IntRange>): Boolean {
        if (ranges.isEmpty()) return true
        if (counts == null) return false
        for ((i, r) in ranges) if (counts[i] < r.first || counts[i] > r.last) return false
        return true
    }

    /** True when nothing at all was detected (every count 0). */
    fun isEmpty(counts: IntArray): Boolean = counts.all { it == 0 }

    /**
     * Faceted histograms: result[label][n] = photos with exactly n of that label that pass every OTHER active range,
     * so the filter sheet can say how many photos each choice for a label would leave. One pass over the photos: a
     * photo failing no range counts for every label, one failing a single range only for that range's label.
     * [skipEmpty] leaves out photos where nothing was detected (the "hide empty" filter applies to every label).
     */
    fun facetHistograms(
        counts: List<IntArray?>,
        maxCounts: IntArray,
        ranges: Map<Int, IntRange>,
        skipEmpty: Boolean = false
    ): List<IntArray> {
        val hist = List(maxCounts.size) { IntArray(maxCounts[it] + 1) }
        val keys = ranges.keys.toIntArray()
        for (c in counts) {
            if (c == null || (skipEmpty && isEmpty(c))) continue
            var failed = -1
            var failures = 0
            for (k in keys) {
                val r = ranges.getValue(k)
                if (c[k] < r.first || c[k] > r.last) { failures++; failed = k; if (failures > 1) break }
            }
            when (failures) {
                0 -> for (i in hist.indices) hist[i][c[i].coerceAtMost(maxCounts[i])]++
                1 -> hist[failed][c[failed].coerceAtMost(maxCounts[failed])]++
            }
        }
        return hist
    }
}

/**
 * Counts for one folder, aligned with its file list: [counts] (null = not scanned / not decodable), per-label
 * [maxCounts] (the sliders' upper ends), how many photos have each label at least once, and per-label [histograms]
 * (histograms[label][n] = photos with exactly n regions of it) for the filter screen's bars.
 */
class NsfwFolderIndex(
    val counts: List<IntArray?>,
    val maxCounts: IntArray,
    val photosWithLabel: IntArray,
    val histograms: List<IntArray>,
    val scanned: Int,
    /** Scanned photos where nothing was detected at this threshold. */
    val empty: Int = 0
) {
    /** Labels found at least once, in [NsfwLabels.ALL] order (one slider each). */
    val presentLabels: List<Int> get() = maxCounts.indices.filter { maxCounts[it] > 0 }

    companion object {
        val EMPTY = build(emptyList(), 1f)

        fun build(results: List<NsfwResult?>, threshold: Float): NsfwFolderIndex {
            val counts = results.map { r -> if (r != null && r.decoded) NsfwFilter.counts(r, threshold) else null }
            val max = NsfwFilter.maxCounts(counts)
            val with = IntArray(NsfwLabels.ALL.size)
            val hist = List(NsfwLabels.ALL.size) { IntArray(max[it] + 1) }
            for (c in counts) if (c != null) for (i in with.indices) {
                if (c[i] > 0) with[i]++
                hist[i][c[i]]++
            }
            return NsfwFolderIndex(
                counts, max, with, hist,
                scanned = counts.count { it != null },
                empty = counts.count { it != null && NsfwFilter.isEmpty(it) }
            )
        }
    }
}
