package com.nestgallery.viewer.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * A folder or media file, resolved directly from a SAF cursor row rather
 * than a DocumentFile. DocumentFile.listFiles() only fires one query, but
 * then every subsequent .name / .isDirectory / .length() access on each
 * child does its OWN separate ContentResolver round trip - for a folder
 * with a few thousand files that's a few thousand IPC calls, which is
 * exactly what was making big folders slow to open. Building this list
 * from a single batched cursor query avoids that entirely.
 */
data class DocEntry(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val isVideo: Boolean
)

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "3gp", "m4v", "avi")

private fun String.extension(): String = substringAfterLast('.', "").lowercase()

private fun isMediaName(name: String, mimeType: String?): Boolean {
    if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) return true
    val ext = name.extension()
    return ext in imageExtensions || ext in videoExtensions
}

private fun isVideoName(name: String, mimeType: String?): Boolean {
    if (mimeType?.startsWith("video/") == true) return true
    return name.extension() in videoExtensions
}

private val listProjection = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE
)

/**
 * Lists the folders and media files directly inside [parent] with a single
 * batched ContentResolver query, folders first, alphabetically. When
 * [hideAux] is true, files whose name contains "_thumb" or "_locked" are
 * skipped.
 */
fun listFolderFast(
    context: Context,
    treeUri: Uri,
    parent: DocEntry,
    hideAux: Boolean
): List<DocEntry> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
    val result = mutableListOf<DocEntry>()

    context.contentResolver.query(childrenUri, listProjection, null, null, null)?.use { cursor ->
        val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)

        while (cursor.moveToNext()) {
            val docId = cursor.getString(idIdx) ?: continue
            val name = cursor.getString(nameIdx) ?: continue
            val mime = cursor.getString(mimeIdx)
            val size = cursor.getLong(sizeIdx)
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR

            if (!isDir && !isMediaName(name, mime)) continue
            if (hideAux && !isDir) {
                val n = name.lowercase()
                if (n.contains("_thumb") || n.contains("_locked")) continue
            }

            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            result.add(DocEntry(uri, docId, name, isDir, size, isVideoName(name, mime)))
        }
    }

    return result.sortedWith(
        compareByDescending<DocEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
    )
}

/** Counts media files directly inside [folder], via one batched query. */
fun countMediaFast(context: Context, treeUri: Uri, folder: DocEntry): Int {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder.documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    var count = 0
    context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIdx) ?: continue
            val mime = cursor.getString(mimeIdx)
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR && isMediaName(name, mime)) count++
        }
    }
    return count
}

/** Builds the DocEntry representing the root of a picked SAF tree. */
fun rootDocEntry(context: Context, treeUri: Uri): DocEntry {
    val docId = DocumentsContract.getTreeDocumentId(treeUri)
    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    var name = treeUri.lastPathSegment?.substringAfterLast('/') ?: "Root"

    context.contentResolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
        }
    }

    return DocEntry(uri = uri, documentId = docId, name = name, isDirectory = true, size = 0, isVideo = false)
}
