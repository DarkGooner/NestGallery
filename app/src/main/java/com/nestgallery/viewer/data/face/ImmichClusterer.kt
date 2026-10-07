package com.nestgallery.viewer.data.face

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
 * Tunables for [ImmichClusterer]. Immich's defaults, except [minFaces].
 */
class ImmichConfig(
    /** Cosine *distance* (1 - similarity). Immich's default 0.5; lower it for twins / look-alikes. */
    val maxDistance: Float = 0.5f,
    /**
     * Faces (including itself) needed within [maxDistance] for a face to be a core point. Immich's default is 3;
     * we use 2 so people who appear in only two photos are still grouped (measured: same split rate and precision
     * as 3, better coverage). Keep [FaceDatabase.MIN_FACES_TO_SHOW] in sync.
     */
    val minFaces: Int = 2,
    val neighborCap: Int = 48,
    val reconcile: Boolean = true,
    val sameImageConstraint: Boolean = true,
    val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
)

/**
 * Port of Immich's facial-recognition clustering (server/src/services/person.service.ts, handleRecognizeFaces),
 * a streaming derivative of DBSCAN.
 *
 * Per unassigned face (Immich semantics):
 *  1. look up the nearest faces within [ImmichConfig.maxDistance] (assigned or not; the face itself counts);
 *  2. only itself nearby  -> noise, skipped (retried on a later run);
 *  3. fewer than [ImmichConfig.minFaces] -> non-core: deferred until every other face has been processed;
 *  4. join the person of the nearest face that has one (single-link reach); a core face with no person nearby
 *     starts a new person; a non-core face with none stays unassigned (an outlier / stranger in the background).
 *
 * Two deliberate additions on top of the faithful port, both switchable in [ImmichConfig]:
 *  - [ImmichConfig.reconcile]: Immich never merges two people once created, so two clusters that a core face
 *    bridges stay split ("duplicate people"; Immich tells users to merge them by hand). DBSCAN would put them
 *    in one cluster; we merge them when a core face is within the distance of a core face of the other.
 *  - [ImmichConfig.sameImageConstraint]: faces in the same photo are different people, so they neither count as
 *    each other's neighbours nor may join a person already present in that photo (keeps siblings / twins apart).
 * People the user named are never merged with each other and always win a merge.
 */
class ImmichClusterer(private val cfg: ImmichConfig = ImmichConfig()) {

    fun run(
        store: FaceStore,
        namedPersons: Set<Long>,
        firstNewPersonId: Long,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ClusterResult {
        val minSim = 1f - cfg.maxDistance
        val n = store.size
        val members = HashMap<Long, ArrayList<Int>>()
        val files = HashMap<Long, HashSet<Int>>()
        val original = LongArray(n)
        val todo = ArrayList<Int>()
        for (r in 0 until n) {
            if (!store.isAlive(r)) continue
            val pid = store.personOf(r)
            original[r] = pid
            if (pid > 0) {
                members.getOrPut(pid) { ArrayList() }.add(r)
                files.getOrPut(pid) { HashSet() }.add(store.fileIndex(r))
            } else if (pid == 0L) todo.add(r)
        }
        todo.sortByDescending { store.qualityOf(it) }          // strong faces first: they become the cores

        var nextId = firstNewPersonId
        val coreCache = HashMap<Int, Boolean>()
        val deferred = ArrayList<Int>()
        val pool: ExecutorService? = if (cfg.threads > 1) Executors.newFixedThreadPool(cfg.threads) else null

        fun scan(row: Int): Neighbors = store.neighbors(store.qvec(row), minSim, cfg.neighborCap, pool, cfg.threads)

        /** Indices into [nb] of usable neighbours (not the face itself, not from the same photo). */
        fun usable(nb: Neighbors, self: Int): IntArray {
            val myFile = store.fileIndex(self)
            val out = IntArray(nb.count); var m = 0
            for (k in 0 until nb.count) {
                val r = nb.rows[k]
                if (r == self) continue
                if (cfg.sameImageConstraint && store.fileIndex(r) == myFile) continue
                out[m++] = k
            }
            return out.copyOf(m)
        }

        fun isCore(row: Int): Boolean = coreCache.getOrPut(row) { 1 + usable(scan(row), row).size >= cfg.minFaces }

        fun canMerge(a: Long, b: Long): Boolean {
            if (a in namedPersons && b in namedPersons) return false
            if (!cfg.sameImageConstraint) return true
            val fa = files[a] ?: return true; val fb = files[b] ?: return true
            val small = if (fa.size <= fb.size) fa else fb; val large = if (small === fa) fb else fa
            for (f in small) if (f in large) return false
            return true
        }

        fun merge(a: Long, b: Long): Long {
            val keep = when {
                a in namedPersons -> a
                b in namedPersons -> b
                (members[a]?.size ?: 0) != (members[b]?.size ?: 0) -> if ((members[a]?.size ?: 0) > (members[b]?.size ?: 0)) a else b
                else -> minOf(a, b)
            }
            val drop = if (keep == a) b else a
            val moved = members.remove(drop) ?: ArrayList()
            for (r in moved) store.setPerson(r, keep)
            members.getOrPut(keep) { ArrayList() }.addAll(moved)
            files.getOrPut(keep) { HashSet() }.addAll(files.remove(drop) ?: emptySet())
            return keep
        }

        fun process(row: Int, isDeferredPass: Boolean) {
            val nb = scan(row)
            val use = usable(nb, row)
            if (cfg.minFaces > 1 && use.isEmpty()) return                    // only itself nearby: noise
            val core = 1 + use.size >= cfg.minFaces
            coreCache[row] = core
            if (!core && !isDeferredPass) { deferred.add(row); return }

            val myFile = store.fileIndex(row)
            var pid = 0L
            for (k in use) {                                                  // nearest first
                val p = store.personOf(nb.rows[k])
                if (p > 0 && !(cfg.sameImageConstraint && files[p]?.contains(myFile) == true)) { pid = p; break }
            }
            if (pid == 0L && core) {
                pid = nextId++
                members[pid] = ArrayList(); files[pid] = HashSet()
            }
            if (pid == 0L) return

            store.setPerson(row, pid)
            members.getOrPut(pid) { ArrayList() }.add(row)
            files.getOrPut(pid) { HashSet() }.add(myFile)

            if (cfg.reconcile && core) {
                var cur = pid
                for (k in use) {
                    val q = store.personOf(nb.rows[k])
                    if (q > 0 && q != cur && canMerge(cur, q) && isCore(nb.rows[k])) cur = merge(cur, q)
                }
            }
        }

        try {
            for ((i, r) in todo.withIndex()) {
                process(r, false)
                if (i % 50 == 0) onProgress?.invoke(i, todo.size + deferred.size)
            }
            for ((i, r) in deferred.withIndex()) {
                process(r, true)
                if (i % 50 == 0) onProgress?.invoke(todo.size + i, todo.size + deferred.size)
            }
        } finally {
            pool?.shutdown()
        }

        val changed = ArrayList<Int>()
        for (r in 0 until n) if (store.isAlive(r) && store.personOf(r) != original[r]) changed.add(r)
        val counts = HashMap<Long, Int>(); val covers = HashMap<Long, Int>()
        for ((pid, rows) in members) {
            if (rows.isEmpty()) continue
            counts[pid] = rows.size
            covers[pid] = rows.maxByOrNull { store.qualityOf(it) } ?: rows[0]
        }
        return ClusterResult(changed.toIntArray(), counts, covers, nextId)
    }
}
