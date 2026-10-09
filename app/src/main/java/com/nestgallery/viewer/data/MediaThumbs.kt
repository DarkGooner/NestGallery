package com.nestgallery.viewer.data

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import java.io.File

/**
 * A grid thumbnail of a MediaStore item, as a Coil model (`AsyncImage(model = MediaThumb(...))`). Android keeps a
 * small cached thumbnail for every indexed photo and video, so this is far cheaper than decoding the original
 * (a 12 MP JPEG, or a video frame through MediaMetadataRetriever) for every tile while scrolling.
 */
data class MediaThumb(val uri: Uri, val file: File)

fun MediaItem.thumb() = MediaThumb(uri, entry.file)

/** Memory-cache key (Coil caches nothing for a model without one). The size is added by Coil. */
class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options) = "thumb:${data.uri}"
}

/**
 * Loads [MediaThumb]s with `ContentResolver.loadThumbnail` (Android 10+). Older Android, or an item MediaStore
 * can't make a thumbnail for, falls back to Coil's normal file loading (which decodes the original).
 */
class MediaThumbFetcher(
    private val data: MediaThumb,
    private val options: Options,
    private val imageLoader: ImageLoader
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val w = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_PX
                val h = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_PX
                val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(w.coerceAtLeast(1), h.coerceAtLeast(1)), null)
                return DrawableResult(BitmapDrawable(options.context.resources, bitmap), isSampled = true, dataSource = DataSource.DISK)
            } catch (e: Exception) {
                // no thumbnail (unsupported format, file gone from the index): decode the file instead
            }
        }
        val (fetcher, _) = imageLoader.components.newFetcher(data.file, options, imageLoader) ?: return null
        return fetcher.fetch()
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, options, imageLoader)
    }

    private companion object {
        const val DEFAULT_PX = 384
    }
}
