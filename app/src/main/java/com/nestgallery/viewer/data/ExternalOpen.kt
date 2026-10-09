package com.nestgallery.viewer.data

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * "Open with another app" for a file the built-in player can't handle. Other apps get a MediaStore content URI (the
 * app has no FileProvider, and file:// URIs are refused since Android 7); a file MediaStore hasn't indexed yet
 * (e.g. in a `.nomedia` folder that the explorer still shows) is scanned first to get one.
 */
fun openWithAnotherApp(context: Context, file: File, isVideo: Boolean = true) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        ?: if (isVideo) "video/*" else "image/*"
    val main = Handler(Looper.getMainLooper())

    fun launch(uri: Uri?) = main.post {
        if (uri == null) {
            Toast.makeText(context, "No other app can open this file", Toast.LENGTH_SHORT).show()
            return@post
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No other app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // the Video table first: some players only accept content://media/external/video/... URIs
    fun lookup(table: Uri): Uri? = runCatching {
        context.contentResolver.query(
            table, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA} = ?", arrayOf(file.absolutePath), null
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(table, c.getLong(0)) else null }
    }.getOrNull()
    val known = (if (isVideo) lookup(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) else null)
        ?: lookup(MediaStore.Files.getContentUri("external"))
    if (known != null) {
        launch(known)
    } else {
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri -> launch(uri) }
    }
}
