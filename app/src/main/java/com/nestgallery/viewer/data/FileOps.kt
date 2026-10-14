package com.nestgallery.viewer.data

import android.content.Context
import android.media.MediaScannerConnection
import com.nestgallery.viewer.data.face.FaceScannerManager
import com.nestgallery.viewer.data.rpa.RpaFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** A copy / move / delete in progress, for the progress dialog. */
data class FileOpProgress(val title: String, val done: Int, val total: Int, val current: String)

/** Files put on the clipboard by Copy / Cut, pasted with "Paste here" in the file explorer (like ZArchiver). */
data class FileClip(val files: List<File>, val cut: Boolean)

/**
 * Create / rename / copy / move / delete for the file explorer, the gallery and the face screens. Never overwrites:
 * a name that is taken gets " (1)", " (2)" ... Copies keep the original's modified time (the face index and the
 * gallery's dates rely on it).
 *
 * After every change: MediaStore is told about old and new paths (so the gallery view follows), cached folder listings
 * are dropped and [revision] is bumped (open screens reload), and the face index follows moved / deleted photos, so
 * people keep their photos after a move instead of losing them.
 */
class FileOps private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var cancelled = false

    private val _progress = MutableStateFlow<FileOpProgress?>(null)
    /** Non-null while a copy / move / delete runs. */
    val progress: StateFlow<FileOpProgress?> = _progress.asStateFlow()

    private val _revision = MutableStateFlow(0)
    /** Bumped after every change to files on disk; listings key their reloads on it. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-line results ("Moved 12 items") for a toast. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _clip = MutableStateFlow<FileClip?>(null)
    val clip: StateFlow<FileClip?> = _clip.asStateFlow()

    fun setClip(files: List<File>, cut: Boolean) {
        if (files.isNotEmpty()) {
            _clip.value = FileClip(files, cut)
            say("${files.size.items()} ${if (cut) "cut" else "copied"}. Open a folder and tap Paste")
        }
    }

    fun clearClip() { _clip.value = null }

    fun cancel() { cancelled = true }

    val busy: Boolean get() = job?.isActive == true

    // ---- quick operations (no progress dialog) ---------------------------------------------------------------------

    /** @return null on success, else why it failed */
    suspend fun createFolder(parent: File, name: String): String? = withContext(Dispatchers.IO) {
        nameError(name)?.let { return@withContext it }
        if (insideArchive(parent)) return@withContext ARCHIVE_READ_ONLY
        val dir = File(parent, name.trim())
        when {
            dir.exists() -> "\"${dir.name}\" already exists"
            !dir.mkdirs() -> "Couldn't create \"${dir.name}\""
            else -> { changed(); null }
        }
    }

    /** @return null on success, else why it failed */
    suspend fun rename(file: File, newName: String): String? = withContext(Dispatchers.IO) {
        nameError(newName)?.let { return@withContext it }
        if (RpaFs.isReadOnly(file)) return@withContext ARCHIVE_READ_ONLY
        val target = File(file.parentFile, newName.trim())
        when {
            target.name == file.name -> null
            // a case-only change is allowed: on Android's case-insensitive storage it "exists" already
            target.exists() && !target.name.equals(file.name, ignoreCase = true) -> "\"${target.name}\" already exists"
            !file.renameTo(target) -> "Couldn't rename \"${file.name}\""
            else -> {
                afterMoves(listOf(file.path to target.path), oldPaths = walk(target).map { it.path.replaceFirst(target.path, file.path) }, newRoots = listOf(target))
                null
            }
        }
    }

    // ---- long operations (progress dialog, one at a time) ----------------------------------------------------------

    fun copy(files: List<File>, dest: File) = launch("Copying") { runTransfer(files, dest, move = false) }

    fun move(files: List<File>, dest: File) = launch("Moving") { runTransfer(files, dest, move = true) }

    fun delete(files: List<File>) = launch("Deleting") { runDelete(files) }

    /** Pastes the clipboard into [dest]: copies, or moves when it was cut (the clipboard is then emptied). */
    fun paste(dest: File) {
        val c = _clip.value ?: return
        if (c.cut) { move(c.files, dest); _clip.value = null } else copy(c.files, dest)
    }

    private fun launch(title: String, block: suspend () -> String) {
        if (busy) { say("Another file operation is still running"); return }
        cancelled = false
        _progress.value = FileOpProgress(title, 0, 0, "")
        job = scope.launch {
            val message = try { block() } catch (e: Exception) { "Failed: ${e.message ?: e.javaClass.simpleName}" }
            _progress.value = null
            say(message)
        }
    }

    private suspend fun runTransfer(files: List<File>, dest: File, move: Boolean): String {
        val verb = if (move) "Moving" else "Copying"
        if (insideArchive(dest)) return "Can't add files to an archive"
        if (move && files.any { RpaFs.isReadOnly(it) }) return "$ARCHIVE_READ_ONLY: copy the files instead"
        // files inside a Ren'Py archive are extracted first, then copied like any other file
        for (f in files) if (RpaFs.isVirtual(f)) {
            _progress.value = FileOpProgress("Extracting", 0, 0, f.name)
            RpaFs.ensureTree(f)
        }
        val sources = files.filter { it.exists() }.distinctBy { it.absolutePath }
        val destCanon = dest.canonicalPath
        // a folder can't go into itself or one of its own subfolders
        sources.firstOrNull { it.isDirectory && (destCanon == it.canonicalPath || destCanon.startsWith(it.canonicalPath + "/")) }?.let {
            return "Can't put \"${it.name}\" inside itself"
        }
        if (!dest.isDirectory && !dest.mkdirs()) return "Couldn't open ${dest.name}"
        val total = sources.sumOf { countFiles(it) }
        var done = 0
        var failed = 0
        var skipped = 0
        val moves = ArrayList<Pair<String, String>>()
        val oldPaths = ArrayList<String>()
        val newRoots = ArrayList<File>()

        fun step(name: String) { _progress.value = FileOpProgress(verb, done, total, name) }

        for (src in sources) {
            if (cancelled) break
            if (move && src.parentFile?.canonicalPath == destCanon) { skipped++; done += countFiles(src); continue }
            val target = freeName(dest, src.name)
            step(src.name)
            if (move) {
                val before = walk(src).map { it.path }
                if (src.renameTo(target)) {
                    // same storage volume: a rename, instant whatever the size
                    moves += src.path to target.path; oldPaths += before; newRoots += target
                    done += before.size.coerceAtLeast(1)
                    step(src.name)
                    continue
                }
            }
            // copy (and for a move across volumes, delete the original once every file arrived)
            var ok = true
            for ((from, to) in plan(src, target)) {
                if (cancelled) { ok = false; break }
                if (from.isDirectory) { if (!to.isDirectory && !to.mkdirs()) ok = false; continue }
                step(from.name)
                if (copyFile(from, to)) done++ else { ok = false; failed++; done++ }
            }
            if (target.exists()) newRoots += target
            if (move && ok) {
                val before = walk(src).map { it.path }
                if (src.deleteRecursively()) { moves += src.path to target.path; oldPaths += before } else failed++
            }
        }
        afterMoves(moves, oldPaths, newRoots)
        val n = sources.size - skipped
        val what = if (move) "Moved" else "Copied"
        return when {
            cancelled -> "$verb cancelled"
            failed > 0 -> "$what to ${dest.name}, $failed ${if (failed == 1) "file" else "files"} failed"
            n == 0 -> "Already in ${dest.name}"
            else -> "$what ${n.items()} to ${dest.name}"
        }
    }

    private suspend fun runDelete(files: List<File>): String {
        if (files.any { RpaFs.isReadOnly(it) }) return ARCHIVE_READ_ONLY
        val sources = files.filter { it.exists() }.distinctBy { it.absolutePath }
        val total = sources.size
        var done = 0
        var failed = 0
        val gone = ArrayList<String>()
        val goneFiles = ArrayList<String>()
        for (f in sources) {
            if (cancelled) break
            _progress.value = FileOpProgress("Deleting", done, total, f.name)
            val inside = walk(f).map { it.path }
            if (f.deleteRecursively()) { gone += f.path; goneFiles += inside } else failed++
            done++
        }
        if (gone.isNotEmpty()) {
            MediaLibrary.getInstance(appContext).dropPaths(gone)
            scan(goneFiles)
            FaceScannerManager.getInstance(appContext).onPathsDeleted(gone)
        }
        changed()
        return when {
            cancelled -> "Deleted ${gone.size.items()}, then cancelled"
            failed > 0 -> "Deleted ${gone.size.items()}, couldn't delete $failed"
            else -> "Deleted ${gone.size.items()}"
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private suspend fun afterMoves(moves: List<Pair<String, String>>, oldPaths: List<String>, newRoots: List<File>) {
        if (moves.isNotEmpty()) {
            MediaLibrary.getInstance(appContext).dropPaths(moves.map { it.first })
            FaceScannerManager.getInstance(appContext).onPathsMoved(moves)
        }
        scan(oldPaths + newRoots.flatMap { walk(it) }.map { it.path })
        changed()
    }

    private fun changed() {
        GalleryCache.clearListings()
        _revision.value++
    }

    /** MediaStore re-checks [paths]: new files get rows, rows of missing files go away. */
    private fun scan(paths: List<String>) {
        val media = paths.filter { isMediaPath(it) }
        if (media.isNotEmpty()) MediaScannerConnection.scanFile(appContext, media.toTypedArray(), null, null)
    }

    private fun say(message: String) { _messages.tryEmit(message) }

    companion object {
        @Volatile private var instance: FileOps? = null
        fun getInstance(context: Context): FileOps =
            instance ?: synchronized(this) { instance ?: FileOps(context.applicationContext).also { instance = it } }

        const val ARCHIVE_READ_ONLY = "Files inside an archive can't be changed"

        /** A Ren'Py archive opened as a folder, or a folder inside one: nothing can be added there. */
        fun insideArchive(dir: File) = RpaFs.isVirtual(dir) || (RpaFs.isArchiveName(dir.name) && dir.isFile)

        /** Why [name] can't be a file or folder name, or null if it can. */
        fun nameError(name: String): String? {
            val n = name.trim()
            return when {
                n.isEmpty() -> "Enter a name"
                n == "." || n == ".." -> "That name isn't allowed"
                n.any { it == '/' || it == '\u0000' } -> "A name can't contain /"
                n.toByteArray().size > 255 -> "That name is too long"
                else -> null
            }
        }

        /** [name] in [dir], or "name (1).ext", "name (2).ext" ... if it is taken. */
        fun freeName(dir: File, name: String): File {
            var f = File(dir, name)
            if (!f.exists()) return f
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot)
            val ext = name.substring(dot)
            var i = 1
            while (f.exists()) f = File(dir, "$base ($i)$ext").also { i++ }
            return f
        }

        /** [root] and everything under it, files only. */
        fun walk(root: File): List<File> = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.toList() else listOf(root)

        private fun countFiles(f: File): Int = if (f.isDirectory) f.walkTopDown().count { it.isFile } else 1

        /** (source, target) for [src] and everything under it, parents before children. */
        private fun plan(src: File, target: File): Sequence<Pair<File, File>> =
            if (!src.isDirectory) sequenceOf(src to target)
            else src.walkTopDown().map { it to File(target, it.relativeTo(src).path) }

        private fun copyFile(from: File, to: File): Boolean = try {
            to.parentFile?.mkdirs()
            FileInputStream(from).use { input -> FileOutputStream(to).use { input.copyTo(it, 256 * 1024) } }
            to.setLastModified(from.lastModified())
            true
        } catch (e: Exception) {
            to.delete()
            false
        }

        private fun Int.items() = if (this == 1) "1 item" else "$this items"
    }
}
