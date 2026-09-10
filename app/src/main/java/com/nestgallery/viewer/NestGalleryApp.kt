package com.nestgallery.viewer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.video.VideoFrameDecoder

/**
 * One shared Coil ImageLoader for the whole app.
 * Registering the video decoder here keeps local video thumbnails working
 * without constructing a separate loader for every grid tile.
 */
class NestGalleryApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
}
