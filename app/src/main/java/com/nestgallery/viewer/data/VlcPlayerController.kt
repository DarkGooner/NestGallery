package com.nestgallery.viewer.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File

/**
 * Small lifecycle wrapper around LibVLC for local-file playback.
 *
 * LibVLC is used instead of Android/Media3 for video playback because it
 * brings VLC's demuxers and software/hardware decoders with it. This is
 * particularly useful for older AVI files whose codecs are not supported by
 * Android's native media stack.
 */
class VlcPlayerController(
    context: Context,
    private val file: File,
    private val muted: Boolean = false,
    private val repeat: Boolean = false,
    private val onEvent: ((MediaPlayer.Event) -> Unit)? = null
) {
    private val appContext = context.applicationContext
    private val libVlc = LibVLC(appContext)
    val mediaPlayer: MediaPlayer = MediaPlayer(libVlc)

    init {
        mediaPlayer.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EndReached && repeat) {
                mediaPlayer.setTime(0L)
                mediaPlayer.play()
            }
            onEvent?.invoke(event)
        }
        mediaPlayer.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
        if (muted) mediaPlayer.setVolume(0)
    }

    fun attach(textureView: TextureView) {
        mediaPlayer.getVLCVout().setVideoView(textureView)
        mediaPlayer.getVLCVout().attachViews()

        val media = Media(libVlc, Uri.fromFile(file))
        media.setHWDecoderEnabled(true, false)
        mediaPlayer.setMedia(media)
        media.release()
        mediaPlayer.play()
    }

    fun play() = mediaPlayer.play()

    fun pause() = mediaPlayer.pause()

    fun togglePlayPause() {
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    fun seekTo(positionMs: Long) {
        val length = durationMs
        if (length > 0L) {
            mediaPlayer.setTime(positionMs.coerceIn(0L, length))
        }
    }

    val positionMs: Long
        get() = mediaPlayer.getTime().coerceAtLeast(0L)

    val durationMs: Long
        get() = mediaPlayer.getLength().coerceAtLeast(0L)

    val isPlaying: Boolean
        get() = mediaPlayer.isPlaying

    fun captureFrame(width: Int = 360, height: Int = 202): Bitmap? {
        val view = textureView ?: return null
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return null
        return runCatching { view.getBitmap(width, height) }.getOrNull()
    }

    private var textureView: TextureView? = null

    fun setTextureView(view: TextureView?) {
        textureView = view
    }

    fun release() {
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
