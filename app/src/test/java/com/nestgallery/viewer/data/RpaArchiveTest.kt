package com.nestgallery.viewer.data

import com.nestgallery.viewer.data.rpa.RpaArchive
import com.nestgallery.viewer.data.rpa.RpaFs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

/**
 * Fixtures in test/resources/rpa were written with Python's pickle (protocols 0, 2, 4, 5) in Ren'Py's layout, as
 * RPA-2.0 / 3.0 / 3.2, with and without prefixes; Rentool's rpatool extracts all of them byte-identically.
 */
class RpaArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val fixtures = listOf("v3_p2.rpa", "v3_p2_prefix.rpa", "v3_p5_prefix.rpa", "v2_p0.rpa", "v32_p4.rpa")
    private fun fixture(name: String): File = File(tmp.root, name).also { f ->
        if (!f.exists()) f.writeBytes(javaClass.getResourceAsStream("/rpa/$name")!!.readBytes())
    }
    private val manifest: Map<String, Long> by lazy {
        javaClass.getResourceAsStream("/rpa/manifest.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
            .associate { it.substringBefore('\t') to it.substringAfter('\t').toLong() }
    }
    // the same bytes the generator wrote for this member
    private val roomPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0x1a, '\n'.code.toByte()) +
        ByteArray(256 * 3) { (it % 256).toByte() }

    @Test
    fun readsEveryFormatAndExtractsIdenticalBytes() {
        for (name in fixtures) {
            val f = fixture(name)
            assertTrue(name, RpaArchive.looksValid(f))
            val index = RpaArchive.readIndex(f)
            assertEquals(name, manifest.keys, index.keys)
            for ((path, size) in manifest) assertEquals("$name $path", size, index.getValue(path).length)
            val out = File(tmp.root, "$name.room")
            RpaArchive.extract(f, index.getValue("images/bg/room.png"), out)
            assertArrayEquals(name, roomPng, out.readBytes())
        }
    }

    @Test
    fun rejectsOtherFiles() {
        val junk = tmp.newFile("x.rpa").apply { writeText("PK\u0003\u0004 not an archive") }
        assertFalse(RpaArchive.looksValid(junk))
        val tooShort = tmp.newFile("y.rpa")
        assertFalse(RpaArchive.looksValid(tooShort))
    }

    /** Ren'Py on Python 2 pickled prefixes as str: SHORT_BINSTRING, read as latin-1 bytes. Hand-written pickle. */
    @Test
    fun python2StringsAndBigOffsets() {
        val p = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x80.toByte(), 2, '}'.code.toByte(), '('.code.toByte()))
            val k = "a.png".toByteArray(); write('U'.code); write(k.size); write(k)
            write(']'.code)
            write(0x8a); write(5); write(byteArrayOf(0, 0, 0, 0, 1))      // LONG1 4294967296 (> 32 bits)
            write('K'.code); write(7)                                    // BININT1 7
            write('U'.code); write(2); write(byteArrayOf(0xE9.toByte(), 0x01))  // py2 str prefix "\xe9\x01"
            write(0x87); write('a'.code); write('u'.code); write('.'.code)
        }.toByteArray()
        val header = "RPA-2.0 %016x\n".format(25).toByteArray()
        val f = tmp.newFile("py2.rpa")
        f.writeBytes(header + ByteArrayOutputStream().also { b -> DeflaterOutputStream(b).use { it.write(p) } }.toByteArray())
        val m = RpaArchive.readIndex(f).getValue("a.png")
        assertEquals(4294967296L, m.offset)
        assertEquals(7L, m.length)
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x01), m.prefix)
    }

    @Test
    fun archivesListLikeFolders() {
        RpaFs.initForTest(tmp.newFolder("cache"))
        val games = tmp.newFolder("games")
        fixture("v3_p5_prefix.rpa").copyTo(File(games, "images.rpa"))
        File(games, "readme.txt").writeText("hi")
        File(games, "cover.png").writeBytes(roomPng)

        // the archive is a folder; non-media files only with "show all"
        val top = listFolderFast(games, hideHidden = true)
        assertEquals(listOf("images.rpa", "cover.png"), top.map { it.name })
        assertTrue(top[0].isDirectory && top[0].isArchive)
        assertEquals(listOf("images.rpa", "cover.png", "readme.txt"), listFolderFast(games, hideHidden = true, showAll = true).map { it.name })
        assertFalse(listFolderFast(games, hideHidden = true, showAll = true).last().isMedia)

        // inside: folders from the member paths, hidden ones only on request, other files only with "show all"
        val archive = File(games, "images.rpa")
        assertEquals(listOf("images", "video"), listFolderFast(archive, hideHidden = true).map { it.name })
        assertEquals(listOf(".hidden", "images", "video", "script.rpyc"), listFolderFast(archive, hideHidden = false, showAll = true).map { it.name })
        val images = listFolderFast(archive, hideHidden = true).first().file
        assertTrue(RpaFs.isVirtual(images) && RpaFs.isReadOnly(images))
        val bg = listFolderFast(images, hideHidden = true).first { it.name == "bg" }.file
        val room = listFolderFast(bg, hideHidden = true).single()
        assertEquals("room.png", room.name)
        assertEquals(roomPng.size.toLong(), room.size)
        assertEquals(1, countMediaFast(bg))

        // nothing is extracted until asked for, then the member is an ordinary file
        assertFalse(room.file.exists())
        assertTrue(RpaFs.ensure(room.file))
        assertArrayEquals(roomPng, room.file.readBytes())

        // the recursive view walks into archives too
        val all = kotlinx.coroutines.runBlocking {
            val out = ArrayList<DocEntry>(); exploreMediaFlow(games, hideHidden = true).collect { out.addAll(it) }; out
        }
        assertEquals(setOf("cover.png", "room.png", "eileen happy.jpg", "intro.webm"), all.map { it.name }.toSet())
        assertEquals(manifest.getValue("video/intro.webm"), RpaFs.sizeOf(all.first { it.name == "intro.webm" }.file)?.first)

        // a whole folder inside the archive can be extracted for copying out
        assertTrue(RpaFs.ensureTree(images))
        assertTrue(File(images, "char/eileen happy.jpg").isFile)
        assertNull(RpaFs.list(games))   // a real folder isn't the archive layer's business
    }

    @Test
    fun extractKeepsFoldersAndStaysInside() {
        val f = fixture("v3_p2_prefix.rpa")
        val index = RpaArchive.readIndex(f)
        val folder = tmp.newFolder("out", "v3_p2_prefix")
        // a hostile member path must not escape the folder
        val evil = com.nestgallery.viewer.data.rpa.RpaMember("../../escaped.png", index.getValue("images/bg/room.png").offset,
            index.getValue("images/bg/room.png").length, index.getValue("images/bg/room.png").prefix)
        val failed = FileOps.extractArchive(f, index.values + evil, folder)
        assertEquals(0, failed)  // "../.." is dropped from the path, so it lands inside as escaped.png
        assertFalse(File(tmp.root, "escaped.png").exists())
        assertArrayEquals(roomPng, File(folder, "escaped.png").readBytes())
        for ((path, size) in manifest) assertEquals(path, size, File(folder, path).length())
        assertArrayEquals(roomPng, File(folder, "images/bg/room.png").readBytes())
        assertEquals(f.lastModified() / 1000, File(folder, "script.rpyc").lastModified() / 1000)
    }

    @Test
    fun archivesAreFilesWhileTheToggleIsOff() {
        RpaFs.initForTest(tmp.newFolder("cache2"))
        RpaFs.enabled = false
        try {
            val dir = tmp.newFolder("g2")
            fixture("v3_p2.rpa").copyTo(File(dir, "a.rpa"))
            assertEquals(emptyList<String>(), listFolderFast(dir, hideHidden = true).map { it.name })
            val all = listFolderFast(dir, hideHidden = true, showAll = true)
            assertEquals(listOf("a.rpa"), all.map { it.name })
            assertFalse(all[0].isDirectory || all[0].isArchive || all[0].isMedia)
            assertNull(RpaFs.list(File(dir, "a.rpa")))
        } finally { RpaFs.enabled = true }
    }
}
