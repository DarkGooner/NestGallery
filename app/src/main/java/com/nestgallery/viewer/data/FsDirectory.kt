package com.nestgallery.viewer.data

import android.os.Environment
import java.io.File

/**
 * A folder or media file on shared storage, backed directly by a java.io.File.
 * With All Files Access granted, this is a plain filesystem read - no
 * cross-process SAF query at all, which is even faster than the batched
 * ContentResolver approach used before.
 */
data class DocEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val isVideo: Boolean
)

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi")

private fun String.extension(): String = substringAfterLast('.', "").lowercase()
private fun isMediaName(name: String) = name.extension() in imageExtensions || name.extension() in videoExtensions
private fun isVideoName(name: String) = name.extension() in videoExtensions

/**
 * Lists the folders and media files directly inside [dir], folders first,
 * alphabetically. When [hideAux] is true, files whose name contains
 * "_thumb" or "_locked" are skipped.
 */
fun listFolderFast(dir: File, hideAux: Boolean): List<DocEntry> {
    val children = dir.listFiles() ?: return emptyList()
    val result = ArrayList<DocEntry>(children.size)

    for (f in children) {
        val name = f.name
        val isDir = f.isDirectory
        if (!isDir && !isMediaName(name)) continue
        if (hideAux && !isDir) {
            val n = name.lowercase()
            if (n.contains("_thumb") || n.contains("_locked")) continue
        }
        result.add(DocEntry(f, name, isDir, if (isDir) 0L else f.length(), isVideoName(name)))
    }

    return result.sortedWith(
        compareByDescending<DocEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
    )
}

/** Counts media files directly inside [dir] (not recursive). */
fun countMediaFast(dir: File): Int {
    val children = dir.listFiles() ?: return 0
    var count = 0
    for (f in children) {
        if (!f.isDirectory && isMediaName(f.name)) count++
    }
    return count
}

/** The root of accessible shared storage, once All Files Access is granted. */
fun storageRootEntry(): DocEntry {
    val root = Environment.getExternalStorageDirectory()
    return DocEntry(file = root, name = "Storage", isDirectory = true, size = 0L, isVideo = false)
}
