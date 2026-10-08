package com.nestgallery.viewer.data.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt

class PeopleClustererTest {

    private val dim = FaceMath.EMBED_DIM
    private val finder = BruteForceNeighborFinder(threads = 4)

    private fun unit(v: FloatArray): FloatArray = FaceMath.l2Normalize(v)
    private fun gaussian(r: Random) = FloatArray(dim) { r.nextGaussian().toFloat() }

    /** A face of an identity: cos(face, centre) ~= [spread]-controlled, like real embeddings (same-person ~0.5-0.8). */
    private fun faceOf(centre: FloatArray, r: Random, noise: Float = 0.9f): FloatArray {
        val n = unit(gaussian(r))
        return unit(FloatArray(dim) { centre[it] + noise * n[it] })
    }

    /** An identity whose centre has cosine ~[sim] with [other] (CGI characters built on the same base mesh). */
    private fun lookalike(other: FloatArray, sim: Float, r: Random): FloatArray {
        val n = unit(gaussian(r))
        val d = PeopleClusterer.dot(n, other)
        val orth = unit(FloatArray(dim) { n[it] - d * other[it] })
        val s = sqrt(1f - sim * sim)
        return unit(FloatArray(dim) { sim * other[it] + s * orth[it] })
    }

    private fun run(store: FaceStore, named: Set<Long> = emptySet(), rejections: Map<Int, Set<Long>> = emptyMap(), firstId: Long = 1): ClusterResult =
        PeopleClusterer().run(store, finder, named, rejections, firstId)

    /** Pairwise precision / recall of store person ids against [truth] (rows with person <= 0 are unassigned). */
    private fun pairMetrics(store: FaceStore, truth: IntArray): Pair<Double, Double> {
        var tp = 0L; var fp = 0L; var pos = 0L
        for (i in truth.indices) for (j in i + 1 until truth.size) {
            val same = truth[i] == truth[j]
            val pi = store.personOf(i); val pj = store.personOf(j)
            val grouped = pi > 0 && pi == pj
            if (same) pos++
            if (grouped && same) tp++
            if (grouped && !same) fp++
        }
        return (if (tp + fp == 0L) 1.0 else tp.toDouble() / (tp + fp)) to tp.toDouble() / pos
    }

    @Test
    fun bridgeFaceDoesNotChainTwoPeople() {
        // The old single-link clusterer merged whole groups through one in-between face. Average linkage must not.
        val r = Random(1)
        val a = unit(gaussian(r)); val b = lookalike(a, 0.25f, r)
        val store = FaceStore()
        var file = 0
        repeat(12) { store.add(it.toLong(), "/p/${file++}.jpg", 0, 0.9f, faceOf(a, r, 0.6f)) }
        repeat(12) { store.add(100L + it, "/p/${file++}.jpg", 0, 0.9f, faceOf(b, r, 0.6f)) }
        val bridge = unit(FloatArray(dim) { a[it] + b[it] })
        store.add(999, "/p/${file++}.jpg", 0, 0.9f, bridge)
        run(store)
        val pa = store.personOf(0); val pb = store.personOf(12)
        assertTrue(pa > 0 && pb > 0)
        assertNotEquals("two different people were merged through a bridge face", pa, pb)
        for (i in 0 until 12) assertEquals(pa, store.personOf(i))
        for (i in 12 until 24) assertEquals(pb, store.personOf(i))
    }

    @Test
    fun lookalikeIdentitiesStaySeparate() {
        // 60 identities, in pairs of look-alikes (centre cosine 0.45 - harder than most real siblings), 8 faces each.
        val r = Random(7)
        val store = FaceStore()
        val truth = ArrayList<Int>()
        var file = 0
        for (pair in 0 until 30) {
            val c1 = unit(gaussian(r)); val c2 = lookalike(c1, 0.45f, r)
            for ((k, c) in listOf(c1, c2).withIndex()) repeat(8) {
                store.add(store.size.toLong(), "/l/${file++}.jpg", 0, 0.8f, faceOf(c, r))
                truth.add(pair * 2 + k)
            }
        }
        run(store)
        val (p, rec) = pairMetrics(store, truth.toIntArray())
        assertTrue("precision $p", p >= 0.99)
        assertTrue("recall $rec", rec >= 0.85)
    }

    @Test
    fun facesInOnePhotoAreNeverOnePerson() {
        val r = Random(3)
        val c = unit(gaussian(r))
        val store = FaceStore()
        // Twins / mirror shots: very similar faces, always two per photo.
        repeat(10) { i ->
            store.add(2L * i, "/t/$i.jpg", 0, 0.9f, faceOf(c, r, 0.4f))
            store.add(2L * i + 1, "/t/$i.jpg", 0, 0.9f, faceOf(c, r, 0.4f))
        }
        run(store)
        for (i in 0 until 10) {
            val p1 = store.personOf(2 * i); val p2 = store.personOf(2 * i + 1)
            assertTrue("faces of one photo grouped together", p1 <= 0 || p1 != p2)
        }
    }

    @Test
    fun namedPeopleNeverMergeAndKeepTheirIds() {
        val r = Random(5)
        val c = unit(gaussian(r))
        val store = FaceStore()
        repeat(5) { store.add(it.toLong(), "/n/a$it.jpg", 10, 0.9f, faceOf(c, r, 0.5f)) }
        repeat(5) { store.add(10L + it, "/n/b$it.jpg", 20, 0.9f, faceOf(c, r, 0.5f)) }
        repeat(4) { store.add(20L + it, "/n/c$it.jpg", 0, 0.9f, faceOf(c, r, 0.5f)) }
        run(store, named = setOf(10L, 20L), firstId = 30)
        for (i in 0 until 5) assertEquals(10L, store.personOf(i))
        for (i in 5 until 10) assertEquals(20L, store.personOf(i))
        for (i in 10 until 14) assertTrue(store.personOf(i) in setOf(10L, 20L))   // new faces join a named person
    }

    @Test
    fun rejectedFaceDoesNotReturnToThePerson() {
        val r = Random(9)
        val c = unit(gaussian(r))
        val store = FaceStore()
        repeat(8) { store.add(it.toLong(), "/r/$it.jpg", 5, 0.9f, faceOf(c, r, 0.5f)) }
        val rejected = store.add(100, "/r/x.jpg", 0, 0.9f, faceOf(c, r, 0.5f))
        val accepted = store.add(101, "/r/y.jpg", 0, 0.9f, faceOf(c, r, 0.5f))
        run(store, rejections = mapOf(rejected to setOf(5L)), firstId = 6)
        assertNotEquals(5L, store.personOf(rejected))
        assertEquals(5L, store.personOf(accepted))
    }

    @Test
    fun incrementalRunKeepsExistingPeopleAndDismissedFaces() {
        val r = Random(11)
        val a = unit(gaussian(r)); val b = unit(gaussian(r))
        val store = FaceStore()
        repeat(6) { store.add(it.toLong(), "/i/a$it.jpg", 0, 0.9f, faceOf(a, r)) }
        repeat(6) { store.add(10L + it, "/i/b$it.jpg", 0, 0.9f, faceOf(b, r)) }
        val first = run(store)
        val pa = store.personOf(0); val pb = store.personOf(6)
        assertTrue(pa > 0 && pb > 0 && pa != pb)
        store.setPerson(11, -1L)                                                   // user dismissed one face
        repeat(3) { store.add(20L + it, "/i/a_new$it.jpg", 0, 0.9f, faceOf(a, r)) }
        run(store, firstId = first.nextPersonId)
        assertEquals(pa, store.personOf(0)); assertEquals(pb, store.personOf(6))
        assertEquals(-1L, store.personOf(11))
        for (row in 12 until 15) assertEquals(pa, store.personOf(row))
    }

    @Test
    fun regroupingKeepsPersonIds() {
        val r = Random(15)
        val store = FaceStore()
        val centres = List(4) { unit(gaussian(r)) }
        for ((k, c) in centres.withIndex()) repeat(6) { store.add(store.size.toLong(), "/s/$k-$it.jpg", 0, 0.9f, faceOf(c, r, 0.6f)) }
        val first = run(store)
        val before = LongArray(store.size) { store.personOf(it) }
        repeat(2) { store.add(store.size.toLong(), "/s/new$it.jpg", 0, 0.9f, faceOf(centres[1], r, 0.6f)) }
        val second = PeopleClusterer().run(store, finder, emptySet(), emptyMap(), first.nextPersonId, fixedPersons = emptySet())
        for (i in before.indices) assertEquals("person id changed on regroup", before[i], store.personOf(i))
        assertEquals(before[6], store.personOf(store.size - 1))                   // new faces joined, no new person
        assertEquals(first.nextPersonId, second.nextPersonId)
    }

    @Test
    fun regroupingHealsASplitFromAnEarlierScan() {
        // One character, two existing people (an earlier scan split it). Keeping people frozen never compares them
        // again; regrouping does.
        val r = Random(17)
        val c = unit(gaussian(r))
        val store = FaceStore()
        repeat(6) { store.add(it.toLong(), "/h/a$it.jpg", 3, 0.9f, faceOf(c, r, 0.6f)) }
        repeat(6) { store.add(10L + it, "/h/b$it.jpg", 4, 0.9f, faceOf(c, r, 0.6f)) }
        run(store, firstId = 5)
        assertNotEquals("frozen people should stay as they are", store.personOf(0), store.personOf(6))
        PeopleClusterer().run(store, finder, emptySet(), emptyMap(), 5, fixedPersons = emptySet())
        val p = store.personOf(0)
        assertTrue(p == 3L || p == 4L)                                            // an old id is reused
        for (i in 0 until 12) assertEquals(p, store.personOf(i))
    }

    @Test
    fun curatedPeopleAreKeptAndDifferentAnswersRespected() {
        val r = Random(19)
        val c = unit(gaussian(r))
        val store = FaceStore()
        repeat(6) { store.add(it.toLong(), "/k/a$it.jpg", 3, 0.9f, faceOf(c, r, 0.6f)) }
        repeat(6) { store.add(10L + it, "/k/b$it.jpg", 4, 0.9f, faceOf(c, r, 0.6f)) }
        repeat(3) { store.add(20L + it, "/k/n$it.jpg", 0, 0.9f, faceOf(c, r, 0.6f)) }
        // The user said 3 and 4 are different people: they stay apart even though they look alike.
        PeopleClusterer().run(store, finder, emptySet(), emptyMap(), 5, fixedPersons = setOf(3L, 4L), notSame = setOf(3L to 4L))
        for (i in 0 until 6) assertEquals(3L, store.personOf(i))
        for (i in 6 until 12) assertEquals(4L, store.personOf(i))
        for (i in 12 until 15) assertTrue(store.personOf(i) in setOf(3L, 4L))
    }

    @Test
    fun suggestionsOfferASplitButNotAnsweredOrSharedPhotoPairs() {
        val r = Random(23)
        val c = unit(gaussian(r)); val other = unit(gaussian(r))
        val store = FaceStore()
        repeat(5) { store.add(it.toLong(), "/q/a$it.jpg", 3, 0.9f, faceOf(c, r, 0.9f)) }
        repeat(5) { store.add(10L + it, "/q/b$it.jpg", 4, 0.9f, faceOf(c, r, 0.9f)) }
        repeat(5) { store.add(20L + it, "/q/o$it.jpg", 5, 0.9f, faceOf(other, r, 0.9f)) }
        val all = setOf(3L, 4L, 5L)
        val s = PeopleClusterer.suggestMerges(store, all, emptySet(), emptySet(), 0.2f, 10)
        assertEquals(listOf(3L to 4L), s.map { it.first to it.second })
        assertTrue(PeopleClusterer.suggestMerges(store, all, emptySet(), setOf(3L to 4L), 0.2f, 10).isEmpty())
        assertTrue(PeopleClusterer.suggestMerges(store, all, setOf(3L, 4L), emptySet(), 0.2f, 10).isEmpty())
        store.add(99, "/q/a0.jpg", 4, 0.9f, faceOf(c, r, 0.9f))                  // 3 and 4 now share a photo
        assertTrue(PeopleClusterer.suggestMerges(store, all, emptySet(), emptySet(), 0.2f, 10).isEmpty())
    }

    @Test
    fun bruteForceNeighboursAreExact() {
        val r = Random(13)
        val store = FaceStore()
        repeat(300) { store.add(it.toLong(), "/k/$it.jpg", 0, 0.5f, unit(gaussian(r))) }
        val q = intArrayOf(0, 17, 299)
        val base = IntArray(300) { it }
        val res = finder.find(store, q, base, 5, null)
        for ((i, row) in q.withIndex()) {
            val expected = base.filter { it != row }.sortedByDescending { store.dotRows(row, it) }.take(5)
            assertEquals(expected, (0 until 5).map { res.rows[i * 5 + it] })
        }
    }

    /**
     * Real embeddings exported by tools/face-eval (`export_fixture.py`): skipped unless FACE_EVAL_FIXTURE points at
     * one. Checks the Kotlin port reaches the precision the Python reference measured.
     */
    @Test
    fun realEmbeddingsFixture() {
        val path = System.getenv("FACE_EVAL_FIXTURE") ?: return
        val f = File(path)
        if (!f.exists()) return
        val (store, truth) = DataInputStream(f.inputStream().buffered()).use { inp ->
            val hdr = ByteArray(8); inp.readFully(hdr)
            val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val n = hb.int; val d = hb.int
            val lab = ByteArray(n * 4); inp.readFully(lab)
            val lb = ByteBuffer.wrap(lab).order(ByteOrder.LITTLE_ENDIAN)
            val truth = IntArray(n) { lb.int }
            val store = FaceStore(d)
            val row = ByteArray(d * 4)
            for (i in 0 until n) {
                inp.readFully(row)
                val fb = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                store.add(i.toLong(), "/fx/$i.jpg", 0, 0.8f, FloatArray(d) { fb.get(it) })
            }
            store to truth
        }
        val t0 = System.nanoTime()
        run(store)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // BCubed precision (the Python harness's headline metric), over grouped faces.
        val members = HashMap<Long, ArrayList<Int>>()
        for (i in truth.indices) store.personOf(i).let { if (it > 0) members.getOrPut(it) { ArrayList() }.add(i) }
        var prec = 0.0; var m = 0
        for (rows in members.values) {
            val counts = rows.groupingBy { truth[it] }.eachCount()
            for (i in rows) { prec += counts[truth[i]]!!.toDouble() / rows.size; m++ }
        }
        val bP = prec / m
        println("fixture ${truth.size} faces: ${members.count { it.value.size >= 2 }} people, BCubed precision %.4f, grouped %.1f%%, %d ms"
            .format(bP, 100.0 * m / truth.size, ms))
        assertTrue("BCubed precision $bP", bP >= 0.99)
    }
}
