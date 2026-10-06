package com.nestgallery.viewer.data.face

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Manages loading and inference for Google FaceNet-512 (512-dimensional embedding model).
 * Uses proper image standardization (whitening: (x - mean) / std) and L2-normalization.
 * Provides high accuracy across age progression, facial hair, glasses, and diverse angles.
 */
class FaceEmbeddingHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize: Int = 160
    private var embeddingDim: Int = 512

    init {
        loadModel()
    }

    @Synchronized
    private fun loadModel() {
        if (interpreter != null) return
        try {
            val assetFd = context.assets.openFd("facenet_512.tflite")
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFd.startOffset
            val declaredLength = assetFd.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val interp = Interpreter(modelBuffer, options)
            val inputShape = interp.getInputTensor(0).shape()
            if (inputShape.size == 4) {
                inputSize = inputShape[1] // 160 for FaceNet
            }
            val outputShape = interp.getOutputTensor(0).shape()
            embeddingDim = outputShape.last() // 512

            interpreter = interp
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isReady: Boolean
        get() = interpreter != null

    /**
     * Extracts a normalized 512D face feature embedding from a cropped face bitmap.
     * Uses FaceNet standardization (pixel whitening): x' = (x - mean) / std_dev
     * @param faceBitmap The cropped face bitmap (scaled to 160x160).
     * @return L2-normalized float embedding of size 512 or null if failed.
     */
    @Synchronized
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        val scaledBitmap = if (faceBitmap.width == inputSize && faceBitmap.height == inputSize) {
            faceBitmap
        } else {
            Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        }

        val totalPixels = inputSize * inputSize
        val intValues = IntArray(totalPixels)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        if (scaledBitmap != faceBitmap && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
        }

        // 1. Calculate mean across all RGB channels
        var sum = 0.0
        for (pixel in intValues) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            sum += r + g + b
        }
        val totalValues = totalPixels * 3
        val mean = (sum / totalValues).toFloat()

        // 2. Calculate standard deviation
        var sumSqDiff = 0.0
        for (pixel in intValues) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val dr = r - mean
            val dg = g - mean
            val db = b - mean
            sumSqDiff += dr * dr + dg * dg + db * db
        }
        val std = sqrt(sumSqDiff / totalValues).toFloat()
        val stdAdj = max(std, 1.0f / sqrt(totalValues.toFloat()))

        // 3. Prepare standardized Float32 buffer [1, 160, 160, 3]
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        for (pixel in intValues) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            inputBuffer.putFloat((r - mean) / stdAdj)
            inputBuffer.putFloat((g - mean) / stdAdj)
            inputBuffer.putFloat((b - mean) / stdAdj)
        }

        val output = Array(1) { FloatArray(embeddingDim) }
        interp.run(inputBuffer, output)

        val rawEmbedding = output[0]
        return normalize(rawEmbedding)
    }

    /**
     * Computes the L2-normalized vector.
     */
    private fun normalize(vector: FloatArray): FloatArray {
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares.toDouble()).toFloat()
        if (norm == 0f) return vector

        val result = FloatArray(vector.size)
        for (i in vector.indices) {
            result[i] = vector[i] / norm
        }
        return result
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        /**
         * Cosine similarity between two L2-normalized embeddings is simply their dot product.
         * For FaceNet-512, values >= 0.60 to 0.65 strongly indicate the same person across ages.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            val length = min(a.size, b.size)
            var dot = 0f
            for (i in 0 until length) {
                dot += a[i] * b[i]
            }
            return max(-1f, min(1f, dot))
        }

        /**
         * Converts FloatArray into ByteArray for SQLite BLOB storage.
         */
        fun toByteArray(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) {
                buffer.putFloat(f)
            }
            return buffer.array()
        }

        /**
         * Restores FloatArray from SQLite BLOB ByteArray.
         */
        fun fromByteArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            for (i in floats.indices) {
                floats[i] = buffer.getFloat()
            }
            return floats
        }
    }
}
