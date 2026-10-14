package com.nestgallery.viewer.data.rpa

import android.webkit.MimeTypeMap
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException

/**
 * Coil fetcher for files inside Ren'Py archives ([RpaFs] paths): extracts the member on first use, then hands Coil
 * the extracted file exactly like its own file fetcher would. Real files are left to Coil's fetcher.
 */
class RpaFetcher(private val file: File) : Fetcher {
    override suspend fun fetch(): FetchResult {
        if (!withContext(Dispatchers.IO) { RpaFs.ensure(file) }) throw IOException("Couldn't read ${file.name} from its archive")
        return SourceResult(
            source = ImageSource(file.toOkioPath()),
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()),
            dataSource = DataSource.DISK
        )
    }

    class Factory : Fetcher.Factory<File> {
        override fun create(data: File, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (RpaFs.isVirtual(data)) RpaFetcher(data) else null
    }
}
