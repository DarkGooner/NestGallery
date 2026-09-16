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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.VlcPlayerController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ImageViewerScreen(
    images: List<DocEntry>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    var chromeVisible by remember { mutableStateOf(true) }
    val currentEntry = images[pagerState.currentPage]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entry = images[page]
            if (entry.isVideo) {
                VideoPlayer(
                    entry = entry,
                    chromeVisible = chromeVisible,
                    onChromeVisibleChange = { chromeVisible = it }
                )
            } else {
                ZoomableImage(entry = entry, onTap = { chromeVisible = !chromeVisible })
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier.fillMaxSize().statusBarsPadding().padding(8.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
                if (!currentEntry.isVideo) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${images.size}  ·  ${currentEntry.name}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(entry: DocEntry, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AsyncImage(
            model = entry.file,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                .pointerInput(entry.file) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = max(1f, min(scale * zoom, 5f))
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(entry.file) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        }
                    )
                }
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
private fun VideoPlayer(
    entry: DocEntry,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }

    val controller = remember(entry.file) {
        VlcPlayerController(
            context = context,
            file = entry.file,
            muted = false,
            repeat = false,
            onAttachError = {
                isBuffering = false
                isPlaying = false
                errorMessage = "VLC could not open this file"
            }
        ) { event ->
            when (event.type) {
                org.videolan.libvlc.MediaPlayer.Event.Opening,
                org.videolan.libvlc.MediaPlayer.Event.Buffering -> isBuffering = true
                org.videolan.libvlc.MediaPlayer.Event.Playing -> {
                    isBuffering = false
                    isPlaying = true
                }
                org.videolan.libvlc.MediaPlayer.Event.Paused -> {
                    isBuffering = false
                    isPlaying = false
                }
                org.videolan.libvlc.MediaPlayer.Event.EndReached -> {
                    isBuffering = false
                    isPlaying = false
                }
                org.videolan.libvlc.MediaPlayer.Event.EncounteredError -> {
                    isBuffering = false
                    isPlaying = false
                    errorMessage = "VLC could not decode this file"
                }
            }
        }
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    fun seekBy(deltaMs: Long) {
        val target = (controller.positionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
        controller.seekTo(target)
        positionMs = target
    }

    // The AndroidView factory below runs only once (when the TextureView is
    // first created), so a plain captured `chromeVisible` parameter would be
    // frozen at whatever it was on that first composition. rememberUpdatedState
    // gives a stable holder whose .value always reflects the latest value.
    val currentChromeVisible = rememberUpdatedState(chromeVisible)

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { ctx ->
            TextureView(ctx).apply {
                textureView = this
                controller.setTextureView(this)
                controller.attach(this)

                // A Compose gesture Box overlapping this TextureView never
                // receives touches: interop views intercept touch input in
                // their own bounds before Compose's overlapping gesture
                // detectors get a chance. So tap/double-tap are handled here
                // on the real View instead.
                val gestureDetector = android.view.GestureDetector(
                    ctx,
                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                            onChromeVisibleChange(!currentChromeVisible.value)
                            return true
                        }
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                            val leftHalf = e.x < width / 2f
                            seekBy(if (leftHalf) -10_000 else 10_000)
                            seekFeedback = if (leftHalf) "⟲ 10s" else "10s ⟳"
                            return true
                        }
                    }
                )
                setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
            }
        },
        update = { view ->
            textureView = view
            controller.setTextureView(view)
        }
    )

    LaunchedEffect(controller) {
        while (isActive) {
            positionMs = controller.positionMs
            val newDuration = controller.durationMs
            if (newDuration > 0) durationMs = newDuration
            isPlaying = controller.isPlaying
            isBuffering = durationMs <= 0L && errorMessage == null
            delay(150)
        }
    }

    // Debounced VLC seeking is also used for the preview frame. This avoids
    // hammering the decoder on every pointer event while still making the
    // preview follow the finger closely.
    LaunchedEffect(isScrubbing, scrubTargetMs) {
        if (!isScrubbing || durationMs <= 0L) return@LaunchedEffect
        delay(90)
        val target = scrubTargetMs.coerceIn(0L, durationMs)
        controller.seekTo(target)
        delay(55)
        previewBitmap = controller.captureFrame()
    }

    LaunchedEffect(chromeVisible, isPlaying, isScrubbing) {
        if (chromeVisible && isPlaying && !isScrubbing) {
            delay(3500)
            onChromeVisibleChange(false)
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(500)
            seekFeedback = null
        }
    }

    Box(Modifier.fillMaxSize()) {

        if (isBuffering && errorMessage == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        errorMessage?.let { message ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = 0.78f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "Can't play this video\n$message",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }

        seekFeedback?.let { text ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(color = Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(24.dp)) {
                    Text(text, color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            VideoControlBar(
                isPlaying = isPlaying,
                positionMs = if (isScrubbing) scrubTargetMs else positionMs,
                durationMs = durationMs,
                isScrubbing = isScrubbing,
                previewBitmap = previewBitmap,
                onPlayPause = {
                    if (controller.isPlaying) controller.pause() else controller.play()
                },
                onScrubStart = {
                    wasPlayingBeforeScrub = controller.isPlaying
                    controller.pause()
                    isScrubbing = true
                    scrubTargetMs = controller.positionMs
                    positionMs = scrubTargetMs
                    previewBitmap = controller.captureFrame()
                    onChromeVisibleChange(true)
                },
                onScrub = {
                    scrubTargetMs = it
                    positionMs = it
                },
                onScrubEnd = {
                    controller.seekTo(it)
                    positionMs = it
                    isScrubbing = false
                    previewBitmap = null
                    if (wasPlayingBeforeScrub) controller.play()
                },
                onScrubCancel = {
                    controller.seekTo(positionMs)
                    isScrubbing = false
                    previewBitmap = null
                    if (wasPlayingBeforeScrub) controller.play()
                }
            )
        }
    }
}

@Composable
private fun VideoControlBar(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    previewBitmap: Bitmap?,
    onPlayPause: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onScrubCancel: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(top = 42.dp, start = 12.dp, end = 12.dp, bottom = 12.dp)
    ) {
        ScrubBar(
            positionMs = positionMs,
            durationMs = durationMs,
            isScrubbing = isScrubbing,
            previewBitmap = previewBitmap,
            onScrubStart = onScrubStart,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
            onScrubCancel = onScrubCancel
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * YouTube-style timeline interaction: the preview does not appear for an
 * ordinary tap. It appears only after the user holds the timeline long
 * enough for Compose's long-press detector, then drags left/right.
 */
@Composable
private fun ScrubBar(
    positionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    previewBitmap: Bitmap?,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onScrubCancel: () -> Unit
) {
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val previewWidthPx = with(density) { 180.dp.roundToPx() }
    val previewTopOffsetPx = with(density) { 132.dp.roundToPx() }
    val thumbRadiusPx = with(density) { 8.dp.roundToPx() }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxWidth()) {
        if (isScrubbing) {
            val rawX = fraction * trackWidthPx - previewWidthPx / 2f
            val clampedX = rawX.coerceIn(0f, max(0f, trackWidthPx - previewWidthPx))

            Column(
                modifier = Modifier
                    .offset { IntOffset(clampedX.roundToInt(), -previewTopOffsetPx) }
                    .width(180.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.width(180.dp).height(102.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF202124))
                ) {
                    previewBitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Video preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Surface(color = Color.Black.copy(alpha = 0.9f), shape = RoundedCornerShape(5.dp)) {
                    Text(
                        formatTime(positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .onGloballyPositioned { trackWidthPx = it.size.width.toFloat() }
                .pointerInput(durationMs) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            if (durationMs > 0 && trackWidthPx > 0) {
                                val fractionAtStart = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                                onScrubStart()
                                onScrub((fractionAtStart * durationMs).toLong())
                            }
                        },
                        onDragEnd = {
                            if (durationMs > 0) onScrubEnd(positionMs.coerceIn(0L, durationMs))
                        },
                        onDragCancel = onScrubCancel,
                        onDrag = { change, _ ->
                            change.consume()
                            if (durationMs > 0 && trackWidthPx > 0) {
                                val fractionAtFinger = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                                onScrub((fractionAtFinger * durationMs).toLong())
                            }
                        }
                    )
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            )
            Box(
                Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Box(
                Modifier.offset { IntOffset((fraction * trackWidthPx).roundToInt() - thumbRadiusPx, 0) }
                    .size(if (isScrubbing) 20.dp else 16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
