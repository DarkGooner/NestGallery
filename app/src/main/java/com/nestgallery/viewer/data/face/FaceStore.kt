package com.nestgallery.viewer.data.face

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.roundToInt

/** A vector quantised to int8 with one scale factor (value ~= q[i] * scale). */
class QVec(val q: ByteArray, val scale: Float) {
    companion object {
        fun of(v: FloatArray): QVec {
            var m = 0f
            for (x in v) { val a = abs(x); if (a > m) m = a }
            val scale = if (m <= 0f) 1f else m / 127f
            val q = ByteArray(v.size)
            val inv = 1f / scale
            for (i in v.indices) q[i] = (v[i] * inv).roundToInt().coerceIn(-127, 127).toByte()
            return QVec(q, scale)
        }
    }
}

/**
 * Row-major int8 matrix stored in fixed-size chunks (so we never need one huge contiguous
 * allocation - 50k faces would be 25 MB here vs 100 MB as float32).
 * Cosine between two unit vectors is recovered as  intDot * scaleA * scaleB  (error < 0.002).
 * NOT thread-safe by itself; [FaceStore] guards it.
 */
class Int8Matrix(val dim: Int, private val rowsPerChunk: Int = 2048) {
    private val chunks = ArrayList<ByteArray>()
    private var scales = FloatArray(256)
    var rows = 0
        private set

    fun addRow(v: FloatArray): Int {
        val row = rows
        if (row / rowsPerChunk >= chunks.size) chunks.add(ByteArray(rowsPerChunk * dim))
        if (row >= scales.size) scales = scales.copyOf(scales.size * 2)
        rows++
        setRow(row, v)
        return row
    }

    fun setRow(row: Int, v: FloatArray) {
        val qv = QVec.of(v)
        System.arraycopy(qv.q, 0, chunks[row / rowsPerChunk], (row % rowsPerChunk) * dim, dim)
        scales[row] = qv.scale
    }

    fun clearRow(row: Int) {
        java.util.Arrays.fill(chunks[row / rowsPerChunk], (row % rowsPerChunk) * dim, (row % rowsPerChunk + 1) * dim, 0.toByte())
        scales[row] = 1f
    }

    fun rowAsQVec(row: Int): QVec {
        val out = ByteArray(dim)
        System.arraycopy(chunks[row / rowsPerChunk], (row % rowsPerChunk) * dim, out, 0, dim)
        return QVec(out, scales[row])
    }

    fun getRow(row: Int, out: FloatArray = FloatArray(dim)): FloatArray {
        val chunk = chunks[row / rowsPerChunk]
        val off = (row % rowsPerChunk) * dim
        val s = scales[row]
        for (i in 0 until dim) out[i] = chunk[off + i] * s
        return out
    }

    fun dot(row: Int, q: QVec): Float =
        rawDot(chunks[row / rowsPerChunk], (row % rowsPerChunk) * dim, q.q) * scales[row] * q.scale

    fun dotRows(a: Int, b: Int): Float {
        val ca = chunks[a / rowsPerChunk]; val oa = (a % rowsPerChunk) * dim
        val cb = chunks[b / rowsPerChunk]; val ob = (b % rowsPerChunk) * dim
        var s0 = 0; var s1 = 0; var s2 = 0; var s3 = 0
        var i = 0
        while (i < dim) {
            s0 += ca[oa + i] * cb[ob + i]
            s1 += ca[oa + i + 1] * cb[ob + i + 1]
            s2 += ca[oa + i + 2] * cb[ob + i + 2]
            s3 += ca[oa + i + 3] * cb[ob + i + 3]
            i += 4
        }
        return (s0 + s1 + s2 + s3) * scales[a] * scales[b]
    }

    private fun rawDot(chunk: ByteArray, off: Int, q: ByteArray): Int {
        var s0 = 0; var s1 = 0; var s2 = 0; var s3 = 0
        var i = 0
        while (i < dim) {
            s0 += chunk[off + i] * q[i]
            s1 += chunk[off + i + 1] * q[i + 1]
            s2 += chunk[off + i + 2] * q[i + 2]
            s3 += chunk[off + i + 3] * q[i + 3]
            i += 4
        }
        return s0 + s1 + s2 + s3
    }
}

class FaceHit(val row: Int, val similarity: Float)

/** Nearest faces within a similarity floor, strongest first (at most `cap`). */
class Neighbors(val rows: IntArray, val sims: FloatArray, val count: Int)

/**
 * In-memory index of every face embedding plus the metadata the clusterer and search need.
 * Built once from SQLite (streamed), then kept in sync as the scanner adds faces. All public methods are
 * thread-safe (many readers / one writer).
 *
 * Search is an exact brute-force scan: ~1 int8 dot-product (512 MACs) per face. For 50k faces that is
 * ~25M MACs, a few tens of ms - which is why no approximate (HNSW/IVF) index is needed at this scale.
 */
class FaceStore(val dim: Int = FaceMath.EMBED_DIM) {
    private val lock = ReentrantReadWriteLock()
    private val matrix = Int8Matrix(dim)

    private var faceIds = LongArray(1024)
    private var fileIdx = IntArray(1024)
    private var personIds = LongArray(1024)
    private var qualities = FloatArray(1024)
    private var alive = BooleanArray(1024)

    private val paths = ArrayList<String>()
    private val pathIndex = HashMap<String, Int>()
    private val fileRows = ArrayList<MutableList<Int>>()

    val size: Int get() = lock.read { matrix.rows }
    val liveCount: Int get() = lock.read { (0 until matrix.rows).count { alive[it] } }

    /** @return row index */
    fun add(faceId: Long, path: String, personId: Long, quality: Float, embedding: FloatArray): Int = lock.write {
        val row = matrix.addRow(embedding)
        if (row >= faceIds.size) {
            val n = faceIds.size * 2
            faceIds = faceIds.copyOf(n); fileIdx = fileIdx.copyOf(n); personIds = personIds.copyOf(n)
            qualities = qualities.copyOf(n); alive = alive.copyOf(n)
        }
        val fi = pathIndex.getOrPut(path) {
            paths.add(path); fileRows.add(ArrayList()); paths.size - 1
        }
        faceIds[row] = faceId; fileIdx[row] = fi; personIds[row] = personId
        qualities[row] = quality; alive[row] = true
        fileRows[fi].add(row)
        row
    }

    /** Tombstones every face of a file (used when a changed photo is re-scanned). */
    fun removeFile(path: String) = lock.write {
        val fi = pathIndex[path] ?: return@write
        for (r in fileRows[fi]) { alive[r] = false; matrix.clearRow(r) }
        fileRows[fi].clear()
    }

    fun maxPersonId(): Long = lock.read { var m = 0L; for (r in 0 until matrix.rows) if (alive[r] && personIds[r] > m) m = personIds[r]; m }
    /** Sets person_id back to 0 for every live face whose person is not in [keep] (dismissed faces stay dismissed). */
    fun unassignExcept(keep: Set<Long>) = lock.write {
        for (r in 0 until matrix.rows) if (alive[r] && personIds[r] > 0 && personIds[r] !in keep) personIds[r] = 0
    }

    fun isAlive(row: Int) = lock.read { alive[row] }
    fun faceId(row: Int): Long = lock.read { faceIds[row] }
    fun fileIndex(row: Int): Int = lock.read { fileIdx[row] }
    fun pathOf(row: Int): String = lock.read { paths[fileIdx[row]] }
    fun personOf(row: Int): Long = lock.read { personIds[row] }
    fun qualityOf(row: Int): Float = lock.read { qualities[row] }
    fun setPerson(row: Int, personId: Long) = lock.write { personIds[row] = personId }
    fun embedding(row: Int): FloatArray = lock.read { matrix.getRow(row) }
    fun qvec(row: Int): QVec = lock.read { matrix.rowAsQVec(row) }
    fun dotRows(a: Int, b: Int): Float = lock.read { matrix.dotRows(a, b) }
    fun dot(row: Int, q: QVec): Float = lock.read { matrix.dot(row, q) }

    /** Rows (alive) with the given person id. */
    fun rowsOfPerson(personId: Long): IntArray = lock.read {
        val out = ArrayList<Int>()
        for (r in 0 until matrix.rows) if (alive[r] && personIds[r] == personId) out.add(r)
        out.toIntArray()
    }

    /**
     * Exact cosine search. [folderPath] restricts results to photos inside that folder tree
     * (null = everything). Results are sorted by similarity, best first.
     */
    fun search(query: FloatArray, minSimilarity: Float, folderPath: String? = null, maxHits: Int = Int.MAX_VALUE): List<FaceHit> =
        lock.read {
            val q = QVec.of(query)
            val inScope = scopeMask(folderPath)
            val hits = ArrayList<FaceHit>()
            for (r in 0 until matrix.rows) {
                if (!alive[r]) continue
                if (inScope != null && !inScope[fileIdx[r]]) continue
                val s = matrix.dot(r, q)
                if (s >= minSimilarity) hits.add(FaceHit(r, s))
            }
            hits.sortByDescending { it.similarity }
            if (hits.size > maxHits) hits.subList(maxHits, hits.size).clear()
            hits
        }

    /**
     * The (at most [cap]) most similar live faces with similarity >= [minSim], strongest first. Faces the user
     * dismissed (person_id < 0) are ignored. With a [pool] the row range is split over [parts] workers; each
     * takes the read lock itself, so the caller must NOT hold the store lock while calling this.
     */
    fun neighbors(query: QVec, minSim: Float, cap: Int, pool: ExecutorService? = null, parts: Int = 1): Neighbors {
        val total = size
        if (pool == null || parts <= 1 || total < 4096) return scanRange(query, minSim, cap, 0, total)
        val step = (total + parts - 1) / parts
        val futures = ArrayList<Future<Neighbors>>(parts)
        var from = 0
        while (from < total) {
            val a = from; val b = minOf(total, from + step)
            futures.add(pool.submit<Neighbors> { scanRange(query, minSim, cap, a, b) })
            from = b
        }
        val rows = IntArray(cap); val sims = FloatArray(cap); var n = 0
        for (f in futures) {
            val part = f.get()
            for (i in 0 until part.count) n = insertSorted(rows, sims, n, cap, part.rows[i], part.sims[i])
        }
        return Neighbors(rows, sims, n)
    }

    private fun scanRange(q: QVec, minSim: Float, cap: Int, from: Int, to: Int): Neighbors = lock.read {
        val rows = IntArray(cap); val sims = FloatArray(cap); var n = 0
        val end = minOf(to, matrix.rows)
        for (r in from until end) {
            if (!alive[r] || personIds[r] < 0) continue
            val s = matrix.dot(r, q)
            if (s >= minSim && (n < cap || s > sims[n - 1])) n = insertSorted(rows, sims, n, cap, r, s)
        }
        Neighbors(rows, sims, n)
    }

    private fun insertSorted(rows: IntArray, sims: FloatArray, n: Int, cap: Int, row: Int, sim: Float): Int {
        if (n == cap && sim <= sims[n - 1]) return n
        var k = if (n < cap) n else n - 1
        while (k > 0 && sims[k - 1] < sim) { rows[k] = rows[k - 1]; sims[k] = sims[k - 1]; k-- }
        rows[k] = row; sims[k] = sim
        return if (n < cap) n + 1 else n
    }

    /** Distinct person ids having at least one face inside [folderPath]. */
    fun personsInFolder(folderPath: String): Set<Long> = lock.read {
        val inScope = scopeMask(folderPath)!!
        val out = HashSet<Long>()
        for (r in 0 until matrix.rows) if (alive[r] && personIds[r] > 0 && inScope[fileIdx[r]]) out.add(personIds[r])
        out
    }

    private fun scopeMask(folderPath: String?): BooleanArray? {
        if (folderPath == null) return null
        val root = folderPath.trimEnd(File.separatorChar, '/')
        val prefix = root + File.separatorChar
        return BooleanArray(paths.size) { i -> paths[i] == root || paths[i].startsWith(prefix) }
    }
}
