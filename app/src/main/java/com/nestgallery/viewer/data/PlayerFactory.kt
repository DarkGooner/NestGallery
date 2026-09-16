package com.nestgallery.viewer.data

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Extractor factory that adds explicit AVI container support
 * while retaining all of Media3's default extractors.
 *
 * AVI support is separate from codec decoding:
 * AviExtractor parses the AVI container, while NextRenderersFactory
 * provides FFmpeg-backed decoders for codecs not supported by Android.
 */
private class BroadExtractorsFactory : ExtractorsFactory {

    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> {
        val defaultExtractors = defaults.createExtractors()

        // Build the array explicitly to avoid Kotlin type-inference
        // problems with arrayOf(...) + another Array<Extractor>.
        return Array(defaultExtractors.size + 1) { index ->
            if (index == 0) {
                AviExtractor()
            } else {
                defaultExtractors[index - 1]
            }
        }
    }
}

/**
 * Creates the ExoPlayer instance used by NestGallery.
 *
 * Features:
 * - Media3 ExoPlayer
 * - Explicit AVI container support
 * - Media3's normal extractors
 * - FFmpeg-backed decoding through nextlib
 *
 * This factory should be shared by the full-screen video player
 * and video preview components so both use the same playback stack.
 */
fun buildExoPlayer(context: Context): ExoPlayer {
    val mediaSourceFactory = DefaultMediaSourceFactory(
        context,
        BroadExtractorsFactory()
    )

    return ExoPlayer.Builder(
        context,
        NextRenderersFactory(context)
    )
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
}
