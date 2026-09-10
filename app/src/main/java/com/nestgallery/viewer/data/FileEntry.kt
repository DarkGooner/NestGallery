package com.nestgallery.viewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Deliberately stores only the URI + metadata.
 *
 * Creating 50,000 DocumentFile objects is surprisingly expensive and also
 * creates a large object graph. DocumentFile is now created only when a folder
 * is actually opened.
 */
data class FileEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val isVideo: Boolean
)

fun FileEntry.document(context: Context): DocumentFile? =
    DocumentFile.fromSingleUri(context, uri)

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi")

private fun String.extension(): String = lowercase().substringAfterLast('.', "")
fun String.isImageName(): Boolean = extension() in imageExtensions
fun String.isVideoName(): Boolean = extension() in videoExtensions
fun String.isMediaName(): Boolean = isImageName() || isVideoName()

fun DocumentFile.isImageFile(): Boolean = isFile && (name?.isImageName() == true)
fun DocumentFile.isVideoFile(): Boolean = isFile && (name?.isVideoName() == true)
fun DocumentFile.isMediaFile(): Boolean = isImageFile() || isVideoFile()

/**
 * Single-provider-call directory enumeration. No DocumentFile is created per
 * child, and no per-file length()/isFile()/name calls are made through SAF.
 */
fun DocumentFile.listEntriesFast(context: Context, hideAux: Boolean): List<FileEntry> {
    val resolver = context.contentResolver
    val treeUri = uri
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri)
    )
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    )
    val result = ArrayList<FileEntry>()

    resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

        while (cursor.moveToNext()) {
            val id = cursor.getString(idCol) ?: continue
            val name = cursor.getString(nameCol) ?: "unnamed"
            val mime = cursor.getString(mimeCol) ?: ""
            val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR

            if (!isDirectory && !name.isMediaName() &&
                !mime.startsWith("image/") && !mime.startsWith("video/")) continue

            if (hideAux && !isDirectory) {
                val lower = name.lowercase()
                if ("_thumb" in lower || "_locked" in lower) continue
            }

            val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else -1L
            result.add(
                FileEntry(
                    uri = childUri,
                    name = name,
                    isDirectory = isDirectory,
                    size = size,
                    isVideo = name.isVideoName() || mime.startsWith("video/")
                )
            )
        }
    }

    // Cache the normalized lowercase name in the comparator rather than
    // allocating it repeatedly while sorting tens of thousands of entries.
    result.sortWith(compareByDescending<FileEntry> { it.isDirectory }
        .thenBy { it.name.lowercase() })
    return result
}

/** Compatibility fallback for callers that still need the old API. */
fun DocumentFile.listEntries(hideAux: Boolean): List<FileEntry> =
    listFiles().asSequence()
        .filter { it.isDirectory || it.isMediaFile() }
        .filter {
            if (!hideAux || it.isDirectory) true
            else {
                val n = it.name?.lowercase() ?: ""
                !n.contains("_thumb") && !n.contains("_locked")
            }
        }
        .map {
            FileEntry(
                uri = it.uri,
                name = it.name ?: "unnamed",
                isDirectory = it.isDirectory,
                size = -1L,
                isVideo = it.isVideoFile()
            )
        }
        .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        .toList()

fun countMedia(folder: DocumentFile): Int = folder.listFiles().count { it.isMediaFile() }
