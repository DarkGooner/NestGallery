package com.nestgallery.viewer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An audio or subtitle track as VLC names it; id -1 = "off" for subtitles. */
data class VlcTrack(val id: Int, val name: String)

/** How the picture is fitted to the screen (VLC's own presets). */
enum class VideoScale(val label: String, internal val vlc: MediaPlayer.ScaleType) {
    FIT("Fit", MediaPlayer.ScaleType.SURFACE_BEST_FIT),
    FILL("Fill", MediaPlayer.ScaleType.SURFACE_FIT_SCREEN),
    STRETCH("Stretch", MediaPlayer.ScaleType.SURFACE_FILL),
    R16_9("16:9", MediaPlayer.ScaleType.SURFACE_16_9),
    R4_3("4:3", MediaPlayer.ScaleType.SURFACE_4_3),
    R21_9("21:9", MediaPlayer.ScaleType.SURFACE_221_1),
    ORIGINAL("100%", MediaPlayer.ScaleType.SURFACE_ORIGINAL);

    fun next(): VideoScale = entries[(ordinal + 1) % entries.size]
}

/**
 * The one LibVLC instance of the process. Creating a LibVLC per video (as before) loaded VLC's module bank again for
 * every player and hold preview; MediaPlayers can share one instance, so it is made once and never released.
 */
object VlcEngine {
    private const val TAG = "NestGalleryVLC"

    @Volatile private var instance: LibVLC? = null
    @Volatile private var warmUpStarted = false

    /**
     * Stops and releases players off the main thread. LibVLC's stop() waits for the playback threads to finish, which
     * can take seconds (the first video output's font scan, below): on the main thread that was an ANR.
     */
    private val releaser = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-release") }

    internal fun releaseLater(player: MediaPlayer) {
        releaser.execute {
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    /**
     * The first video output of a fresh install loads VLC's text renderer, whose fontconfig reads every font in
     * /system/fonts (~20 s on an SM7550) until its cache is saved; a hold preview sat on a spinner that long, and
     * killing the app mid-scan (the ANR dialog) meant scanning again next time. This plays a tiny PNG in the
     * background at startup, so the scan happens once, out of sight, and later starts only read the cache. No window
     * is attached, so the video output itself fails (logged as "video output creation failed"), but VLC loads the
     * text renderer before it asks for the window, which is all this is for (`:vout=dummy` is not accepted per media).
     * Also creates the LibVLC instance off the main thread. Measured on the SM7550 with a saved cache: ~350 ms.
     */
    fun warmUp(context: Context) {
        if (warmUpStarted) return
        warmUpStarted = true
        val app = context.applicationContext
        Thread({
            runCatching { warmUpBlocking(app) }.onFailure { Log.w(TAG, "VLC warm-up failed", it) }
        }, "vlc-warmup").apply { isDaemon = true }.start()
    }

    private fun warmUpBlocking(context: Context) {
        val vlc = get(context)
        val png = File(context.cacheDir, "vlc-warmup.png")
        if (!png.exists()) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val done = CountDownLatch(1)
        val player = MediaPlayer(vlc)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Vout, MediaPlayer.Event.EndReached,
                MediaPlayer.Event.EncounteredError, MediaPlayer.Event.Stopped -> done.countDown()
            }
        }
        val media = Media(vlc, Uri.fromFile(png))
        media.addOption(":no-audio")
        media.addOption(":image-duration=1")
        player.setMedia(media)
        media.release()
        val start = System.nanoTime()
        player.play()
        done.await(90, TimeUnit.SECONDS)
        player.setEventListener(null)
        runCatching { player.stop() }
        runCatching { player.release() }
        Log.i(TAG, "VLC warm-up done in ${(System.nanoTime() - start) / 1_000_000} ms")
    }

    fun get(context: Context): LibVLC =
        instance ?: synchronized(this) {
            instance ?: LibVLC(
                context.applicationContext,
                arrayListOf(
                    "--no-video-title-show",
                    "--audio-time-stretch",            // keep the pitch when the speed changes
                    "--sub-autodetect-file"            // pick up "movie.srt" / ".ass" / ... next to "movie.mkv"
                )
            ).also { instance = it }
        }
}

/**
 * Lifecycle wrapper around LibVLC for local-file playback (the viewer and the hold-to-preview tiles).
 *
 * Decoding is software by default ([hardwareDecoding] false): it is the compatibility path for legacy AVI / DivX /
 * WMV and the like, and avoids device-specific MediaCodec failures. Hardware decoding (MediaCodec) is opt-in from the
 * player's settings for heavy files (4K HEVC / AV1); if it fails on a file the controller reopens it in software at
 * the same position by itself, like VLC does.
 */
class VlcPlayerController(
    context: Context,
    private val file: File,
    private val muted: Boolean = false,
    private val repeat: Boolean = false,
    hardwareDecoding: Boolean = false,
    private val onAttachError: (() -> Unit)? = null,
    /** Called (main thread) when hardware decoding failed and playback was reopened in software. */
    private val onDecoderFallback: (() -> Unit)? = null,
    /**
     * Called (main thread) once VLC's first frame is on the TextureView, which is later than Event.Vout, with the
     * [videoAspect] known by then.
     */
    private val onFirstFrame: ((aspect: Float?) -> Unit)? = null,
    private val onEvent: ((MediaPlayer.Event) -> Unit)? = null
) {
    companion object {
        private const val TAG = "NestGalleryVLC"
    }

    private val libVlc = VlcEngine.get(context)
    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)
    private val main = Handler(Looper.getMainLooper())

    private var textureView: TextureView? = null
    @Volatile private var released = false
    private var sizeListener: android.view.View.OnLayoutChangeListener? = null

    /** True while the current media is decoded by MediaCodec. */
    @Volatile var usingHardware: Boolean = hardwareDecoding
        private set
    private var fellBack = false
    private var lastKnownPositionMs = 0L

    init {
        mediaPlayer.setEventListener { event ->
            if (event.type == MediaPlayer.Event.TimeChanged) lastKnownPositionMs = event.timeChanged
            if (event.type == MediaPlayer.Event.EndReached && repeat && !released) {
                // backup for files the input-repeat option can't loop
                main.post { if (!released) restartFromBeginning() }
            }
            if (event.type == MediaPlayer.Event.EncounteredError && usingHardware && !fellBack && !released) {
                // MediaCodec could not handle it: reopen in software where we were, instead of showing an error
                fellBack = true
                main.post {
                    if (!released) {
                        usingHardware = false
                        openMedia(startMs = lastKnownPositionMs)
                        onDecoderFallback?.invoke()
                    }
                }
                return@setEventListener
            }
            // VLC callbacks arrive on native threads; Compose-facing callbacks run on the main thread.
            if (!released) main.post { if (!released) onEvent?.invoke(event) }
        }
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        if (muted) mediaPlayer.setVolume(0)
    }

    /**
     * Pushes the TextureView's pixel size to VLC so the scale presets can fit / centre correctly. A plain layout
     * listener, because attachViews() installs its own SurfaceTextureListener (replacing it stops rendering).
     */
    private fun layoutListener(): android.view.View.OnLayoutChangeListener =
        android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (!released && width > 0 && height > 0) mediaPlayer.getVLCVout().setWindowSize(width, height)
        }

    /**
     * Attaches VLC's video output to the TextureView and starts the file at [startMs]. TextureView (not SurfaceView)
     * so the scrub preview can read the rendered frame.
     */
    fun attach(view: TextureView, startMs: Long = 0L, autoPlay: Boolean = true) {
        if (released) return
        textureView = view
        try {
            val vout = mediaPlayer.getVLCVout()
            if (vout.areViewsAttached()) vout.detachViews()
            vout.setVideoView(view)
            vout.attachViews()
            onFirstFrame?.let { notifyFirstFrame(view, it) }
            view.addOnLayoutChangeListener(layoutListener().also { sizeListener = it })
            if (view.width > 0 && view.height > 0) vout.setWindowSize(view.width, view.height)
            openMedia(startMs, autoPlay)
        } catch (t: Throwable) {
            // attach() runs from the AndroidView factory: an escaping throwable would crash the app, not just this video
            Log.e(TAG, "Failed to attach/play: ${file.absolutePath}", t)
            runCatching { mediaPlayer.stop() }
            if (!released) main.post { if (!released) onAttachError?.invoke() }
        }
    }

    /**
     * Wraps the SurfaceTextureListener attachViews() installed (VLC renders through it, so it is delegated, not
     * replaced) to learn when the first frame has been drawn. Without VLC's listener, falls back to now.
     */
    private fun notifyFirstFrame(view: TextureView, callback: (Float?) -> Unit) {
        val inner = view.surfaceTextureListener ?: run { callback(null); return }
        var fired = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) =
                inner.onSurfaceTextureAvailable(surface, width, height)

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) =
                inner.onSurfaceTextureSizeChanged(surface, width, height)

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean =
                inner.onSurfaceTextureDestroyed(surface)

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                inner.onSurfaceTextureUpdated(surface)
                if (!fired && !released) {
                    fired = true
                    callback(videoAspect())
                }
            }
        }
    }

    private fun openMedia(startMs: Long = 0L, autoPlay: Boolean = true) {
        val media = Media(libVlc, Uri.fromFile(file))
        media.setHWDecoderEnabled(usingHardware, false)
        if (!usingHardware) media.addOption(":avcodec-hw=none")
        media.addOption(":file-caching=300")
        if (muted) {                                  // previews: no audio decoder / output, no subtitle parsing
            media.addOption(":no-audio")
            media.addOption(":no-spu")
        }
        if (repeat) media.addOption(":input-repeat=65535")
        if (startMs > 0) media.addOption(":start-time=${startMs / 1000.0}")
        mediaPlayer.setMedia(media)
        media.release()
        if (autoPlay) mediaPlayer.play()
    }

    private fun restartFromBeginning() {
        runCatching { mediaPlayer.stop(); mediaPlayer.play() }
    }

    fun play() {
        if (released) return
        // after the end VLC is stopped: playing again starts from the top
        if (mediaPlayer.playerState == org.videolan.libvlc.interfaces.IMedia.State.Ended) restartFromBeginning() else mediaPlayer.play()
    }

    fun pause() {
        if (!released) mediaPlayer.pause()
    }

    fun togglePlayPause() {
        if (released) return
        if (mediaPlayer.isPlaying) pause() else play()
    }

    /** @param fast keyframe seek (instant, used while scrubbing); false = exact frame */
    fun seekTo(positionMs: Long, fast: Boolean = false) {
        if (released) return
        val length = durationMs
        if (length > 0L) runCatching { mediaPlayer.setTime(positionMs.coerceIn(0L, length), fast) }
    }

    val positionMs: Long
        get() = if (released) 0L else runCatching { mediaPlayer.getTime() }.getOrDefault(0L).coerceAtLeast(0L)

    val durationMs: Long
        get() = if (released) 0L else runCatching { mediaPlayer.getLength() }.getOrDefault(0L).coerceAtLeast(0L)

    val isPlaying: Boolean
        get() = !released && runCatching { mediaPlayer.isPlaying }.getOrDefault(false)

    var rate: Float
        get() = if (released) 1f else runCatching { mediaPlayer.rate }.getOrDefault(1f)
        set(value) { if (!released) runCatching { mediaPlayer.rate = value } }

    fun setScale(scale: VideoScale) {
        if (!released) runCatching { mediaPlayer.setVideoScale(scale.vlc) }
    }

    fun audioTracks(): List<VlcTrack> =
        if (released) emptyList() else runCatching { mediaPlayer.audioTracks?.map { VlcTrack(it.id, it.name) } }.getOrNull().orEmpty()

    val audioTrack: Int get() = if (released) -1 else runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
    fun selectAudioTrack(id: Int) { if (!released) runCatching { mediaPlayer.setAudioTrack(id) } }

    /** Subtitle tracks, VLC's own "Disable" (id -1) first. */
    fun subtitleTracks(): List<VlcTrack> =
        if (released) emptyList() else runCatching { mediaPlayer.spuTracks?.map { VlcTrack(it.id, it.name) } }.getOrNull().orEmpty()

    val subtitleTrack: Int get() = if (released) -1 else runCatching { mediaPlayer.spuTrack }.getOrDefault(-1)
    fun selectSubtitleTrack(id: Int) { if (!released) runCatching { mediaPlayer.setSpuTrack(id) } }

    /** "1920×1080 · H264 · 29.97 fps" once VLC knows the video track, else null. */
    fun videoInfo(): String? {
        if (released) return null
        val t = runCatching { mediaPlayer.currentVideoTrack }.getOrNull() ?: return null
        if (t.width <= 0 || t.height <= 0) return null
        val parts = arrayListOf("${t.width}×${t.height}")
        (t.codec ?: t.originalCodec)?.takeIf { it.isNotBlank() }?.let { parts += it.uppercase() }
        if (t.frameRateNum > 0 && t.frameRateDen > 0) {
            val fps = t.frameRateNum.toDouble() / t.frameRateDen
            parts += if (fps % 1.0 == 0.0) "${fps.toInt()} fps" else "%.2f fps".format(fps)
        }
        return parts.joinToString(" · ")
    }

    // Two reused buffers for captureFrame: scrubbing grabs a frame every ~130 ms, and a fresh bitmap each time was
    // pure garbage. Alternating means the one on screen is never overwritten while it is drawn, and each capture
    // returns a different object, so Compose sees the change.
    private val frames = arrayOfNulls<Bitmap>(2)
    private var nextFrame = 0

    /** The frame on screen, scaled to [targetWidth]. The bitmap is reused by the capture after next: don't keep it. */
    fun captureFrame(targetWidth: Int = 360): Bitmap? {
        val view = textureView ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        val targetHeight = (targetWidth.toFloat() * view.height / view.width).toInt()
        if (targetHeight <= 0) return null
        val i = nextFrame
        var buffer = frames[i]
        if (buffer == null || buffer.width != targetWidth || buffer.height != targetHeight) {
            buffer = runCatching { Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
            frames[i] = buffer
        }
        if (runCatching { view.getBitmap(buffer) }.isFailure) return null
        nextFrame = 1 - i
        return buffer
    }

    /**
     * Display aspect ratio (width / height) of the video track once VLC knows it, with the pixel aspect and a
     * 90 / 270 degree rotation applied, or null.
     */
    fun videoAspect(): Float? {
        if (released) return null
        val t = runCatching { mediaPlayer.currentVideoTrack }.getOrNull() ?: return null
        if (t.width <= 0 || t.height <= 0) return null
        val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toFloat() / t.sarDen else 1f
        val ar = t.width * sar / t.height
        // orientations 4..7 (LeftTop ... RightBottom) are transposed
        return if (t.orientation >= 4) 1f / ar else ar
    }

    fun setTextureView(view: TextureView?) {
        if (!released) textureView = view
    }

    fun release() {
        if (released) return
        released = true
        runCatching { textureView?.let { tv -> sizeListener?.let { tv.removeOnLayoutChangeListener(it) } } }
        sizeListener = null
        runCatching { if (mediaPlayer.getVLCVout().areViewsAttached()) mediaPlayer.getVLCVout().detachViews() }
        runCatching { mediaPlayer.setEventListener(null) }
        VlcEngine.releaseLater(mediaPlayer)        // stop() can block for seconds: never on the main thread
        textureView = null
        frames.fill(null)
    }
}
