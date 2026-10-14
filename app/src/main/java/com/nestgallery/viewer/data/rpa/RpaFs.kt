package com.nestgallery.viewer.data.rpa

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Ren'Py archives (.rpa) as read-only folders.
 *
 * An archive's members get real-looking paths under the app cache, `<cache>/rpa/<id>/<path inside the archive>`, where
 * id is derived from the archive's path, size and modified time (so an archive that changes gets fresh paths). The
 * explorer lists them from the archive's index ([list]); a member's file is only written when something needs its
 * bytes ([ensure]: thumbnails through Coil, the viewer, VLC, copy, share). Everything else in the app then sees an
 * ordinary file. `<cache>/rpa/<id>.src` remembers which archive an id belongs to, so paths survive a restart.
 */
object RpaFs {
    /** A folder or file directly inside a listed folder (real or virtual). */
    class Node(val file: File, val name: String, val isDirectory: Boolean, val size: Long, val modified: Long, val isArchive: Boolean)

    private class Opened(val archive: File, val dir: File, val members: Map<String, RpaMember>, val tree: Map<String, List<Pair<String, RpaMember?>>>)

    @Volatile private var root: File? = null
    private val opened = ConcurrentHashMap<String, Opened>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun init(context: Context) { if (root == null) root = File(context.cacheDir, "rpa") }

    /** For tests: where virtual paths live. */
    fun initForTest(cacheRoot: File) { root = cacheRoot; opened.clear() }

    fun isArchiveName(name: String) = name.endsWith(".rpa", ignoreCase = true)

    /** An .rpa file that really is an archive this app can open. */
    fun isArchive(f: File): Boolean = isArchiveName(f.name) && f.isFile && RpaArchive.looksValid(f)

    /** A path inside an archive (its virtual folder or a member). */
    fun isVirtual(f: File): Boolean = root?.let { f.path.startsWith(it.path + "/") } ?: false

    /** Archive paths can't be moved, renamed or deleted; [f] is one, or the archive opened as a folder is not. */
    fun isReadOnly(f: File): Boolean = isVirtual(f)

    /**
     * The folders and files directly inside [dir] when it is an archive or a folder inside one, or null when it is
     * neither (then list it from disk). An unreadable archive lists as empty.
     */
    fun list(dir: File): List<Node>? {
        val (o, rel) = resolve(dir) ?: return null
        if (o == null) return emptyList()
        val base = if (rel.isEmpty()) o.dir else File(o.dir, rel)
        val modified = o.archive.lastModified()
        return o.tree[rel].orEmpty().map { (name, member) ->
            Node(File(base, name), name, member == null, member?.length ?: 0L, modified, isArchive = false)
        }
    }

    /** The virtual folder [archive] opens as (its root), or null if it can't be read. */
    fun folderOf(archive: File): File? = open(archive)?.dir

    /**
     * Makes sure [f] exists on disk: for a member not extracted yet, extracts it (blocking; call off the main thread).
     * Real files are returned as they are. False if it can't be had (unknown archive, member, read error).
     */
    fun ensure(f: File): Boolean {
        if (!isVirtual(f)) return f.exists()
        val (o, rel) = resolve(f) ?: return false
        o ?: return false
        val member = o.members[rel] ?: return f.isDirectory || o.tree.containsKey(rel)
        if (f.isFile && f.length() == member.length) return true
        synchronized(locks.getOrPut(f.path) { Any() }) {
            if (f.isFile && f.length() == member.length) return true
            return try {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".part")
                RpaArchive.extract(o.archive, member, tmp)
                tmp.setLastModified(o.archive.lastModified())
                tmp.renameTo(f)
            } catch (e: Exception) {
                false
            }
        }
    }

    /** Extracts every member under [dir] (a virtual folder) so it can be walked like a real folder. */
    fun ensureTree(dir: File): Boolean {
        val (o, rel) = resolve(dir) ?: return dir.exists()
        o ?: return false
        if (rel in o.members) return ensure(dir)
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        var ok = true
        for (path in o.members.keys) if (path.startsWith(prefix)) ok = ensure(File(o.dir, path)) && ok
        File(o.dir, rel).mkdirs()
        return ok
    }

    /** Total size of [f] and everything under it per the archive index (nothing is extracted), or null if not virtual. */
    fun sizeOf(f: File): Pair<Long, Int>? {
        val (o, rel) = resolve(f) ?: return null
        o ?: return 0L to 0
        o.members[rel]?.let { return it.length to 1 }
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        val inside = o.members.values.filter { it.path.startsWith(prefix) }
        return inside.sumOf { it.length } to inside.size
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    /** (opened archive or null if unreadable, path inside it) for an archive file or a virtual path; null otherwise. */
    private fun resolve(f: File): Pair<Opened?, String>? {
        val r = root ?: return null
        if (isVirtual(f)) {
            val rest = f.path.removePrefix(r.path + "/")
            val id = rest.substringBefore('/')
            val rel = if ('/' in rest) rest.substringAfter('/') else ""
            val o = opened[id] ?: File(r, "$id.src").takeIf { it.isFile }?.readText()?.let { open(File(it)) }?.takeIf { it.dir.name == id }
            return o to rel.trimEnd('/')
        }
        if (isArchiveName(f.name) && f.isFile) return open(f) to ""
        return null
    }

    private fun idOf(archive: File): String {
        val key = "${archive.absolutePath}|${archive.length()}|${archive.lastModified()}"
        return MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun open(archive: File): Opened? {
        val r = root ?: return null
        val id = idOf(archive)
        opened[id]?.let { return it }
        val members = try { RpaArchive.readIndex(archive) } catch (e: Exception) { return null }
        val tree = HashMap<String, LinkedHashMap<String, RpaMember?>>()
        tree[""] = LinkedHashMap()
        for (m in members.values) {
            val parts = m.path.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
            if (parts.isEmpty()) continue
            var dir = ""
            for (i in 0 until parts.size - 1) {
                val child = if (dir.isEmpty()) parts[i] else "$dir/${parts[i]}"
                tree.getOrPut(dir) { LinkedHashMap() }.putIfAbsent(parts[i], null)
                tree.getOrPut(child) { LinkedHashMap() }
                dir = child
            }
            tree.getOrPut(dir) { LinkedHashMap() }[parts.last()] = m
        }
        // members keyed by their cleaned path, so a lookup by virtual path always matches
        val byPath = members.values.associateBy { it.path.split('/').filter { p -> p.isNotEmpty() && p != "." && p != ".." }.joinToString("/") }
        val o = Opened(archive, File(r, id), byPath, tree.mapValues { (_, v) -> v.entries.map { it.key to it.value } })
        r.mkdirs()
        try { File(r, "$id.src").writeText(archive.absolutePath) } catch (_: Exception) {}
        // keep a few archives' indexes in memory (big games have tens of thousands of members)
        if (opened.size >= 8) opened.keys.firstOrNull()?.let { opened.remove(it) }
        opened[id] = o
        return o
    }
}
