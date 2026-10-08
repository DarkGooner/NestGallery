package com.nestgallery.viewer.ui

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.DocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing

/** The viewer's "i" sheet: file details plus whatever the image's EXIF / the video's container reports. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(entry: DocEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var details by remember(entry.file) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(entry.file) {
        details = withContext(Dispatchers.IO) { mediaDetails(entry, Formatter.formatFileSize(context, entry.file.length())) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom) }
    ) {
        SelectionContainer {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
            ) {
                Text("Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                for ((label, value) in details ?: listOf("Name" to entry.name, "Path" to entry.file.absolutePath)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

private fun mediaDetails(entry: DocEntry, sizeText: String): List<Pair<String, String>> {
    val f = entry.file
    val out = ArrayList<Pair<String, String>>()
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
    out += "Name" to f.name
    out += "Path" to f.absolutePath
    out += "Size" to "$sizeText (${"%,d".format(f.length())} bytes)"
    out += "Modified" to dateFormat.format(Date(f.lastModified()))
    runCatching { if (entry.isVideo) videoDetails(f.absolutePath, out) else imageDetails(f.absolutePath, out) }
    return out
}

private fun imageDetails(path: String, out: MutableList<Pair<String, String>>) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val exif = runCatching { ExifInterface(path) }.getOrNull()
    val rotation = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        // shown as displayed: a portrait photo stored sideways with an EXIF rotation reads as portrait
        val (w, h) = if (rotation % 180 == 90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        out += "Resolution" to "$w × $h (${"%.1f".format(Locale.US, w.toLong() * h / 1_000_000f)} MP)"
    }
    bounds.outMimeType?.let { out += "Type" to it }
    if (exif == null) return
    if (rotation != 0) out += "Rotation" to "$rotation°"
    exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { out += "Taken" to it }
    val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
        .map { it.trim() }.filter { it.isNotEmpty() }
    if (camera.isNotEmpty()) out += "Camera" to camera.joinToString(" ")
    exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { out += "Aperture" to "f/%.1f".format(Locale.US, it) }
    exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
        out += "Exposure" to if (it in 0.0..0.5) "1/${Math.round(1 / it)} s" else "%.1f s".format(Locale.US, it)
    }
    exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { out += "ISO" to it }
    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let {
        out += "Focal length" to "%.1f mm".format(Locale.US, it)
    }
    exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += "Software" to it }
    val latLong = FloatArray(2)
    if (exif.getLatLong(latLong)) out += "Location" to "%.6f, %.6f".format(Locale.US, latLong[0], latLong[1])
}

private fun videoDetails(path: String, out: MutableList<Pair<String, String>>) {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(path)
        fun meta(key: Int) = r.extractMetadata(key)
        val w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w != null && h != null && w > 0 && h > 0) {
            out += "Resolution" to if (rotation % 180 == 90) "$h × $w" else "$w × $h"
        }
        meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
            val s = ms / 1000
            out += "Duration" to if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += "Bitrate" to "%.1f Mbps".format(Locale.US, it / 1_000_000f) }
        meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { out += "Type" to it }
        if (rotation != 0) out += "Rotation" to "$rotation°"
        meta(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { out += "Recorded" to it }
        meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { out += "Location" to it }
    } finally {
        r.release()
    }
}
