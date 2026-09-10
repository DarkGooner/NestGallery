package com.nestgallery.viewer.data

import androidx.documentfile.provider.DocumentFile

/**
 * A single row in the browser: either a folder or an image file, backed
 * directly by its DocumentFile so no separate lookups are needed later.
 */
data class FileEntry(
    val doc: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

fun DocumentFile.isImageFile(): Boolean {
    val n = name?.lowercase() ?: return false
    val ext = n.substringAfterLast('.', "")
    return isFile && ext in imageExtensions
}

/**
 * Lists the folders and image files directly inside this DocumentFile,
 * folders first, alphabetically. When [hideAux] is true, files whose name
 * contains "_thumb" or "_locked" are filtered out.
 */
fun DocumentFile.listEntries(hideAux: Boolean): List<FileEntry> {
    val children = listFiles().filter { it.isDirectory || it.isImageFile() }
    val filtered = if (hideAux) {
        children.filter {
            val n = it.name?.lowercase() ?: ""
            it.isDirectory || (!n.contains("_thumb") && !n.contains("_locked"))
        }
    } else {
        children
    }

    return filtered
        .map { FileEntry(it, it.name ?: "unnamed", it.isDirectory, it.length()) }
        .sortedWith(
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
}

fun countImages(folder: DocumentFile): Int =
    folder.listFiles().count { it.isImageFile() }
