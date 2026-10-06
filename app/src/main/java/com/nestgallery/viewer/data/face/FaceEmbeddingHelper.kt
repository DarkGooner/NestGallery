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
 * Manages loading and inference for on-device MobileFaceNet face embedding model.
 * Produces L2-normalized 192-dimensional embeddings for fast cosine similarity face search.
 */
class FaceEmbeddingHelper(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize: Int = 112
    private var embeddingDim: Int = 192

    init {
        loadModel()
    }

    @Synchronized
    private fun loadModel() {
        if (interpreter != null) return
        try {
            val assetFd = context.assets.openFd("mobilefacenet.tflite")
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFd.startOffset
            val declaredLength = assetFd.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            val interp = Interpreter(modelBuffer, options)
            val inputShape = interp.getInputTensor(0).shape()
            if (inputShape.size == 4) {
                inputSize = inputShape[1] // typically 112
            }
            val outputShape = interp.getOutputTensor(0).shape()
            embeddingDim = outputShape.last() // typically 192

            interpreter = interp
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isReady: Boolean
        get() = interpreter != null

    /**
     * Extracts a normalized face feature embedding from a cropped face bitmap.
     * @param faceBitmap The cropped face bitmap (will be scaled to 112x112).
     * @return L2-normalized float embedding or null if model failed.
     */
    @Synchronized
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: return null

        val scaledBitmap = if (faceBitmap.width == inputSize && faceBitmap.height == inputSize) {
            faceBitmap
        } else {
            Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        }

        // Buffer for 1 * inputSize * inputSize * 3 * 4 bytes
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            rewind()
        }

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            val r = (pixel shr 16 and 0xFF)
            val g = (pixel shr 8 and 0xFF)
            val b = (pixel and 0xFF)

            // MobileFaceNet standard normalization: (x - 128) / 128.0f
            inputBuffer.putFloat((r - 128f) / 128f)
            inputBuffer.putFloat((g - 128f) / 128f)
            inputBuffer.putFloat((b - 128f) / 128f)
        }

        if (scaledBitmap != faceBitmap && !scaledBitmap.isRecycled) {
            scaledBitmap.recycle()
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
         * Returns a value between -1.0 and 1.0 (typically 0.65+ signifies same person).
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
