package com.nestgallery.viewer.data.face

import kotlin.math.max
import kotlin.math.min

class ClusterResult(
    /** Store rows whose person id changed in this run. */
    val changedRows: IntArray,
    /** Person ids that exist after the run (each has >= 1 face). */
    val faceCountByPerson: Map<Long, Int>,
    /** Best representative store row per person (for the cover thumbnail). */
    val coverRowByPerson: Map<Long, Int>,
    val nextPersonId: Long
)

/**
 * Tunables for [PeopleClusterer], measured for the AdaFace IR-101 int8 recogniser (they are model-specific) on real
 * people (LFW), CGI renders (DigiFace-1M, incl. 72 renders per character) and a mix of both, see tools/face-eval.
 */
class ClusterConfig(
    /**
     * Two groups merge only while the *average* cosine similarity over every pair of their faces is at least this.
     * Average linkage is what stops one look-alike face from chaining two people together (the old single-link
     * clusterer merged whole groups through one bridging face). 0.42 rather than 0.45: on CGI characters with varied
     * expression / lighting it halves the "same character split in two" cases (69% -> 50% of characters) for ~0.3
     * points of grouping precision (mixed library 0.996 -> 0.994).
     */
    val linkThreshold: Float = 0.42f,
    /** A face left alone after merging joins the person whose faces it matches best on average, if at least this… */
    val attachThreshold: Float = 0.33f,
    /** …and if that person beats the runner-up by this much (an ambiguous face is better left ungrouped). */
    val attachMargin: Float = 0.06f,
    /** Neighbours looked up per face. Only these pairs can start a merge or an attach. */
    val k: Int = 24,
    /**
     * Two people whose faces match at least this well on average (but below [linkThreshold]) are offered to the user
     * as "Same person?" instead of being merged: in that band the score cannot tell one character split by lighting
     * or expression from two look-alikes.
     */
    val askThreshold: Float = 0.36f
)

/** Finds, for each query row, the [k] most similar rows of [base] (cosine, best first). */
fun interface NeighborFinder {
    fun find(store: FaceStore, queries: IntArray, base: IntArray, k: Int, onProgress: ((done: Int, total: Int) -> Unit)?): KnnResult
}

/** Row-major [count] x [k] neighbour table; `rows[i*k + j] == -1` marks an empty slot. */
class KnnResult(val k: Int, val rows: IntArray, val sims: FloatArray)

/**
 * Groups faces into people with constrained average-linkage agglomerative clustering.
 *
 * 1. **Neighbours.** Every face being (re)grouped gets its [ClusterConfig.k] nearest faces (through ONNX Runtime on the
 *    device, see `OnnxKnn`). Only those pairs are ever considered, so the cost is linear in the face count.
 * 2. **Merge.** Start with every kept person as one group and every other face on its own; repeatedly merge
 *    the two groups with the highest average similarity while it is >= [ClusterConfig.linkThreshold]. For unit
 *    vectors the average pairwise cosine is `sumA . sumB / (|A| |B|)`, so each group only keeps a running sum.
 *    Average linkage never rises when groups merge, so stale heap entries are upper bounds and are re-scored lazily.
 * 3. **Attach.** A face still alone joins the best-matching person if the match is good enough and clearly better
 *    than the second best ([ClusterConfig.attachMargin]); otherwise it stays ungrouped rather than being guessed.
 *
 * Hard constraints, checked on every merge and attach:
 *  - faces in the same photo are different people;
 *  - two people the user named are never merged;
 *  - two people the user said are different ([notSame]) are never merged;
 *  - a face the user removed from a person never goes back to that person ([rejections]).
 *
 * People in [fixedPersons] (named or otherwise curated by the user) are kept exactly as they are and only grow. Every
 * other person is regrouped from scratch on each run and then takes back the id of the old person it overlaps most,
 * so ids stay stable. Regrouping matters: grouping scan by scan and freezing the result locks in early mistakes in both
 * directions (tools/face-eval: 4 incremental passes tripled the impure groups on a mixed library, and a character
 * split early stayed split because two existing people were never compared again).
 */
class PeopleClusterer(private val cfg: ClusterConfig = ClusterConfig()) {

    fun run(
        store: FaceStore,
        finder: NeighborFinder,
        namedPersons: Set<Long>,
        /** store row -> person ids that face must never join */
        rejections: Map<Int, Set<Long>>,
        firstNewPersonId: Long,
        /** People kept as they are; every other person is regrouped. null = keep every existing person. */
        fixedPersons: Set<Long>? = null,
        /** Person id pairs (smaller id first) the user said are different people. */
        notSame: Set<Pair<Long, Long>> = emptySet(),
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ClusterResult {
        val n = store.size
        val original = LongArray(n)
        val previous = LongArray(n)                      // old person of a face being regrouped (for id reuse)
        val parent = IntArray(n) { it }
        val size = IntArray(n)
        val pid = LongArray(n)                           // person id of the group whose root is this row (0 = none)
        val sums = arrayOfNulls<FloatArray>(n)
        val files = arrayOfNulls<HashSet<Int>>(n)
        val rejects = arrayOfNulls<HashSet<Long>>(n)
        val version = IntArray(n)

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }

        // ---- initial groups: one per existing person, one per ungrouped face --------------------------------------
        val base = ArrayList<Int>()
        val queries = ArrayList<Int>()
        val rootOfPerson = HashMap<Long, Int>()
        for (r in 0 until n) {
            if (!store.isAlive(r)) continue
            val p = store.personOf(r)
            original[r] = p
            if (p < 0) continue                              // dismissed by the user
            base.add(r)
            size[r] = 1
            if (p == 0L || (fixedPersons != null && p !in fixedPersons)) { previous[r] = p; queries.add(r); continue }
            var root = rootOfPerson[p] ?: -1
            if (root < 0) {
                root = r; rootOfPerson[p] = r; pid[r] = p
                sums[r] = FloatArray(store.dim); files[r] = HashSet()
            } else { parent[r] = root; size[root]++ }
            addInto(sums[root]!!, store.embedding(r)); files[root]!!.add(store.fileIndex(r))
        }
        for ((r, set) in rejections) {
            if (r !in 0 until n || !store.isAlive(r) || store.personOf(r) < 0) continue
            val root = find(r)
            (rejects[root] ?: HashSet<Long>().also { rejects[root] = it }).addAll(set)
        }
        fun sumOf(root: Int): FloatArray = sums[root] ?: store.embedding(root).also { sums[root] = it }
        fun filesOf(root: Int): HashSet<Int> = files[root] ?: hashSetOf(store.fileIndex(root)).also { files[root] = it }

        if (queries.isEmpty()) return finish(store, n, original, previous, ::find, size, pid, sums, firstNewPersonId, ::sumOf)

        // ---- 1. neighbours ----------------------------------------------------------------------------------------
        // Progress is reported in per-mille: the neighbour search is ~90% of the work.
        val qArr = queries.toIntArray()
        val k = cfg.k
        val knn = finder.find(store, qArr, base.toIntArray(), k) { d, t -> onProgress?.invoke(900 * d / max(t, 1), 1000) }
        onProgress?.invoke(900, 1000)

        // ---- 2. constrained average-linkage merging ---------------------------------------------------------------
        fun linkage(a: Int, b: Int): Float = dot(sumOf(a), sumOf(b)) / (size[a].toFloat() * size[b].toFloat())

        fun allowed(a: Int, b: Int): Boolean {
            val pa = pid[a]; val pb = pid[b]
            if (pa > 0 && pb > 0 && pa in namedPersons && pb in namedPersons) return false
            if (pa > 0 && pb > 0 && notSame.isNotEmpty() && (minOf(pa, pb) to maxOf(pa, pb)) in notSame) return false
            if (pb > 0 && rejects[a]?.contains(pb) == true) return false
            if (pa > 0 && rejects[b]?.contains(pa) == true) return false
            val fa = filesOf(a); val fb = filesOf(b)
            val (small, large) = if (fa.size <= fb.size) fa to fb else fb to fa
            for (f in small) if (f in large) return false
            return true
        }

        fun merge(x: Int, y: Int): Int {
            val (a, b) = if (size[x] >= size[y]) x to y else y to x          // b is absorbed into a
            parent[b] = a
            addInto(sumOf(a), sumOf(b)); sums[b] = null          // sumOf() never aliases the store (copies a row)
            size[a] += size[b]
            val fa = filesOf(a); fa.addAll(filesOf(b)); files[b] = null
            rejects[b]?.let { rb -> (rejects[a] ?: HashSet<Long>().also { rejects[a] = it }).addAll(rb); rejects[b] = null }
            val pa = pid[a]; val pb = pid[b]
            pid[a] = when {
                pa > 0 && pb > 0 -> if (pb in namedPersons && pa !in namedPersons) pb else pa
                pa > 0 -> pa
                else -> pb
            }
            pid[b] = 0
            version[a]++
            return a
        }

        val heap = PairHeap(qArr.size * 4 + 16)
        for ((i, q) in qArr.withIndex()) {
            for (j in 0 until k) {
                val r = knn.rows[i * k + j]
                if (r < 0) break
                val s = knn.sims[i * k + j]
                if (s < cfg.linkThreshold) break                              // sorted best first
                if (r == q || store.personOf(r) < 0 || store.fileIndex(r) == store.fileIndex(q)) continue
                heap.push(s, q, r, 0, 0)
            }
        }
        val initialPairs = heap.size.coerceAtLeast(1)
        var popped = 0
        while (heap.size > 0) {
            heap.pop()
            val s = heap.topScore; val a0 = heap.topA; val b0 = heap.topB
            if (++popped % 4096 == 0) onProgress?.invoke(900 + 80 * min(popped, initialPairs) / initialPairs, 1000)
            val a = find(a0); val b = find(b0)
            if (a == b) continue
            if (a != a0 || b != b0 || version[a] != heap.topVa || version[b] != heap.topVb) {
                val fresh = linkage(a, b)                                     // <= s: average linkage is reducible
                if (fresh >= cfg.linkThreshold) heap.push(fresh, a, b, version[a], version[b])
                continue
            }
            if (s < cfg.linkThreshold || !allowed(a, b)) continue             // constraints only ever get stricter
            merge(a, b)
        }

        // ---- 3. attach faces that are still alone (decided against a frozen snapshot, then applied) ---------------
        val attachTo = IntArray(qArr.size) { -1 }
        val candidates = HashMap<Int, Float>()
        for ((i, q) in qArr.withIndex()) {
            if (size[find(q)] != 1) continue
            candidates.clear()
            val e = sumOf(find(q))
            for (j in 0 until k) {
                val r = knn.rows[i * k + j]
                if (r < 0) break
                if (r == q || store.personOf(r) < 0) continue
                val c = find(r)
                if (size[c] < 2 || c in candidates) continue
                candidates[c] = dot(e, sumOf(c)) / size[c]
            }
            if (candidates.isEmpty()) continue
            var best = -1; var bestSim = -2f; var second = -2f
            val myFile = store.fileIndex(q); val myRejects = rejects[find(q)]
            for ((c, sim) in candidates) {
                val ok = myFile !in filesOf(c) && !(pid[c] > 0 && myRejects?.contains(pid[c]) == true)
                if (ok && sim > bestSim) { if (best >= 0) second = max(second, bestSim); best = c; bestSim = sim }
                else second = max(second, sim)
            }
            if (best >= 0 && bestSim >= cfg.attachThreshold && bestSim - second >= cfg.attachMargin) attachTo[i] = best
        }
        for ((i, q) in qArr.withIndex()) {
            val c = attachTo[i]
            if (c < 0) continue
            val root = find(c)
            if (!filesOf(root).contains(store.fileIndex(q))) merge(root, find(q))   // re-check: two faces of one photo
        }

        return finish(store, n, original, previous, ::find, size, pid, sums, firstNewPersonId, ::sumOf)
    }

    private fun finish(
        store: FaceStore, n: Int, original: LongArray, previous: LongArray, find: (Int) -> Int, size: IntArray,
        pid: LongArray, sums: Array<FloatArray?>, firstNewPersonId: Long, sumOf: (Int) -> FloatArray
    ): ClusterResult {
        // A regrouped person takes back the old id it shares the most faces with (largest overlaps first, each old id
        // once), so "Person 12" stays Person 12 across scans; only genuinely new groups get new ids.
        val reuse = HashMap<Int, Long>()
        val overlap = HashMap<Int, HashMap<Long, Int>>()
        for (r in 0 until n) {
            if (previous[r] <= 0 || !store.isAlive(r) || original[r] < 0) continue
            val root = find(r)
            if (pid[root] > 0 || size[root] < 2) continue
            overlap.getOrPut(root) { HashMap() }.merge(previous[r], 1, Int::plus)
        }
        val used = HashSet<Long>()
        overlap.flatMap { (root, counts) -> counts.map { (p, c) -> Triple(c, root, p) } }
            .sortedWith(compareByDescending<Triple<Int, Int, Long>> { it.first }.thenBy { it.third })
            .forEach { (_, root, p) -> if (root !in reuse && p !in used) { reuse[root] = p; used.add(p) } }

        var nextId = firstNewPersonId
        val members = HashMap<Long, ArrayList<Int>>()
        val rootPid = HashMap<Int, Long>()
        for (r in 0 until n) {
            if (!store.isAlive(r) || original[r] < 0) continue
            val root = find(r)
            val p = rootPid.getOrPut(root) {
                when {
                    pid[root] > 0 -> pid[root]
                    size[root] >= 2 -> reuse[root] ?: nextId++
                    else -> 0L
                }
            }
            if (p != original[r]) store.setPerson(r, p)
            if (p > 0) members.getOrPut(p) { ArrayList() }.add(r)
        }
        val changed = ArrayList<Int>()
        for (r in 0 until n) if (store.isAlive(r) && original[r] >= 0 && store.personOf(r) != original[r]) changed.add(r)

        val counts = HashMap<Long, Int>(); val covers = HashMap<Long, Int>()
        for ((p, rows) in members) {
            counts[p] = rows.size
            val root = find(rows[0])
            val centroid = sumOf(root)
            val norm = kotlin.math.sqrt(dot(centroid, centroid)).coerceAtLeast(1e-6f)
            val qc = QVec.of(centroid)
            // A clear, typical face: high quality and close to the person's average look.
            covers[p] = rows.maxByOrNull { store.qualityOf(it) + 0.5f * store.dot(it, qc) / norm } ?: rows[0]
        }
        return ClusterResult(changed.toIntArray(), counts, covers, nextId)
    }

    companion object {
        /**
         * "Same person?" questions: pairs of [persons] whose faces match well on average (average linkage >= [minSim])
         * but were not merged automatically, best first. Below the merge threshold the score alone cannot tell a
         * character split by lighting / expression from two look-alike characters (tools/face-eval: in the 0.36-0.40
         * band 97% of pairs were one character on DigiFace-72, but nearly none on a set dense with look-alikes), so
         * the user decides. Pairs sharing a photo, two named people and pairs answered "different" are skipped.
         */
        fun suggestMerges(
            store: FaceStore, persons: Set<Long>, namedPersons: Set<Long>, notSame: Set<Pair<Long, Long>>,
            minSim: Float, max: Int
        ): List<Triple<Long, Long, Float>> {
            val sums = HashMap<Long, FloatArray>(); val counts = HashMap<Long, Int>(); val files = HashMap<Long, HashSet<Int>>()
            for (r in 0 until store.size) {
                if (!store.isAlive(r)) continue
                val p = store.personOf(r)
                if (p !in persons) continue
                addInto(sums.getOrPut(p) { FloatArray(store.dim) }, store.embedding(r))
                counts.merge(p, 1, Int::plus); files.getOrPut(p) { HashSet() }.add(store.fileIndex(r))
            }
            val ids = sums.keys.sorted()
            val means = ids.map { p -> val s = sums[p]!!; val c = counts[p]!!.toFloat(); FloatArray(s.size) { s[it] / c } }
            val out = ArrayList<Triple<Long, Long, Float>>()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val sim = dot(means[i], means[j])                   // = average cosine over every pair of their faces
                if (sim < minSim) continue
                val a = ids[i]; val b = ids[j]
                if (a in namedPersons && b in namedPersons) continue
                if ((a to b) in notSame) continue
                val fa = files[a]!!; val fb = files[b]!!
                if (if (fa.size <= fb.size) fa.any { it in fb } else fb.any { it in fa }) continue
                out.add(Triple(a, b, sim))
            }
            out.sortByDescending { it.third }
            return if (out.size > max) out.subList(0, max) else out
        }

        fun dot(a: FloatArray, b: FloatArray): Float {
            var s0 = 0f; var s1 = 0f; var s2 = 0f; var s3 = 0f
            var i = 0
            val n = min(a.size, b.size)
            while (i + 3 < n) {
                s0 += a[i] * b[i]; s1 += a[i + 1] * b[i + 1]; s2 += a[i + 2] * b[i + 2]; s3 += a[i + 3] * b[i + 3]
                i += 4
            }
            while (i < n) { s0 += a[i] * b[i]; i++ }
            return s0 + s1 + s2 + s3
        }

        fun addInto(acc: FloatArray, v: FloatArray) { for (i in acc.indices) acc[i] += v[i] }
    }
}

/** Binary max-heap of (score, a, b, versionA, versionB) in parallel primitive arrays (no per-entry objects). */
internal class PairHeap(initial: Int) {
    private var score = FloatArray(initial)
    private var a = IntArray(initial); private var b = IntArray(initial)
    private var va = IntArray(initial); private var vb = IntArray(initial)
    var size = 0
        private set
    var topScore = 0f; var topA = 0; var topB = 0; var topVa = 0; var topVb = 0
        private set

    fun push(s: Float, x: Int, y: Int, vx: Int, vy: Int) {
        if (size == score.size) {
            val c = size * 2
            score = score.copyOf(c); a = a.copyOf(c); b = b.copyOf(c); va = va.copyOf(c); vb = vb.copyOf(c)
        }
        var i = size++
        while (i > 0) {
            val p = (i - 1) / 2
            if (score[p] >= s) break
            move(p, i); i = p
        }
        score[i] = s; a[i] = x; b[i] = y; va[i] = vx; vb[i] = vy
    }

    /** Removes the maximum into the `top*` fields. */
    fun pop() {
        topScore = score[0]; topA = a[0]; topB = b[0]; topVa = va[0]; topVb = vb[0]
        size--
        if (size == 0) return
        val s = score[size]; val x = a[size]; val y = b[size]; val vx = va[size]; val vy = vb[size]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && score[c + 1] > score[c]) c++
            if (score[c] <= s) break
            move(c, i); i = c
        }
        score[i] = s; a[i] = x; b[i] = y; va[i] = vx; vb[i] = vy
    }

    private fun move(from: Int, to: Int) {
        score[to] = score[from]; a[to] = a[from]; b[to] = b[from]; va[to] = va[from]; vb[to] = vb[from]
    }
}
