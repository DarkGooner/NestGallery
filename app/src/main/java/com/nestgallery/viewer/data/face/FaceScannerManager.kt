package com.nestgallery.viewer.data.face

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

/** Where the face models run during a scan (Settings). The GPU goes through Qualcomm QNN, so only Snapdragon phones have it. */
enum class FaceAccelerator(val title: String, val description: String) {
    CPU("CPU", "All cores, one photo each. Runs everywhere."),
    GPU("GPU (Adreno)", "Face detection and recognition run on the graphics processor; the CPU only decodes photos. " +
        "Cooler and lighter on the battery, but the GPU handles one face at a time. Experimental."),
    CPU_GPU("CPU + GPU", "Both at once: most cores analyse photos while the GPU analyses others. Usually the fastest " +
        "on the phone, but it can make the phone laggy while it runs. Experimental."),
    PC("NVIDIA PC", "The face scan runs on your own computer's NVIDIA graphics card (tools/nvidia-server, over USB " +
        "or Wi-Fi), by far the fastest option. Each photo is sent to the PC (big ones shrunk first); the PC keeps " +
        "nothing. If the PC can't be reached, the phone does the work itself.");

    companion object {
        fun byName(name: String?): FaceAccelerator = entries.firstOrNull { it.name == name } ?: CPU
    }
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

    private val prefs = appContext.getSharedPreferences("face", Context.MODE_PRIVATE)
    private val _accelerator = MutableStateFlow(FaceAccelerator.byName(prefs.getString(KEY_ACCEL, null)))
    val accelerator: StateFlow<FaceAccelerator> = _accelerator.asStateFlow()
    /** What the current / last scan actually ran on (e.g. after a GPU fallback); null before the first scan. */
    private val _backendLabel = MutableStateFlow<String?>(null)
    val backendLabel: StateFlow<String?> = _backendLabel.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private val paused = MutableStateFlow(false)

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle(FaceDbStats(0, 0, 0)))
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
    private val thumbDir by lazy { File(appContext.filesDir, "face_thumbs").apply { mkdirs() } }

    init {
        // A native crash while the GPU was being set up leaves the probe marker behind: don't try it again by itself.
        if (prefs.getBoolean(KEY_PROBE, false)) prefs.edit().remove(KEY_PROBE).putBoolean(KEY_GPU_CRASHED, true).commit()
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

    fun isScanning(): Boolean = scanJob?.isActive == true

    /** Switches the scan hardware (used from the next scan). Refused (false) while a scan runs. */
    fun setAccelerator(a: FaceAccelerator): Boolean {
        if (isScanning()) return false
        prefs.edit().putString(KEY_ACCEL, a.name).apply()
        _accelerator.value = a
        return true
    }

    /** The "NVIDIA PC" endpoint (e.g. http://localhost:8765), as typed in Settings. */
    val pcEndpoint: String get() = prefs.getString(KEY_PC_URL, null) ?: "http://localhost:8765"

    fun setPcEndpoint(url: String) {
        prefs.edit().putString(KEY_PC_URL, url.trim()).remove(KEY_PC_TOKEN).apply()
    }

    /** True once setting up the GPU crashed the app; it is then skipped until [forgetGpuCrash]. */
    fun gpuCrashed(): Boolean = prefs.getBoolean(KEY_GPU_CRASHED, false)

    fun forgetGpuCrash() { prefs.edit().remove(KEY_GPU_CRASHED).apply() }

    /** The GPU sessions of one scan; null = that model could not run on the GPU (see [note]). */
    private class GpuModels(val detector: ScrfdDetector?, val embedder: ArcFaceEmbedder?, val note: String) {
        fun close() { detector?.close(); embedder?.close() }
    }

    /**
     * Opens both models on the GPU, each on its own (one can work without the other), and runs each once: some
     * drivers only fail on the first run. A committed marker around it turns a native crash into "skip the GPU".
     */
    private fun openGpu(): GpuModels {
        if (gpuCrashed()) return GpuModels(null, null, "GPU crashed the app before; Settings can try it again")
        fun reason(e: Throwable) = e.message?.lineSequence()?.firstOrNull()?.take(80) ?: e.javaClass.simpleName
        prefs.edit().putBoolean(KEY_PROBE, true).commit()
        try {
            val notes = ArrayList<String>()
            val det = try {
                ScrfdDetector(appContext, gpu = true).also { d ->
                    val bmp = noisePicture(640, 480)
                    try { d.detect(bmp) } catch (e: Throwable) { d.close(); throw e } finally { bmp.recycle() }
                }
            } catch (e: Throwable) { e.printStackTrace(); notes += "detector on CPU (GPU: ${reason(e)})"; null }
            val emb = try {
                ArcFaceEmbedder(appContext, gpu = true).also { m ->
                    val bmp = noisePicture(FaceMath.ALIGN_SIZE, FaceMath.ALIGN_SIZE)
                    try {
                        val v = m.embed(bmp)
                        check(v.all { it.isFinite() } && v.any { it != 0f }) { "the GPU returned an invalid embedding" }
                    } catch (e: Throwable) { m.close(); throw e } finally { bmp.recycle() }
                }
            } catch (e: Throwable) { e.printStackTrace(); notes += "recogniser on CPU (GPU: ${reason(e)})"; null }
            return GpuModels(det, emb, notes.joinToString("; "))
        } finally {
            prefs.edit().remove(KEY_PROBE).commit()
        }
    }

    private fun noisePicture(w: Int, h: Int): Bitmap {
        val r = java.util.Random(7)
        val px = IntArray(w * h) { 0xFF000000.toInt() or r.nextInt(0x1000000) }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** @return (photos processed, faces found) */
    private suspend fun runPipeline(todo: List<File>): Pair<Int, Int> {
        val accel = _accelerator.value
        if (accel == FaceAccelerator.PC) return runPcPipeline(todo)
        if (todo.isNotEmpty() && accel != FaceAccelerator.CPU) _status.value = ScanStatus.Scanning(0, todo.size, 0, "Starting the GPU…")
        val gpu = if (todo.isNotEmpty() && accel != FaceAccelerator.CPU) withContext(Dispatchers.IO) { openGpu() } else null
        // Sessions are closed only after every worker has finished: coroutineScope waits for them even when cancelled.
        try { return runPipeline(todo, accel, gpu) } finally { gpu?.close() }
    }

    /**
     * "NVIDIA PC": the PC detects, aligns and embeds; the phone only reads each photo (or decodes and shrinks a big one)
     * and sends it (see [RemoteFaceAnalyzer]). Many photos are in flight at once so network round trips overlap, while
     * the CPU part (reading / decoding) is limited to one per core. If the PC fails, that photo is analysed on the
     * phone and the PC is left alone for [PC_RETRY_MS] before it is tried again; no photo is lost.
     */
    private suspend fun runPcPipeline(todo: List<File>): Pair<Int, Int> = coroutineScope {
        val cores = Runtime.getRuntime().availableProcessors()
        val cpuWorkers = cores.coerceIn(1, 8)
        if (todo.isEmpty()) return@coroutineScope runPipeline(todo, List(cpuWorkers) { analyzer })
        _status.value = ScanStatus.Scanning(0, todo.size, 0, "Connecting to the PC…")
        val health = withContext(Dispatchers.IO) { RemoteFaceAnalyzer.check(pcEndpoint) }
        if (!health.ok) {
            _backendLabel.value = "CPU · $cpuWorkers photos at once (NVIDIA PC: ${health.message})"
            return@coroutineScope runPipeline(todo, List(cpuWorkers) { analyzer })
        }
        val remote = RemoteFaceAnalyzer(pcEndpoint)
        val total = todo.size
        val cpu = Semaphore(cpuWorkers)
        val next = AtomicInteger(0)
        val onPhone = AtomicInteger(0)
        val downUntil = AtomicLong(0L)
        val lastError = AtomicReference<String?>(null)
        val results = Channel<ScannedFile>(64)
        _backendLabel.value = "NVIDIA PC · $PC_IN_FLIGHT photos in flight · ${health.message}"
        _status.value = ScanStatus.Scanning(0, total, 0, "Starting…")

        val workers = List(PC_IN_FLIGHT) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    if (paused.value) paused.first { !it }
                    val i = next.getAndIncrement()
                    if (i >= total) break
                    val f = todo[i]
                    val lastModified = f.lastModified(); val size = f.length()
                    var faces: List<NewFace>? = null
                    if (System.currentTimeMillis() >= downUntil.get()) {
                        val payload = cpu.withPermit { remote.prepare(f) }
                        if (payload == null) faces = emptyList()           // not decodable: no faces, as on the phone
                        else try {
                            faces = remote.analyze(payload)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            lastError.set(e.message ?: e.javaClass.simpleName)
                            downUntil.set(System.currentTimeMillis() + PC_RETRY_MS)
                        }
                    }
                    if (faces == null) {                                    // PC down: this photo on the phone
                        onPhone.incrementAndGet()
                        faces = cpu.withPermit {
                            val bmp = FaceImageLoader.decodeFile(f)
                            try {
                                bmp?.let { b -> analyzer.analyze(b).map { NewFace(it.bounds, it.landmarksNorm, it.embedding, it.quality) } }
                                    ?: emptyList()
                            } catch (e: Exception) { emptyList() } finally { bmp?.recycle() }
                        }
                    }
                    results.send(ScannedFile(f.absolutePath, lastModified, size, faces))
                }
            }
        }
        launch { workers.joinAll(); results.close() }
        try {
            saveResults(results, total)
        } finally {
            val fell = onPhone.get()
            if (fell > 0) _backendLabel.value = "NVIDIA PC · $fell photos done on the phone because the PC failed " +
                "(${lastError.get()?.take(80)})"
        }
    }

    private suspend fun runPipeline(todo: List<File>, accel: FaceAccelerator, gpu: GpuModels?): Pair<Int, Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        // Measured on a Snapdragon 7 Gen 3 (8 cores), 80 library photos, no saving: decoding (~35 ms per camera photo)
        // starved the models with 2 decoders. 2 decoders / 4 workers: 20.5 photos/s (16-17 when warm); 3 / 4: 25.2
        // (19); 4 / 5: 21.4 when warm, the best of the tried set. More workers alone did not help (6: 24, 8: 21-24).
        // That set had about one face per photo, so decoding was the limit. Real libraries average more faces and the
        // recogniser (~190 ms per face) dominates: the decoders then mostly wait on the full channel, and 5 workers
        // left 3 cores idle. One worker per core keeps them all busy in both cases (idle workers just wait on the
        // channel). They get their own threads so blocking model calls never starve Dispatchers.Default.
        val cpuWorkers = cores.coerceIn(1, 8)
        // GPU: it runs one model call at a time, so a few photos in flight keep it fed (one is prepared while another
        // runs). If only the detector made it onto the GPU, recognition is still CPU work: one worker per core.
        // CPU + GPU: the GPU's workers spend most of their time waiting on it, so they take two cores' places.
        val gpuAnalyzer = gpu?.takeIf { it.detector != null || it.embedder != null }
            ?.let { FaceAnalyzer(it.detector ?: detector, it.embedder ?: embedder) }
        val workerAnalyzers: List<FaceAnalyzer> = when {
            gpuAnalyzer == null -> List(cpuWorkers) { analyzer }
            accel == FaceAccelerator.GPU -> List(if (gpu.embedder != null) 4 else cpuWorkers) { gpuAnalyzer }
            else -> List(max(1, cpuWorkers - 2)) { analyzer } + List(2) { gpuAnalyzer }
        }
        val onGpu = listOfNotNull(gpu?.detector?.let { "detection" }, gpu?.embedder?.let { "recognition" })
        _backendLabel.value = when {
            gpu == null -> "CPU · $cpuWorkers photos at once"
            gpuAnalyzer == null -> "CPU · $cpuWorkers photos at once (GPU unavailable: ${gpu.note})"
            accel == FaceAccelerator.GPU -> "GPU (${onGpu.joinToString(" + ")}) · ${workerAnalyzers.size} photos at once" +
                (if (gpu.note.isNotEmpty()) " (${gpu.note})" else "")
            else -> "CPU + GPU · ${workerAnalyzers.size - 2} CPU + 2 GPU photos at once; GPU: ${onGpu.joinToString(" + ")}" +
                (if (gpu.note.isNotEmpty()) " (${gpu.note})" else "")
        }
        return runPipeline(todo, workerAnalyzers)
    }

    /** Decoders -> one worker per entry of [workerAnalyzers] -> batched writer. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runPipeline(todo: List<File>, workerAnalyzers: List<FaceAnalyzer>): Pair<Int, Int> = coroutineScope {
        val total = todo.size
        val cores = Runtime.getRuntime().availableProcessors()
        val decoderCount = (cores / 2).coerceIn(1, 4)
        val analysisDispatcher = Dispatchers.IO.limitedParallelism(workerAnalyzers.size)
        val decoded = Channel<Decoded>(2)
        val results = Channel<ScannedFile>(64)
        val next = AtomicInteger(0)
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
        val workers = workerAnalyzers.map { analyzer ->
            launch(analysisDispatcher) {
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
        saveResults(results, total)
    }

    /** Batched writer: saves what [results] delivers (up to 32 photos per transaction) and publishes progress. */
    private suspend fun saveResults(results: ReceiveChannel<ScannedFile>, total: Int): Pair<Int, Int> {
        val startNs = System.nanoTime()
        var scanned = 0
        var facesFound = 0
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
        return Pair(scanned, facesFound)
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

    // ---- stored data (Settings > Face scan data) -------------------------------------------------------------------

    /** Bytes used on disk: the SQLite file (+ WAL) and the cover thumbnails. */
    fun storageBytes(): Pair<Long, Long> {
        val db = appContext.getDatabasePath(FaceDatabase.DB_NAME)
        val dbBytes = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { it.length() }
        val thumbBytes = thumbDir.listFiles()?.sumOf { it.length() } ?: 0L
        return dbBytes to thumbBytes
    }

    /** Forgets the photos directly in [folderPath]. Refused (false) while a scan or regrouping runs. */
    suspend fun deleteFolderData(folderPath: String): Boolean = withContext(Dispatchers.IO) {
        if (isScanning()) return@withContext false
        ensureStoreLoaded()
        val gone = database.deleteFolder(folderPath)
        for (p in gone.paths) store.removeFile(p)
        for (t in gone.thumbnails) File(t).delete()
        for (pid in gone.people) refreshCover(pid)
        database.vacuum()
        true
    }

    /** Forgets everything face scan stored, names included. Refused (false) while a scan or regrouping runs. */
    suspend fun deleteAllData(): Boolean = withContext(Dispatchers.IO) {
        if (isScanning()) return@withContext false
        ensureStoreLoaded()
        database.clearAll()
        store.clear()
        thumbDir.listFiles()?.forEach { it.delete() }
        database.vacuum()
        _status.value = ScanStatus.Idle(FaceDbStats(0, 0, 0))
        true
    }

    // ---- files moved / deleted in the app (FileOps) ---------------------------------------------------------------

    /** Photos or folders were moved / renamed: their faces and people follow them (DB and in-memory index). */
    suspend fun onPathsMoved(moves: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (moves.isEmpty()) return@withContext
        storeMutex.withLock {
            database.movePaths(moves)
            if (storeLoaded) store.renamePaths { path ->
                moves.firstNotNullOfOrNull { (from, to) ->
                    when {
                        path == from -> to
                        path.startsWith("$from/") -> to + path.substring(from.length)
                        else -> null
                    }
                }
            }
        }
    }

    /** Photos or folders were deleted: their faces are forgotten; people who lost their cover get a new one. */
    suspend fun onPathsDeleted(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        val affected = storeMutex.withLock {
            database.forgetPaths(paths)
            if (storeLoaded) store.removeFilesWhere { p -> paths.any { p == it || p.startsWith("$it/") } } else emptySet()
        }
        for (person in affected) refreshCover(person)
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
        private const val KEY_ACCEL = "accelerator"
        private const val KEY_PROBE = "gpu_probe"
        private const val KEY_GPU_CRASHED = "gpu_crashed"
        private const val KEY_PC_URL = "pc_endpoint"
        private const val KEY_PC_TOKEN = "pc_token"        // old servers' token; removed when the endpoint is edited
        /** Photos on their way to / being analysed by the PC at once; enough to hide Wi-Fi round trips. */
        private const val PC_IN_FLIGHT = 24
        private const val PC_RETRY_MS = 15_000L
        @Volatile private var INSTANCE: FaceScannerManager? = null
        fun getInstance(context: Context): FaceScannerManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: FaceScannerManager(context.applicationContext).also { INSTANCE = it } }
    }
}
