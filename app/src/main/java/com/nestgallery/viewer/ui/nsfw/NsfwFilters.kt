package com.nestgallery.viewer.ui.nsfw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.nestgallery.viewer.data.nsfw.NsfwFilter
import com.nestgallery.viewer.data.nsfw.NsfwFolderIndex
import com.nestgallery.viewer.data.nsfw.NsfwLabels
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The NSFW filter of one folder: a [threshold] (minimum detection score that counts) and a count range per label.
 * Held per folder for the life of the process (like GalleryCache), so it survives opening the viewer.
 */
@Stable
class NsfwFilterState {
    var threshold by mutableFloatStateOf(DEFAULT_THRESHOLD)

    /** Leave out photos where nothing at all was detected (at [threshold]). */
    var hideEmpty by mutableStateOf(false)

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

    val thresholdChanged: Boolean get() = abs(threshold - DEFAULT_THRESHOLD) > 0.001f

    fun clear() = ranges.clear()

    fun reset() { ranges.clear(); threshold = DEFAULT_THRESHOLD; hideEmpty = false }

    /** Everything that differs from the defaults: label ranges, hide-empty and the confidence. */
    fun filterCount(active: Map<Int, IntRange>): Int =
        active.size + (if (hideEmpty) 1 else 0) + (if (thresholdChanged) 1 else 0)

    /** True if [counts] (null = not scanned) passes the label ranges [active] and hide-empty. */
    fun matches(counts: IntArray?, active: Map<Int, IntRange>): Boolean =
        counts != null && NsfwFilter.matches(counts, active) && !(hideEmpty && NsfwFilter.isEmpty(counts))

    companion object {
        /** Detections below this score are ignored when counting (the stored floor is 0.25). */
        const val DEFAULT_THRESHOLD = 0.45f

        /** The confidence choices offered (the default among them). */
        val THRESHOLD_PRESETS = listOf(0.30f, DEFAULT_THRESHOLD, 0.60f, 0.75f)

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

/** Name inside its group of the sheet: "Female face", "Female breast" (under Exposed), "Feet" (under Covered). */
private fun shortName(label: String): String = when (label) {
    "FACE_FEMALE" -> "Female face"
    "FACE_MALE" -> "Male face"
    else -> NsfwLabels.display(label.removeSuffix("_EXPOSED").removeSuffix("_COVERED"))
}

/** Name on its own (bar chips): "Female face", "Female breast · exposed". */
private fun fullName(label: String): String =
    if (label.startsWith("FACE_")) shortName(label) else "${shortName(label)} · ${NsfwLabels.group(label).lowercase()}"

/** Current range of label [i] clamped to 0..[max] (no entry = 0..max). */
private fun NsfwFilterState.rangeOf(i: Int, max: Int): Pair<Int, Int> {
    val r = ranges[i] ?: return 0 to max
    val lo = r.first.coerceIn(0, max)
    val hi = if (r.last == NsfwFilter.NO_MAX) max else r.last.coerceIn(lo, max)
    return lo to hi
}

private fun IntArray.sumRange(lo: Int, hi: Int): Int {
    var s = 0
    for (k in lo.coerceAtLeast(0)..hi.coerceAtMost(lastIndex)) s += this[k]
    return s
}

/**
 * Above the photo grid: a "Filters" chip that opens the sheet, one removable chip per active filter (tap to edit it),
 * quick suggestions while nothing is filtered, then "N of M photos" and Clear all.
 */
@Composable
fun NsfwFilterBar(
    index: NsfwFolderIndex,
    active: Map<Int, IntRange>,
    state: NsfwFilterState,
    shownCount: Int,
    onOpenSheet: (focusLabel: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val n = remember { NumberFormat.getInstance() }
    val filterCount = state.filterCount(active)
    Column(modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) {
            item(key = "filters") {
                FilterChip(
                    selected = filterCount > 0,
                    onClick = { onOpenSheet(null) },
                    label = { Text(if (filterCount == 0) "Filters" else "Filters · $filterCount") },
                    leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                )
            }
            // the most-wanted switch, one tap from the grid
            item(key = "hideEmpty") {
                FilterChip(
                    selected = state.hideEmpty,
                    onClick = { state.hideEmpty = !state.hideEmpty },
                    label = { Text("Something detected") },
                    leadingIcon = if (state.hideEmpty) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    } else null
                )
            }
            if (state.thresholdChanged) item(key = "confidence") {
                InputChip(
                    selected = true,
                    onClick = { onOpenSheet(null) },
                    label = { Text("Confidence ${(state.threshold * 100).roundToInt()}%") },
                    trailingIcon = { RemoveIcon("Reset confidence") { state.threshold = NsfwFilterState.DEFAULT_THRESHOLD } }
                )
            }
            items(active.keys.sorted(), key = { it }) { i ->
                val r = active.getValue(i)
                InputChip(
                    selected = true,
                    onClick = { onOpenSheet(i) },
                    label = { Text("${fullName(NsfwLabels.ALL[i])}: ${rangeText(r.first, r.last, index.maxCounts[i]).lowercase()}") },
                    trailingIcon = { RemoveIcon("Remove filter") { state.ranges.remove(i) } }
                )
            }
            if (active.isEmpty()) {
                // the most common exposed labels, one tap = "has at least one"
                val suggestions = index.presentLabels
                    .filter { NsfwLabels.group(NsfwLabels.ALL[it]) == "Exposed" }
                    .sortedByDescending { index.photosWithLabel[it] }
                    .take(4)
                items(suggestions, key = { "s$it" }) { i ->
                    SuggestionChip(
                        onClick = { state.set(i, 1, index.maxCounts[i], index.maxCounts[i]) },
                        label = { Text(fullName(NsfwLabels.ALL[i])) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (active.isEmpty() && !state.hideEmpty) "${n.format(index.scanned)} scanned photos"
                else "${n.format(shownCount)} of ${n.format(index.scanned)} photos match",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (filterCount > 0) TextButton(onClick = { state.reset() }) { Text("Clear all") }
        }
    }
}

@Composable
private fun RemoveIcon(description: String, onClick: () -> Unit) {
    Icon(
        Icons.Default.Close,
        contentDescription = description,
        modifier = Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).padding(3.dp)
    )
}

/**
 * All filters, in a sheet: detection confidence as four presets, then one row per label found (grouped Faces /
 * Exposed / Covered). A row shows how many photos have that label among those the other filters leave; expanding it
 * offers Any / None / 1+ / 2+ ... with the photo count each would give, and a custom from-to range. The sheet ends
 * with "Show N photos". [focusLabel] opens with that row expanded and scrolled to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NsfwFilterSheet(
    index: NsfwFolderIndex,
    state: NsfwFilterState,
    shownCount: Int,
    focusLabel: Int?,
    onDismiss: () -> Unit
) {
    val n = remember { NumberFormat.getInstance() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val active = state.activeRanges(index.maxCounts)
    val hideEmpty = state.hideEmpty
    val facets = remember(index, active, hideEmpty) {
        NsfwFilter.facetHistograms(index.counts, index.maxCounts, active, skipEmpty = hideEmpty)
    }
    val expanded = remember { listOfNotNull(focusLabel).toMutableStateList() }
    fun close() { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    // flat list: confidence, then a heading before the first label of each group
    val labels = index.presentLabels
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val at = labels.indexOf(focusLabel)
        if (at >= 0) {
            val headingsBefore = labels.take(at + 1).map { NsfwLabels.group(NsfwLabels.ALL[it]) }.distinct().size
            listState.scrollToItem(1 + at + headingsBefore - 1)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        DarkSheetBars()
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.reset() }, enabled = state.filterCount(active) > 0) { Text("Reset") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.weight(1f, fill = false), state = listState, contentPadding = PaddingValues(bottom = 8.dp)) {
            item(key = "hideEmpty") { HideEmptyRow(state, index.empty) }
            item(key = "confidence") { ConfidenceSection(state) }
            labels.forEachIndexed { k, i ->
                val group = NsfwLabels.group(NsfwLabels.ALL[i])
                if (k == 0 || NsfwLabels.group(NsfwLabels.ALL[labels[k - 1]]) != group) {
                    item(key = "h$group") { SectionHeading(group) }
                }
                item(key = i) {
                    LabelRow(
                        labelIndex = i,
                        max = index.maxCounts[i],
                        facet = facets[i],
                        state = state,
                        expanded = i in expanded,
                        onToggle = { if (i in expanded) expanded.remove(i) else expanded.add(i) }
                    )
                }
            }
            if (labels.isEmpty()) item(key = "none") {
                Text(
                    "Nothing detected at this confidence.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp)
                )
            }
        }
        HorizontalDivider()
        Button(
            onClick = { close() },
            enabled = shownCount > 0,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 48.dp)
        ) {
            Text(if (shownCount == 0) "No photos match" else "Show ${n.format(shownCount)} photos")
        }
    }
}

/**
 * The sheet is its own window, which takes light status / navigation bars from the system theme; the app is always
 * dark, so on a light-themed phone the clock went dark on the sheet and the navigation bar turned white.
 */
@Composable
private fun DarkSheetBars() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp).semantics { this.contentDescription = "$text section" }
    )
}

/** Switch row: leave out photos where the detector found nothing at the current confidence. */
@Composable
private fun HideEmptyRow(state: NsfwFilterState, emptyCount: Int) {
    val n = remember { NumberFormat.getInstance() }
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = state.hideEmpty, role = Role.Switch, onValueChange = { state.hideEmpty = it })
            .heightIn(min = 72.dp)
            .padding(start = 24.dp, end = 24.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text("Hide photos with nothing detected", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${n.format(emptyCount)} ${if (emptyCount == 1) "photo has" else "photos have"} no detections at this confidence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = state.hideEmpty, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfidenceSection(state: NsfwFilterState) {
    val presets = NsfwFilterState.THRESHOLD_PRESETS
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
        Text("Detection confidence", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            presets.forEachIndexed { k, p ->
                SegmentedButton(
                    selected = abs(state.threshold - p) < 0.001f,
                    onClick = { state.threshold = p },
                    shape = SegmentedButtonDefaults.itemShape(k, presets.size),
                    icon = {}
                ) { Text("${(p * 100).roundToInt()}%") }
            }
        }
        Text(
            "How sure the detector must be for a region to count. 45% is the default; lower finds more but also more " +
                "mistakes. No rescan needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelRow(
    labelIndex: Int,
    max: Int,
    facet: IntArray,
    state: NsfwFilterState,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val n = remember { NumberFormat.getInstance() }
    val colors = MaterialTheme.colorScheme
    val label = NsfwLabels.ALL[labelIndex]
    val (lo, hi) = state.rangeOf(labelIndex, max)
    val active = NsfwFilter.isActive(lo..hi, max)
    val choice = rangeText(lo, hi, max)
    val withIt = facet.sumRange(1, max)
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Collapse" else "Change", onClick = onToggle)
                .heightIn(min = 64.dp)
                .padding(start = 24.dp, end = 16.dp)
                .semantics(mergeDescendants = true) { stateDescription = choice },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    shortName(label),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    // none left by the other filters: still listed (it can be set to None / Any), but quieter
                    color = if (withIt == 0 && !active) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "In ${n.format(withIt)} ${if (withIt == 1) "photo" else "photos"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            if (active) {
                Surface(shape = RoundedCornerShape(50), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                    Text(choice, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            } else {
                Text(choice, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).rotate(arrow))
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            // preset choices, each with the photos it would leave; "Custom" for any other from-to range
            val presets = buildList {
                add(Triple("Any", 0, max))
                add(Triple("None", 0, 0))
                for (k in 1..minOf(max, 3)) add(Triple("$k+", k, max))
            }
            val isPreset = presets.any { it.second == lo && it.third == hi }
            var custom by remember { mutableStateOf(!isPreset) }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((text, a, b) in presets) {
                        val count = facet.sumRange(a, b)
                        val selected = !custom && lo == a && hi == b
                        FilterChip(
                            selected = selected,
                            onClick = { custom = false; state.set(labelIndex, a, b, max) },
                            enabled = selected || count > 0,
                            label = { ChipLabel(text, n.format(count)) }
                        )
                    }
                    if (max >= 2) FilterChip(
                        selected = custom,
                        onClick = { custom = !custom },
                        label = { Text("Custom") }
                    )
                }
                if (custom && max >= 2) {
                    RangeStepper(lo, hi, max, facet.sumRange(lo, hi)) { a, b -> state.set(labelIndex, a, b, max) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 24.dp), color = colors.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun ChipLabel(text: String, count: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text)
        Text(
            "  $count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

/** "From [-] 1 [+]   To [-] 3 [+]" (the top value means "or more"), and how many photos that leaves. */
@Composable
private fun RangeStepper(lo: Int, hi: Int, max: Int, matching: Int, onChange: (Int, Int) -> Unit) {
    val n = remember { NumberFormat.getInstance() }
    Column(Modifier.padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stepper("From", "$lo", canDown = lo > 0, canUp = lo < hi, onDown = { onChange(lo - 1, hi) }, onUp = { onChange(lo + 1, hi) })
            Stepper(
                "To", if (hi >= max) "$max+" else "$hi",
                canDown = hi > lo, canUp = hi < max, onDown = { onChange(lo, hi - 1) }, onUp = { onChange(lo, hi + 1) }
            )
        }
        Text(
            "${rangeText(lo, hi, max)} · ${n.format(matching)} ${if (matching == 1) "photo" else "photos"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Stepper(title: String, value: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "$title fewer")
        }
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
        FilledTonalIconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Add, contentDescription = "$title more")
        }
    }
}
