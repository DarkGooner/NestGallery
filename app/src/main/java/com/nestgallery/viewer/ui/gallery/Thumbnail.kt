package com.nestgallery.viewer.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nestgallery.viewer.data.MediaItem
import com.nestgallery.viewer.data.MediaLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A grid thumbnail, center-cropped. Deliberately much lighter than Coil's AsyncImage: rows are composed on the main
 * thread while scrolling, and Coil's per-image request machinery (painter, request, size resolver, interceptors)
 * cost ~2.5 ms per cell there, which is what made fast scrolling drop frames. This only remembers one state, starts
 * one coroutine when the picture isn't cached, and draws the bitmap itself.
 *
 * Pictures come from MediaStore's own cached thumbnails (Android 10+), loaded off the main thread and cancelled when
 * the cell scrolls away; [sizePx] is the cell's size in pixels.
 */
@Composable
fun Thumbnail(item: MediaItem, sizePx: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bucket = ThumbnailCache.bucket(sizePx)
    var image by remember(item.id, bucket) { mutableStateOf(ThumbnailCache.get(item.id, bucket)) }
    if (image == null) {
        LaunchedEffect(item.id, bucket) {
            image = ThumbnailCache.load(context.applicationContext, item, bucket)
        }
    }
    Box(
        modifier.drawWithCache {
            val img = image
            // center crop: the largest centred square-ish part of the picture with the cell's aspect ratio
            val src = img?.let {
                val scale = max(size.width / it.width, size.height / it.height)
                val w = (size.width / scale).roundToInt().coerceIn(1, it.width)
                val h = (size.height / scale).roundToInt().coerceIn(1, it.height)
                IntOffset((it.width - w) / 2, (it.height - h) / 2) to IntSize(w, h)
            }
            val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
            onDrawBehind {
                if (img != null && src != null) drawImage(img, src.first, src.second, dstSize = dst)
            }
        }
    )
}

/** Process-wide thumbnail cache (by bytes) and loader. */
object ThumbnailCache {
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmapSize()
    }

    /** Two sizes only, so changing the column count mostly reuses what is cached. */
    fun bucket(sizePx: Int) = if (sizePx <= 320) 320 else 640

    fun get(id: Long, bucket: Int): ImageBitmap? = cache.get("$id:$bucket") ?: if (bucket == 320) null else cache.get("$id:320")

    /** At most a few decodes at once: a fast fling would otherwise queue hundreds that are cancelled anyway. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    suspend fun load(context: Context, item: MediaItem, bucket: Int): ImageBitmap? = withContext(io) {
        val signal = CancellationSignal()
        val handle = currentCoroutineContext().job.invokeOnCompletion { signal.cancel() }
        try {
            val bitmap = decode(context, item, bucket, signal)
            if (bitmap == null) {
                // MediaStore can list files that are gone (deleted behind its back): drop them from the gallery
                if (!item.entry.file.exists()) MediaLibrary.getInstance(context).forgetMissing(item)
                return@withContext null
            }
            bitmap.prepareToDraw()                      // upload to the GPU now, not in the frame that first draws it
            bitmap.asImageBitmap().also { cache.put("${item.id}:$bucket", it) }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException && !item.entry.file.exists()) {
                MediaLibrary.getInstance(context).forgetMissing(item)
            }
            null                                         // cancelled, deleted, or a format nothing can thumbnail
        } finally {
            handle.dispose()
        }
    }

    private fun decode(context: Context, item: MediaItem, bucket: Int, signal: CancellationSignal): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return context.contentResolver.loadThumbnail(item.uri, Size(bucket, bucket), signal)
            } catch (e: android.os.OperationCanceledException) {
                throw e
            } catch (e: Exception) {
                // fall through: decode the file itself
            }
        }
        val file = item.entry.file
        return if (item.entry.isVideo) {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= bucket && bounds.outHeight / (sample * 2) >= bucket) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun ImageBitmap.asAndroidBitmapSize(): Int = width * height * 4
}
