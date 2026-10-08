package com.nestgallery.viewer.ui.nsfw

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/** Minimum-confidence card. */
@Composable
fun NsfwConfidenceCard(state: NsfwFilterState, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Minimum confidence", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${(state.threshold * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = state.threshold,
                onValueChange = { state.threshold = (it * 20).roundToInt() / 20f },
                valueRange = NsfwFilterState.MIN_THRESHOLD..NsfwFilterState.MAX_THRESHOLD,
                steps = ((NsfwFilterState.MAX_THRESHOLD - NsfwFilterState.MIN_THRESHOLD) / 0.05f).roundToInt() - 1
            )
            Text(
                "A region counts when the detector is at least this sure. Lower finds more but also more mistakes; no rescan needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One label: name and current choice, a histogram of how many photos have 0, 1, 2... of it (tap a bar for "exactly
 * that many"), a RangeSlider over 0..max, and quick choices (Any / None / 1 or more).
 */
@Composable
fun NsfwLabelCard(index: NsfwFolderIndex, labelIndex: Int, state: NsfwFilterState, modifier: Modifier = Modifier) {
    val n = NumberFormat.getInstance()
    val max = index.maxCounts[labelIndex]
    val hist = index.histograms[labelIndex]
    val range = state.ranges[labelIndex]
    val lo = range?.first?.coerceIn(0, max) ?: 0
    val hi = range?.last?.let { if (it == NsfwFilter.NO_MAX) max else it.coerceIn(lo, max) } ?: max
    val active = NsfwFilter.isActive(lo..hi, max)
    val colors = MaterialTheme.colorScheme
    val border by animateColorAsState(if (active) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.4f), label = "labelBorder")

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) colors.primary.copy(alpha = 0.08f) else colors.surfaceVariant.copy(alpha = 0.35f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.height(10.dp))
            Histogram(hist, lo, hi, onPick = { v -> state.set(labelIndex, v, v, max) }, modifier = Modifier.padding(end = 8.dp))
            RangeSlider(
                value = lo.toFloat()..hi.toFloat(),
                onValueChange = { v ->
                    val a = v.start.roundToInt().coerceIn(0, max)
                    state.set(labelIndex, a, v.endInclusive.roundToInt().coerceIn(a, max), max)
                },
                valueRange = 0f..max.toFloat(),
                steps = (max - 1).coerceAtLeast(0),
                modifier = Modifier.padding(end = 8.dp)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !active, onClick = { state.ranges.remove(labelIndex) }, label = { Text("Any") })
                FilterChip(selected = active && hi == 0, onClick = { state.set(labelIndex, 0, 0, max) }, label = { Text("None") })
                FilterChip(selected = active && lo == 1 && hi == max, onClick = { state.set(labelIndex, 1, max, max) }, label = { Text("1 or more") })
                if (max >= 2) FilterChip(selected = active && lo == 2 && hi == max, onClick = { state.set(labelIndex, 2, max, max) }, label = { Text("2 or more") })
            }
        }
    }
}

/**
 * Photos per count (0..max). Bar heights use a square root so a huge "0" bar doesn't flatten the rest; bars inside
 * the chosen range are highlighted. Tapping a bar selects exactly that count.
 */
@Composable
private fun Histogram(hist: IntArray, lo: Int, hi: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val peak = (hist.maxOrNull() ?: 0).coerceAtLeast(1)
    val labelEvery = if (hist.size <= 12) 1 else (hist.size + 11) / 12
    Row(modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        for (v in hist.indices) {
            val inRange = v in lo..hi
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).clickable { onPick(v) }
                    .semantics { contentDescription = "${hist[v]} photos with exactly $v" },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                val frac = if (hist[v] == 0) 0f else 0.08f + 0.92f * sqrt(hist[v].toFloat() / peak)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((44 * frac).dp.coerceAtLeast(if (hist[v] > 0) 3.dp else 1.dp))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (inRange) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.25f))
                )
                Text(
                    if (v % labelEvery == 0) "$v" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (inRange) colors.primary else colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
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
