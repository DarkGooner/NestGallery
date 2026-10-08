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

/** A person the "Find by face" query probably is: [similarity] = average cosine to that person's faces. */
class PersonMatch(val person: PersonEntity, val similarity: Float)

/** "Same person?": two people whose faces match well on average but were not merged automatically. */
class MergeSuggestion(val a: PersonEntity, val b: PersonEntity, val similarity: Float)

/**
 * Face scanning, grouping and search, all on-device.
 *
 * Scan pipeline (everything overlaps, bounded by channel back-pressure):
 *   decoder threads (IO)  ->  [decoded bitmaps]  ->  analysis workers (CPU: SCRFD + ArcFace)
 *   ->  [results]  ->  one writer (batched SQLite transaction + in-memory index)
 * Afterwards: clustering (people the user curated are kept, the rest regrouped), then cover thumbnails.
 */
class FaceScannerManager private constructor(private val appContext: Context) {

    val database = FaceDatabase.getInstance(appContext)
    private val detector by lazy { ScrfdDetector(appContext) }
    private val embedder by lazy { ArcFaceEmbedder(appContext) }
    private val analyzer by lazy { FaceAnalyzer(detector, embedder) }
    private val clusterConfig = ClusterConfig()
    private val clusterer = PeopleClusterer(clusterConfig)
    /** ONNX Runtime kNN kernel; the plain-Kotlin search is the (slower) fallback if it can't be loaded. */
    private val neighborFinder: NeighborFinder by lazy {
        try { OnnxKnn(appContext) } catch (e: Throwable) { e.printStackTrace(); BruteForceNeighborFinder() }
    }

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
                withContext(NonCancellable) { if (needsGrouping(scanned)) groupPeople(folderPath, scanned, faces) }
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (needsGrouping(scanned)) groupPeople(folderPath, scanned, faces)
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

    private suspend fun groupPeople(folderPath: String, scanned: Int, faces: Int, rebuild: Boolean = false) = withContext(Dispatchers.Default) {
        if (!storeLoaded) return@withContext
        _status.value = ScanStatus.Grouping(0, 1000)
        val named = database.namedPersonIds()
        // "Regroup people" (or groups made by an older algorithm): also drop the user's merges / corrections on
        // unnamed people. Named people keep their faces.
        if (rebuild || database.getMeta(CLUSTER_ALGO_KEY) != CLUSTER_ALGO) {
            withContext(Dispatchers.IO) { database.unassignUnnamedPeople() }
            store.unassignExcept(named)
            database.setMeta(CLUSTER_ALGO_KEY, CLUSTER_ALGO)
        }
        // Every run regroups all people the user has not curated (and they keep their ids), so earlier scans never
        // freeze a wrong merge or a split. This costs a neighbour search over those faces each time.
        val kept = withContext(Dispatchers.IO) { database.keptPersonIds() }
        val notSame = withContext(Dispatchers.IO) { database.notSamePairs() }
        val rejections = rejectionsByRow()
        // Person ids only ever grow, so a rejection can never point at a different, later person.
        val highWater = database.getMeta(MAX_PERSON_ID_KEY)?.toLongOrNull() ?: 0L
        val nextId = maxOf(database.maxPersonId(), store.maxPersonId(), highWater) + 1
        var lastPublish = 0L
        val res = clusterer.run(store, neighborFinder, named, rejections, nextId, kept, notSame) { done, total ->
            val now = System.currentTimeMillis()
            if (now - lastPublish > 300) { lastPublish = now; _status.value = ScanStatus.Grouping(done, total) }
        }
        val changed = res.changedRows.map { store.faceId(it) to store.personOf(it) }
        val covers = res.coverRowByPerson.mapValues { store.faceId(it.value) }
        withContext(Dispatchers.IO) {
            database.applyClustering(changed, res.faceCountByPerson, covers)
            database.setMeta(MAX_PERSON_ID_KEY, (res.nextPersonId - 1).toString())
        }

        _status.value = ScanStatus.Scanning(scanned, scanned, faces, "Preparing faces…")
        for ((personId, faceId) in covers) {
            if ((res.faceCountByPerson[personId] ?: 0) < FaceDatabase.MIN_FACES_TO_SHOW) continue
            ensureThumbnail(faceId)
        }
    }

    /** Nothing new was indexed and the grouping is current: skip the (neighbour-search heavy) regrouping. */
    private fun needsGrouping(scanned: Int): Boolean = scanned > 0 || database.getMeta(CLUSTER_ALGO_KEY) != CLUSTER_ALGO

    /** DB rejections (by face id) translated to store rows. */
    private fun rejectionsByRow(): Map<Int, Set<Long>> {
        val byFace = database.loadRejections()
        if (byFace.isEmpty()) return emptyMap()
        val out = HashMap<Int, Set<Long>>()
        for (r in 0 until store.size) {
            if (!store.isAlive(r)) continue
            byFace[store.faceId(r)]?.let { out[r] = it }
        }
        return out
    }

    /**
     * Regroups every person the user has not named from scratch (named people and "not this person" corrections on
     * them are kept). Useful after big imports, or if the incremental grouping drifted.
     */
    fun rebuildPeople(folderPath: String) {
        if (scanJob?.isActive == true) return
        _status.value = ScanStatus.Grouping(0, 1000)
        startForegroundService()
        scanJob = scope.launch {
            try {
                ensureStoreLoaded()
                groupPeople(folderPath, 0, 0, rebuild = true)
            } catch (e: CancellationException) {
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }
            _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
        }
    }

    // ---- corrections (keep SQLite and the in-memory index in step) ---------------------------------------------------

    suspend fun renamePerson(personId: Long, name: String) = withContext(Dispatchers.IO) { database.renamePerson(personId, name) }

    /** "Not this person" for whole photos: their faces leave the person and will not be grouped back into it. */
    suspend fun removePhotosFromPerson(personId: Long, paths: Collection<String>) = withContext(Dispatchers.IO) {
        ensureStoreLoaded()
        val removed = database.removePhotosFromPerson(personId, paths).toHashSet()
        for (r in store.rowsOfPerson(personId)) if (store.faceId(r) in removed) store.setPerson(r, 0L)
        refreshCover(personId)
    }

    /** Merges [sources] into [target] (e.g. the same person split in two). */
    suspend fun mergePeople(target: Long, sources: Collection<Long>) = withContext(Dispatchers.IO) {
        ensureStoreLoaded()
        database.mergePeople(target, sources)
        for (src in sources) if (src != target) for (r in store.rowsOfPerson(src)) store.setPerson(r, target)
        refreshCover(target)
    }

    /** "Same person?" questions for the people shown in [folderPath], most likely first (all of them by default). */
    suspend fun mergeSuggestions(folderPath: String, max: Int = Int.MAX_VALUE): List<MergeSuggestion> = withContext(Dispatchers.Default) {
        ensureStoreLoaded()
        val visible = withContext(Dispatchers.IO) { database.getPeopleInFolder(folderPath) }.associateBy { it.id }
        val named = withContext(Dispatchers.IO) { database.namedPersonIds() }
        val notSame = withContext(Dispatchers.IO) { database.notSamePairs() }
        PeopleClusterer.suggestMerges(store, visible.keys, named, notSame, clusterConfig.askThreshold, max)
            .map { (a, b, sim) -> MergeSuggestion(visible.getValue(a), visible.getValue(b), sim) }
    }

    /** The user's answer to a "Same person?" question: merge them, or remember that they are different. */
    suspend fun answerSuggestion(s: MergeSuggestion, same: Boolean) {
        if (same) {
            // Keep the named one, else the bigger one (its id and cover are what the user has been seeing).
            val aNamed = !s.a.name.startsWith("Person "); val bNamed = !s.b.name.startsWith("Person ")
            val target = when {
                aNamed != bNamed -> if (aNamed) s.a else s.b
                else -> if (s.a.faceCount >= s.b.faceCount) s.a else s.b
            }
            mergePeople(target.id, listOf(s.a.id, s.b.id))
        } else withContext(Dispatchers.IO) { database.markNotSame(s.a.id, s.b.id) }
    }

    /** Hides a person: their faces are marked dismissed and never regrouped. */
    suspend fun deletePerson(personId: Long) = withContext(Dispatchers.IO) {
        ensureStoreLoaded()
        for (r in store.rowsOfPerson(personId)) store.setPerson(r, -1L)
        database.deletePerson(personId)
    }

    /** Picks a new cover if the old one left the person, keeping the stored face count in sync. */
    private fun refreshCover(personId: Long) {
        val rows = store.rowsOfPerson(personId)
        if (rows.isEmpty()) return
        val current = database.getPersonById(personId)?.coverFaceId
        if (current != null && rows.any { store.faceId(it) == current }) return
        val best = rows.maxByOrNull { store.qualityOf(it) } ?: return
        val faceId = store.faceId(best)
        database.applyClustering(emptyList(), mapOf(personId to rows.size), mapOf(personId to faceId))
        ensureThumbnail(faceId)
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

    /**
     * People in [folderPath] the query face most likely belongs to, best first: average cosine between the query and
     * each person's faces (the same score the clusterer attaches faces with).
     */
    suspend fun suggestPeople(embedding: FloatArray, folderPath: String, max: Int = 6): List<PersonMatch> = withContext(Dispatchers.Default) {
        ensureStoreLoaded()
        val visible = withContext(Dispatchers.IO) { database.getPeopleInFolder(folderPath) }.associateBy { it.id }
        store.personSums().mapNotNull { (pid, sc) ->
            val person = visible[pid] ?: return@mapNotNull null
            val sim = PeopleClusterer.dot(embedding, sc.first) / sc.second
            if (sim >= FaceMath.MATCH_POSSIBLE) PersonMatch(person, sim) else null
        }.sortedByDescending { it.similarity }.take(max)
    }

    companion object {
        private const val CLUSTER_ALGO_KEY = "cluster_algo"
        private const val CLUSTER_ALGO = "avg-linkage-v2"
        private const val MAX_PERSON_ID_KEY = "max_person_id"
        @Volatile private var INSTANCE: FaceScannerManager? = null
        fun getInstance(context: Context): FaceScannerManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: FaceScannerManager(context.applicationContext).also { INSTANCE = it } }
    }
}
