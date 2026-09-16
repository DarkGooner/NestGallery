package com.nestgallery.viewer.data

/**
 * Video playback is implemented by [VlcPlayerController].
 *
 * The previous Media3/NextLib factory has intentionally been removed: AVI
 * files can contain codecs outside the decoder set exposed by that stack,
 * while LibVLC bundles VLC's own demuxers and decoders.
 */
object PlayerFactory
