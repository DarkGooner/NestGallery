package com.nestgallery.viewer.data.face

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

sealed class ScanStatus {
    data class Idle(val stats: FaceDbStats) : ScanStatus()
    data class Scanning(
        val scannedCount: Int,
        val totalCount: Int,
        val facesFound: Int,
        val currentFileName: String
    ) : ScanStatus()
    data class Paused(
        val scannedCount: Int,
        val totalCount: Int,
        val facesFound: Int
    ) : ScanStatus()
    data class Completed(
        val totalScanned: Int,
        val facesFound: Int
    ) : ScanStatus()
}

/**
 * Manages background face indexing and clustering across 10k+ photos.
 * Ensures:
 * - Zero UI freezing (runs on Dispatchers.Default with memory safety)
 * - Incremental scanning: skips unchanged files instantly
 * - Periodic auto-clustering
 * - Real-time progress updates via StateFlow
 */
class FaceScannerManager private constructor(private val appContext: Context) {

    private val database = FaceDatabase.getInstance(appContext)
    private val embeddingHelper = FaceEmbeddingHelper(appContext)
    private val detectorHelper = FaceDetectorHelper()
    private val clusterer = FaceClusterer()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var scanJob: Job? = null
    private val isPaused = AtomicBoolean(false)

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle(database.getStats()))
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp")

    private val thumbDir by lazy {
        File(appContext.cacheDir, "face_thumbs").apply { mkdirs() }
    }

    fun refreshStats() {
        if (_status.value is ScanStatus.Idle) {
            _status.value = ScanStatus.Idle(database.getStats())
        }
    }

    /**
     * Starts or resumes scanning for media in [rootDirs].
     */
    fun startScan(rootDirs: List<File>) {
        if (scanJob?.isActive == true) {
            if (isPaused.get()) {
                isPaused.set(false)
            }
            return
        }

        scanJob = scope.launch {
            try {
                // Collect candidate files
                val candidateFiles = mutableListOf<File>()
                for (dir in rootDirs) {
                    collectImageFiles(dir, candidateFiles)
                }

                // Filter out already indexed & unmodified files for speed
                val filesToProcess = candidateFiles.filter { !database.isFileIndexedAndCurrent(it) }
                val totalCount = filesToProcess.size

                var scannedCount = 0
                var facesFoundCount = 0
                var newFacesSinceCluster = 0

                _status.value = ScanStatus.Scanning(
                    scannedCount = 0,
                    totalCount = totalCount,
                    facesFound = 0,
                    currentFileName = if (totalCount > 0) filesToProcess[0].name else "Checking library..."
                )

                for (file in filesToProcess) {
                    while (isPaused.get() && isActive) {
                        kotlinx.coroutines.delay(200)
                    }
                    if (!isActive) break

                    _status.value = ScanStatus.Scanning(
                        scannedCount = scannedCount,
                        totalCount = totalCount,
                        facesFound = facesFoundCount,
                        currentFileName = file.name
                    )

                    try {
                        val newFaces = processSingleImage(file)
                        scannedCount++
                        if (newFaces.isNotEmpty()) {
                            facesFoundCount += newFaces.size
                            newFacesSinceCluster += newFaces.size
                        }

                        // Re-cluster in batches of 150 faces so people appear progressively
                        if (newFacesSinceCluster >= 150) {
                            clusterer.clusterFaces(database)
                            newFacesSinceCluster = 0
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Final clustering pass
                clusterer.clusterFaces(database)

                _status.value = ScanStatus.Completed(
                    totalScanned = scannedCount,
                    facesFound = facesFoundCount
                )

                // Return to idle with updated stats after 2 seconds
                kotlinx.coroutines.delay(2000)
                _status.value = ScanStatus.Idle(database.getStats())

            } catch (e: CancellationException) {
                _status.value = ScanStatus.Idle(database.getStats())
            } catch (e: Exception) {
                e.printStackTrace()
                _status.value = ScanStatus.Idle(database.getStats())
            }
        }
    }

    private fun collectImageFiles(dir: File, list: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory || dir.name.startsWith(".")) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.isDirectory) {
                if (!f.name.startsWith(".")) {
                    collectImageFiles(f, list)
                }
            } else {
                val ext = f.extension.lowercase()
                if (ext in imageExtensions && !f.name.startsWith(".")) {
                    list.add(f)
                }
            }
        }
    }

    private fun processSingleImage(file: File): List<PendingFaceRecord> {
        val bitmap = detectorHelper.decodeSampledBitmap(file, maxDimension = 1024)
            ?: run {
                // Mark skipped / undecodable file so it's not checked again
                database.saveFileFaces(file, emptyList())
                return emptyList()
            }

        try {
            val detected = detectorHelper.detectFaces(bitmap)
            if (detected.isEmpty()) {
                database.saveFileFaces(file, emptyList())
                return emptyList()
            }

            val pending = mutableListOf<PendingFaceRecord>()
            for ((idx, faceRes) in detected.withIndex()) {
                val embedding = embeddingHelper.extractEmbedding(faceRes.faceBitmap) ?: continue

                // Save face thumbnail crop to disk for fast UI rendering
                val thumbFile = File(thumbDir, "face_${file.name.hashCode()}_${idx}.jpg")
                try {
                    FileOutputStream(thumbFile).use { out ->
                        faceRes.faceBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    }
                } catch (e: Exception) {
                    // ignore
                }

                pending.add(
                    PendingFaceRecord(
                        normalizedBounds = faceRes.normalizedBounds,
                        embedding = embedding,
                        thumbnailPath = thumbFile.absolutePath
                    )
                )
            }

            database.saveFileFaces(file, pending)
            return pending
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun pauseScan() {
        isPaused.set(true)
        val current = _status.value
        if (current is ScanStatus.Scanning) {
            _status.value = ScanStatus.Paused(
                scannedCount = current.scannedCount,
                totalCount = current.totalCount,
                facesFound = current.facesFound
            )
        }
    }

    fun resumeScan() {
        isPaused.set(false)
        val current = _status.value
        if (current is ScanStatus.Paused) {
            _status.value = ScanStatus.Scanning(
                scannedCount = current.scannedCount,
                totalCount = current.totalCount,
                facesFound = current.facesFound,
                currentFileName = "Resuming..."
            )
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        isPaused.set(false)
        _status.value = ScanStatus.Idle(database.getStats())
    }

    /**
     * Performs reverse face search: extracts embedding from user-supplied face bitmap
     * and queries all matching photos in the database.
     */
    suspend fun searchByFace(
        queryBitmap: Bitmap,
        minSimilarity: Float = 0.65f
    ): Pair<Bitmap, List<FaceMatch>> = withContext(Dispatchers.Default) {
        // Detect face in user input
        val detected = detectorHelper.detectFaces(queryBitmap)
        val faceResult = if (detected.isNotEmpty()) {
            detected[0] // pick the primary face
        } else {
            // Edge case: user input is already tightly cropped or partial face
            detectorHelper.extractFallbackFace(queryBitmap)
        }

        val embedding = embeddingHelper.extractEmbedding(faceResult.faceBitmap)
            ?: return@withContext Pair(faceResult.faceBitmap, emptyList())

        val matches = database.searchByEmbedding(embedding, minSimilarity)
        Pair(faceResult.faceBitmap, matches)
    }

    companion object {
        @Volatile
        private var INSTANCE: FaceScannerManager? = null

        fun getInstance(context: Context): FaceScannerManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaceScannerManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
