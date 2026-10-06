package com.nestgallery.viewer.data.face

/**
 * Tunables for [PersonClusterer]. All thresholds are raw cosine similarities of the ArcFace (w600k_mbf)
 * embedding. Measured on the labeled sample set: same-person min 0.45 / median 0.74,
 * different-person max 0.24 / median 0.01 - so ~0.45-0.55 leaves a wide margin on both sides, and the
 * stricter "pair" thresholds guard against the heavier impostor tail you get once you compare
 * 10k+ faces against each other.
 */
class ClusterConfig(
    /** Score needed for a face to join a person that already has >= 2 faces. */
    val joinThreshold: Float = 0.45f,
    /** Same, when the person has a single face (a lone pair has no averaging to protect it). */
    val pairJoinThreshold: Float = 0.50f,
    /** Faces below [seedQuality] may only join, and need a higher score. */
    val lowQualityJoinThreshold: Float = 0.55f,
    val mergeThreshold: Float = 0.50f,
    val pairMergeThreshold: Float = 0.54f,
    val seedQuality: Float = FaceQuality.SEED_MIN_QUALITY,
    val maxExemplars: Int = 8,
    /** How many nearest-centroid persons get the (more expensive) exemplar comparison. */
    val candidatePersons: Int = 6,
    /** Centroid similarity below which two persons are never considered for merging. */
    val mergePrefilter: Float = 0.35f,
    val mergePasses: Int = 3
)

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
 * Incremental face clusterer.
 *
 * Why this replaces the old agglomerative (HAC) clusterer: HAC re-scored *every pair of clusters* on
 * *every merge*, each score being an O(|A|*|B|*512) double loop - effectively O(n^3 * 512). It cannot
 * finish for the 10k+ photo libraries this app targets. This one is:
 *
 *  1. **Incremental** - only faces with person_id = 0 are assigned; existing people (and the names the
 *     user typed) are never reshuffled.
 *  2. **Linear in new faces** - each face is compared with every person's centroid (one int8 dot each),
 *     and only the nearest few persons get an exemplar comparison.
 *  3. **Quality aware** - only good faces (size/pose/detector confidence) may *start* a person; poor ones
 *     can only join, so crowd/background faces don't create a thousand junk "people".
 *  4. **Constraint aware** - two faces from the same photo are never the same person, so a face can't
 *     join a person that already appears in its photo, and two persons that co-occur in a photo are never
 *     merged. This is what keeps siblings / look-alikes apart.
 *  5. **Repairs fragmentation** - a final merge pass joins persons whose centroids and exemplars agree,
 *     which bridges pose / age / lighting changes without the cubic cost.
 */
class PersonClusterer(private val cfg: ClusterConfig = ClusterConfig()) {

    private class Person(val id: Long, var named: Boolean, dim: Int) {
        val rows = ArrayList<Int>()
        val files = HashSet<Int>()
        val sum = FloatArray(dim)
        var exemplars = ArrayList<Int>()
        var alive = true
    }

    private class MergeCandidate(val i: Int, val j: Int, val score: Float)

    fun run(store: FaceStore, namedPersons: Set<Long>, firstNewPersonId: Long): ClusterResult {
        val dim = store.dim
        val n = store.size
        val persons = ArrayList<Person>()           // index in this list == row in [centroids]
        val byId = HashMap<Long, Person>()
        val centroids = Int8Matrix(dim)
        val unassigned = ArrayList<Int>()
        val originalPerson = LongArray(n)
        var nextId = firstNewPersonId

        // ---- 1. group already-assigned faces into persons -------------------------------------
        for (r in 0 until n) {
            if (!store.isAlive(r)) continue
            val pid = store.personOf(r)
            originalPerson[r] = pid
            if (pid > 0) {
                val p = byId.getOrPut(pid) { Person(pid, pid in namedPersons, dim).also { persons.add(it) } }
                addMember(store, p, r)
            } else if (pid == 0L) {
                unassigned.add(r)
            } // pid < 0: dismissed by the user, never clustered again
        }
        for (p in persons) {
            centroids.addRow(FaceMath.l2Normalize(p.sum))
            p.exemplars = pickExemplars(store, p.rows, cfg.maxExemplars)
        }

        // ---- 2. assign new faces, best quality first so strong faces seed the persons ----------
        unassigned.sortByDescending { store.qualityOf(it) }
        val candIdx = IntArray(cfg.candidatePersons)
        val candSim = FloatArray(cfg.candidatePersons)
        for (r in unassigned) {
            val qv = store.qvec(r)
            val file = store.fileIndex(r)
            val quality = store.qualityOf(r)
            val canSeed = quality >= cfg.seedQuality

            java.util.Arrays.fill(candSim, -2f)
            java.util.Arrays.fill(candIdx, -1)
            for (i in persons.indices) {
                val p = persons[i]
                if (!p.alive || file in p.files) continue          // cannot-link: same photo
                val c = centroids.dot(i, qv)
                if (c <= candSim[candSim.size - 1]) continue
                var k = candSim.size - 1
                while (k > 0 && candSim[k - 1] < c) { candSim[k] = candSim[k - 1]; candIdx[k] = candIdx[k - 1]; k-- }
                candSim[k] = c; candIdx[k] = i
            }

            var bestIdx = -1
            var bestScore = -2f
            for (k in candIdx.indices) {
                val i = candIdx[k]
                if (i < 0) break
                var m = -2f
                for (e in persons[i].exemplars) {
                    val s = store.dotRows(r, e)
                    if (s > m) m = s
                }
                val score = 0.5f * candSim[k] + 0.5f * m
                if (score > bestScore) { bestScore = score; bestIdx = i }
            }

            val threshold = when {
                bestIdx < 0 -> Float.MAX_VALUE
                !canSeed -> cfg.lowQualityJoinThreshold
                persons[bestIdx].rows.size < 2 -> cfg.pairJoinThreshold
                else -> cfg.joinThreshold
            }

            if (bestIdx >= 0 && bestScore >= threshold) {
                val p = persons[bestIdx]
                join(store, p, r)
                centroids.setRow(bestIdx, FaceMath.l2Normalize(p.sum))
                if (p.exemplars.size < cfg.maxExemplars) {
                    var maxS = -2f
                    for (e in p.exemplars) maxS = maxOf(maxS, store.dotRows(r, e))
                    if (maxS < 0.85f) p.exemplars.add(r)         // keep exemplars diverse
                }
            } else if (canSeed) {
                val p = Person(nextId++, false, dim)
                persons.add(p)
                join(store, p, r)
                centroids.addRow(FaceMath.l2Normalize(p.sum))
                p.exemplars.add(r)
            } // else: stays unassigned (person_id = 0) and may be picked up by a later run
        }

        // ---- 3. merge fragments of the same person --------------------------------------------
        mergePass(store, persons, centroids)

        // ---- 4. results -----------------------------------------------------------------------
        val changed = ArrayList<Int>()
        for (r in 0 until n) if (store.isAlive(r) && store.personOf(r) != originalPerson[r]) changed.add(r)

        val counts = HashMap<Long, Int>()
        val covers = HashMap<Long, Int>()
        for (i in persons.indices) {
            val p = persons[i]
            if (!p.alive || p.rows.isEmpty()) continue
            counts[p.id] = p.rows.size
            val cq = centroids.rowAsQVec(i)
            var best = p.rows[0]
            var bestScore = -9f
            for (r in p.rows) {
                val s = store.dot(r, cq) + 0.3f * store.qualityOf(r)
                if (s > bestScore) { bestScore = s; best = r }
            }
            covers[p.id] = best
        }
        return ClusterResult(changed.toIntArray(), counts, covers, nextId)
    }

    // -----------------------------------------------------------------------------------------

    private fun addMember(store: FaceStore, p: Person, row: Int) {
        p.rows.add(row)
        p.files.add(store.fileIndex(row))
        val e = store.embedding(row)
        for (k in e.indices) p.sum[k] += e[k]
    }

    private fun join(store: FaceStore, p: Person, row: Int) {
        addMember(store, p, row)
        store.setPerson(row, p.id)
    }

    /** Farthest-point sampling (seeded with the best-quality face) so exemplars cover pose/age modes. */
    private fun pickExemplars(store: FaceStore, rows: List<Int>, k: Int): ArrayList<Int> {
        val chosen = ArrayList<Int>()
        if (rows.isEmpty()) return chosen
        var first = rows[0]
        for (r in rows) if (store.qualityOf(r) > store.qualityOf(first)) first = r
        chosen.add(first)
        val maxSim = FloatArray(rows.size) { store.dotRows(rows[it], first) }
        while (chosen.size < k) {
            var pick = -1
            var lowest = Float.MAX_VALUE
            for (i in rows.indices) if (maxSim[i] < lowest) { lowest = maxSim[i]; pick = i }
            if (pick < 0 || lowest > 0.92f) break                // everything left is a near-duplicate
            val r = rows[pick]
            chosen.add(r)
            for (i in rows.indices) maxSim[i] = maxOf(maxSim[i], store.dotRows(rows[i], r))
        }
        return chosen
    }

    private fun mergePass(store: FaceStore, persons: ArrayList<Person>, centroids: Int8Matrix) {
        var dirty: BooleanArray? = null
        for (pass in 0 until cfg.mergePasses) {
            val alive = persons.indices.filter { persons[it].alive }
            val cands = ArrayList<MergeCandidate>()
            for (ai in alive.indices) {
                val i = alive[ai]
                for (aj in ai + 1 until alive.size) {
                    val j = alive[aj]
                    if (dirty != null && !dirty[i] && !dirty[j]) continue
                    val c = centroids.dotRows(i, j)
                    if (c < cfg.mergePrefilter) continue
                    val pi = persons[i]; val pj = persons[j]
                    if (pi.named && pj.named) continue
                    var m = -2f
                    for (a in pi.exemplars) for (b in pj.exemplars) {
                        val s = store.dotRows(a, b)
                        if (s > m) m = s
                    }
                    val score = 0.5f * c + 0.5f * m
                    val thr = if (pi.rows.size < 2 || pj.rows.size < 2) cfg.pairMergeThreshold else cfg.mergeThreshold
                    if (score >= thr) cands.add(MergeCandidate(i, j, score))
                }
            }
            if (cands.isEmpty()) return
            cands.sortByDescending { it.score }

            val nowDirty = BooleanArray(persons.size)
            var merged = false
            for (m in cands) {
                var a = persons[m.i]; var b = persons[m.j]
                if (!a.alive || !b.alive) continue
                if (a.named && b.named) continue
                if (sharePhoto(a, b)) continue                    // co-occur in a photo => different people
                var ia = m.i; var ib = m.j
                // survivor: the user-named one, else the larger one
                if ((b.named && !a.named) || (a.named == b.named && b.rows.size > a.rows.size)) {
                    val t = a; a = b; b = t
                    val ti = ia; ia = ib; ib = ti
                }
                absorb(store, a, b)
                centroids.setRow(ia, FaceMath.l2Normalize(a.sum))
                centroids.clearRow(ib)
                nowDirty[ia] = true
                merged = true
            }
            if (!merged) return
            dirty = nowDirty
        }
    }

    private fun absorb(store: FaceStore, into: Person, from: Person) {
        for (r in from.rows) store.setPerson(r, into.id)
        into.rows.addAll(from.rows)
        into.files.addAll(from.files)
        for (k in into.sum.indices) into.sum[k] += from.sum[k]
        into.named = into.named || from.named
        val union = ArrayList(into.exemplars)
        union.addAll(from.exemplars)
        into.exemplars = pickExemplars(store, union, cfg.maxExemplars)
        from.alive = false
        from.rows.clear(); from.files.clear(); from.exemplars = ArrayList()
    }

    private fun sharePhoto(a: Person, b: Person): Boolean {
        val small = if (a.files.size <= b.files.size) a.files else b.files
        val large = if (small === a.files) b.files else a.files
        for (f in small) if (f in large) return true
        return false
    }
}
