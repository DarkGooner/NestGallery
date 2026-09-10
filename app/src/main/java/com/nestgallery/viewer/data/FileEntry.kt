package com.nestgallery.viewer.data

import androidx.documentfile.provider.DocumentFile

/**
 * A single row in the browser: either a folder or a media file (image, gif,
 * or video), backed directly by its DocumentFile so no separate lookups are
 * needed later.
 */
data class FileEntry(
    val doc: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val isVideo: Boolean
)

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi")

private fun String?.extension(): String =
    this?.lowercase()?.substringAfterLast('.', "") ?: ""

fun DocumentFile.isImageFile(): Boolean = isFile && name.extension() in imageExtensions
fun DocumentFile.isVideoFile(): Boolean = isFile && name.extension() in videoExtensions
fun DocumentFile.isMediaFile(): Boolean = isImageFile() || isVideoFile()

/**
 * Lists the folders and media files directly inside this DocumentFile,
 * folders first, alphabetically. When [hideAux] is true, files whose name
 * contains "_thumb" or "_locked" are filtered out.
 */
fun DocumentFile.listEntries(hideAux: Boolean): List<FileEntry> {
    val children = listFiles().filter { it.isDirectory || it.isMediaFile() }
    val filtered = if (hideAux) {
        children.filter {
            val n = it.name?.lowercase() ?: ""
            it.isDirectory || (!n.contains("_thumb") && !n.contains("_locked"))
        }
    } else {
        children
    }

    return filtered
        .map { FileEntry(it, it.name ?: "unnamed", it.isDirectory, it.length(), it.isVideoFile()) }
        .sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
}

/** Counts images + videos directly inside [folder] (not recursive). */
fun countMedia(folder: DocumentFile): Int =
    folder.listFiles().count { it.isMediaFile() }
