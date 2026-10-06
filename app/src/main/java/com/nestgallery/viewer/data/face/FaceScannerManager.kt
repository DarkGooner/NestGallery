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
 * Manages fast background face scanning and clustering scoped to explored folders.
 */
class FaceScannerManager private constructor(private val appContext: Context) {

    val database = FaceDatabase.getInstance(appContext)
    val embeddingHelper = FaceEmbeddingHelper(appContext)
    val detectorHelper = FaceDetectorHelper()
    val clusterer = FaceClusterer()

    private val scope = CoroutineScope(Dispatchers.Default)
    private var scanJob: Job? = null
    private val isPaused = AtomicBoolean(false)

    private val _status = MutableStateFlow<ScanStatus>(ScanStatus.Idle(FaceDbStats(0, 0, 0)))
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "bmp")

    private val thumbDir by lazy {
        File(appContext.cacheDir, "face_thumbs").apply { mkdirs() }
    }

    /**
     * Starts face scanning on the media files collected by the Recursive Scan.
     * @param files List of candidate image/media files.
     * @param folderPath Root directory path of the recursive exploration.
     */
    fun startScanForFiles(files: List<File>, folderPath: String) {
        if (scanJob?.isActive == true) {
            if (isPaused.get()) {
                isPaused.set(false)
            }
            return
        }

        scanJob = scope.launch {
            try {
                // Filter only image files that are not already scanned/up-to-date
                val imageFiles = files.filter { it.extension.lowercase() in imageExtensions }
                val filesToProcess = imageFiles.filter { !database.isFileIndexedAndCurrent(it) }
                val totalCount = filesToProcess.size

                var scannedCount = 0
                var facesFoundCount = 0

                _status.value = ScanStatus.Scanning(
                    scannedCount = 0,
                    totalCount = totalCount,
                    facesFound = 0,
                    currentFileName = if (totalCount > 0) filesToProcess[0].name else "Analyzing folder..."
                )

                for (file in filesToProcess) {
                    while (isPaused.get() && isActive) {
                        kotlinx.coroutines.delay(100)
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
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Run clustering pass on this folder's faces
                clusterer.clusterFaces(database, folderPath)

                _status.value = ScanStatus.Completed(
                    totalScanned = scannedCount,
                    facesFound = facesFoundCount
                )

                kotlinx.coroutines.delay(1500)
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))

            } catch (e: CancellationException) {
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
            } catch (e: Exception) {
                e.printStackTrace()
                _status.value = ScanStatus.Idle(database.getFolderStats(folderPath))
            }
        }
    }

    private fun processSingleImage(file: File): List<PendingFaceRecord> {
        val bitmap = detectorHelper.decodeSampledBitmap(file, maxDimension = 512)
            ?: run {
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

                // Save small avatar crop to cache for fast rendering
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
    }

    /**
     * Performs reverse face search strictly within the specified explored folder.
     */
    suspend fun searchByFaceInFolder(
        queryBitmap: Bitmap,
        folderPath: String,
        minSimilarity: Float = 0.58f
    ): Pair<Bitmap, List<FaceMatch>> = withContext(Dispatchers.Default) {
        val detected = detectorHelper.detectFaces(queryBitmap)
        val faceResult = if (detected.isNotEmpty()) {
            detected[0]
        } else {
            detectorHelper.extractFallbackFace(queryBitmap)
        }

        val embedding = embeddingHelper.extractEmbedding(faceResult.faceBitmap)
            ?: return@withContext Pair(faceResult.faceBitmap, emptyList())

        val matches = database.searchByEmbeddingInFolder(embedding, folderPath, minSimilarity)
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
