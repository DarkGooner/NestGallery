package com.nestgallery.viewer.data

import android.os.Environment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * A folder or media file on shared storage, backed directly by a java.io.File.
 * With All Files Access granted, this is a plain filesystem read - no
 * cross-process SAF query at all.
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

/** True for dotfiles/dotfolders - the standard Unix/Android "hidden" convention. */
private fun isHiddenName(name: String) = name.startsWith(".")

/**
 * Lists the folders and media files directly inside [dir], folders first,
 * alphabetically. When [hideHidden] is true, dotfiles/dotfolders are
 * skipped (the standard "hidden item" convention).
 */
fun listFolderFast(dir: File, hideHidden: Boolean): List<DocEntry> {
    val children = dir.listFiles() ?: return emptyList()
    val result = ArrayList<DocEntry>(children.size)

    for (f in children) {
        val name = f.name
        val isDir = f.isDirectory
        if (hideHidden && isHiddenName(name)) continue
        if (!isDir && !isMediaName(name)) continue
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

private const val EXPLORE_BATCH_SIZE = 300

/**
 * Recursively (BFS, iterative - no recursion-depth risk on deep trees) walks
 * every folder under [root] and streams the media files it finds in small
 * batches, so the UI can start showing results immediately instead of
 * blocking until the entire tree - which can be tens of thousands of
 * entries - has been fully walked. Runs off the main thread.
 */
fun exploreMediaFlow(root: File, hideHidden: Boolean): Flow<List<DocEntry>> = flow {
    val queue = ArrayDeque<File>()
    queue.add(root)
    val batch = ArrayList<DocEntry>(EXPLORE_BATCH_SIZE)

    while (queue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val dir = queue.removeFirst()
        val children = dir.listFiles() ?: continue

        for (f in children) {
            currentCoroutineContext().ensureActive()
            val name = f.name
            if (hideHidden && isHiddenName(name)) continue

            if (f.isDirectory) {
                queue.addLast(f)
            } else if (isMediaName(name)) {
                batch.add(DocEntry(f, name, false, f.length(), isVideoName(name)))
                if (batch.size >= EXPLORE_BATCH_SIZE) {
                    emit(ArrayList(batch))
                    batch.clear()
                }
            }
        }
    }

    if (batch.isNotEmpty()) emit(ArrayList(batch))
}.flowOn(Dispatchers.IO)

/** [file]'s path relative to [root], not including the filename itself. */
fun relativeDirOf(file: File, root: File): String {
    val parent = file.parentFile ?: return ""
    val rel = parent.path.removePrefix(root.path).trim(File.separatorChar)
    return rel
}
