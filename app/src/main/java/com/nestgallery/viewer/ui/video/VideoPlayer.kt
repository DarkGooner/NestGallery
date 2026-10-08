package com.nestgallery.viewer.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.provider.Settings
import android.view.TextureView
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.PlayerPrefs
import com.nestgallery.viewer.data.VideoScale
import com.nestgallery.viewer.data.VlcPlayerController
import com.nestgallery.viewer.data.VlcTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f)

/** Transient centre overlay: brightness / volume level, seek amount, speed, decoder notice. */
private class Hud(val icon: ImageVector, val text: String, val level: Float? = null, val at: Long = System.nanoTime())

private enum class Sheet { NONE, SETTINGS, AUDIO, SUBTITLES }

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Full-screen video page of the viewer, played by LibVLC ([VlcPlayerController]), so it opens what VLC opens.
 *
 * Layers, bottom to top (plain Compose siblings in one Box, so overlays get touches normally): the TextureView; a
 * gesture layer (tap = controls, double-tap left / right = -/+10 s and more per repeat, hold = 2x speed, vertical
 * swipe left = brightness / right = volume - horizontal swipes are left to the pager); status (buffering, errors,
 * HUD, resume chip); the controls (top bar, -10 / play / +10, scrub bar, quick actions). Lock hides all of it and
 * ignores gestures until unlocked.
 *
 * @param isCurrentPage only the settled page plays; a neighbour the pager composes while swiping stays paused
 * @param onLockedChange the viewer disables pager swiping while locked
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayer(
    entry: DocEntry,
    isCurrentPage: Boolean,
    chromeVisible: Boolean,
    onChromeVisibleChange: (Boolean) -> Unit,
    onLockedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onInfo: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember { PlayerPrefs(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var hardware by remember { mutableStateOf(prefs.hardwareDecoding) }
    var loop by remember { mutableStateOf(prefs.loop) }
    var scale by remember { mutableStateOf(prefs.scale) }
    var rate by remember { mutableFloatStateOf(1f) }
    var locked by remember { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var videoInfo by remember { mutableStateOf<String?>(null) }
    var audioTracks by remember { mutableStateOf(emptyList<VlcTrack>()) }
    var subtitleTracks by remember { mutableStateOf(emptyList<VlcTrack>()) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubTargetMs by remember { mutableLongStateOf(0L) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var wasPlayingBeforeScrub by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var userPaused by remember { mutableStateOf(false) }
    var showRemaining by remember { mutableStateOf(true) }
    var loopRestarts by remember { mutableIntStateOf(0) }

    // Where to start: the remembered position (VLC-style resume), or where we were when the decoder was switched.
    val resumeAt = remember(entry.file) { prefs.resumePosition(entry.file) }
    var resumeChip by remember(entry.file) { mutableStateOf(resumeAt > 0) }
    var reopenAt by remember(entry.file) { mutableLongStateOf(resumeAt) }
    val currentPage by rememberUpdatedState(isCurrentPage)
    val loopNow by rememberUpdatedState(loop)

    val controller = remember(entry.file, hardware) {
        VlcPlayerController(
            context = context,
            file = entry.file,
            hardwareDecoding = hardware,
            onAttachError = {
                isBuffering = false; isPlaying = false
                errorMessage = "VLC could not open this file."
            },
            onDecoderFallback = {
                hud = Hud(Icons.Default.Speed, "Hardware decoding failed here · switched to software")
            }
        ) { event ->
            when (event.type) {
                MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering -> if (durationMs <= 0) isBuffering = true
                MediaPlayer.Event.Playing -> { isBuffering = false; isPlaying = true; ended = false }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> { isBuffering = false; isPlaying = false }
                MediaPlayer.Event.EndReached -> {
                    isBuffering = false; isPlaying = false
                    if (loopNow) loopRestarts++ else ended = true
                }
                MediaPlayer.Event.EncounteredError -> {
                    isBuffering = false; isPlaying = false
                    errorMessage = "VLC could not decode this file."
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.Vout -> Unit
            }
        }
    }

    // Track lists and the video description appear once VLC has parsed the streams.
    fun refreshTracks() {
        audioTracks = controller.audioTracks()
        subtitleTracks = controller.subtitleTracks()
        videoInfo = controller.videoInfo() ?: videoInfo
    }

    fun saveResume() {
        val d = controller.durationMs
        if (d > 0) prefs.saveResume(entry.file, controller.positionMs, d)
    }

    DisposableEffect(controller) {
        // a new controller (first open, or the decoder was switched) starts from a clean slate
        errorMessage = null; isBuffering = true; ended = false; durationMs = 0L
        controller.rate = rate
        controller.setScale(scale)
        onDispose {
            saveResume()
            controller.release()
        }
    }

    // Leaving the app (home, screen off, another app on top) pauses, like VLC without background audio.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { controller.pause(); saveResume() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Brightness is a window override for this viewing only; rotation is put back too.
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(isCurrentPage, controller) {
        if (!isCurrentPage) controller.pause() else if (!userPaused && !controller.isPlaying && errorMessage == null && !ended) controller.play()
    }
    LaunchedEffect(locked) { onLockedChange(locked) }
    // loop: the end event can't reach the controller it comes from, so it bumps a counter this effect reacts to
    LaunchedEffect(loopRestarts) { if (loopRestarts > 0) controller.play() }

    LaunchedEffect(controller) {
        var ticks = 0
        while (isActive) {
            if (!isScrubbing) positionMs = controller.positionMs
            val d = controller.durationMs
            if (d > 0) { durationMs = d; isBuffering = false }
            isPlaying = controller.isPlaying
            if (ticks++ % 6 == 0) refreshTracks()
            delay(200)
        }
    }

    // Scrubbing: fast (keyframe) seeks while the finger moves, then grab the frame VLC rendered for the preview.
    LaunchedEffect(isScrubbing, scrubTargetMs) {
        if (!isScrubbing || durationMs <= 0L) return@LaunchedEffect
        delay(60)
        controller.seekTo(scrubTargetMs, fast = true)
        delay(70)
        previewBitmap = controller.captureFrame()
    }

    LaunchedEffect(chromeVisible, isPlaying, isScrubbing, sheet) {
        if (chromeVisible && isPlaying && !isScrubbing && sheet == Sheet.NONE) {
            delay(3500)
            onChromeVisibleChange(false)
        }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(1100); hud = null } }
    LaunchedEffect(resumeChip) { if (resumeChip) { delay(6000); resumeChip = false } }
    LaunchedEffect(lockHint) { if (lockHint) { delay(2500); lockHint = false } }

    fun playPause() {
        if (controller.isPlaying) { controller.pause(); userPaused = true } else { ended = false; controller.play(); userPaused = false }
    }

    fun seekBy(deltaMs: Long) {
        val target = (controller.positionMs + deltaMs).coerceIn(0L, durationMs.coerceAtLeast(0L))
        controller.seekTo(target)
        positionMs = target
        ended = false
    }

    // Double-tap seeking adds up while the taps keep coming (10 s, 20 s, 30 s ...), like YouTube / VLC.
    var seekStreak by remember { mutableIntStateOf(0) }
    var seekStreakAt by remember { mutableLongStateOf(0L) }
    var seekStreakDir by remember { mutableIntStateOf(0) }
    var boosting by remember { mutableStateOf(false) }

    // vertical-swipe levels
    var brightness by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0.05f, 1f)
        )
    }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(controller) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        controller.setTextureView(tv)
                        controller.attach(tv, startMs = reopenAt, autoPlay = currentPage)
                    }
                },
                update = { it.keepScreenOn = isPlaying }
            )
        }

        // ---- gestures ----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (locked) lockHint = !lockHint
                            else onChromeVisibleChange(!chromeVisible)
                        },
                        onDoubleTap = { p ->
                            if (locked || durationMs <= 0) return@detectTapGestures
                            val dir = if (p.x < size.width / 2f) -1 else 1
                            val now = System.currentTimeMillis()
                            seekStreak = if (dir == seekStreakDir && now - seekStreakAt < 1200) seekStreak + 1 else 1
                            seekStreakDir = dir; seekStreakAt = now
                            seekBy(dir * 10_000L)
                            hud = Hud(if (dir < 0) Icons.Default.Replay10 else Icons.Default.Forward10,
                                (if (dir < 0) "−" else "+") + "${seekStreak * 10} s")
                        },
                        onLongPress = {
                            if (!locked && controller.isPlaying) {
                                boosting = true
                                controller.rate = 2f
                                hud = Hud(Icons.Default.FastForward, "2× while held")
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (boosting) { boosting = false; controller.rate = rate }
                        }
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var leftSide = true
                    detectVerticalDragGestures(
                        onDragStart = { p ->
                            leftSide = p.x < size.width / 2f
                            if (!leftSide) volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            val delta = -dy / (size.height * 0.7f)
                            if (leftSide) {
                                brightness = (brightness + delta).coerceIn(0.01f, 1f)
                                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = brightness } }
                                hud = Hud(Icons.Default.BrightnessMedium, "Brightness ${(brightness * 100).roundToInt()}%", brightness)
                            } else {
                                volume = (volume + delta).coerceIn(0f, 1f)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).roundToInt(), 0)
                                hud = Hud(if (volume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                    "Volume ${(volume * 100).roundToInt()}%", volume)
                            }
                        }
                    )
                }
        )

        // ---- status ----
        if (isBuffering && errorMessage == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }
        errorMessage?.let { message ->
            Surface(color = Color.Black.copy(alpha = 0.82f), shape = RoundedCornerShape(16.dp), modifier = Modifier.align(Alignment.Center).padding(24.dp)) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Can't play this video", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("$message\n${entry.file.extension.uppercase()} · ${if (hardware) "hardware" else "software"} decoding",
                        color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        reopenAt = positionMs
                        hardware = !hardware
                        prefs.hardwareDecoding = hardware
                    }) { Text(if (hardware) "Try software decoding" else "Try hardware decoding") }
                }
            }
        }
        hud?.let { h -> HudPill(h, Modifier.align(Alignment.Center)) }

        // ---- lock ----
        if (locked) {
            AnimatedVisibility(lockHint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f), shape = CircleShape,
                    modifier = Modifier.padding(start = 24.dp).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                ) {
                    IconButton(onClick = { locked = false; lockHint = false; onChromeVisibleChange(true) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = Color.White)
                    }
                }
            }
        }

        // ---- controls ----
        AnimatedVisibility(visible = chromeVisible && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                TopBar(
                    title = entry.name,
                    info = videoInfo,
                    hasAudioChoice = audioTracks.size > 1,
                    hasSubtitles = subtitleTracks.any { it.id >= 0 },
                    onBack = onDismiss,
                    onAudio = { sheet = Sheet.AUDIO },
                    onSubtitles = { sheet = Sheet.SUBTITLES },
                    onInfo = onInfo,
                    onMore = { sheet = Sheet.SETTINGS },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
                CenterControls(
                    isPlaying = isPlaying,
                    ended = ended,
                    onBack10 = { seekBy(-10_000); hud = Hud(Icons.Default.Replay10, "−10 s") },
                    onPlayPause = { playPause() },
                    onFwd10 = { seekBy(10_000); hud = Hud(Icons.Default.Forward10, "+10 s") },
                    modifier = Modifier.align(Alignment.Center)
                )
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 6.dp)
                ) {
                    if (resumeChip && !isScrubbing) {
                        ResumeChip(resumeAt, onStartOver = {
                            controller.seekTo(0); positionMs = 0; resumeChip = false
                        })
                        Spacer(Modifier.height(8.dp))
                    }
                    ScrubBar(
                        positionMs = if (isScrubbing) scrubTargetMs else positionMs,
                        durationMs = durationMs,
                        isScrubbing = isScrubbing,
                        previewBitmap = previewBitmap,
                        showRemaining = showRemaining,
                        onToggleRemaining = { showRemaining = !showRemaining },
                        onScrubStart = {
                            wasPlayingBeforeScrub = controller.isPlaying
                            controller.pause()
                            isScrubbing = true
                            scrubTargetMs = controller.positionMs
                            previewBitmap = controller.captureFrame()
                            onChromeVisibleChange(true)
                        },
                        onScrub = { scrubTargetMs = it },
                        onScrubEnd = {
                            controller.seekTo(it)                         // exact frame at the end
                            positionMs = it
                            isScrubbing = false
                            previewBitmap = null
                            ended = false
                            if (wasPlayingBeforeScrub) controller.play()
                        },
                        onScrubCancel = {
                            controller.seekTo(positionMs)
                            isScrubbing = false
                            previewBitmap = null
                            if (wasPlayingBeforeScrub) controller.play()
                        },
                        onSeekTo = { target -> controller.seekTo(target); positionMs = target; ended = false }
                    )
                    QuickActions(
                        scale = scale,
                        rate = rate,
                        loop = loop,
                        hardware = hardware,
                        onLock = { locked = true; lockHint = true; onChromeVisibleChange(false) },
                        onScale = {
                            scale = scale.next(); prefs.scale = scale; controller.setScale(scale)
                            hud = Hud(Icons.Default.AspectRatio, scale.label)
                        },
                        onSpeed = { sheet = Sheet.SETTINGS },
                        onLoop = { loop = !loop; prefs.loop = loop; hud = Hud(if (loop) Icons.Default.RepeatOne else Icons.Default.Repeat, if (loop) "Loop on" else "Loop off") },
                        onRotate = {
                            val a = activity ?: return@QuickActions
                            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            a.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    )
                }
            }
        }
    }

    // ---- sheets ----
    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.AUDIO -> TrackSheet("Audio track", audioTracks, controller.audioTrack, onDismiss = { sheet = Sheet.NONE }) {
            controller.selectAudioTrack(it); sheet = Sheet.NONE
        }
        Sheet.SUBTITLES -> TrackSheet("Subtitles", subtitleTracks, controller.subtitleTrack, onDismiss = { sheet = Sheet.NONE }) {
            controller.selectSubtitleTrack(it); sheet = Sheet.NONE
        }
        Sheet.SETTINGS -> SettingsSheet(
            rate = rate,
            scale = scale,
            loop = loop,
            hardware = hardware,
            audioTracks = audioTracks,
            audioTrack = controller.audioTrack,
            subtitleTracks = subtitleTracks,
            subtitleTrack = controller.subtitleTrack,
            onRate = { rate = it; controller.rate = it },
            onScale = { scale = it; prefs.scale = it; controller.setScale(it) },
            onLoop = { loop = it; prefs.loop = it },
            onHardware = {
                reopenAt = controller.positionMs
                hardware = it
                prefs.hardwareDecoding = it
            },
            onAudio = { controller.selectAudioTrack(it) },
            onSubtitle = { controller.selectSubtitleTrack(it) },
            onDismiss = { sheet = Sheet.NONE }
        )
    }
}

@Composable
private fun HudPill(h: Hud, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(h.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(h.text, color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
            h.level?.let {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { it }, modifier = Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color.White, trackColor = Color.White.copy(alpha = 0.25f)
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    info: String?,
    hasAudioChoice: Boolean,
    hasSubtitles: Boolean,
    onBack: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onInfo: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White) }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (info != null) Text(info, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (hasAudioChoice) IconButton(onClick = onAudio) { Icon(Icons.Default.Audiotrack, contentDescription = "Audio track", tint = Color.White) }
        if (hasSubtitles) IconButton(onClick = onSubtitles) { Icon(Icons.Default.ClosedCaption, contentDescription = "Subtitles", tint = Color.White) }
        IconButton(onClick = onInfo) { Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White) }
        IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, contentDescription = "Playback settings", tint = Color.White) }
    }
}

@Composable
private fun CenterControls(isPlaying: Boolean, ended: Boolean, onBack10: () -> Unit, onPlayPause: () -> Unit, onFwd10: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        RoundButton(Icons.Default.Replay10, "Back 10 seconds", 52, onBack10)
        RoundButton(
            when { ended -> Icons.Default.Replay; isPlaying -> Icons.Default.Pause; else -> Icons.Default.PlayArrow },
            when { ended -> "Play again"; isPlaying -> "Pause"; else -> "Play" }, 76, onPlayPause
        )
        RoundButton(Icons.Default.Forward10, "Forward 10 seconds", 52, onFwd10)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, sizeDp: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.38f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.58f).dp)) }
}

@Composable
private fun ResumeChip(atMs: Long, onStartOver: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Resumed at ${formatTime(atMs)}", color = Color.White, style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }
}

@Composable
private fun QuickActions(
    scale: VideoScale,
    rate: Float,
    loop: Boolean,
    hardware: Boolean,
    onLock: () -> Unit,
    onScale: () -> Unit,
    onSpeed: () -> Unit,
    onLoop: () -> Unit,
    onRotate: () -> Unit
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(Icons.Default.LockOpen, "Lock", onLock)
        ActionButton(Icons.Default.AspectRatio, scale.label, onScale)
        ActionButton(Icons.Default.Speed, if (rate == 1f) "1×" else "${trimRate(rate)}×", onSpeed, highlighted = rate != 1f)
        ActionButton(if (loop) Icons.Default.RepeatOne else Icons.Default.Repeat, "Loop", onLoop, highlighted = loop)
        ActionButton(Icons.Default.ScreenRotation, "Rotate", onRotate)
        Spacer(Modifier.weight(1f))
        Text(if (hardware) "HW" else "SW", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 8.dp))
    }
}

private fun trimRate(r: Float): String = if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    val tint = if (highlighted) MaterialTheme.colorScheme.primary else Color.White
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Timeline: elapsed time, the bar, remaining (or total - tap to switch). The bar owns its touches from the first
 * contact (every change is consumed), so the viewer's pager never takes a drag that starts on it - that is what made
 * scrubbing unreliable before. A tap jumps there; a drag scrubs with a frame preview above the thumb.
 */
@Composable
private fun ScrubBar(
    positionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    previewBitmap: Bitmap?,
    showRemaining: Boolean,
    onToggleRemaining: () -> Unit,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onScrubCancel: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp))
        var trackWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val thumb by animateDpAsState(if (isScrubbing) 22.dp else 14.dp, label = "thumb")
        val barHeight by animateDpAsState(if (isScrubbing) 6.dp else 4.dp, label = "bar")
        val start by rememberUpdatedState(onScrubStart)
        val scrub by rememberUpdatedState(onScrub)
        val end by rememberUpdatedState(onScrubEnd)
        val cancel by rememberUpdatedState(onScrubCancel)
        val seek by rememberUpdatedState(onSeekTo)
        val duration by rememberUpdatedState(durationMs)
        Box(
            Modifier
                .weight(1f)
                .height(44.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        if (trackWidthPx <= 0f || duration <= 0) return@awaitEachGesture
                        fun at(x: Float) = ((x / trackWidthPx).coerceIn(0f, 1f) * duration).toLong()
                        var dragging = false
                        var finished = false
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUp()) {
                                    change.consume()
                                    if (dragging) end(at(change.position.x)) else seek(at(down.position.x))
                                    finished = true
                                    break
                                }
                                if (!dragging && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop / 2) {
                                    dragging = true
                                    start()
                                }
                                if (dragging) scrub(at(change.position.x))
                                change.consume()
                            }
                        } finally {
                            if (dragging && !finished) cancel()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.28f)))
            Box(Modifier.fillMaxWidth(fraction).height(barHeight).clip(RoundedCornerShape(3.dp)).background(primary))
            val thumbPx = with(density) { thumb.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((fraction * trackWidthPx - thumbPx / 2).roundToInt(), 0) }
                    .size(thumb)
                    .clip(CircleShape)
                    .background(primary)
            )
            if (isScrubbing) ScrubPreview(previewBitmap, positionMs, fraction, trackWidthPx)
        }
        Text(
            if (showRemaining) "-${formatTime((durationMs - positionMs).coerceAtLeast(0L))}" else formatTime(durationMs),
            color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onToggleRemaining).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
        )
    }
}

/** The frame and time above the thumb while scrubbing, kept inside the bar's width. */
@Composable
private fun ScrubPreview(bitmap: Bitmap?, positionMs: Long, fraction: Float, trackWidthPx: Float) {
    val density = LocalDensity.current
    val wPx = with(density) { 168.dp.toPx() }
    val liftPx = with(density) { 132.dp.roundToPx() }
    val x = (fraction * trackWidthPx - wPx / 2).coerceIn(0f, (trackWidthPx - wPx).coerceAtLeast(0f))
    Column(
        Modifier.offset { IntOffset(x.roundToInt(), -liftPx) }.width(168.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.width(168.dp).height(95.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF202124))) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Preview", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(4.dp))
        Surface(color = Color.Black.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp)) {
            Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSheet(title: String, tracks: List<VlcTrack>, selected: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            TrackList(tracks, selected, onSelect)
        }
    }
}

@Composable
private fun TrackList(tracks: List<VlcTrack>, selected: Int, onSelect: (Int) -> Unit) {
    if (tracks.isEmpty()) {
        Text("None in this file", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
    for (t in tracks) {
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(t.id) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = t.id == selected, onClick = { onSelect(t.id) })
            Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    rate: Float,
    scale: VideoScale,
    loop: Boolean,
    hardware: Boolean,
    audioTracks: List<VlcTrack>,
    audioTrack: Int,
    subtitleTracks: List<VlcTrack>,
    subtitleTrack: Int,
    onRate: (Float) -> Unit,
    onScale: (VideoScale) -> Unit,
    onLoop: (Boolean) -> Unit,
    onHardware: (Boolean) -> Unit,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var audioSel by remember { mutableIntStateOf(audioTrack) }
    var subSel by remember { mutableIntStateOf(subtitleTrack) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Section("Speed")
            ChipRow(SPEEDS, { "${trimRate(it)}×" }, { it == rate }, onRate)
            Section("Picture")
            ChipRow(VideoScale.entries, { it.label }, { it == scale }, onScale)
            if (audioTracks.size > 1) {
                Section("Audio track")
                TrackList(audioTracks, audioSel) { audioSel = it; onAudio(it) }
            }
            if (subtitleTracks.any { it.id >= 0 }) {
                Section("Subtitles")
                TrackList(subtitleTracks, subSel) { subSel = it; onSubtitle(it) }
            }
            Section("Playback")
            SwitchRow("Loop this video", null, loop, onLoop)
            SwitchRow(
                "Hardware decoding",
                "Faster for 4K / HEVC / AV1. Falls back to software by itself if a file fails. Off = most compatible (old AVI / DivX / WMV).",
                hardware, onHardware
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun <T> ChipRow(items: List<T>, label: (T) -> String, isSelected: (T) -> Boolean, onClick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) {
            FilterChip(
                selected = isSelected(item),
                onClick = { onClick(item) },
                label = { Text(label(item)) },
                colors = FilterChipDefaults.filterChipColors()
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
