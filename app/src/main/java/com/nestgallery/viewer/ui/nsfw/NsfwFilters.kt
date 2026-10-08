package com.nestgallery.viewer.ui.nsfw

import androidx.compose.animation.animateColorAsState
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.nsfw.NsfwFilter
import com.nestgallery.viewer.data.nsfw.NsfwFolderIndex
import com.nestgallery.viewer.data.nsfw.NsfwLabels
import java.text.NumberFormat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The NSFW filter of one folder: a [threshold] (minimum detection score that counts) and a count range per label.
 * Held per folder for the life of the process (like GalleryCache), so it survives opening the viewer.
 */
@Stable
class NsfwFilterState {
    var threshold by mutableFloatStateOf(DEFAULT_THRESHOLD)

    /** Selected tab of the NSFW screen (0 Filters, 1 Photos): coming back from the viewer lands on Photos again. */
    var tab by mutableIntStateOf(0)

    /** label index -> chosen range; last = [NsfwFilter.NO_MAX] means "and more". Absent = full range (no filter). */
    val ranges = mutableStateMapOf<Int, IntRange>()

    /** The ranges that narrow anything given the library's current [maxCounts], clamped to them. */
    fun activeRanges(maxCounts: IntArray): Map<Int, IntRange> {
        val out = HashMap<Int, IntRange>()
        for ((i, r) in ranges) {
            val max = maxCounts.getOrElse(i) { 0 }
            val lo = r.first.coerceAtMost(max)
            val hi = if (r.last == NsfwFilter.NO_MAX) max else r.last.coerceAtMost(max)
            if (NsfwFilter.isActive(lo..hi, max)) out[i] = lo..hi
        }
        return out
    }

    /** Stores [lo]..[hi] for label [i] whose highest count is [max]; the full range removes the filter. */
    fun set(i: Int, lo: Int, hi: Int, max: Int) {
        if (lo <= 0 && hi >= max) ranges.remove(i)
        else ranges[i] = lo..(if (hi >= max) NsfwFilter.NO_MAX else hi)   // the top end means "and more"
    }

    fun clear() = ranges.clear()

    companion object {
        /** Detections below this score are ignored when counting (the stored floor is 0.25). */
        const val DEFAULT_THRESHOLD = 0.45f
        const val MIN_THRESHOLD = 0.25f
        const val MAX_THRESHOLD = 0.90f

        private val byFolder = HashMap<String, NsfwFilterState>()
        fun forFolder(path: String): NsfwFilterState = byFolder.getOrPut(path) { NsfwFilterState() }
    }
}

/** "Any", "None", "Exactly 2", "2 or more", "Up to 3", "1 – 3". */
fun rangeText(lo: Int, hi: Int, max: Int): String = when {
    lo <= 0 && hi >= max -> "Any"
    lo == hi -> if (lo == 0) "None" else "Exactly $lo"
    hi >= max -> "$lo or more"
    lo == 0 -> "Up to $hi"
    else -> "$lo – $hi"
}

/**
 * Drops taps and drags that are really part of scrolling the filter list: while it scrolls and for [QUIET_MS] after it
 * stops (the tap that stops a fling lands on whatever is under the finger). Plain fields, read only inside gesture
 * handlers, so nothing recomposes.
 */
class TouchGuard {
    internal var scrolling = false
    internal var lastScrollEnd = 0L
    fun allows(): Boolean = !scrolling && SystemClock.uptimeMillis() - lastScrollEnd > QUIET_MS

    companion object { const val QUIET_MS = 350L }
}

@Composable
fun rememberTouchGuard(state: ScrollableState): TouchGuard {
    val guard = remember { TouchGuard() }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { s ->
            if (!s && guard.scrolling) guard.lastScrollEnd = SystemClock.uptimeMillis()
            guard.scrolling = s
        }
    }
    return guard
}

/** Minimum-confidence card (0.25..0.90 in steps of 0.05). */
@Composable
fun NsfwConfidenceCard(state: NsfwFilterState, guard: TouchGuard, modifier: Modifier = Modifier) {
    val stops = ((NsfwFilterState.MAX_THRESHOLD - NsfwFilterState.MIN_THRESHOLD) / 0.05f).roundToInt() + 1
    val index = ((state.threshold - NsfwFilterState.MIN_THRESHOLD) / 0.05f).roundToInt().coerceIn(0, stops - 1)
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minimum confidence", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${(state.threshold * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val geom = StopGeometry(constraints.maxWidth.toFloat(), with(LocalDensity.current) { TRACK_INSET.toPx() }, stops)
                StepTrack(
                    geom, index, null, guard,
                    onChange = { lo, _ -> state.threshold = ((NsfwFilterState.MIN_THRESHOLD * 100).roundToInt() + lo * 5) / 100f },
                    description = "Minimum confidence ${(state.threshold * 100).roundToInt()} percent"
                )
            }
            Text(
                "A region counts when the detector is at least this sure. Lower finds more but also more mistakes; no rescan needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One label: name and current choice, a histogram of how many photos have 0, 1, 2... of it with each bar exactly over
 * its slider stop (tap a bar for "exactly that many"), a two-thumb slider over 0..max, and quick choices.
 */
@Composable
fun NsfwLabelCard(index: NsfwFolderIndex, labelIndex: Int, state: NsfwFilterState, guard: TouchGuard, modifier: Modifier = Modifier) {
    val n = NumberFormat.getInstance()
    val max = index.maxCounts[labelIndex]
    val hist = index.histograms[labelIndex]
    val range = state.ranges[labelIndex]
    val lo = range?.first?.coerceIn(0, max) ?: 0
    val hi = range?.last?.let { if (it == NsfwFilter.NO_MAX) max else it.coerceIn(lo, max) } ?: max
    val active = NsfwFilter.isActive(lo..hi, max)
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(if (active) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.4f), label = "labelBorder")
    fun guarded(action: () -> Unit): () -> Unit = { if (guard.allows()) action() }

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) colors.primary.copy(alpha = 0.08f) else colors.surfaceVariant.copy(alpha = 0.35f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        NsfwLabels.display(NsfwLabels.ALL[labelIndex]),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "In ${n.format(index.photosWithLabel[labelIndex])} photos · up to $max in one",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) colors.primary else colors.surfaceVariant,
                    contentColor = if (active) colors.onPrimary else colors.onSurfaceVariant
                ) {
                    Text(rangeText(lo, hi, max), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            // bars, count labels and slider share one StopGeometry, so bar k sits exactly above slider stop k
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val geom = StopGeometry(constraints.maxWidth.toFloat(), with(LocalDensity.current) { TRACK_INSET.toPx() }, max + 1)
                Column {
                    Histogram(geom, hist, lo, hi, guard, onPick = { v -> state.set(labelIndex, v, v, max) })
                    StopLabels(geom, lo, hi)
                    StepTrack(
                        geom, lo, hi, guard,
                        onChange = { a, b -> state.set(labelIndex, a, b ?: max, max) },
                        description = "${NsfwLabels.display(NsfwLabels.ALL[labelIndex])}: ${rangeText(lo, hi, max)}"
                    )
                }
            }
            Row(Modifier.padding(start = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !active, onClick = guarded { state.ranges.remove(labelIndex) }, label = { Text("Any") })
                FilterChip(selected = active && hi == 0, onClick = guarded { state.set(labelIndex, 0, 0, max) }, label = { Text("None") })
                FilterChip(selected = active && lo == 1 && hi == max, onClick = guarded { state.set(labelIndex, 1, max, max) }, label = { Text("1 or more") })
                if (max >= 2) FilterChip(selected = active && lo == 2 && hi == max, onClick = guarded { state.set(labelIndex, 2, max, max) }, label = { Text("2 or more") })
            }
        }
    }
}

/** Space between the card edge and the first / last stop: room for a thumb, and the bars use the same positions. */
private val TRACK_INSET = 16.dp

/** Evenly spaced stops 0..n-1 across [width], [inset] from both ends. Used by the bars, labels and the slider alike. */
private class StopGeometry(val width: Float, val inset: Float, val n: Int) {
    val spacing: Float get() = if (n <= 1) width else (width - 2 * inset) / (n - 1)
    fun x(k: Int): Float = if (n <= 1) width / 2 else inset + k * spacing
    fun nearest(x: Float): Int = if (n <= 1) 0 else ((x - inset) / spacing).roundToInt().coerceIn(0, n - 1)
}

/**
 * Photos per count, bar k centred on stop k. Heights use a square root so a huge "0" bar doesn't flatten the rest;
 * bars inside the chosen range are highlighted. A tap (not a scroll) selects exactly that count.
 */
@Composable
private fun Histogram(geom: StopGeometry, hist: IntArray, lo: Int, hi: Int, guard: TouchGuard, onPick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val on = colors.primary
    val off = colors.onSurfaceVariant.copy(alpha = 0.25f)
    val peak = (hist.maxOrNull() ?: 0).coerceAtLeast(1)
    val pick by rememberUpdatedState(onPick)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(geom.n, geom.width) {
                detectTapGestures { p ->
                    val k = geom.nearest(p.x)
                    if (guard.allows() && abs(p.x - geom.x(k)) <= geom.spacing / 2) pick(k)
                }
            }
            .semantics { contentDescription = "Photos per count: " + hist.withIndex().joinToString { "${it.value} with ${it.index}" } }
    ) {
        val barW = min(geom.spacing * 0.7f, 28.dp.toPx())
        for (k in hist.indices) {
            if (hist[k] == 0) continue
            val h = (0.08f + 0.92f * sqrt(hist[k].toFloat() / peak)) * size.height
            drawRoundRect(
                color = if (k in lo..hi) on else off,
                topLeft = Offset(geom.x(k) - barW / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
        }
    }
}

/** The count under each stop (every few stops when there are many), centred on it. */
@Composable
private fun StopLabels(geom: StopGeometry, lo: Int, hi: Int) {
    val every = if (geom.n <= 12) 1 else (geom.n + 11) / 12
    val shown = (0 until geom.n).filter { it % every == 0 || it == geom.n - 1 }
    val colors = MaterialTheme.colorScheme
    Layout(
        content = {
            for (k in shown) Text("$k", style = MaterialTheme.typography.labelSmall, color = if (k in lo..hi) colors.primary else colors.onSurfaceVariant)
        },
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
    ) { measurables, c ->
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(c.maxWidth, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEachIndexed { j, p ->
                p.place((geom.x(shown[j]) - p.width / 2f).roundToInt().coerceIn(0, (c.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }
    }
}

/**
 * Slider over the stops of [geom] with one thumb ([hi] = null) or two. Unlike Material's Slider it ignores taps on the
 * track and only moves when a thumb is grabbed and dragged sideways past the touch slop, so a vertical swipe that
 * starts on it scrolls the list instead of changing the value; [guard] also drops touches right after a scroll.
 */
@Composable
private fun StepTrack(
    geom: StopGeometry,
    lo: Int,
    hi: Int?,
    guard: TouchGuard,
    onChange: (Int, Int?) -> Unit,
    description: String
) {
    val colors = MaterialTheme.colorScheme
    val primary = colors.primary
    val inactive = colors.onSurfaceVariant.copy(alpha = 0.25f)
    val tickOn = colors.onPrimary.copy(alpha = 0.8f)
    val tickOff = colors.onSurfaceVariant.copy(alpha = 0.5f)
    val curLo by rememberUpdatedState(lo)
    val curHi by rememberUpdatedState(hi)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf<Int?>(null) }        // 0 = low thumb, 1 = high thumb
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = description }
            .pointerInput(geom.n, geom.width) {
                val grab = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!guard.allows()) return@awaitEachGesture
                    val dLo = abs(down.position.x - geom.x(curLo))
                    val dHi = curHi?.let { abs(down.position.x - geom.x(it)) } ?: Float.MAX_VALUE
                    if (min(dLo, dHi) > grab) return@awaitEachGesture            // the bare track: no jumping
                    var thumb: Int? = when {
                        curHi == null || dLo < dHi -> 0
                        dHi < dLo -> 1
                        else -> null                                           // thumbs on one stop: pick by direction
                    }
                    var dir = 0f
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { c, over -> c.consume(); dir = over }
                        ?: return@awaitEachGesture                             // vertical first: it is a scroll
                    val t = thumb ?: (if (dir < 0) 0 else 1).also { thumb = it }
                    dragging = t
                    fun moveTo(x: Float) {
                        val k = geom.nearest(x)
                        val h = curHi
                        if (t == 0) { val v = if (h == null) k else min(k, h); if (v != curLo) change(v, h) }
                        else { val v = max(k, curLo); if (v != h) change(curLo, v) }
                    }
                    moveTo(start.position.x)
                    horizontalDrag(start.id) { c -> moveTo(c.position.x); c.consume() }
                    dragging = null
                }
            }
    ) {
        val cy = size.height / 2
        val stroke = 4.dp.toPx()
        drawLine(inactive, Offset(geom.x(0), cy), Offset(geom.x(geom.n - 1), cy), stroke, StrokeCap.Round)
        val a = if (hi == null) geom.x(0) else geom.x(lo)
        val b = if (hi == null) geom.x(lo) else geom.x(hi)
        drawLine(primary, Offset(a, cy), Offset(b, cy), stroke, StrokeCap.Round)
        if (geom.n <= 30) for (k in 0 until geom.n) {
            val inside = if (hi == null) k <= lo else k in lo..hi
            drawCircle(if (inside) tickOn else tickOff, 1.5.dp.toPx(), Offset(geom.x(k), cy))
        }
        val thumbs = if (hi == null) listOf(lo) else listOf(lo, hi)
        thumbs.forEachIndexed { t, v ->
            val pressed = dragging == t
            if (pressed) drawCircle(primary.copy(alpha = 0.18f), 20.dp.toPx(), Offset(geom.x(v), cy))
            drawCircle(primary, (if (pressed) 12 else 10).dp.toPx(), Offset(geom.x(v), cy))
        }
    }
}

/** Removable chips for the active filters ("Face female: Exactly 2  x"), for the top of the photo grid. */
@Composable
fun NsfwActiveFilterChips(active: Map<Int, IntRange>, maxCounts: IntArray, state: NsfwFilterState, modifier: Modifier = Modifier) {
    if (active.isEmpty()) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((i, r) in active.entries.sortedBy { it.key }) {
            InputChip(
                selected = true,
                onClick = { state.ranges.remove(i) },
                label = { Text("${NsfwLabels.display(NsfwLabels.ALL[i])}: ${rangeText(r.first, r.last, maxCounts[i]).lowercase()}") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove filter", modifier = Modifier.size(16.dp)) }
            )
        }
    }
}
