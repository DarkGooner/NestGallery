import com.nestgallery.viewer.data.face.*
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "PASS  " else "FAIL  ") + name + (if (detail.isNotEmpty()) "   [$detail]" else ""))
    if (!ok) failures++
}

fun floats(line: String): FloatArray = line.trim().split(" ").map { it.toFloat() }.toFloatArray()

fun pairMetrics(assign: IntArray, truth: IntArray): Triple<Double, Double, Int> {
    val cell = HashMap<Long, Int>(); val cl = HashMap<Int, Int>(); val tr = HashMap<Int, Int>()
    for (i in assign.indices) {
        if (assign[i] <= 0) continue
        cell.merge(assign[i].toLong() shl 32 or truth[i].toLong(), 1, Int::plus)
        cl.merge(assign[i], 1, Int::plus)
    }
    for (i in truth.indices) tr.merge(truth[i], 1, Int::plus)
    fun c2(x: Int) = x.toLong() * (x - 1) / 2
    val tp = cell.entries.filter { (it.key shr 32).toInt() > 0 }.sumOf { c2(it.value) }.toDouble()
    val sameCluster = cl.values.sumOf { c2(it) }.toDouble()
    val sameTruth = tr.values.sumOf { c2(it) }.toDouble()
    return Triple(if (sameCluster == 0.0) 1.0 else tp / sameCluster, tp / sameTruth, cl.size)
}

fun randUnit(r: Random, d: Int): FloatArray {
    val v = FloatArray(d) { r.nextGaussian().toFloat() }
    return FaceMath.l2Normalize(v)
}

fun main() {
    val dir = "/home/claude/jvm/"

    // ---------- T1: similarity transform vs Umeyama reference ----------
    run {
        var maxErr = 0f; var n = 0
        for (line in File(dir + "sim_expected.txt").readLines()) {
            val v = floats(line); n++
            val t = FaceMath.estimateSimilarity(v.copyOfRange(0, 10), FaceMath.ARCFACE_TEMPLATE)
            maxErr = max(maxErr, max(abs(t.a - v[10]), max(abs(t.b - v[11]), max(abs(t.tx - v[12]), abs(t.ty - v[13])))))
            // inverse round-trip
            val inv = t.inverse()
            val x = 10f; val y = 20f
            val fx = t.a * x - t.b * y + t.tx; val fy = t.b * x + t.a * y + t.ty
            val rx = inv.a * fx - inv.b * fy + inv.tx; val ry = inv.b * fx + inv.a * fy + inv.ty
            maxErr = max(maxErr, max(abs(rx - x), abs(ry - y)) / 100f)
        }
        check("T1 similarity transform == Umeyama reference ($n faces) + inverse round-trip", maxErr < 2e-3f, "max err %.5f".format(maxErr))
    }

    // ---------- T2: SCRFD decode + NMS vs Python ----------
    run {
        val lines = File(dir + "scrfd_raw.txt").readLines()
        val scores = Array(3) { floats(lines[it]) }
        val boxes = Array(3) { floats(lines[3 + it]) }
        val kps = Array(3) { floats(lines[6 + it]) }
        val exp = File(dir + "scrfd_expected.txt").readLines()
        val s = exp[0].toFloat()
        val expected = exp.drop(1).map { floats(it) }
        val dets = ScrfdDecoder.decode(scores, boxes, kps, 640, 640)
        var err = 0f
        val okCount = dets.size == expected.size
        if (okCount) for (i in dets.indices) {
            val d = dets[i]; val e = expected[i]
            err = max(err, abs(d.x1 / s - e[0])); err = max(err, abs(d.y2 / s - e[3])); err = max(err, abs(d.score - e[4]))
            for (k in 0 until 10) err = max(err, abs(d.landmarks[k] / s - e[5 + k]))
        }
        check("T2 SCRFD decode matches reference (${dets.size} faces)", okCount && err < 0.05f, "max err %.4f px".format(err))
    }

    // ---------- T3: int8 store search vs exact float cosine ----------
    val names = ArrayList<String>(); val truth = ArrayList<String>(); val embs = ArrayList<FloatArray>()
    for (line in File(dir + "emb.txt").readLines()) {
        val p = line.split("|"); truth.add(p[0]); names.add(p[1]); embs.add(floats(p[2]))
    }
    val truthIdx = run { val m = HashMap<String, Int>(); IntArray(truth.size) { m.getOrPut(truth[it]) { m.size } } }
    run {
        val store = FaceStore()
        for (i in embs.indices) store.add(i.toLong(), "/photos/${names[i]}", 0, 0.9f, embs[i])
        var maxErr = 0f
        for (q in embs.indices) {
            val hits = store.search(embs[q], -1f)
            for (h in hits) maxErr = max(maxErr, abs(h.similarity - FaceMath.dot(embs[q], embs[h.row])))
        }
        check("T3 int8 store similarity == float cosine (all ${embs.size}x${embs.size} pairs)", maxErr < 0.003f, "max err %.5f".format(maxErr))
        val hits = store.search(embs[0], 0.4f)
        val allSame = hits.all { truthIdx[it.row] == truthIdx[0] }
        check("T3 search(thr 0.40) returns only same-identity faces", allSame, "${hits.size} hits")
        // folder scoping
        val s2 = FaceStore()
        s2.add(1, "/a/b/x.jpg", 0, 1f, embs[0]); s2.add(2, "/a/bc/y.jpg", 0, 1f, embs[0]); s2.add(3, "/a/b/sub/z.jpg", 0, 1f, embs[0])
        val scoped = s2.search(embs[0], 0.5f, "/a/b/").map { s2.faceId(it.row) }.toSet()
        check("T3 folder scope is a true path-prefix match (/a/b !~ /a/bc)", scoped == setOf(1L, 3L), scoped.toString())
        s2.removeFile("/a/b/x.jpg")
        check("T3 removeFile tombstones faces", s2.search(embs[0], 0.5f).none { s2.faceId(it.row) == 1L })
    }

    // ---------- T4: clustering real embeddings (25 photos, 9 people) ----------
    fun clusterReal(order: List<Int>, batches: Int): Triple<Double, Double, Int> {
        val store = FaceStore(); val rowOf = IntArray(embs.size) { -1 }
        val clusterer = ImmichClusterer()
        var nextId = 1L
        val chunk = (order.size + batches - 1) / batches
        for (b in 0 until batches) {
            for (i in order.drop(b * chunk).take(chunk)) rowOf[i] = store.add(i.toLong(), "/p/${names[i]}", 0, 0.9f, embs[i])
            val res = clusterer.run(store, emptySet(), nextId); nextId = res.nextPersonId
        }
        val assign = IntArray(embs.size) { store.personOf(rowOf[it]).toInt() }
        return pairMetrics(assign, truthIdx)
    }
    run {
        val rnd = Random(7); var worstP = 1.0; var worstR = 1.0; var clusters = IntArray(0)
        val cs = ArrayList<Int>()
        for (trial in 0 until 20) {
            val order = embs.indices.shuffled(rnd)
            val (p, r, c) = clusterReal(order, if (trial % 2 == 0) 1 else 4)
            worstP = min(worstP, p); worstR = min(worstR, r); cs.add(c)
        }
        check("T4 real faces: pairwise precision = 1.0 over 20 random orders (1 batch & 4 incremental batches)", worstP == 1.0, "worst precision %.3f".format(worstP))
        check("T4 real faces: pairwise recall >= 0.95", worstR >= 0.95, "worst recall %.3f, persons found %d..%d (truth 9)".format(worstR, cs.min(), cs.max()))
    }

    // ---------- T5: cannot-link (same photo) ----------
    run {
        val store = FaceStore()
        // two near-identical faces in the SAME photo, plus two more photos of that person
        val e = embs[0]
        store.add(1, "/p/one.jpg", 0, 0.9f, e); store.add(2, "/p/one.jpg", 0, 0.9f, e)
        store.add(3, "/p/two.jpg", 0, 0.9f, e); store.add(4, "/p/three.jpg", 0, 0.9f, e)
        ImmichClusterer().run(store, emptySet(), 1)
        val p1 = store.personOf(0); val p2 = store.personOf(1)
        check("T5 two faces in one photo never share a person", p1 != p2 && p1 > 0 && p2 > 0, "p1=$p1 p2=$p2")
    }

    // ---------- T6: user-named person survives & absorbs; low-quality faces never seed ----------
    run {
        val store = FaceStore()
        val a = store.add(1, "/p/a.jpg", 7, 0.9f, embs[0]); store.add(2, "/p/b.jpg", 0, 0.9f, embs[0])
        val lowq = store.add(3, "/p/c.jpg", 0, 0.2f, embs[10])   // different person, poor quality
        val r = ImmichClusterer().run(store, setOf(7L), 100)
        check("T6 new face joins the user-named person (id 7)", store.personOf(1) == 7L)
        check("T6 low-quality unmatched face stays unassigned (does not create a person)", store.personOf(lowq) == 0L)
        check("T6 stable id: named person kept", r.faceCountByPerson[7L] == 2)
    }

    // ---------- T7: scale + accuracy on synthetic 20k faces, vs the OLD hierarchical clusterer ----------
    fun synth(nIdent: Int, perIdent: IntArray, seed: Long): Triple<List<FloatArray>, IntArray, IntArray> {
        val r = Random(seed); val d = FaceMath.EMBED_DIM
        val centers = List(nIdent) { randUnit(r, d) }
        val out = ArrayList<FloatArray>(); val tr = ArrayList<Int>(); val fileOf = ArrayList<Int>()
        var file = 0
        for (i in 0 until nIdent) for (k in 0 until perIdent[i]) {
            val rho = 0.35f + 0.45f * r.nextFloat()                 // per-face "how typical" -> genuine pair cos ~0.35..0.8
            val noise = randUnit(r, d)
            val v = FloatArray(d) { sqrt(rho) * centers[i][it] + sqrt(1 - rho) * noise[it] }
            out.add(FaceMath.l2Normalize(v)); tr.add(i); fileOf.add(file++)
        }
        return Triple(out, tr.toIntArray(), fileOf.toIntArray())
    }

    // old algorithm, ported 1:1 (centroid + average + max-pair blend, rescan of all pairs after each merge)
    fun oldHac(e: List<FloatArray>, thr: Float): Pair<IntArray, Long> {
        val t0 = System.nanoTime()
        val d = e[0].size
        class C(val faces: ArrayList<Int>, var centroid: FloatArray)
        fun cen(c: ArrayList<Int>): FloatArray { val s = FloatArray(d); for (f in c) for (k in 0 until d) s[k] += e[f][k]; return FaceMath.l2Normalize(s) }
        val cl = ArrayList<C>(); for (i in e.indices) cl.add(C(arrayListOf(i), e[i]))
        fun sim(a: C, b: C): Float {
            val cs = FaceMath.dot(a.centroid, b.centroid); var mx = -1f; var sum = 0f; var cnt = 0
            for (x in a.faces) for (y in b.faces) { val s = FaceMath.dot(e[x], e[y]); if (s > mx) mx = s; sum += s; cnt++ }
            return 0.5f * cs + 0.3f * (sum / cnt) + 0.2f * mx
        }
        var merged = true
        while (merged && cl.size > 1) {
            merged = false; var bi = -1; var bj = -1; var hs = thr
            for (i in cl.indices) for (j in i + 1 until cl.size) { val s = sim(cl[i], cl[j]); if (s > hs) { hs = s; bi = i; bj = j; merged = true } }
            if (merged) { cl[bi].faces.addAll(cl[bj].faces); cl[bi].centroid = cen(cl[bi].faces); cl.removeAt(bj) }
        }
        val a = IntArray(e.size); for ((ci, c) in cl.withIndex()) for (f in c.faces) a[f] = ci
        return Pair(a, (System.nanoTime() - t0) / 1_000_000)
    }

    println("\n--- scaling: OLD hierarchical clusterer vs NEW (synthetic, same data) ---")
    for (n in intArrayOf(200, 400, 800)) {
        val per = IntArray(n / 8) { 8 }
        val (e, tr, files) = synth(n / 8, per, 1)
        val (oa, oms) = oldHac(e, 0.62f)
        val store = FaceStore(); val rows = IntArray(e.size) { store.add(it.toLong(), "/f/${files[it]}", 0, 0.9f, e[it]) }
        val t0 = System.nanoTime(); ImmichClusterer().run(store, emptySet(), 1)
        val nms = (System.nanoTime() - t0) / 1_000_000
        val (op, orc, oc) = pairMetrics(oa, tr)
        val (np, nr, nc) = pairMetrics(IntArray(e.size) { store.personOf(rows[it]).toInt() }, tr)
        println("n=%5d faces | OLD %7d ms  (prec %.3f rec %.3f, %d clusters) | NEW %5d ms (prec %.3f rec %.3f, %d clusters) | truth %d".format(n, oms, op, orc, oc, nms, np, nr, nc, n / 8))
    }

    println("\n--- NEW clusterer at library scale (synthetic, JVM) ---")
    for ((nIdent, total) in listOf(500 to 8000, 1500 to 25000)) {
        val r = Random(3)
        val per = IntArray(nIdent) { 3 + (r.nextDouble().let { it * it * it } * (2.0 * total / nIdent - 3)).toInt() }
        val (e, tr, files0) = synth(nIdent, per, 2)
        // put some faces of different people into shared photos (co-occurrence), 3 faces/photo for 30% of faces
        val perm = (0 until e.size).shuffled(r); val files = IntArray(e.size)
        var ph = 0; var idx = 0
        while (idx < perm.size) { val k = if (r.nextInt(100) < 30) 3 else 1; for (j in 0 until k) if (idx + j < perm.size) files[perm[idx + j]] = ph; ph++; idx += k }
        val store = FaceStore()
        val rows = IntArray(e.size) { store.add(it.toLong(), "/f/${files[it]}", 0, if (it % 4 == 0) 0.3f else 0.8f, e[it]) }
        val t0 = System.nanoTime(); val res = ImmichClusterer().run(store, emptySet(), 1); val ms = (System.nanoTime() - t0) / 1_000_000
        val assign = IntArray(e.size) { store.personOf(rows[it]).toInt() }
        val (p, rc, c) = pairMetrics(assign, tr)
        val unassigned = assign.count { it == 0 }
        val mem = store.size.toLong() * 512 / 1_000_000
        println("faces=%6d identities=%5d -> %5d persons | precision %.4f recall %.4f | unassigned (low-q, unmatched) %d | %d ms | embedding memory ~%d MB (float32 would be %d MB)".format(e.size, nIdent, c, p, rc, unassigned, ms, mem, mem * 4))
        // second, incremental run adding 10% new faces
    }

    // ---------- T8: search latency at scale ----------
    run {
        val r = Random(5); val store = FaceStore(); val n = 50_000
        for (i in 0 until n) store.add(i.toLong(), "/f/${i / 2}.jpg", 0, 0.8f, randUnit(r, 512))
        val q = randUnit(r, 512)
        store.search(q, 0.3f)
        val t0 = System.nanoTime(); repeat(10) { store.search(q, 0.3f, "/f") }; val ms = (System.nanoTime() - t0) / 10 / 1_000_000.0
        println("\nsearch over %d faces: %.1f ms per query (JVM, single thread)".format(n, ms))
    }

    println("\n" + if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
}
