package com.nestgallery.viewer.data.nsfw

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

sealed class NsfwScanStatus {
    data object Idle : NsfwScanStatus()
    data class Scanning(
        val folderPath: String,
        val scannedCount: Int,
        val totalCount: Int,
        val currentFileName: String,
        val photosPerSecond: Float = 0f,
        /** -1 = not known yet */
        val etaSeconds: Long = -1
    ) : NsfwScanStatus()
    data class Paused(val folderPath: String, val scannedCount: Int, val totalCount: Int) : NsfwScanStatus()
    data class Completed(val folderPath: String, val totalScanned: Int, val totalCount: Int) : NsfwScanStatus()
    data class Failed(val folderPath: String, val message: String) : NsfwScanStatus()

    val activeFolder: String?
        get() = when (this) { is Scanning -> folderPath; is Paused -> folderPath; else -> null }
}

/**
 * NSFW scan of a recursive folder view: every photo goes through the NudeNet variant chosen in Settings ([model],
 * [NsfwAnalyzer]); results are kept per model in SQLite ([NsfwDatabase]) and, for the current model, in memory, so
 * filtering is instant and unchanged photos are never scanned twice.
 *
 * Same structure as the face scan: decoder threads (IO) -> analysis workers (CPU) -> one writer (batched SQLite
 * transaction + in-memory map), all overlapping and bounded by channel back-pressure. [NsfwScanService] keeps it alive
 * in the background and shows the notification. Pause stops new decodes; Stop keeps what was saved.
 */
class NsfwScannerManager private constructor(private val appContext: Context) {

    val database = NsfwDatabase.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences("nsfw", Context.MODE_PRIVATE)

    private val _model = MutableStateFlow(NsfwModels.byId(prefs.getString(KEY_MODEL, null)))
    /** The NudeNet variant scans use and results are shown for (Settings). */
    val model: StateFlow<NsfwModel> = _model.asStateFlow()

    private var analyzer: NsfwAnalyzer? = null
    private val analyzerLock = Any()

    /** Results of [model] only; reloaded when the model changes. */
    private val results = ConcurrentHashMap<String, NsfwResult>()
    private val loadMutex = Mutex()
    /** Model id whose results [results] holds; null = not loaded. */
    @Volatile private var loadedModel: String? = null

    /** Bumped (at most about once a second while scanning) whenever new results are visible through [result]. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanJob: Job? = null
    private val paused = MutableStateFlow(false)

    private val _status = MutableStateFlow<NsfwScanStatus>(NsfwScanStatus.Idle)
    val status: StateFlow<NsfwScanStatus> = _status.asStateFlow()


    suspend fun ensureLoaded() {
        val id = _model.value.id
        if (loadedModel == id) return
        loadMutex.withLock {
            if (loadedModel == id) return
            val all = withContext(Dispatchers.IO) { database.loadAll(id) }
            results.clear()
            results.putAll(all)
            loadedModel = id
        }
        _revision.value++
    }

    /**
     * Switches the model. Refused (false) while a scan runs. Results of the old model stay in the database, so
     * switching back shows them again; photos never scanned with the new model need a scan.
     */
    fun setModel(m: NsfwModel): Boolean {
        if (isScanning()) return false
        if (m.id == _model.value.id) return true
        prefs.edit().putString(KEY_MODEL, m.id).apply()
        synchronized(analyzerLock) { analyzer?.close(); analyzer = null }   // the 640m session alone is >100 MB
        _model.value = m
        loadedModel = null
        results.clear()
        _revision.value++
        return true
    }

    /** Photos each model has results for. */
    suspend fun resultCounts(): Map<String, Int> = withContext(Dispatchers.IO) { database.countByModel() }

    private fun analyzerFor(m: NsfwModel): NsfwAnalyzer = synchronized(analyzerLock) {
        analyzer?.takeIf { it.model.id == m.id } ?: NsfwAnalyzer(appContext, m).also { analyzer?.close(); analyzer = it }
    }

    fun result(path: String): NsfwResult? = results[path]

    /** Per-photo counts for a recursive view's [files] (same order) at [threshold]. */
    suspend fun folderIndex(files: List<File>, threshold: Float): NsfwFolderIndex = withContext(Dispatchers.Default) {
        ensureLoaded()
        NsfwFolderIndex.build(files.map { results[it.absolutePath] }, threshold)
    }

    // ---- scanning ------------------------------------------------------------------------------

    private class Decoded(val file: File, val lastModified: Long, val size: Long, val image: NsfwAnalyzer.Decoded?, val decodeMs: Long)

    fun isScanning(): Boolean = scanJob?.isActive == true

    /** Scans the photos among [files] (videos and other files are skipped) that have no current result yet. */
    fun startScan(files: List<File>, folderPath: String) {
        if (scanJob?.isActive == true) {
            resumeScan()
            return
        }
        paused.value = false
        // Publish a non-idle status *before* the service starts, so it never observes a stale Idle and quits.
        _status.value = NsfwScanStatus.Scanning(folderPath, 0, 0, "Preparing…")
        startForegroundService()
        scanJob = scope.launch {
            var scanned = 0
            var total = 0
            try {
                ensureLoaded()
                val m = _model.value
                val indexed = withContext(Dispatchers.IO) { database.loadIndexedFiles(m.id, folderPath) }
                val todo = withContext(Dispatchers.IO) {
                    files.filter { isScannable(it) }.filter { f ->
                        val s = indexed[f.absolutePath]
                        s == null || s.first != f.lastModified() || s.second != f.length()
                    }
                }
                total = todo.size
                scanned = runPipeline(todo, folderPath, m)
            } catch (e: CancellationException) {
                _revision.value++
                _status.value = NsfwScanStatus.Idle
                throw e
            } catch (e: Throwable) {
                // e.g. a model that fails to load: report it instead of silently finishing
                e.printStackTrace()
                _revision.value++
                _status.value = NsfwScanStatus.Failed(folderPath, e.message ?: e.javaClass.simpleName)
                delay(4000)
                _status.value = NsfwScanStatus.Idle
                return@launch
            }
            _revision.value++
            _status.value = NsfwScanStatus.Completed(folderPath, scanned, total)
            delay(2500)
            _status.value = NsfwScanStatus.Idle
        }
    }

    /** @return photos processed */
    private suspend fun runPipeline(todo: List<File>, folderPath: String, model: NsfwModel): Int = coroutineScope {
        val total = todo.size
        if (total == 0) return@coroutineScope 0
        val analyzer = analyzerFor(model)                // load the model before the clock starts (and fail early)
        val cores = Runtime.getRuntime().availableProcessors()
        val decoderCount = (cores / 4).coerceIn(1, 2)
        // 640m: fewer photos in flight (each run holds far more memory), two threads each (see YoloDetector)
        val analysisCount = if (model.inputSize > 320) (cores / 4).coerceIn(1, 2) else (cores / 2).coerceIn(1, 4)
        val decoded = Channel<Decoded>(2)
        val out = Channel<NsfwScannedFile>(64)
        val next = AtomicInteger(0)
        val startNs = System.nanoTime()
        var scanned = 0
        var lastRevision = 0L
        _status.value = NsfwScanStatus.Scanning(folderPath, 0, total, "Starting…")

        val decoders = List(decoderCount) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    if (paused.value) paused.first { !it }
                    val i = next.getAndIncrement()
                    if (i >= total) break
                    val f = todo[i]
                    val t0 = System.nanoTime()
                    val img = try { analyzer.decode(f) } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
                    decoded.send(Decoded(f, f.lastModified(), f.length(), img, (System.nanoTime() - t0) / 1_000_000))
                }
            }
        }
        val workers = List(analysisCount) {
            launch(Dispatchers.Default) {
                for (d in decoded) {
                    val img = d.image
                    val t0 = System.nanoTime()
                    val dets = try {
                        if (img == null) emptyList() else analyzer.analyze(img)
                    } catch (e: Exception) { emptyList() } finally { img?.bitmap?.recycle() }
                    val ms = d.decodeMs + (System.nanoTime() - t0) / 1_000_000
                    val result = NsfwResult(img?.width ?: 0, img?.height ?: 0, dets, ms)
                    out.send(NsfwScannedFile(d.file.absolutePath, d.lastModified, d.size, result))
                }
            }
        }
        launch { decoders.joinAll(); decoded.close() }
        launch { workers.joinAll(); out.close() }

        val batch = ArrayList<NsfwScannedFile>()
        while (true) {
            val first = out.receiveCatching().getOrNull() ?: break
            batch.add(first)
            while (batch.size < 32) { batch.add(out.tryReceive().getOrNull() ?: break) }
            withContext(Dispatchers.IO) { database.saveBatch(model.id, batch) }
            for (f in batch) results[f.path] = f.result
            scanned += batch.size
            val now = System.nanoTime()
            if (now - lastRevision > 1_000_000_000L) { lastRevision = now; _revision.value++ }
            val seconds = max(0.001f, (now - startNs) / 1e9f)
            val rate = scanned / seconds
            val eta = if (scanned >= 8 && rate > 0f) ((total - scanned) / rate).toLong() else -1L
            _status.value = if (paused.value) NsfwScanStatus.Paused(folderPath, scanned, total)
            else NsfwScanStatus.Scanning(folderPath, scanned, total, File(batch.last().path).name, rate, eta)
            batch.clear()
        }
        scanned
    }

    /** Keeps the process alive (and the CPU awake) while the app is minimised or the screen is off. */
    private fun startForegroundService() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, NsfwScanService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pauseScan() {
        paused.value = true
        val current = _status.value
        if (current is NsfwScanStatus.Scanning) _status.value = NsfwScanStatus.Paused(current.folderPath, current.scannedCount, current.totalCount)
    }

    fun resumeScan() {
        paused.value = false
        val current = _status.value
        if (current is NsfwScanStatus.Paused) {
            _status.value = NsfwScanStatus.Scanning(current.folderPath, current.scannedCount, current.totalCount, "Resuming…")
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        paused.value = false
    }

    /** Forgets the results under [folderPath] so the next scan redoes them. */
    suspend fun clearFolder(folderPath: String) {
        if (isScanning()) return
        ensureLoaded()
        withContext(Dispatchers.IO) { database.clearFolder(_model.value.id, folderPath) }
        val root = folderPath.trimEnd('/')
        results.keys.removeIf { it == root || it.startsWith("$root/") }
        _revision.value++
    }

    companion object {
        private const val KEY_MODEL = "model"
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        /** Photos the scan looks at (videos and other files are skipped). */
        fun isScannable(file: File): Boolean = file.extension.lowercase() in imageExtensions

        @Volatile private var INSTANCE: NsfwScannerManager? = null
        fun getInstance(context: Context): NsfwScannerManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: NsfwScannerManager(context.applicationContext).also { INSTANCE = it } }
    }
}
