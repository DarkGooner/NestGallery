package com.nestgallery.viewer.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** One photo or video as the gallery view sees it: the file plus what MediaStore knows about it. */
class MediaItem(
    val id: Long,
    /** content://media/... URI, used for sharing and deleting. */
    val uri: Uri,
    val entry: DocEntry,
    /** When it was taken (EXIF / video metadata), else when the file was last modified. */
    val dateMs: Long,
    /** [dateMs]'s local calendar day, for the timeline's day headers. */
    val epochDay: Long,
    /** The folder it is in (its path): what a typical gallery calls an album. */
    val albumId: String,
    val albumName: String,
    val durationMs: Long
)

class Album(val id: String, val name: String, val items: List<MediaItem>) {
    val cover: MediaItem get() = items.first()
}

/**
 * Every photo and video MediaStore has indexed, newest first, for the gallery view (the file explorer walks folders
 * itself instead). This is what a typical gallery app shows: MediaStore skips folders with a `.nomedia` file and some
 * formats Android doesn't recognise (e.g. WMV), which the file explorer still lists. Reloads by itself when MediaStore
 * changes (new photos, deletions by other apps).
 */
class MediaLibrary private constructor(private val appContext: Context) {

    private val _items = MutableStateFlow<List<MediaItem>?>(null)
    /** Null until the first load finishes. */
    val items: StateFlow<List<MediaItem>?> = _items.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var observing = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = reload(debounceMs = 1500)
    }

    /** Loads the library if it isn't loaded yet and starts following MediaStore changes. */
    fun start() {
        if (!observing) {
            observing = true
            appContext.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        }
        if (_items.value == null && loadJob == null) reload()
    }

    /** Queries MediaStore again; a burst of change notifications (a camera burst, a copy) becomes one reload. */
    fun reload(debounceMs: Long = 0) {
        loadJob?.cancel()
        loadJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            _items.value = query()
        }
    }

    /** What a pull to refresh changed. */
    class RefreshResult(val added: Int, val removed: Int, val total: Int)

    /**
     * Pull to refresh: queries MediaStore again now, drops rows whose file is gone (and has MediaStore rescan those
     * paths so the rows go away), and asks the media scanner to re-check every album folder for files other apps
     * added without telling it. That folder scan runs in the background; whatever it finds arrives through the
     * change observer a little later.
     */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        loadJob?.cancel()
        val before = _items.value.orEmpty().mapTo(HashSet()) { it.id }
        val (present, missing) = query().partition { it.entry.file.exists() }
        _items.value = present
        if (missing.isNotEmpty()) {
            MediaScannerConnection.scanFile(appContext, missing.map { it.entry.file.path }.toTypedArray(), null, null)
        }
        GalleryCache.clearAll()
        // each folder once: one nested in another album's folder is covered by that folder's (recursive) scan
        val folders = present.mapTo(sortedSetOf()) { it.albumId }
        val roots = folders.filter { dir -> folders.none { other -> other != dir && dir.startsWith("$other/") } }
        if (roots.isNotEmpty()) MediaScannerConnection.scanFile(appContext, roots.toTypedArray(), null, null)
        val now = present.mapTo(HashSet()) { it.id }
        RefreshResult(added = now.count { it !in before }, removed = before.count { it !in now }, total = present.size)
    }

    private fun query(): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val full = arrayOf(COL_ID, COL_DATA, COL_NAME, COL_SIZE, COL_TYPE, COL_MODIFIED, COL_TAKEN, COL_DURATION)
        // datetaken / duration are in the files table on every version we know of; if a phone disagrees, do without
        val cursor = try {
            appContext.contentResolver.query(uri, full, selection, null, null)
        } catch (e: IllegalArgumentException) {
            appContext.contentResolver.query(uri, full.copyOfRange(0, 6), selection, null, null)
        } ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val out = ArrayList<MediaItem>(cursor.count)
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(COL_ID)
            val iData = c.getColumnIndexOrThrow(COL_DATA)
            val iName = c.getColumnIndexOrThrow(COL_NAME)
            val iSize = c.getColumnIndexOrThrow(COL_SIZE)
            val iType = c.getColumnIndexOrThrow(COL_TYPE)
            val iModified = c.getColumnIndexOrThrow(COL_MODIFIED)
            val iTaken = c.getColumnIndex(COL_TAKEN)
            val iDuration = c.getColumnIndex(COL_DURATION)
            while (c.moveToNext()) {
                val path = c.getString(iData) ?: continue
                val file = File(path)
                val parent = file.parentFile ?: continue
                val id = c.getLong(iId)
                val video = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val taken = if (iTaken >= 0) c.getLong(iTaken) else 0L
                val date = if (taken > 0) taken else c.getLong(iModified) * 1000
                val contentUri = ContentUris.withAppendedId(
                    if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                val name = c.getString(iName) ?: file.name
                out += MediaItem(
                    id = id,
                    uri = contentUri,
                    entry = DocEntry(file, name, false, c.getLong(iSize), video),
                    dateMs = date,
                    epochDay = Instant.ofEpochMilli(date).atZone(zone).toLocalDate().toEpochDay(),
                    albumId = parent.path,
                    albumName = parent.name.ifEmpty { "Storage" },
                    durationMs = if (iDuration >= 0) c.getLong(iDuration) else 0L
                )
            }
        }
        out.sortWith(compareByDescending<MediaItem> { it.dateMs }.thenByDescending { it.id })
        return out
    }

    /**
     * Permanently deletes [items] (the file, then its MediaStore row). All Files Access lets the app delete the file
     * itself, so no per-file system prompt is needed. Returns how many were deleted.
     */
    suspend fun delete(items: Collection<MediaItem>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        val stale = ArrayList<String>()
        for (item in items) {
            val file = item.entry.file
            val gone = !file.exists() || file.delete()
            if (!gone) continue
            deleted++
            val rowRemoved = try {
                appContext.contentResolver.delete(item.uri, null, null) > 0
            } catch (e: Exception) { false }
            if (!rowRemoved) stale += file.path
        }
        // rows MediaStore wouldn't let us remove directly disappear once it rescans the (now missing) files
        if (stale.isNotEmpty()) MediaScannerConnection.scanFile(appContext, stale.toTypedArray(), null, null)
        GalleryCache.clearAll()
        val ids = items.mapTo(HashSet()) { it.id }
        _items.value = _items.value?.filter { it.id !in ids }
        reload(debounceMs = 1000)
        deleted
    }

    /**
     * [item]'s file no longer exists although MediaStore still lists it (deleted behind its back): hide it now and
     * have MediaStore rescan the path, which removes the stale row.
     */
    fun forgetMissing(item: MediaItem) {
        _items.value = _items.value?.filter { it.id != item.id }
        MediaScannerConnection.scanFile(appContext, arrayOf(item.entry.file.path), null, null)
    }

    companion object {
        private const val COL_ID = MediaStore.Files.FileColumns._ID
        @Suppress("DEPRECATION") private const val COL_DATA = MediaStore.Files.FileColumns.DATA
        private const val COL_NAME = MediaStore.Files.FileColumns.DISPLAY_NAME
        private const val COL_SIZE = MediaStore.Files.FileColumns.SIZE
        private const val COL_TYPE = MediaStore.Files.FileColumns.MEDIA_TYPE
        private const val COL_MODIFIED = MediaStore.Files.FileColumns.DATE_MODIFIED
        private const val COL_TAKEN = "datetaken"
        private const val COL_DURATION = "duration"

        @Volatile private var instance: MediaLibrary? = null
        fun getInstance(context: Context): MediaLibrary =
            instance ?: synchronized(this) { instance ?: MediaLibrary(context.applicationContext).also { instance = it } }

        /**
         * Folders as albums, the usual way round: Camera and Screenshots first, then by their newest item.
         * [items] must be newest first (as [MediaLibrary.items] is), so each album's cover is its newest item.
         */
        fun albums(items: List<MediaItem>): List<Album> {
            val groups = LinkedHashMap<String, ArrayList<MediaItem>>()
            for (item in items) groups.getOrPut(item.albumId) { ArrayList() }.add(item)
            fun rank(a: Album) = when {
                a.id.endsWith("/DCIM/Camera", ignoreCase = true) -> 0
                a.name.equals("Camera", ignoreCase = true) -> 1
                a.name.equals("Screenshots", ignoreCase = true) -> 2
                else -> 3
            }
            return groups.map { (id, list) -> Album(id, list.first().albumName, list) }
                .sortedWith(compareBy<Album> { rank(it) }.thenByDescending { it.cover.dateMs })
        }

        /** Id of the "Videos" collection on the Albums tab (not a folder). */
        const val VIDEOS_ALBUM = "::videos"
    }
}
