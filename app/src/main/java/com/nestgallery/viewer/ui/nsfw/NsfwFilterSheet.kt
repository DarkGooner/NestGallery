package com.nestgallery.viewer.ui.nsfw

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.nsfw.NsfwFilter
import com.nestgallery.viewer.data.nsfw.NsfwFolderIndex
import com.nestgallery.viewer.data.nsfw.NsfwLabels
import com.nestgallery.viewer.data.nsfw.NsfwScanService
import com.nestgallery.viewer.data.nsfw.NsfwScanStatus
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The NSFW filter of one recursive view: a [threshold] (minimum detection score that counts) and a count range per
 * label. Held per folder for the life of the process (like GalleryCache), so it survives opening the viewer.
 */
@Stable
class NsfwFilterState {
    var threshold by mutableFloatStateOf(DEFAULT_THRESHOLD)

    /** label index -> chosen range; last = [NsfwFilter.NO_MAX] means "up to the highest count". Absent = full range. */
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

/** Bottom sheet: minimum confidence, then one RangeSlider per label found in this view, grouped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NsfwFilterSheet(
    index: NsfwFolderIndex,
    state: NsfwFilterState,
    shownCount: Int,
    photoCount: Int,
    onForgetResults: () -> Unit,
    onDismiss: () -> Unit
) {
    val n = NumberFormat.getInstance()
    val active = state.activeRanges(index.maxCounts)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) }
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("NSFW filters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (active.isEmpty()) "No filter · ${n.format(index.scanned)} of ${n.format(photoCount)} items scanned"
                            else "Showing ${n.format(shownCount)} of ${n.format(photoCount)} items",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(onClick = { state.clear() }, enabled = active.isNotEmpty()) { Text("Clear filters") }
                }
                Spacer(Modifier.height(16.dp))
                Text("Minimum confidence: ${(state.threshold * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.threshold,
                    onValueChange = { state.threshold = (it * 20).roundToInt() / 20f },
                    valueRange = NsfwFilterState.MIN_THRESHOLD..NsfwFilterState.MAX_THRESHOLD,
                    steps = ((NsfwFilterState.MAX_THRESHOLD - NsfwFilterState.MIN_THRESHOLD) / 0.05f).roundToInt() - 1
                )
                Text(
                    "A region counts if the detector is at least this sure. Lower finds more, with more mistakes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (index.presentLabels.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        if (index.scanned == 0) "Nothing scanned in this folder yet. Tap NSFW scan first."
                        else "Nothing detected at this confidence.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            // (label index, heading to show above it or null); ALL is ordered by group, so headings change at most once each
            val rows = index.presentLabels.let { present ->
                present.mapIndexed { k, i ->
                    val group = NsfwLabels.group(NsfwLabels.ALL[i])
                    i to group.takeIf { k == 0 || NsfwLabels.group(NsfwLabels.ALL[present[k - 1]]) != it }
                }
            }
            items(rows, key = { it.first }) { (i, heading) ->
                val label = NsfwLabels.ALL[i]
                Column {
                    if (heading != null) {
                        Spacer(Modifier.height(18.dp))
                        Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    LabelRange(
                        label = label,
                        max = index.maxCounts[i],
                        photos = index.photosWithLabel[i],
                        range = state.ranges[i],
                        onChange = { r -> if (r == null) state.ranges.remove(i) else state.ranges[i] = r }
                    )
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                TextButton(onClick = onForgetResults) { Text("Forget NSFW results for this folder") }
            }
        }
    }
}

/** One label: name, chosen "lo – hi", photo count, and an integer RangeSlider over 0..[max]. */
@Composable
private fun LabelRange(label: String, max: Int, photos: Int, range: IntRange?, onChange: (IntRange?) -> Unit) {
    val lo = range?.first?.coerceIn(0, max) ?: 0
    val hi = range?.last?.let { if (it == NsfwFilter.NO_MAX) max else it.coerceIn(lo, max) } ?: max
    val narrowed = NsfwFilter.isActive(lo..hi, max)
    Column(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                NsfwLabels.display(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (narrowed) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                (if (lo == hi) "exactly $lo" else "$lo – $hi") + " · $photos photo" + if (photos == 1) "" else "s",
                style = MaterialTheme.typography.labelMedium,
                color = if (narrowed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RangeSlider(
            value = lo.toFloat()..hi.toFloat(),
            onValueChange = { v ->
                val a = v.start.roundToInt().coerceIn(0, max)
                val b = v.endInclusive.roundToInt().coerceIn(a, max)
                // full range = no filter; the top end means "and more", so it keeps up with a scan still finding more
                onChange(if (a == 0 && b == max) null else a..(if (b == max) NsfwFilter.NO_MAX else b))
            },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0)
        )
    }
}

/** Floating progress card for a running scan of this folder: count, rate, time left, current file, pause / stop. */
@Composable
fun NsfwScanBanner(status: NsfwScanStatus, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val n = NumberFormat.getInstance()
    val (title, detail, progress) = when (status) {
        is NsfwScanStatus.Scanning -> Triple(
            if (status.totalCount > 0) "NSFW scan · ${n.format(status.scannedCount)} / ${n.format(status.totalCount)}" else "NSFW scan",
            listOfNotNull(
                status.photosPerSecond.takeIf { it > 0.05f }?.let { "%.1f photos/s".format(it) },
                status.etaSeconds.takeIf { it >= 0 }?.let { "~${NsfwScanService.formatEta(it)} left" },
                status.currentFileName
            ).joinToString(" · "),
            if (status.totalCount > 0) status.scannedCount.toFloat() / status.totalCount else null
        )
        is NsfwScanStatus.Paused -> Triple(
            "NSFW scan paused · ${n.format(status.scannedCount)} / ${n.format(status.totalCount)}", "",
            if (status.totalCount > 0) status.scannedCount.toFloat() / status.totalCount else 0f
        )
        is NsfwScanStatus.Completed -> Triple("NSFW scan complete", "${n.format(status.totalScanned)} new photos scanned", 1f)
        is NsfwScanStatus.Failed -> Triple("NSFW scan failed", status.message, 0f)
        NsfwScanStatus.Idle -> return
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) {
                    Text(detail, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (status is NsfwScanStatus.Scanning || status is NsfwScanStatus.Paused) {
                val isPaused = status is NsfwScanStatus.Paused
                IconButton(onClick = if (isPaused) onResume else onPause, modifier = Modifier.size(40.dp)) {
                    Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (isPaused) "Resume" else "Pause", tint = Color.White)
                }
                IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Stop NSFW scan", tint = Color.White)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
        else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
    }
}
