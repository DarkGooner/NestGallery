package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Grid tile shared by the regular browser and the recursive explorer, so
 * both look and behave identically. [subtitle] is an optional second
 * caption line under the filename (used by the explorer to show each
 * item's relative path); pass null to show just the filename, as in the
 * regular browser.
 */
@Composable
fun MediaImageTile(
    entry: DocEntry,
    showNames: Boolean,
    subtitle: String? = null,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(entry.file) {
                // Let Compose's gesture detector arbitrate tap, scroll and
                // long-press using the platform touch-slop/long-press rules.
                // The previous hand-written timeout could mistake a pointer
                // cancellation from LazyGrid scrolling for a real timeout,
                // which made the preview flash during ordinary touches.
                var holdStarted = false
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = {
                        holdStarted = true
                        onHoldStart()
                    },
                    onPress = {
                        holdStarted = false
                        tryAwaitRelease()
                        if (holdStarted) {
                            onHoldEnd()
                        }
                    }
                )
            }
    ) {
        AsyncImage(
            model = entry.file,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (entry.isVideo) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }
        if (showNames) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Column {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Instagram-style hold-to-preview.
 *
 * The surrounding area is a blurred, cropped copy of the selected media,
 * while the actual media stays sharp and keeps the full available width.
 * Only this one overlay image is blurred - the LazyGrid is never blurred.
 */
@Composable
fun HoldPreviewOverlay(entry: DocEntry) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Background: a single full-screen, cropped copy of the selected
        // media. Coil can reuse its cached request for the same File. For
        // videos, coil-video supplies a frame, so we don't need a second
        // ExoPlayer just to create the blurred surroundings.
        AsyncImage(
            model = entry.file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(32.dp)
        )

        // Darken the blurred surroundings without changing the sharp media.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )

        if (entry.isVideo) {
            HoldPreviewVideo(entry = entry)
        } else {
            // Keep this full width. The background blur fills any vertical
            // space required by the image's aspect ratio.
            AsyncImage(
                model = entry.file,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            )
        }
    }
}

@Composable
private fun HoldPreviewVideo(entry: DocEntry) {
    val context = LocalContext.current
    val exoPlayer = remember(entry.file) {
        ExoPlayer.Builder(context, NextRenderersFactory(context)).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(entry.file)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
            }
        }
    )
}
