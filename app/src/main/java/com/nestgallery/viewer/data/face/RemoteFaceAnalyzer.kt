package com.nestgallery.viewer.data.face

import android.graphics.Bitmap
import android.graphics.RectF
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Face scan on the user's own NVIDIA PC (tools/nvidia-server): the PC detects, aligns and embeds; the phone only reads
 * (or, for big photos, decodes and shrinks) each photo and sends it. The PC runs the phone's FaceAnalyzer steps with
 * the same detector and the same float recogniser graph as the phone's GPU option, so results share the phone's
 * vector space.
 *
 * Why the PC does detection too: with only recognition on the PC the phone still decoded, resized and ran SCRFD on its
 * CPU for every photo (~90 ms of CPU each), which held a scan at ~30 photos/s. Small JPEG / PNG / WebP files now go
 * as they are (no decoding at all on the phone); bigger ones are decoded at reduced size (like the phone's own scan
 * does) and sent as a JPEG of a few hundred KB, which keeps Wi-Fi from becoming the limit.
 */
class RemoteFaceAnalyzer(endpoint: String) {
    private val base = normalizeEndpoint(endpoint)

    /** What to send for [file]: its own bytes, or a re-encoded smaller copy; null = not decodable. CPU work. */
    fun prepare(file: File): ByteArray? {
        val ext = file.extension.lowercase()
        val len = file.length()
        if (ext in RAW_TYPES && len in 1..RAW_MAX_BYTES) return try { file.readBytes() } catch (e: IOException) { null }
        val bmp = FaceImageLoader.decodeFile(file) ?: return null
        return try {
            ByteArrayOutputStream(256 shl 10).also { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
        } finally { bmp.recycle() }
    }

    /** The faces the PC found in [payload] (see [prepare]). Throws on any network / server failure. Blocking. */
    fun analyze(payload: ByteArray, maxFaces: Int = 40): List<NewFace> {
        val conn = (URL("$base/v1/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000; readTimeout = 30000
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Max-Faces", maxFaces.toString())
        }
        // Success: the response body is read to the end and closed, which returns the socket to the keep-alive pool
        // (disconnect() would close it). Any failure: disconnect.
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code == 422) { errorText(conn); return emptyList() }   // the PC could not decode it: no faces, like the phone
            if (code != 200) throw IOException("PC answered $code: ${errorText(conn)}")
            val bytes = conn.inputStream.use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            if (n < 0 || bytes.size != 4 + n * FACE_FLOATS * 4) throw IOException("PC sent ${bytes.size} bytes for $n faces")
            val fb = buf.asFloatBuffer()
            return List(n) {
                val b = FloatArray(4).also { a -> fb.get(a) }
                val lm = FloatArray(10).also { a -> fb.get(a) }
                val quality = fb.get(); fb.get()                      // detector score: not stored
                val emb = FloatArray(DIM).also { a -> fb.get(a) }
                NewFace(RectF(b[0], b[1], b[2], b[3]), lm, emb, quality)
            }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    class Health(val ok: Boolean, val message: String)

    companion object {
        /** Must equal the server's MODEL_ID: the graph whose embeddings match the phone's (see make_gpu_models.py). */
        const val MODEL_ID = "adaface_ir101_gpu_from_int8:v1"
        /** The server protocol this app speaks (2 = whole photos in, faces out). */
        private const val API = 2
        private const val DIM = 512
        private const val FACE_FLOATS = 4 + 10 + 2 + DIM
        /** Up to this size a JPEG / PNG / WebP is sent as it is: reading it is far cheaper than decoding it. */
        private const val RAW_MAX_BYTES = 400L * 1024
        private val RAW_TYPES = setOf("jpg", "jpeg", "png", "webp")
        private const val JPEG_QUALITY = 90

        init {
            // Android's HttpURLConnection keeps only 5 idle connections by default; a scan has more requests in flight,
            // so the rest were closed after every photo and each photo paid a new TCP handshake.
            System.setProperty("http.maxConnections", "32")
        }

        /** "192.168.1.5:8765" -> "http://192.168.1.5:8765"; trailing slashes dropped. */
        fun normalizeEndpoint(s: String): String {
            val t = s.trim().trimEnd('/')
            return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
        }

        /** Checks that [endpoint] is a NestGallery face endpoint with the right model and protocol. Blocking. */
        fun check(endpoint: String): Health = try {
            val conn = (URL("${normalizeEndpoint(endpoint)}/v1/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000; readTimeout = 5000
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> {
                        val j = JSONObject(conn.inputStream.use { it.readBytes() }.decodeToString())
                        val model = j.optString("model_id")
                        val provider = j.optString("provider")
                        when {
                            j.optString("app") != "nestgallery-face" -> Health(false, "Not a NestGallery face endpoint")
                            j.optInt("api", 1) < API -> Health(false, "The PC runs an older server. Copy the new tools/nvidia-server folder and run setup.cmd again.")
                            model != MODEL_ID -> Health(false, "The PC runs model \"$model\", this app needs \"$MODEL_ID\". Update the server folder.")
                            provider != "CUDAExecutionProvider" -> Health(true, "Connected, but the PC runs on $provider, not the NVIDIA GPU")
                            else -> Health(true, "Connected: NVIDIA GPU (CUDA)")
                        }
                    }
                    401 -> Health(false, "The PC runs an older server that asks for a token. Copy the new tools/nvidia-server folder and run setup.cmd again.")
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
