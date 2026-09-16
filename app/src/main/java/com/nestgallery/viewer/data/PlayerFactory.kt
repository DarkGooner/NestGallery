package com.nestgallery.viewer.data

import android.content.Context
import androidx.media3.exoplayer.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.avi.AviExtractor
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory

/**
 * Media3's DefaultExtractorsFactory doesn't include AviExtractor even
 * though Media3 ships one - AVI demuxing has to be added explicitly. This
 * is what made .avi files fail even with FFmpeg decoders available: the
 * container itself couldn't be parsed to find the audio/video streams in
 * the first place, a separate problem from decoding the codecs inside it.
 */
private class BroadExtractorsFactory : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> =
        arrayOf(AviExtractor()) + defaults.createExtractors()
}

/**
 * One ExoPlayer builder shared by the full-screen player and the
 * hold-to-preview popup: AVI container support plus FFmpeg-backed decoders
 * (nextlib) for codecs the platform doesn't ship, closing most of the gap
 * with a general player like VLC.
 */
fun buildExoPlayer(context: Context): ExoPlayer {
    val mediaSourceFactory = DefaultMediaSourceFactory(context, BroadExtractorsFactory())
    return ExoPlayer.Builder(context, NextRenderersFactory(context))
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
}
