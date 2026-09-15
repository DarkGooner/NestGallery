package com.nestgallery.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A draggable scroll thumb for quickly jumping through a long list/grid.
 * Appears while scrolling or dragging, fades out after a short idle period.
 *
 * [itemCount]/[visibleCount]/[firstVisibleIndex] describe the underlying
 * list/grid state; [onDragToIndex] is called with a target item index as
 * the thumb is dragged (the caller is expected to scroll to it, e.g. via
 * `coroutineScope.launch { state.scrollToItem(index) }`).
 */
@Composable
fun FastScrollbar(
    itemCount: Int,
    visibleCount: Int,
    firstVisibleIndex: Int,
    isScrolling: Boolean,
    onDragToIndex: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (itemCount <= visibleCount || itemCount <= 0) return

    var isDragging by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isScrolling, isDragging) {
        if (isScrolling || isDragging) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }

    val thumbFraction = (visibleCount.toFloat() / itemCount).coerceIn(0.06f, 1f)
    val scrollRange = (itemCount - visibleCount).coerceAtLeast(1)
    val progressFraction = (firstVisibleIndex.toFloat() / scrollRange).coerceIn(0f, 1f)
    val thumbHeightPx = trackHeightPx * thumbFraction
    val restingOffsetPx = (trackHeightPx - thumbHeightPx) * progressFraction

    // detectDragGestures runs inside a long-lived coroutine that only
    // restarts when pointerInput's keys change. Plain local `val`s like
    // restingOffsetPx/thumbHeightPx/scrollRange get captured at whatever
    // value they had when that coroutine last (re)started, which goes
    // stale the moment the list scrolls without those keys changing - and
    // that staleness was exactly what made grabbing the thumb feel glitchy
    // (it would jump to wherever it was several scrolls ago). Reading them
    // through rememberUpdatedState guarantees the gesture always sees the
    // live value instead.
    val latestRestingOffset by rememberUpdatedState(restingOffsetPx)
    val latestThumbHeight by rememberUpdatedState(thumbHeightPx)
    val latestScrollRange by rememberUpdatedState(scrollRange)
    val latestItemCount by rememberUpdatedState(itemCount)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxHeight()
                .width(28.dp)
                .onGloballyPositioned { trackHeightPx = it.size.height.toFloat() }
        ) {
            val thumbOffsetPx = if (isDragging) dragOffsetPx else restingOffsetPx
            val density = LocalDensity.current

            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .offset(y = with(density) { thumbOffsetPx.toDp() })
                    .width(6.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                dragOffsetPx = latestRestingOffset
                                isDragging = true
                            },
                            onDragEnd = { isDragging = false },
                            onDragCancel = { isDragging = false }
                        ) { change, dragAmount ->
                            change.consume()
                            val maxOffset = (trackHeightPx - latestThumbHeight).coerceAtLeast(0f)
                            dragOffsetPx = (dragOffsetPx + dragAmount.y).coerceIn(0f, maxOffset)
                            val fraction = if (maxOffset > 0f) dragOffsetPx / maxOffset else 0f
                            val targetIndex = (fraction * latestScrollRange).roundToInt().coerceIn(0, latestItemCount - 1)
                            onDragToIndex(targetIndex)
                        }
                    }
            )
        }
    }
}
