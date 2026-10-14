package com.nestgallery.viewer.data.rpa

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.util.zip.InflaterInputStream

/** One file inside a Ren'Py archive: [length] bytes at [offset], the first of them [prefix] (stored in the index). */
class RpaMember(val path: String, val offset: Long, val length: Long, val prefix: ByteArray)

/**
 * Reads Ren'Py archives (.rpa), the same formats Rentool's rpatool reads: RPA-2.0, RPA-3.0 and RPA-3.2.
 *
 *     RPA-3.0 <index offset, 16 hex> <key, 8 hex>\n   (3.2: "RPA-3.2 <offset> <ignored> <key parts...>")
 *
 * At the offset: a zlib-compressed pickle of {path: [(offset, length[, prefix])]}; in v3 offset and length are XOR'd
 * with the key (the XOR of every key field). A member's data is its prefix followed by length - prefix.size bytes read
 * at offset. RPA-1.0 (separate .rpi index, Ren'Py 6.2 era) is not supported.
 */
object RpaArchive {

    class Invalid(message: String) : Exception(message)

    /** True if [file] starts like an archive this reader understands (cheap: reads the first line). */
    fun looksValid(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { headerOf(it) != null }
    } catch (e: Exception) { false }

    /** Every member, by path inside the archive ('/' separated). */
    fun readIndex(file: File): Map<String, RpaMember> = RandomAccessFile(file, "r").use { raf ->
        val (offset, key) = headerOf(raf) ?: throw Invalid("Not a Ren'Py archive (or an unsupported version)")
        if (offset <= 0 || offset >= raf.length()) throw Invalid("Archive index offset out of range")
        raf.seek(offset)
        val compressed = ByteArray((raf.length() - offset).toInt())
        raf.readFully(compressed)
        val raw = InflaterInputStream(compressed.inputStream()).readBytes()
        val index = Pickle(raw).load() as? Map<*, *> ?: throw Invalid("Archive index is not a dictionary")
        val out = LinkedHashMap<String, RpaMember>(index.size)
        for ((k, v) in index) {
            val path = when (k) { is String -> k; is ByteArray -> String(k, Charsets.UTF_8); else -> continue }
                .replace('\\', '/').trimStart('/')
            val first = (v as? List<*>)?.firstOrNull() as? List<*> ?: continue
            val o = (first.getOrNull(0) as? Number)?.toLong() ?: continue
            val l = (first.getOrNull(1) as? Number)?.toLong() ?: continue
            val prefix = when (val p = first.getOrNull(2)) {
                is ByteArray -> p
                is String -> p.toByteArray(Charsets.ISO_8859_1)    // py2 str / latin1, as rpatool's _unmangle
                else -> ByteArray(0)
            }
            out[path] = RpaMember(path, o xor key, l xor key, prefix)
        }
        out
    }

    /** Writes [member]'s data to [to]. */
    fun extract(archive: File, member: RpaMember, to: File) {
        RandomAccessFile(archive, "r").use { raf ->
            to.outputStream().buffered(256 * 1024).use { out ->
                out.write(member.prefix)
                var left = member.length - member.prefix.size
                if (left < 0 || member.offset < 0 || member.offset + left > raf.length()) throw Invalid("Member ${member.path} out of range")
                raf.seek(member.offset)
                val buf = ByteArray(256 * 1024)
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw Invalid("Archive ended inside ${member.path}")
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        }
    }

    /** (index offset, key) from the first line, or null if it isn't an RPA-2.0 / 3.0 / 3.2 header. */
    private fun headerOf(raf: RandomAccessFile): Pair<Long, Long>? {
        raf.seek(0)
        val line = ByteArray(minOf(256L, raf.length()).toInt()).also { raf.readFully(it) }
            .let { b -> String(b, Charsets.ISO_8859_1).substringBefore('\n') }
        val parts = line.trim().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val offset = parts[1].toLongOrNull(16) ?: return null
        fun keyOf(fields: List<String>): Long? = fields.fold(0L) { k, f -> k xor (f.toLongOrNull(16) ?: return null) }
        return when (parts[0]) {
            "RPA-2.0" -> offset to 0L
            "RPA-3.0" -> offset to (keyOf(parts.drop(2)) ?: return null)
            "RPA-3.2" -> offset to (keyOf(parts.drop(3)) ?: return null)
            else -> null
        }
    }
}

/**
 * The subset of Python's pickle (protocols 0-5) that Ren'Py archive indexes use: dicts, lists, tuples, ints, str and
 * bytes. Bytes pickled by Python 3 at protocol < 3 arrive as `_codecs.encode(str, "latin1")`, which is evaluated;
 * any other global / object becomes an opaque value.
 */
internal class Pickle(private val data: ByteArray) {
    private var pos = 0
    private val stack = ArrayList<Any?>()
    private val marks = ArrayList<Int>()
    private val memo = HashMap<Int, Any?>()

    private object Mark
    private class Global(val module: String, val name: String)
    private object Opaque

    fun load(): Any? {
        while (pos < data.size) {
            when (val op = u8()) {
                0x80 -> u8()                                         // PROTO
                0x95 -> pos += 8                                     // FRAME
                '.'.code -> return stack.removeAt(stack.size - 1)                // STOP
                '('.code -> { marks.add(stack.size) }                // MARK
                '}'.code -> stack.add(LinkedHashMap<Any?, Any?>())   // EMPTY_DICT
                ']'.code -> stack.add(ArrayList<Any?>())             // EMPTY_LIST
                ')'.code -> stack.add(emptyList<Any?>())             // EMPTY_TUPLE
                'N'.code -> stack.add(null)
                0x88 -> stack.add(true)
                0x89 -> stack.add(false)
                'J'.code -> stack.add(le(4).toInt().toLong())        // BININT (signed 32)
                'K'.code -> stack.add(u8().toLong())                 // BININT1
                'M'.code -> stack.add(le(2))                         // BININT2
                'I'.code -> stack.add(line().let { if (it == "01") true else if (it == "00") false else it.toLong() })
                'L'.code -> stack.add(line().removeSuffix("L").toBigInteger().toLong())
                0x8a -> stack.add(longBytes(u8()))                   // LONG1
                0x8b -> stack.add(longBytes(le(4).toInt()))          // LONG4
                'X'.code -> stack.add(utf8(le(4).toInt()))           // BINUNICODE
                0x8c -> stack.add(utf8(u8()))                        // SHORT_BINUNICODE
                0x8d -> stack.add(utf8(le(8).toInt()))               // BINUNICODE8
                'V'.code -> stack.add(rawUnicodeEscape(line()))      // UNICODE
                'T'.code -> stack.add(bytes(le(4).toInt()))          // BINSTRING (py2 str)
                'U'.code -> stack.add(bytes(u8()))                   // SHORT_BINSTRING
                'B'.code -> stack.add(bytes(le(4).toInt()))          // BINBYTES
                'C'.code -> stack.add(bytes(u8()))                   // SHORT_BINBYTES
                0x8e -> stack.add(bytes(le(8).toInt()))              // BINBYTES8
                0x96 -> stack.add(bytes(le(8).toInt()))              // BYTEARRAY8
                'S'.code -> stack.add(pyStringLiteral(line()))       // STRING (protocol 0)
                't'.code -> stack.add(popMark())                     // TUPLE
                0x85 -> stack.add(listOf(pop()))                     // TUPLE1
                0x86 -> { val b = pop(); val a = pop(); stack.add(listOf(a, b)) }
                0x87 -> { val c = pop(); val b = pop(); val a = pop(); stack.add(listOf(a, b, c)) }
                'l'.code -> stack.add(ArrayList(popMark()))          // LIST
                'd'.code -> stack.add(LinkedHashMap<Any?, Any?>().also { m -> popMark().chunked(2).forEach { m[it[0]] = it.getOrNull(1) } })
                'a'.code -> { val v = pop(); list(top()).add(v) }    // APPEND
                'e'.code -> { val items = popMark(); list(top()).addAll(items) }
                's'.code -> { val v = pop(); val k = pop(); dict(top())[k] = v }
                'u'.code -> { val items = popMark(); val d = dict(top()); items.chunked(2).forEach { d[it[0]] = it.getOrNull(1) } }
                0x94 -> memo[memo.size] = stack.last()               // MEMOIZE
                'q'.code -> memo[u8()] = stack.last()                // BINPUT
                'r'.code -> memo[le(4).toInt()] = stack.last()       // LONG_BINPUT
                'p'.code -> memo[line().toInt()] = stack.last()      // PUT
                'h'.code -> stack.add(memo[u8()])                    // BINGET
                'j'.code -> stack.add(memo[le(4).toInt()])           // LONG_BINGET
                'g'.code -> stack.add(memo[line().toInt()])          // GET
                '0'.code -> pop()                                    // POP
                '2'.code -> stack.add(stack.last())                  // DUP
                'c'.code -> { val m = line(); val n = line(); stack.add(Global(m, n)) }
                0x93 -> { val n = pop() as? String ?: ""; val m = pop() as? String ?: ""; stack.add(Global(m, n)) }
                'R'.code -> { val args = pop() as? List<*> ?: emptyList<Any?>(); stack.add(call(pop(), args)) }
                0x81 -> { pop(); pop(); stack.add(Opaque) }          // NEWOBJ
                'b'.code -> pop()                                    // BUILD: state is dropped
                else -> throw RpaArchive.Invalid("Unsupported pickle opcode 0x%02x at %d".format(op, pos - 1))
            }
        }
        throw RpaArchive.Invalid("Archive index ended without STOP")
    }

    private fun call(f: Any?, args: List<*>): Any? {
        val g = f as? Global ?: return Opaque
        return when ("${g.module}.${g.name}") {
            "_codecs.encode" -> (args.getOrNull(0) as? String)?.toByteArray(Charsets.ISO_8859_1) ?: ByteArray(0)
            "__builtin__.bytes", "builtins.bytes" -> (args.getOrNull(0) as? ByteArray) ?: ByteArray(0)
            "__builtin__.set", "builtins.set", "__builtin__.frozenset", "builtins.frozenset" -> (args.getOrNull(0) as? List<*>)?.toList() ?: emptyList<Any?>()
            else -> Opaque
        }
    }

    private fun pop(): Any? = stack.removeAt(stack.size - 1)
    private fun top(): Any? = stack.last()
    private fun popMark(): List<Any?> {
        val at = marks.removeAt(marks.size - 1)
        val items = ArrayList(stack.subList(at, stack.size))
        while (stack.size > at) stack.removeAt(stack.size - 1)
        return items
    }

    @Suppress("UNCHECKED_CAST") private fun list(v: Any?) = v as? MutableList<Any?> ?: throw RpaArchive.Invalid("APPEND to a non-list")
    @Suppress("UNCHECKED_CAST") private fun dict(v: Any?) = v as? MutableMap<Any?, Any?> ?: throw RpaArchive.Invalid("SETITEM on a non-dict")

    private fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    private fun le(n: Int): Long { need(n); var v = 0L; for (i in 0 until n) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i)); pos += n; return v }
    private fun bytes(n: Int): ByteArray { need(n); return data.copyOfRange(pos, pos + n).also { pos += n } }
    private fun utf8(n: Int) = String(bytes(n), Charsets.UTF_8)
    private fun line(): String {
        val end = (pos until data.size).firstOrNull { data[it] == '\n'.code.toByte() } ?: throw RpaArchive.Invalid("Unterminated pickle line")
        return String(data, pos, end - pos, Charsets.ISO_8859_1).also { pos = end + 1 }
    }
    private fun need(n: Int) { if (n < 0 || pos + n > data.size) throw RpaArchive.Invalid("Archive index is truncated") }

    /** LONG1 / LONG4: little-endian two's complement. */
    private fun longBytes(n: Int): Long {
        if (n == 0) return 0L
        val b = bytes(n).reversedArray()
        return BigInteger(b).toLong()
    }

    private fun rawUnicodeEscape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && (s[i + 1] == 'u' || s[i + 1] == 'U')) {
                val len = if (s[i + 1] == 'u') 4 else 8
                out.appendCodePoint(s.substring(i + 2, i + 2 + len).toInt(16)); i += 2 + len
            } else out.append(s[i++])
        }
        return out.toString()
    }

    /** Protocol 0 STRING: a quoted Python 2 literal with backslash escapes; kept as latin-1 bytes. */
    private fun pyStringLiteral(s: String): ByteArray {
        val body = s.trim().let { if (it.length >= 2 && (it[0] == '\'' || it[0] == '"')) it.substring(1, it.length - 1) else it }
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) { out.write(c.code); i++; continue }
            when (val e = body[i + 1]) {
                'n' -> { out.write('\n'.code); i += 2 }
                'r' -> { out.write('\r'.code); i += 2 }
                't' -> { out.write('\t'.code); i += 2 }
                'x' -> { out.write(body.substring(i + 2, i + 4).toInt(16)); i += 4 }
                in '0'..'7' -> { var j = i + 1; var v = 0; while (j < body.length && j < i + 4 && body[j] in '0'..'7') { v = v * 8 + (body[j] - '0'); j++ }; out.write(v); i = j }
                else -> { out.write(e.code); i += 2 }
            }
        }
        return out.toByteArray()
    }
}
