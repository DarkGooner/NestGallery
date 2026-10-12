package com.nestgallery.viewer.data.face

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Face recognition on the user's own NVIDIA PC (tools/nvidia-server). The phone still detects and aligns faces; only
 * the 112x112 aligned crops are sent, and the PC returns their embeddings. It runs the same float graph as the phone's
 * GPU option, so they share the phone's vector space.
 *
 * Never loses a photo: if the PC can't be reached or answers with an error, that photo's faces are embedded on the
 * phone ([fallback]) and the PC is left alone for [RETRY_AFTER_MS] before it is tried again.
 */
class RemoteEmbedder(endpoint: String, private val token: String, private val fallback: FaceEmbedder) : FaceEmbedder {
    private val base = normalizeEndpoint(endpoint)
    @Volatile private var downUntil = 0L
    /** Faces the phone had to embed itself because the PC failed. */
    val fallbackFaces = AtomicInteger(0)
    @Volatile var lastError: String? = null
        private set

    override fun embedBatch(faces: List<Bitmap>): List<FloatArray> {
        if (faces.isEmpty()) return emptyList()
        if (System.currentTimeMillis() >= downUntil) {
            try {
                return faces.chunked(MAX_FACES).flatMap { post(it) }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                downUntil = System.currentTimeMillis() + RETRY_AFTER_MS
            }
        }
        fallbackFaces.addAndGet(faces.size)
        return fallback.embedBatch(faces)
    }

    private fun post(faces: List<Bitmap>): List<FloatArray> {
        val body = ByteArray(faces.size * FACE_BYTES)
        val px = IntArray(SIZE * SIZE)
        for ((k, f) in faces.withIndex()) {
            require(f.width == SIZE && f.height == SIZE)
            f.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            var o = k * FACE_BYTES
            for (p in px) {
                body[o++] = (p shr 16).toByte(); body[o++] = (p shr 8).toByte(); body[o++] = p.toByte()
            }
        }
        val conn = (URL("$base/v1/embed").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000; readTimeout = 30000
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Nest-Token", token)
            setRequestProperty("X-Faces", faces.size.toString())
        }
        // Success: the response body is read to the end and closed, which returns the socket to the keep-alive pool
        // (disconnect() would close it). Any failure: disconnect.
        try {
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) throw IOException("PC answered ${conn.responseCode}: ${errorText(conn)}")
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size != faces.size * DIM * 4) throw IOException("PC sent ${bytes.size} bytes for ${faces.size} faces")
            val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return List(faces.size) { FloatArray(DIM).also { v -> fb.get(v) } }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    class Health(val ok: Boolean, val message: String)

    companion object {
        /** Must equal the server's MODEL_ID: the graph whose embeddings match the phone's (see make_gpu_models.py). */
        const val MODEL_ID = "adaface_ir101_gpu_from_int8:v1"
        private const val SIZE = FaceMath.ALIGN_SIZE
        private const val DIM = 512
        private const val FACE_BYTES = SIZE * SIZE * 3
        private const val MAX_FACES = 64
        private const val RETRY_AFTER_MS = 15_000L

        /** "192.168.1.5:8765" -> "http://192.168.1.5:8765"; trailing slashes dropped. */
        fun normalizeEndpoint(s: String): String {
            val t = s.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }

        /** Checks that [endpoint] is a NestGallery face endpoint with the right model and [token]. Blocking. */
        fun check(endpoint: String, token: String): Health = try {
            val conn = (URL("${normalizeEndpoint(endpoint)}/v1/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 5000
                setRequestProperty("X-Nest-Token", token.trim())
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val j = JSONObject(conn.inputStream.use { it.readBytes() }.decodeToString())
                        val model = j.optString("model_id")
                        val provider = j.optString("provider")
                        when {
                            j.optString("app") != "nestgallery-face" -> Health(false, "Not a NestGallery face endpoint")
                            model != MODEL_ID -> Health(false, "The PC runs model \"$model\", this app needs \"$MODEL_ID\". Update the server folder.")
                            provider != "CUDAExecutionProvider" -> Health(true, "Connected, but the PC runs on $provider, not the NVIDIA GPU")
                            else -> Health(true, "Connected: NVIDIA GPU (CUDA)")
                        }
                    }
                    401 -> Health(false, "Wrong token (the PC shows it when the endpoint starts)")
                    else -> Health(false, "The PC answered $code: ${errorText(conn)}")
                }
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            Health(false, "Can't reach ${normalizeEndpoint(endpoint)}: ${e.message ?: e.javaClass.simpleName}" +
                if (endpoint.contains("localhost") || endpoint.contains("127.0.0.1")) " (USB: is the endpoint running and adb reverse set up?)" else "")
        }

        private fun errorText(conn: HttpURLConnection): String =
            try { conn.errorStream?.use { it.readBytes() }?.decodeToString()?.take(120) ?: "" } catch (e: Exception) { "" }
    }
}
