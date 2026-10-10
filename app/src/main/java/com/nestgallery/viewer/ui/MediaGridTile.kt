package com.nestgallery.viewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.TextureView
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.PlayerPrefs
import com.nestgallery.viewer.data.VideoScale
import com.nestgallery.viewer.data.VlcPlayerController
import com.nestgallery.viewer.ui.video.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.videolan.libvlc.MediaPlayer

private val stillDecoder = BitmapFactoryDecoder.Factory()

/**
 * The Coil model for a browsing thumbnail (grid tiles, list rows): a still, size-sampled bitmap. GIFs and animated
 * WebP show their first frame instead of animating - Coil's GIF decoder keeps the whole file plus a full-size frame
 * per visible GIF and draws every frame on the main thread, which froze the grid on a folder of GIFs. The viewer
 * and the hold preview still animate. Videos keep Coil's video-frame decoder.
 */
@Composable
fun rememberThumbModel(entry: DocEntry): Any {
    if (entry.isVideo) return entry.file
    val context = LocalContext.current
    return remember(entry.file) {
        ImageRequest.Builder(context).data(entry.file).decoderFactory(stillDecoder).build()
    }
}

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
            .holdPreviewGestures(entry.file, onClick, onHoldStart, onHoldEnd)
    ) {
        if (entry.isVideo) {
            // Behind the thumbnail: what shows when Android can't make one (WMV, FLV, RMVB, VOB ... still play in VLC)
            Box(Modifier.fillMaxSize().background(Color(0xFF1C2127)), contentAlignment = Alignment.BottomStart) {
                Text(
                    entry.file.extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(6.dp)
                )
            }
        }
        AsyncImage(
            model = rememberThumbModel(entry),
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
        } else if (entry.file.extension.equals("gif", ignoreCase = true)) {
            // the tile is a still frame; the badge says it animates when opened
            Text(
                "GIF",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
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
 * Tap = [onClick]; long-press shows the hold preview until the finger is lifted. Compose's gesture detector
 * arbitrates tap, scroll and long-press with the platform touch-slop / long-press rules (a hand-written timeout
 * once mistook a pointer cancellation from grid scrolling for a hold, which made the preview flash). Used by
 * [MediaImageTile] and by tiles that draw their own content (face search results, person photos).
 */
fun Modifier.holdPreviewGestures(
    key: Any,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
): Modifier = this.pointerInput(key) {
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
            if (holdStarted) onHoldEnd()
        }
    )
}

/**
 * Instagram-style hold-to-preview.
 *
 * Behind: a blurred, darkened copy of the media made from a tiny (48 px) decode stretched over the screen - cheap,
 * usually served straight from the grid tile's cached bitmap, and soft even on Android < 12 where Modifier.blur does
 * nothing. In front: the media on a rounded card sized to its own aspect ratio (so a video never sits in a
 * letterboxed black box), with the file name under it. Only this overlay is drawn; the grid is never blurred.
 */
@Composable
fun HoldPreviewOverlay(entry: DocEntry) {
    val haptics = LocalHapticFeedback.current
    val appear = remember(entry.file) { Animatable(0f) }
    LaunchedEffect(entry.file) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        appear.animateTo(1f, tween(durationMillis = 160))
    }
    val context = LocalContext.current
    val backdrop = remember(entry.file) {
        ImageRequest.Builder(context).data(entry.file).size(48).build()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black)
    ) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize().blur(24.dp)
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))

        // card bounds: the screen minus a margin, leaving room for the caption
        val maxW = maxWidth - 24.dp
        val maxH = maxHeight * 0.78f
        Column(
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val s = 0.92f + 0.08f * appear.value
                    scaleX = s
                    scaleY = s
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (entry.isVideo) {
                HoldPreviewVideo(entry = entry, maxW = maxW, maxH = maxH)
            } else {
                var aspect by remember(entry.file) { mutableFloatStateOf(0f) }
                AsyncImage(
                    model = entry.file,
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    onSuccess = { state ->
                        val size = state.painter.intrinsicSize
                        if (size.width > 0f && size.height > 0f) aspect = size.width / size.height
                    },
                    modifier = Modifier
                        .previewCardSize(aspect, maxW, maxH)
                        .clip(PreviewCardShape)
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                entry.name,
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = maxW).padding(horizontal = 8.dp)
            )
        }
    }
}

private val PreviewCardShape = RoundedCornerShape(16.dp)

/** The largest box of [aspect] (width / height) inside maxW x maxH; the whole area while the aspect is unknown. */
private fun Modifier.previewCardSize(aspect: Float, maxW: Dp, maxH: Dp): Modifier {
    if (aspect <= 0f) return size(maxW, maxH)
    val w = minOf(maxW, maxH * aspect)
    return size(w, w / aspect)
}

/**
 * A muted, looping preview (VLC opens it without audio and loops it itself). The poster frame - the grid's
 * thumbnail, normally already in Coil's memory cache - shows at once and stays until VLC has drawn its first frame,
 * so there is no black flash. The card takes the poster's aspect ratio straight away and VLC's own (pixel aspect and
 * rotation applied) once it plays.
 */
@Composable
private fun HoldPreviewVideo(entry: DocEntry, maxW: Dp, maxH: Dp) {
    val context = LocalContext.current
    var aspect by remember(entry.file) { mutableFloatStateOf(0f) }
    var playing by remember(entry.file) { mutableStateOf(false) }
    var failed by remember(entry.file) { mutableStateOf(false) }
    var progress by remember(entry.file) { mutableFloatStateOf(0f) }
    var remainingMs by remember(entry.file) { mutableLongStateOf(-1L) }

    val controller = remember(entry.file) {
        VlcPlayerController(
            context = context,
            file = entry.file,
            muted = true,
            repeat = true,
            hardwareDecoding = PlayerPrefs(context).hardwareDecoding,
            onAttachError = { failed = true },
            // not Event.Vout: the output exists a moment before it has drawn anything, and the empty TextureView
            // flashed black between the poster and the video
            onFirstFrame = { vlcAspect ->
                vlcAspect?.let { aspect = it }
                playing = true
            }
        ) { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) failed = true
        }.also {
            // fill the card (it has the video's aspect): a brief size mismatch then crops a few pixels instead of
            // drawing black bars
            it.setScale(VideoScale.FILL)
        }
    }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    LaunchedEffect(controller) {
        while (isActive) {
            val d = controller.durationMs
            if (d > 0) {
                val p = controller.positionMs
                progress = (p.toFloat() / d).coerceIn(0f, 1f)
                remainingMs = (d - p).coerceAtLeast(0L)
            }
            if (playing) controller.videoAspect()?.let { aspect = it }
            delay(if (playing) 250L else 100L)
        }
    }

    Box(
        Modifier
            .previewCardSize(aspect, maxW, maxH)
            .clip(PreviewCardShape)
            .background(Color(0xFF111316))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    controller.setTextureView(this)
                    controller.attach(this)
                }
            }
        )
        if (!playing || failed) {
            AsyncImage(
                model = entry.file,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (aspect <= 0f && size.width > 0f && size.height > 0f) aspect = size.width / size.height
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        when {
            failed -> Text(
                "Can't preview this video",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            !playing -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.align(Alignment.Center).size(28.dp)
            )
            else -> {
                if (remainingMs >= 0) {
                    Text(
                        formatTime(remainingMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color.White))
                }
            }
        }
    }
}
