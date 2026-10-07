package com.nestgallery.viewer.data.face

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

sealed class ScanStatus {
    data class Idle(val stats: FaceDbStats) : ScanStatus()
    data class Scanning(
        val scannedCount: Int,
        val totalCount: Int,
        val facesFound: Int,
        val currentFileName: String,
        val photosPerSecond: Float = 0f
    ) : ScanStatus()
    data class Paused(val scannedCount: Int, val totalCount: Int, val facesFound: Int) : ScanStatus()
    /** Photos are done; faces are being grouped into people. */
    data class Grouping(val done: Int, val total: Int) : ScanStatus()
    data class Completed(val totalScanned: Int, val facesFound: Int) : ScanStatus()
}

/** A face found in the photo the user picked for "Find by face". [aligned] is the 112px crop shown in the UI. */
class QueryFace(val aligned: Bitmap, val embedding: FloatArray)

/**
 * Face scanning, grouping and search, all on-device.
 *
 * Scan pipeline (everything overlaps, bounded by channel back-pressure):
 *   decoder threads (IO)  ->  [decoded bitmaps]  ->  analysis workers (CPU: SCRFD + ArcFace)
 *   ->  [results]  ->  one writer (batched SQLite transaction + in-memory index)
 * Afterwards: incremental clustering, then cover thumbnails for the visible people.
 */
class FaceScannerManager private constructor(private val appContext: Context) {

    val database = FaceDatabase.getInstance(appContext)
    private val detector by lazy { ScrfdDetector(appContext) }
    private val embedder by lazy { ArcFaceEmbedder(appContext) }
    private val analyzer by lazy { FaceAnalyzer(detector, embedder) }
    private val clusterer = ImmichClusterer()

    /** In-memory index of every embedding; also what "Find by face" searches. */
    val store = FaceStore()
    private val storeMutex = Mutex()
    @Volatile private var storeLoaded = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private val paused = MutableStateFlow(false)

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle(FaceDbStats(0, 0, 0)))
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val thumbDir by lazy { File(appContext.filesDir, "face_thumbs").apply { mkdirs() } }

    init {
        // The previous version cached face crops in cacheDir (evictable, and name-colliding). Drop them.
        scope.launch(Dispatchers.IO) { File(appContext.cacheDir, "face_thumbs").deleteRecursively() }
    }

    private suspend fun ensureStoreLoaded() {
        if (storeLoaded) return
        storeMutex.withLock {
            if (storeLoaded) return
            withContext(Dispatchers.IO) {
                database.forEachFace { f -> store.add(f.id, f.path, f.personId, f.quality, f.embedding) }
            }
            storeLoaded = true
        }
    }

    // ---- scanning ------------------------------------------------------------------------------

    private class Decoded(val file: File, val lastModified: Long, val size: Long, val bitmap: Bitmap?)

    fun startScanForFiles(files: List<File>, folderPath: String) {
        if (scanJob?.isActive == true) {
            paused.value = false
            return
        }
        paused.value = false
        // Publish a non-idle status *before* the service starts, so it never observes a stale Idle and quits.
        _status.value = ScanStatus.Scanning(0, 0, 0, "Preparing…")
        startForegroundService()
        scanJob = scope.launch {
            var scanned = 0
            var faces = 0
            try {
                ensureStoreLoaded()
                val indexed = withContext(Dispatchers.IO) { database.loadIndexedFiles(folderPath) }
                val todo = withContext(Dispatchers.IO) {
                    files.filter { it.extension.lowercase() in imageExtensions }.filter { f ->
                        val s = indexed[f.absolutePath]
                        s == null || s.first != f.lastModified() || s.second != f.length()
                    }
                }
                val result = runPipeline(todo)
                scanned = result.first; faces = result.second
            } catch (e: CancellationException) {
                // fall through to grouping whatever was saved, then rethrow below
                withContext(NonCancellable) { groupPeople(folderPath, scanned, faces) }
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
            groupPeople(folderPath, scanned, faces)
            _status.value = ScanStatus.Completed(scanned, faces)
            delay(1500)
            _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
        }
    }

    /** @return (photos processed, faces found) */
    private suspend fun runPipeline(todo: List<File>): Pair<Int, Int> = coroutineScope {
        val total = todo.size
        val cores = Runtime.getRuntime().availableProcessors()
        val decoderCount = (cores / 4).coerceIn(1, 2)
        val analysisCount = (cores / 2).coerceIn(1, 4)
        val decoded = Channel<Decoded>(2)
        val results = Channel<ScannedFile>(64)
        val next = AtomicInteger(0)
        val startNs = System.nanoTime()
        var scanned = 0
        var facesFound = 0
        _status.value = ScanStatus.Scanning(0, total, 0, if (total > 0) "Starting…" else "Everything is already indexed")

        val decoders = List(decoderCount) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    if (paused.value) paused.first { !it }
                    val i = next.getAndIncrement()
                    if (i >= total) break
                    val f = todo[i]
                    decoded.send(Decoded(f, f.lastModified(), f.length(), FaceImageLoader.decodeFile(f)))
                }
            }
        }
        val workers = List(analysisCount) {
            launch(Dispatchers.Default) {
                for (d in decoded) {
                    val bmp = d.bitmap
                    val found: List<NewFace> = try {
                        if (bmp == null) emptyList()
                        else analyzer.analyze(bmp).map { NewFace(it.bounds, it.landmarksNorm, it.embedding, it.quality) }
                    } catch (e: Exception) { emptyList() } finally { bmp?.recycle() }
                    results.send(ScannedFile(d.file.absolutePath, d.lastModified, d.size, found))
                }
            }
        }
        launch { decoders.joinAll(); decoded.close() }
        launch { workers.joinAll(); results.close() }

        val batch = ArrayList<ScannedFile>()
        while (true) {
            val first = results.receiveCatching().getOrNull() ?: break
            batch.add(first)
            while (batch.size < 32) { batch.add(results.tryReceive().getOrNull() ?: break) }

            val saved = withContext(Dispatchers.IO) { database.saveBatch(batch) }
            for ((k, file) in batch.withIndex()) {
                store.removeFile(file.path)
                for ((j, face) in file.faces.withIndex()) store.add(saved[k][j], file.path, 0L, face.quality, face.embedding)
                facesFound += file.faces.size
            }
            scanned += batch.size
            val seconds = max(0.001f, (System.nanoTime() - startNs) / 1e9f)
            _status.value = if (paused.value) ScanStatus.Paused(scanned, total, facesFound)
            else ScanStatus.Scanning(scanned, total, facesFound, File(batch.last().path).name, scanned / seconds)
            batch.clear()
        }
        Pair(scanned, facesFound)
    }

    /**
     * Keeps the process alive (and the CPU awake) while the app is minimised or the screen is off.
     * If the OS refuses (e.g. restricted background start), the scan still runs - it just isn't protected.
     */
    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, FaceScanService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- grouping (clustering) -------------------------------------------------------------------

    private suspend fun groupPeople(folderPath: String, scanned: Int, faces: Int) = withContext(Dispatchers.Default) {
        if (!storeLoaded) return@withContext
        _status.value = ScanStatus.Grouping(0, 1)
        val named = database.namedPersonIds()
        // One-time: groups made by the previous (centroid-based) algorithm are rebuilt with the Immich-style one,
        // which is what repairs people that were split in two. Names the user typed are kept.
        if (database.getMeta(CLUSTER_ALGO_KEY) != CLUSTER_ALGO) {
            withContext(Dispatchers.IO) { database.unassignUnnamedPeople() }
            store.unassignExcept(named)
            database.setMeta(CLUSTER_ALGO_KEY, CLUSTER_ALGO)
        }
        val nextId = max(database.maxPersonId(), store.maxPersonId()) + 1
        var lastPublish = 0L
        val res = clusterer.run(store, named, nextId) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastPublish > 400) { lastPublish = now; _status.value = ScanStatus.Grouping(done, total) }
        }
        val changed = res.changedRows.map { store.faceId(it) to store.personOf(it) }
        val covers = res.coverRowByPerson.mapValues { store.faceId(it.value) }
        withContext(Dispatchers.IO) { database.applyClustering(changed, res.faceCountByPerson, covers) }

        _status.value = ScanStatus.Scanning(scanned, scanned, faces, "Preparing faces…")
        for ((personId, faceId) in covers) {
            if ((res.faceCountByPerson[personId] ?: 0) < FaceDatabase.MIN_FACES_TO_SHOW) continue
            ensureThumbnail(faceId)
        }
    }

    private fun ensureThumbnail(faceId: Long) {
        val existing = database.hasThumbnail(faceId)
        if (existing != null && File(existing).exists()) return
        val loc = database.getFaceLocation(faceId) ?: return
        val out = File(thumbDir, "f$faceId.jpg")
        if (FaceThumbnails.render(File(loc.path), loc.landmarksNorm, out)) database.setThumbnail(faceId, out.absolutePath)
    }

    fun pauseScan() {
        paused.value = true
        val current = _status.value
        if (current is ScanStatus.Scanning) _status.value = ScanStatus.Paused(current.scannedCount, current.totalCount, current.facesFound)
    }

    fun resumeScan() {
        paused.value = false
        val current = _status.value
        if (current is ScanStatus.Paused) {
            _status.value = ScanStatus.Scanning(current.scannedCount, current.totalCount, current.facesFound, "Resuming…")
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        paused.value = false
    }

    // ---- "Find by face" ------------------------------------------------------------------------------

    /** Detects faces in a user-picked photo, largest first. Falls back to the centre square for tight avatar crops. */
    suspend fun analyzeQueryImage(bitmap: Bitmap): List<QueryFace> = withContext(Dispatchers.Default) {
        val found = analyzer.analyze(bitmap, keepAligned = true, maxFaces = 12)
        if (found.isNotEmpty()) return@withContext found.map { QueryFace(it.aligned!!, it.embedding) }
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, FaceMath.ALIGN_SIZE, FaceMath.ALIGN_SIZE, true)
        listOf(QueryFace(scaled, embedder.embed(scaled)))
    }

    /** Exact search over the in-memory index; best face per photo, strongest first. */
    suspend fun searchFaces(embedding: FloatArray, folderPath: String, minSimilarity: Float, maxResults: Int = 300): List<FaceMatch> =
        withContext(Dispatchers.Default) {
            ensureStoreLoaded()
            val bestPerFile = LinkedHashMap<String, FaceHit>()
            for (h in store.search(embedding, minSimilarity, folderPath)) bestPerFile.putIfAbsent(store.pathOf(h.row), h)
            bestPerFile.entries.take(maxResults).map { (path, hit) -> FaceMatch(path, hit.similarity, null) }
        }

    companion object {
        private const val CLUSTER_ALGO_KEY = "cluster_algo"
        private const val CLUSTER_ALGO = "immich-v1"
        @Volatile private var INSTANCE: FaceScannerManager? = null
        fun getInstance(context: Context): FaceScannerManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: FaceScannerManager(context.applicationContext).also { INSTANCE = it } }
    }
}
