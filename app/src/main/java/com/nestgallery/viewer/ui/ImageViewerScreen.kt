@file:OptIn(ExperimentalFoundationApi::class)

package com.nestgallery.viewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.isVideoFile
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ImageViewerScreen(
    media: List<File>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { media.size }
    var chromeVisible by remember { mutableStateOf(true) }
    val currentEntry = media[pagerState.currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entry = media[page]
            if (entry.isVideoFile()) {
                VideoPlayer(file = entry)
            } else {
                ZoomableImage(
                    entry = entry,
                    onTap = { chromeVisible = !chromeVisible }
                )
            }
        }

        if (chromeVisible) {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${media.size}  ·  ${currentEntry.name}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(entry: File, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        AsyncImage(
            model = entry,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .pointerInput(entry) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = max(1f, min(scale * zoom, 5f))
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(entry) {
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

/**
 * Plays a video file full-screen with the standard Media3 controls.
 *
 * Double-tap left half = -10s, right half = +10s. Single taps are NOT
 * consumed by the overlay (see [detectDoubleTapSeek]), so the ExoPlayer
 * controller keeps working normally: play/pause, seek bar, tap-to-hide.
 */
@Composable
private fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    val scope = rememberCoroutineScope()
    // null = hidden, true = "+10s" (forward), false = "-10s" (backward)
    var seekFeedback by remember { mutableStateOf<Boolean?>(null) }
    var feedbackJob by remember { mutableStateOf<Job?>(null) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            }
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(file) {
                    detectDoubleTapSeek { position ->
                        val forward = position.x > size.width / 2f
                        val delta = if (forward) 10_000L else -10_000L
                        val duration = exoPlayer.duration
                        val target = exoPlayer.currentPosition + delta
                        exoPlayer.seekTo(
                            if (duration > 0) target.coerceIn(0L, duration)
                            else target.coerceAtLeast(0L)
                        )
                        seekFeedback = forward
                        feedbackJob?.cancel()
                        feedbackJob = scope.launch {
                            delay(700)
                            seekFeedback = null
                        }
                    }
                }
        )

        seekFeedback?.let { forward ->
            Text(
                text = if (forward) "+10s" else "-10s",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 48.dp)
                    .background(Color(0x66000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Double-tap-only gesture detector that never consumes single taps or
 * drags, so both the ExoPlayer controller underneath and the surrounding
 * HorizontalPager keep receiving them. Only a confirmed double tap's
 * events are consumed.
 */
private suspend fun PointerInputScope.detectDoubleTapSeek(
    onDoubleTap: (Offset) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown()
        val firstUp = waitForUpOrCancellation() ?: return@awaitEachGesture

        // Second tap must arrive within the platform double-tap timeout...
        val secondDown = withTimeoutOrNull(
            viewConfiguration.doubleTapTimeoutMillis.toLong()
        ) {
            awaitFirstDown()
        } ?: return@awaitEachGesture

        // ...and land close to the first one.
        val maxDistance = viewConfiguration.touchSlop * 2f
        if ((secondDown.position - firstUp.position).getDistance() > maxDistance) {
            return@awaitEachGesture
        }

        // Confirmed double tap: this event pair is ours.
        secondDown.consume()
        waitForUpOrCancellation()?.consume()
        onDoubleTap(secondDown.position)
    }
}
