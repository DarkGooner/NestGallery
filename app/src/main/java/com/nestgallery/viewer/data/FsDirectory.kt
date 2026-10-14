package com.nestgallery.viewer.data

import android.os.Environment
import com.nestgallery.viewer.data.rpa.RpaFs
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
    val isVideo: Boolean,
    /** Last-modified time (ms); only filled in by [listFolderFast], 0 elsewhere. */
    val modified: Long = 0L,
    /** False for other files, listed only when "Show non-media files" is on. */
    val isMedia: Boolean = true,
    /** A Ren'Py archive (.rpa) shown as a folder ([isDirectory] is true; [file] is the archive itself). */
    val isArchive: Boolean = false
)

/** How the file explorer orders a folder (folders always come first). */
enum class SortBy(val label: String) { NAME("Name"), DATE("Date modified"), SIZE("Size") }

private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
/**
 * Every common container LibVLC plays (the viewer uses VLC, so these all open): MP4 family, Matroska / WebM, AVI /
 * DivX, Windows Media, Flash, MPEG program / transport streams (TV and camcorder recordings), DVD VOB, Ogg, RealMedia,
 * DV / MXF and raw streams.
 */
private val videoExtensions = setOf(
    "mp4", "m4v", "mov", "qt", "3gp", "3g2", "3gpp", "f4v",
    "mkv", "mk3d", "webm",
    "avi", "divx", "xvid",
    "wmv", "asf",
    "flv",
    "ts", "m2ts", "mts", "m2t", "tp", "trp",
    "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv", "vob", "dat",
    "ogv", "ogm",
    "rm", "rmvb",
    "dv", "mxf", "nut", "y4m", "ivf", "amv",
    "h264", "264", "h265", "265", "hevc"
)

private fun String.extension(): String = substringAfterLast('.', "").lowercase()
private fun isMediaName(name: String) = name.extension() in imageExtensions || name.extension() in videoExtensions
private fun isVideoName(name: String) = name.extension() in videoExtensions
fun isMediaPath(path: String) = isMediaName(path.substringAfterLast('/'))

/** True for dotfiles/dotfolders - the standard Unix/Android "hidden" convention. */
private fun isHiddenName(name: String) = name.startsWith(".")

private val chunkRegex = Regex("\\d+|\\D+")

/**
 * "Natural" filename comparison: runs of digits compare by numeric value
 * rather than character-by-character, so "day2" sorts before "day11" (plain
 * string comparison would put "day11" first, since '1' < '2'). Handles
 * names with several numeric runs the same way, e.g. "day1_1_0" before
 * "day11_1_0", and arbitrarily long digit runs without integer overflow by
 * comparing them as strings once leading zeros are stripped and lengths
 * are equalized.
 */
fun naturalCompare(a: String, b: String): Int {
    val aChunks = chunkRegex.findAll(a).iterator()
    val bChunks = chunkRegex.findAll(b).iterator()

    while (aChunks.hasNext() && bChunks.hasNext()) {
        val ac = aChunks.next().value
        val bc = bChunks.next().value

        val cmp = if (ac[0].isDigit() && bc[0].isDigit()) {
            val aTrimmed = ac.trimStart('0').ifEmpty { "0" }
            val bTrimmed = bc.trimStart('0').ifEmpty { "0" }
            if (aTrimmed.length != bTrimmed.length) {
                aTrimmed.length - bTrimmed.length
            } else {
                aTrimmed.compareTo(bTrimmed)
            }
        } else {
            ac.compareTo(bc, ignoreCase = true)
        }
        if (cmp != 0) return cmp
    }

    return when {
        aChunks.hasNext() -> 1
        bChunks.hasNext() -> -1
        else -> 0
    }
}

private val byNaturalName = Comparator<DocEntry> { a, b -> naturalCompare(a.name, b.name) }

/**
 * What is directly inside [dir]: a folder on disk, a Ren'Py archive (.rpa, read as a folder through [RpaFs]) or a
 * folder inside one. A valid .rpa on disk comes back as a folder. Null if [dir] can't be listed.
 */
private fun childrenOf(dir: File, withModified: Boolean): List<RpaFs.Node>? {
    RpaFs.list(dir)?.let { return it }
    val files = dir.listFiles() ?: return null
    return files.map { f ->
        val isDir = f.isDirectory
        val archive = !isDir && RpaFs.enabled && RpaFs.isArchiveName(f.name) && RpaFs.isArchive(f)
        RpaFs.Node(f, f.name, isDir || archive, if (isDir) 0L else f.length(), if (withModified) f.lastModified() else 0L, archive)
    }
}

/**
 * Lists the folders and media files directly inside [dir], folders first, then by [sort] (natural name order by
 * default; folders have no size, so a size sort orders them by name). When [hideHidden] is true, dotfiles/dotfolders
 * are skipped (the standard "hidden item" convention); with [showAll], files that are not photos or videos are listed
 * too ([DocEntry.isMedia] false). Ren'Py archives list as folders, and so do the folders inside them.
 */
fun listFolderFast(
    dir: File,
    hideHidden: Boolean,
    sort: SortBy = SortBy.NAME,
    descending: Boolean = false,
    showAll: Boolean = false
): List<DocEntry> {
    val children = childrenOf(dir, withModified = sort == SortBy.DATE) ?: return emptyList()
    val result = ArrayList<DocEntry>(children.size)

    for (c in children) {
        val name = c.name
        if (hideHidden && isHiddenName(name)) continue
        val media = !c.isDirectory && isMediaName(name)
        if (!c.isDirectory && !media && !showAll) continue
        result.add(DocEntry(c.file, name, c.isDirectory, if (c.isDirectory && !c.isArchive) 0L else c.size,
            media && isVideoName(name), c.modified, isMedia = c.isDirectory || media, isArchive = c.isArchive))
    }

    val order: Comparator<DocEntry> = when (sort) {
        SortBy.NAME -> byNaturalName
        SortBy.DATE -> compareBy<DocEntry> { it.modified }.then(byNaturalName)
        SortBy.SIZE -> compareBy<DocEntry> { it.size }.then(byNaturalName)
    }
    return result.sortedWith(
        compareByDescending<DocEntry> { it.isDirectory }.then(if (descending) order.reversed() else order)
    )
}

/** Counts media files directly inside [dir] (not recursive; an archive or a folder in one counts its own). */
fun countMediaFast(dir: File): Int {
    RpaFs.list(dir)?.let { nodes -> return nodes.count { !it.isDirectory && isMediaName(it.name) } }
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
        // archives are walked like folders (their members are listed from the index, nothing is extracted)
        val children = (childrenOf(dir, withModified = false) ?: continue).sortedWith { x, y -> naturalCompare(x.name, y.name) }

        for (c in children) {
            currentCoroutineContext().ensureActive()
            val name = c.name
            if (hideHidden && isHiddenName(name)) continue

            if (c.isDirectory) {
                queue.addLast(c.file)
            } else if (isMediaName(name)) {
                batch.add(DocEntry(c.file, name, false, c.size, isVideoName(name)))
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
