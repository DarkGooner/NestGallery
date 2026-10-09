package com.nestgallery.viewer.ui.gallery

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.MediaItem
import com.nestgallery.viewer.ui.FastScrollbar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Photos per row the pinch gesture steps through. */
val GALLERY_COLUMN_STEPS = listOf(2, 3, 4, 5, 7)

/** One day of a timeline: its header label and where its items sit in the flat item list. */
@Immutable
class TimelineDay(val epochDay: Long, val label: String, val month: String, val from: Int, val to: Int)

/**
 * Groups [items] (newest first) by day. Labels follow the usual gallery wording: Today, Yesterday, the weekday within
 * the last week, then "Sat, 3 Oct", and the year once it isn't this year's.
 */
fun timelineDays(items: List<MediaItem>, today: LocalDate = LocalDate.now()): List<TimelineDay> {
    val locale = Locale.getDefault()
    val thisYear = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
    val otherYear = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    val todayDay = today.toEpochDay()
    val days = ArrayList<TimelineDay>()
    var start = 0
    while (start < items.size) {
        val day = items[start].epochDay
        var end = start
        while (end + 1 < items.size && items[end + 1].epochDay == day) end++
        val date = LocalDate.ofEpochDay(day)
        val label = when {
            day == todayDay -> "Today"
            day == todayDay - 1 -> "Yesterday"
            day in (todayDay - 6) until todayDay -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            date.year == today.year -> date.format(thisYear)
            else -> date.format(otherYear)
        }
        days += TimelineDay(day, label, date.format(monthFmt), start, end)
        start = end + 1
    }
    return days
}

/**
 * The photo grid of a typical gallery: day headers, square thumbnails edge to edge, pinch to change the number of
 * columns, long-press to start selecting (then taps add / remove, a day's check selects the whole day), a draggable
 * scroller that shows the month. Selection itself is owned by the caller ([selected]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaTimeline(
    items: List<MediaItem>,
    state: LazyGridState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    /** Taps select instead of opening (the selection may still be empty, after the menu's "Select"). */
    selecting: Boolean,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
    onOpen: (Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null
) {
    val days = remember(items) { timelineDays(items) }
    val haptics = LocalHapticFeedback.current
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val thumbPx = with(LocalDensity.current) { (screenWidthDp.dp / columns).roundToPx() }

    // flat grid index -> month label, for the scroller's bubble (header + day header + items)
    val extra = if (header != null) 1 else 0
    val cellMonth = remember(days, extra) {
        val labels = ArrayList<String>(items.size + days.size + extra)
        if (extra == 1) labels += days.firstOrNull()?.month ?: ""
        for (d in days) repeat(d.to - d.from + 2) { labels += d.month }
        labels
    }

    val latestColumns by rememberUpdatedState(columns)
    val latestOnColumns by rememberUpdatedState(onColumnsChange)

    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Fixed(columns),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Two fingers: pinch out = bigger photos (fewer columns), pinch in = more. One step per gesture.
                    // Read on the Initial pass and consumed, so the grid doesn't also scroll; one finger is untouched.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoom = 1f
                        var done = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoom *= event.calculateZoom()
                                if (!done) {
                                    val i = GALLERY_COLUMN_STEPS.indexOf(latestColumns).let { if (it < 0) 2 else it }
                                    val next = when {
                                        zoom > 1.25f -> GALLERY_COLUMN_STEPS.getOrNull(i - 1)
                                        zoom < 0.8f -> GALLERY_COLUMN_STEPS.getOrNull(i + 1)
                                        else -> null
                                    }
                                    if (next != null || zoom > 1.25f || zoom < 0.8f) done = true
                                    if (next != null) latestOnColumns(next)
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "top") { header() }
            }
            for (day in days) {
                val dayIds = items.subList(day.from, day.to + 1)
                item(key = "d${day.epochDay}", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                    val allSelected = selected.isNotEmpty() && dayIds.all { it.id in selected }
                    DayHeader(day.label, selecting, allSelected) {
                        val ids = dayIds.map { it.id }
                        onSelectedChange(if (allSelected) selected - ids.toSet() else selected + ids)
                    }
                }
                itemsIndexed(dayIds, key = { _, it -> it.id }, contentType = { _, _ -> "media" }) { i, item ->
                    val isSelected = item.id in selected
                    MediaCell(
                        item = item,
                        thumbPx = thumbPx,
                        selecting = selecting,
                        selected = isSelected,
                        onClick = {
                            if (selecting) onSelectedChange(if (isSelected) selected - item.id else selected + item.id)
                            else onOpen(day.from + i)
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectedChange(selected + item.id)
                        }
                    )
                }
            }
        }
        TimelineScrollbar(state, cellMonth, Modifier.align(Alignment.CenterEnd).padding(top = contentPadding.calculateTopPadding()))
    }
}

/**
 * The scroller in its own composable on purpose: it reads the scroll position, which changes every frame. Read in
 * MediaTimeline itself, that made the whole timeline (thousands of day sections) recompose on every scroll frame.
 */
@Composable
private fun TimelineScrollbar(state: LazyGridState, cellMonth: List<String>, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val visibleCount by remember(state) { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val firstVisible by remember(state) { derivedStateOf { state.firstVisibleItemIndex } }
    FastScrollbar(
        itemCount = cellMonth.size,
        visibleCount = visibleCount,
        firstVisibleIndex = firstVisible,
        isScrolling = state.isScrollInProgress,
        onDragToIndex = { index -> scope.launch { state.scrollToItem(index) } },
        label = { cellMonth.getOrElse(it) { "" } },
        modifier = modifier
    )
}

@Composable
private fun DayHeader(label: String, selecting: Boolean, allSelected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        if (selecting) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
                    .semantics { contentDescription = if (allSelected) "Deselect $label" else "Select all of $label" },
                contentAlignment = Alignment.Center
            ) { SelectionMark(allSelected) }
        }
    }
}

/**
 * One photo. Kept light on purpose, since rows are composed on the main thread as they scroll in (that cost was the
 * stutter): a plain tap / long-press detector instead of combinedClickable (no ripple or interaction state), one
 * node for the picture, and the selection shrink done in a graphics layer (draw only; no relayout, no recomposition
 * per animation frame).
 */
@Composable
private fun MediaCell(item: MediaItem, thumbPx: Int, selecting: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    // the usual "selected" look: the photo shrinks into a rounded inset, with a filled check
    val shrink by animateFloatAsState(if (selected) 1f else 0f, label = "sel")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    Box(
        Modifier
            .aspectRatio(1f)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .pointerInput(Unit) { detectTapGestures(onTap = { click() }, onLongPress = { longClick() }) }
            .semantics(mergeDescendants = true) {
                contentDescription = (if (item.entry.isVideo) "Video " else "Photo ") + item.entry.name
                this.selected = selected
                onClick { click(); true }
                onLongClick { longClick(); true }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = shrink
                    if (f > 0f) {
                        val scale = 1f - 0.14f * f
                        scaleX = scale; scaleY = scale
                        shape = RoundedCornerShape((14 * f).dp)
                        clip = true
                    }
                }
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Thumbnail(item, thumbPx, Modifier.fillMaxSize())
            if (item.entry.isVideo) VideoBadge(item.durationMs)
        }
        if (selecting) {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { SelectionMark(selected) }
        }
    }
}

@Composable
private fun BoxScope.VideoBadge(durationMs: Long) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(28.dp)
            .background(VIDEO_SCRIM)
    )
    Row(
        Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (durationMs > 0) {
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

private val VIDEO_SCRIM = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))

/** The round check used on photos and day headers while selecting. */
@Composable
private fun SelectionMark(checked: Boolean) {
    if (checked) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
    } else {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.25f))
                .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
        )
    }
}

/** 0:07, 12:34, 1:02:03. */
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
