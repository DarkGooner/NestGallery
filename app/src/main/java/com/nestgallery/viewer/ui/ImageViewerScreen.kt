@file:OptIn(ExperimentalFoundationApi::class)

package com.nestgallery.viewer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

@Composable
fun ImageViewerScreen(
    images: List<DocEntry>,
    startIndex: Int,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }
    var chromeVisible by remember { mutableStateOf(true) }
    val currentEntry = images[pagerState.currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val entry = images[page]
            if (entry.isVideo) {
                VideoPlayer(entry = entry, title = entry.name)
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
                    text = "${pagerState.currentPage + 1} / ${images.size}  ·  ${currentEntry.name}",
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
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
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

/**
 * Full-screen Media3 player. Media3 1.11 includes an AVI extractor, but the
 * actual audio/video codecs are still device-dependent. The player therefore
 * reports a useful error and offers an external-player fallback when the
 * device codec stack cannot decode a particular AVI/MKV/etc. file.
 */
@Composable
private fun VideoPlayer(entry: DocEntry, title: String) {
    val context = LocalContext.current
    val exoPlayer = remember(entry.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(entry.file)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var dragging by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            }

            override fun onPlayerError(playbackException: PlaybackException) {
                error = playbackException
                isPlaying = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            if (!dragging) {
                position = exoPlayer.currentPosition.coerceAtLeast(0L)
                duration = exoPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, lastInteraction) {
        if (controlsVisible && isPlaying) {
            delay(3500)
            if (SystemClock.elapsedRealtime() - lastInteraction >= 3300) {
                controlsVisible = false
            }
        }
    }

    fun interact() {
        controlsVisible = true
        lastInteraction = SystemClock.elapsedRealtime()
    }

    fun seekBy(delta: Long) {
        exoPlayer.seekTo((exoPlayer.currentPosition + delta).coerceAtLeast(0L))
        interact()
    }

    fun togglePlayback() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        interact()
    }

    fun toggleMute() {
        muted = !muted
        exoPlayer.volume = if (muted) 0f else 1f
        interact()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = true
                    setOnClickListener {
                        controlsVisible = !controlsVisible
                        lastInteraction = SystemClock.elapsedRealtime()
                    }
                }
            },
            update = { it.player = exoPlayer }
        )

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { toggleMute() }) {
                            Icon(
                                if (muted) Icons.Default.MusicOff else Icons.Default.MusicNote,
                                contentDescription = if (muted) "Unmute" else "Mute",
                                tint = Color.White
                            )
                        }
                    }
                }

                if (error == null) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { seekBy(-10_000) },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Default.FastRewind, "Back 10 seconds", tint = Color.White)
                        }
                        IconButton(
                            onClick = { togglePlayback() },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        IconButton(
                            onClick = { seekBy(10_000) },
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(Icons.Default.FastForward, "Forward 10 seconds", tint = Color.White)
                        }
                    }
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .align(Alignment.BottomCenter)
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        if (duration > 0L) {
                            Slider(
                                value = (position.toFloat() / duration).coerceIn(0f, 1f),
                                onValueChange = { value ->
                                    dragging = true
                                    position = (duration * value).toLong()
                                    interact()
                                },
                                onValueChangeFinished = {
                                    exoPlayer.seekTo(position)
                                    dragging = false
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formatTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.weight(1f))
                            Text(formatTime(duration), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                            IconButton(onClick = {
                                speed = when (speed) {
                                    0.5f -> 1f
                                    1f -> 1.5f
                                    1.5f -> 2f
                                    else -> 0.5f
                                }
                                exoPlayer.setPlaybackSpeed(speed)
                                interact()
                            }) {
                                Icon(Icons.Default.Settings, "Playback speed", tint = Color.White)
                            }
                            Text(
                                text = "${speed}×",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        error?.let { playbackError ->
            VideoErrorOverlay(
                title = "Can't play this video",
                message = playbackError.errorCodeName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                onOpenExternal = { openExternalPlayer(context, entry.file) },
                onRetry = {
                    error = null
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            )
        }
    }
}

@Composable
private fun VideoErrorOverlay(
    title: String,
    message: String,
    onOpenExternal: () -> Unit,
    onRetry: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF202124),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.padding(28.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(message, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Button(onClick = onRetry) { Text("Retry") }
                    androidx.compose.material3.OutlinedButton(onClick = onOpenExternal) { Text("Open externally") }
                }
            }
        }
    }
}

private fun openExternalPlayer(context: Context, file: java.io.File) {
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "video/*"
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val vlcIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        setPackage("org.videolan.vlc")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val genericIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        if (vlcIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(vlcIntent)
        } else {
            context.startActivity(genericIntent)
        }
    } catch (_: ActivityNotFoundException) {
        // No external player installed; keep the in-app error state visible.
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
