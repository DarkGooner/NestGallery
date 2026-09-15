package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

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
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    try {
                        // Races the release against the hold threshold.
                        // withTimeout (not withTimeoutOrNull) is essential
                        // here: waitForUpOrCancellation() itself returns
                        // null on cancellation (e.g. the instant a parent
                        // scroll takes over the gesture), which would be
                        // indistinguishable from an actual timeout if we
                        // used withTimeoutOrNull - that conflation was
                        // exactly what caused a hold-preview flash on every
                        // single scroll touch.
                        val up = withTimeout(300) { waitForUpOrCancellation() }
                        if (up != null) {
                            onClick()
                        }
                        // up == null here means the gesture was cancelled
                        // (most commonly: the grid started scrolling) -
                        // do nothing, no click and no hold.
                    } catch (e: TimeoutCancellationException) {
                        // Still down past the threshold with no cancellation -
                        // a genuine hold.
                        onHoldStart()
                        waitForUpOrCancellation()
                        onHoldEnd()
                    }
                }
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
 * Instagram-reel-style hold-to-preview: a dimmed, blurred-behind popup
 * showing the full image, or an auto-playing muted/looping video, while
 * the finger stays down on the tile. Purely a visual overlay - it doesn't
 * consume touches, so lifting the finger (handled by the tile's own
 * gesture) is what dismisses it.
 */
@Composable
fun HoldPreviewOverlay(entry: DocEntry) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        if (entry.isVideo) {
            HoldPreviewVideo(entry = entry)
        } else {
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
        ExoPlayer.Builder(context).build().apply {
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
            .fillMaxHeight(0.9f),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
            }
        }
    )
}
