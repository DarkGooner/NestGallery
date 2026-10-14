package com.nestgallery.viewer.ui.files

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import com.nestgallery.viewer.data.rpa.RpaFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows [content] once [file] exists on disk: a file inside a Ren'Py archive is extracted first (with a spinner
 * meanwhile). For players that open the path themselves (VLC); photos go through Coil, which extracts on its own.
 */
@Composable
fun WhenOnDisk(file: File, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    if (!RpaFs.isVirtual(file)) { content(); return }
    val ready by produceState<Boolean?>(if (file.isFile) true else null, file) {
        if (value == null) value = withContext(Dispatchers.IO) { RpaFs.ensure(file) }
    }
    when (ready) {
        true -> content()
        false -> Box(modifier, contentAlignment = Alignment.Center) { Text("Couldn't read this file from its archive", color = Color.White) }
        null -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

/** Extracts [files] that live inside archives (off the main thread), so they can be shared or opened. */
suspend fun onDisk(files: List<File>): List<File> = withContext(Dispatchers.IO) {
    files.filter { f -> if (RpaFs.isVirtual(f)) RpaFs.ensure(f) && f.isFile else true }
}

/** Opens a non-media file (text, audio, a script ...) in another app, through the app's FileProvider. */
suspend fun openExternally(context: Context, file: File) {
    val f = onDisk(listOf(file)).firstOrNull()
    if (f == null) { Toast.makeText(context, "Couldn't read ${file.name}", Toast.LENGTH_SHORT).show(); return }
    val uri = try {
        FileProvider.getUriForFile(context, context.packageName + ".files", f)
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "This file can't be opened", Toast.LENGTH_SHORT).show(); return
    }
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, f.name))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open ${f.name}", Toast.LENGTH_SHORT).show()
    }
}
