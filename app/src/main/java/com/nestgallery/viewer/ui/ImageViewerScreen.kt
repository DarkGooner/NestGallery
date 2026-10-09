@file:OptIn(ExperimentalFoundationApi::class)

package com.nestgallery.viewer.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.ui.video.VideoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import androidx.compose.material.icons.filled.PersonSearch
import java.io.File
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun ImageViewerScreen(
    images: List<DocEntry>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    var chromeVisible by remember { mutableStateOf(true) }
    var infoEntry by remember { mutableStateOf<DocEntry?>(null) }
    var playerLocked by remember { mutableStateOf(false) }       // a locked video player also stops page swipes
    val currentEntry = images[pagerState.currentPage]

    // Decode the photos either side of the one shown, so a swipe lands on a ready picture instead of black.
    // (Not via beyondViewportPageCount: that would also open neighbouring videos in VLC.)
    val context = LocalContext.current
    val screen = LocalConfiguration.current.let { with(LocalDensity.current) { it.screenWidthDp.dp.roundToPx() to it.screenHeightDp.dp.roundToPx() } }
    LaunchedEffect(pagerState.settledPage) {
        val loader = context.imageLoader
        for (i in listOf(pagerState.settledPage + 1, pagerState.settledPage - 1)) {
            val e = images.getOrNull(i) ?: continue
            if (e.isVideo) continue
            loader.enqueue(ImageRequest.Builder(context).data(e.file).size(screen.first, screen.second).build())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 12.dp,                                   // a black gap between photos while swiping
            key = { images[it].file.path },
            userScrollEnabled = !playerLocked
        ) { page ->
            val entry = images[page]
            if (entry.isVideo) {
                VideoPlayer(
                    entry = entry,
                    isCurrentPage = pagerState.settledPage == page,
                    chromeVisible = chromeVisible,
                    onChromeVisibleChange = { chromeVisible = it },
                    onLockedChange = { playerLocked = it },
                    onDismiss = onDismiss,
                    onInfo = { infoEntry = entry }
                )
            } else {
                ZoomableImage(
                    entry = entry,
                    isCurrentPage = pagerState.settledPage == page,
                    onTap = { chromeVisible = !chromeVisible }
                )
            }
        }

        // Image pages' back button/filename bar. Video pages draw their own
        // (see VideoPlayer) since they also need it layered with the
        // control bar/scrub bar in one place.
        AnimatedVisibility(
            visible = chromeVisible && !currentEntry.isVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
                IconButton(onClick = { infoEntry = currentEntry }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}  ·  ${currentEntry.name}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                )
            }
        }

        infoEntry?.let { MediaInfoSheet(entry = it, onDismiss = { infoEntry = null }) }
    }
}

/**
 * One photo page: pinch / double-tap zoom, pan while zoomed, fling. One-finger drags are left unconsumed at 1x and
 * when a zoomed photo is already at the edge it is dragged towards, so the pager swipes to the next / previous page
 * (consuming them, as the old detectTransformGestures did, made the viewer impossible to swipe). Zoom is reset when
 * the page is swiped away.
 */
@Composable
private fun ZoomableImage(entry: DocEntry, isCurrentPage: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var imageSize by remember { mutableStateOf(Size.Unspecified) }  // intrinsic size, once loaded
    val scope = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) { anim?.cancel(); scale = 1f; offset = Offset.Zero }
    }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val center = Offset(box.width / 2f, box.height / 2f)

        // how far the photo can move at [s]: half of what overflows the screen (letterboxing never shows)
        fun maxOffset(s: Float): Offset {
            val fitted = if (imageSize.isSpecified && imageSize.width > 0f && imageSize.height > 0f) {
                val k = min(box.width / imageSize.width, box.height / imageSize.height)
                Size(imageSize.width * k, imageSize.height * k)
            } else box
            return Offset(max(0f, (fitted.width * s - box.width) / 2f), max(0f, (fitted.height * s - box.height) / 2f))
        }
        fun clamp(o: Offset, s: Float): Offset {
            val m = maxOffset(s)
            return Offset(o.x.coerceIn(-m.x, m.x), o.y.coerceIn(-m.y, m.y))
        }
        // keep the content point under [focus] fixed while the scale goes from [scale] to [s]
        fun zoomTo(s: Float, focus: Offset, pan: Offset = Offset.Zero) {
            val z = s / scale
            offset = clamp((focus - center) * (1f - z) + offset * z + pan, s)
            scale = s
        }

        AsyncImage(
            model = entry.file,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize },
            modifier = Modifier
                .fillMaxSize()
                // lambda form: zoom / pan frames only redraw the layer, no recomposition
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                }
        )
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(entry.file, box) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        anim?.cancel()
                        // null = undecided (under touch slop), true = this handler owns the gesture, false = pager does
                        var mine: Boolean? = null
                        var drag = Offset.Zero
                        val velocity = VelocityTracker()
                        do {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.count { it.pressed }
                            if (mine == null) {
                                drag += event.calculatePan()
                                if (pointers >= 2) {
                                    mine = true
                                } else if (drag.getDistance() > slop) {
                                    val m = maxOffset(scale)
                                    val horizontal = abs(drag.x) > abs(drag.y)
                                    // dragging right shows what is left of the photo: blocked at the left edge (x at +max)
                                    val atEdge = (drag.x > 0 && offset.x >= m.x - 0.5f) || (drag.x < 0 && offset.x <= -m.x + 0.5f)
                                    mine = scale > 1.01f && !(horizontal && atEdge)
                                }
                            }
                            if (mine == true && event.changes.none { it.isConsumed }) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (pointers >= 2) {
                                    zoomTo((scale * zoom).coerceIn(0.8f, MAX_ZOOM), event.calculateCentroid(useCurrent = true), pan)
                                } else {
                                    offset = clamp(offset + pan, scale)
                                }
                                event.changes.firstOrNull { it.pressed }?.let { velocity.addPosition(it.uptimeMillis, it.position) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })

                        if (mine == true) {
                            if (scale < 1f) {
                                // pinched below 1x: spring back
                                val from = scale; val fromOffset = offset
                                anim = scope.launch {
                                    animate(0f, 1f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                                        scale = from + (1f - from) * t
                                        offset = fromOffset * (1f - t)
                                    }
                                }
                            } else if (scale > 1.01f) {
                                val v = velocity.calculateVelocity()
                                val start = offset
                                anim = scope.launch {
                                    val decay = FloatExponentialDecaySpec(frictionMultiplier = 1.6f)
                                    launch { animateDecay(start.x, v.x, decay) { x, _ -> offset = clamp(Offset(x, offset.y), scale) } }
                                    launch { animateDecay(start.y, v.y, decay) { y, _ -> offset = clamp(Offset(offset.x, y), scale) } }
                                }
                            } else {
                                scale = 1f; offset = Offset.Zero
                            }
                        }
                    }
                }
                .pointerInput(entry.file, box) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            anim?.cancel()
                            val from = scale
                            val to = if (from > 1.01f) 1f else DOUBLE_TAP_ZOOM
                            // the zoomed-in end keeps the tapped point under the finger; zooming out recentres
                            val fromOffset = offset
                            val toOffset = if (to > 1f) clamp((tap - center) * (1f - to), to) else Offset.Zero
                            anim = scope.launch {
                                animate(0f, 1f, animationSpec = tween(250, easing = FastOutSlowInEasing)) { t, _ ->
                                    scale = from + (to - from) * t
                                    offset = fromOffset + (toOffset - fromOffset) * t
                                }
                            }
                        }
                    )
                }
        )
    }
}

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
