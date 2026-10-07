package com.nestgallery.viewer.data.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** Runs the real `face_knn.onnx` asset through desktop ONNX Runtime and checks it against the exact Kotlin search. */
class OnnxKnnTest {

    private fun asset(): File = listOf("src/main/assets/face_knn.onnx", "app/src/main/assets/face_knn.onnx")
        .map(::File).first { it.exists() }

    @Test
    fun matchesBruteForceAcrossBlocksAndBatches() {
        val r = Random(21)
        val store = FaceStore()
        // > one base block (16384) and > one query batch (256), with near-duplicates so ordering matters
        val centres = List(400) { FaceMath.l2Normalize(FloatArray(FaceMath.EMBED_DIM) { r.nextGaussian().toFloat() }) }
        repeat(17000) { i ->
            val c = centres[i % centres.size]
            store.add(i.toLong(), "/k/$i.jpg", 0, 0.5f, FaceMath.l2Normalize(FloatArray(c.size) { c[it] + 0.8f * r.nextGaussian().toFloat() / 22.6f }))
        }
        val base = IntArray(store.size) { it }
        val queries = IntArray(600) { it * 28 }
        val k = 24
        val onnx = OnnxKnn(asset()).find(store, queries, base, k, null)
        val exact = BruteForceNeighborFinder(4).find(store, queries, base, k, null)
        var same = 0; var total = 0
        for (i in queries.indices) {
            val a = (0 until k).map { onnx.rows[i * k + it] }.toSet()
            val b = (0 until k).map { exact.rows[i * k + it] }.toSet()
            assertTrue("query row returned as its own neighbour", queries[i] !in a)
            same += a.intersect(b).size; total += k
            for (j in 1 until k) assertTrue("not sorted", onnx.sims[i * k + j - 1] >= onnx.sims[i * k + j])
            // the similarities are float32 cosines; the brute force is int8 - they agree to quantisation error
            assertEquals(exact.sims[i * k], onnx.sims[i * k], 0.01f)
        }
        // int8 vs float32 can swap neighbours whose similarities are within ~0.002 of each other
        assertTrue("overlap ${same.toDouble() / total}", same.toDouble() / total > 0.97)
    }

    @Test
    fun int8RecogniserRunsBatchedOnOrt122() {
        val file = listOf("src/main/assets/${ArcFaceEmbedder.ASSET}", "app/src/main/assets/${ArcFaceEmbedder.ASSET}").map(::File).first { it.exists() }
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        env.createSession(file.absolutePath, ai.onnxruntime.OrtSession.SessionOptions()).use { s ->
            val r = Random(3)
            val n = 3
            val data = FloatArray(n * 3 * 112 * 112) { r.nextFloat() * 2f - 1f }
            ai.onnxruntime.OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(data), longArrayOf(n.toLong(), 3, 112, 112)).use { t ->
                s.run(mapOf(s.inputNames.first() to t)).use { out ->
                    val fb = (out.get(0) as ai.onnxruntime.OnnxTensor).floatBuffer
                    assertEquals(n * FaceMath.EMBED_DIM, fb.remaining())
                }
            }
        }
    }
}
