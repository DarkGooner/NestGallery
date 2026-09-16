package com.nestgallery.viewer.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.TextureView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * Small lifecycle wrapper around LibVLC for local-file playback.
 *
 * For maximum compatibility with older AVI codecs, this controller deliberately
 * disables VLC hardware decoding. Some legacy AVI codecs/container combinations
 * can crash device hardware decoders even though VLC desktop can decode them.
 * Software decoding is slower but is much safer for a gallery/player whose main
 * requirement is broad codec compatibility.
 */
class VlcPlayerController(
    context: Context,
    private val file: File,
    private val muted: Boolean = false,
    private val repeat: Boolean = false,
    private val onEvent: ((MediaPlayer.Event) -> Unit)? = null
) {
    companion object {
        private const val TAG = "NestGalleryVLC"
    }

    private val appContext = context.applicationContext
    private val libVlc = LibVLC(
        appContext,
        arrayListOf(
            "--no-video-title-show",
            "--avcodec-hw=none"
        )
    )
    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    private var textureView: TextureView? = null
    private var released = false

    init {
        mediaPlayer.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EndReached && repeat && !released) {
                runCatching {
                    mediaPlayer.setTime(0L)
                    mediaPlayer.play()
                }
            }

            // VLC callbacks are delivered from native/player threads. Keep the
            // Compose-facing callback on Android's main thread.
            if (!released) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!released) onEvent?.invoke(event)
                }
            }
        }

        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        if (muted) mediaPlayer.setVolume(0)
    }

    /**
     * Attach the VLC video output to the TextureView and start the file.
     * TextureView is intentional: unlike SurfaceView it lets us read the
     * currently rendered frame for the YouTube-style scrub preview.
     */
    fun attach(view: TextureView) {
        if (released) return

        textureView = view

        try {
            val vout = mediaPlayer.getVLCVout()
            if (vout.areViewsAttached()) {
                vout.detachViews()
            }
            vout.setVideoView(view)
            vout.attachViews()

            val media = Media(libVlc, Uri.fromFile(file))
            // Start with software decoding. This is the compatibility path for
            // legacy AVI codecs and avoids device-specific MediaCodec crashes.
            media.setHWDecoderEnabled(false, false)
            media.addOption(":avcodec-hw=none")
            media.addOption(":file-caching=300")
            mediaPlayer.setMedia(media)
            media.release()
            mediaPlayer.play()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to attach/play: ${file.absolutePath}", t)
            runCatching { mediaPlayer.stop() }
            throw t
        }
    }

    fun play() {
        if (!released) mediaPlayer.play()
    }

    fun pause() {
        if (!released) mediaPlayer.pause()
    }

    fun togglePlayPause() {
        if (released) return
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        val length = durationMs
        if (length > 0L) {
            runCatching {
                mediaPlayer.setTime(positionMs.coerceIn(0L, length))
            }
        }
    }

    val positionMs: Long
        get() = if (released) 0L else runCatching { mediaPlayer.getTime() }.getOrDefault(0L).coerceAtLeast(0L)

    val durationMs: Long
        get() = if (released) 0L else runCatching { mediaPlayer.getLength() }.getOrDefault(0L).coerceAtLeast(0L)

    val isPlaying: Boolean
        get() = !released && runCatching { mediaPlayer.isPlaying }.getOrDefault(false)

    fun captureFrame(width: Int = 360, height: Int = 202): Bitmap? {
        val view = textureView ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        return runCatching { view.getBitmap(width, height) }.getOrNull()
    }

    fun setTextureView(view: TextureView?) {
        if (!released) textureView = view
    }

    fun release() {
        if (released) return
        released = true

        runCatching {
            if (mediaPlayer.getVLCVout().areViewsAttached()) {
                mediaPlayer.getVLCVout().detachViews()
            }
        }
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
        textureView = null
    }
}
