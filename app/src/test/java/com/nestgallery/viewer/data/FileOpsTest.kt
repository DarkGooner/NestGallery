package com.nestgallery.viewer.data

import com.nestgallery.viewer.data.face.FaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun freeNameNeverOverwrites() {
        val dir = tmp.newFolder("d")
        assertEquals("a.jpg", FileOps.freeName(dir, "a.jpg").name)
        File(dir, "a.jpg").writeText("x")
        assertEquals("a (1).jpg", FileOps.freeName(dir, "a.jpg").name)
        File(dir, "a (1).jpg").writeText("x")
        assertEquals("a (2).jpg", FileOps.freeName(dir, "a.jpg").name)
        // folders and dotfiles have no extension to keep
        File(dir, "Album").mkdir()
        assertEquals("Album (1)", FileOps.freeName(dir, "Album").name)
        File(dir, ".nomedia").writeText("")
        assertEquals(".nomedia (1)", FileOps.freeName(dir, ".nomedia").name)
    }

    @Test
    fun nameRules() {
        assertNull(FileOps.nameError("Holiday 2026"))
        assertNotNull(FileOps.nameError("   "))
        assertNotNull(FileOps.nameError(".."))
        assertNotNull(FileOps.nameError("a/b"))
        assertNotNull(FileOps.nameError("x".repeat(256)))
    }

    @Test
    fun walkListsFilesOnly() {
        val root = tmp.newFolder("w")
        File(root, "sub/deeper").mkdirs()
        File(root, "1.jpg").writeText("1")
        File(root, "sub/2.png").writeText("2")
        File(root, "sub/deeper/3.mp4").writeText("3")
        assertEquals(setOf("1.jpg", "2.png", "3.mp4"), FileOps.walk(root).map { it.name }.toSet())
        assertEquals(listOf(File(root, "1.jpg")), FileOps.walk(File(root, "1.jpg")))
    }

    @Test
    fun explorerSortsFoldersFirstThenByChoice() {
        val dir = tmp.newFolder("s")
        File(dir, "zeta").mkdir()
        File(dir, "alpha").mkdir()
        File(dir, "img10.jpg").apply { writeBytes(ByteArray(10)); setLastModified(3_000_000) }
        File(dir, "img2.jpg").apply { writeBytes(ByteArray(300)); setLastModified(1_000_000) }
        File(dir, "img1.jpg").apply { writeBytes(ByteArray(200)); setLastModified(2_000_000) }
        File(dir, "notes.txt").writeText("not media")

        fun names(by: SortBy, desc: Boolean) = listFolderFast(dir, hideHidden = true, sort = by, descending = desc).map { it.name }
        assertEquals(listOf("alpha", "zeta", "img1.jpg", "img2.jpg", "img10.jpg"), names(SortBy.NAME, false))
        assertEquals(listOf("zeta", "alpha", "img10.jpg", "img2.jpg", "img1.jpg"), names(SortBy.NAME, true))
        assertEquals(listOf("img2.jpg", "img1.jpg", "img10.jpg"), names(SortBy.DATE, false).drop(2))
        assertEquals(listOf("img2.jpg", "img1.jpg", "img10.jpg"), names(SortBy.SIZE, true).drop(2))
    }

    @Test
    fun faceStoreFollowsMovesAndDeletes() {
        val store = FaceStore(dim = 4)
        val a = store.add(1, "/s/A/x.jpg", 7, 1f, floatArrayOf(1f, 0f, 0f, 0f))
        val b = store.add(2, "/s/A/sub/y.jpg", 7, 1f, floatArrayOf(0f, 1f, 0f, 0f))
        val c = store.add(3, "/s/AB/z.jpg", 8, 1f, floatArrayOf(0f, 0f, 1f, 0f))

        // folder A moved to B: everything under A follows, the look-alike prefix "AB" doesn't
        val from = "/s/A"; val to = "/s/B"
        store.renamePaths { p -> if (p == from || p.startsWith("$from/")) to + p.substring(from.length) else null }
        assertEquals("/s/B/x.jpg", store.pathOf(a))
        assertEquals("/s/B/sub/y.jpg", store.pathOf(b))
        assertEquals("/s/AB/z.jpg", store.pathOf(c))
        // and folder-scoped search sees the new place
        assertEquals(1, store.search(floatArrayOf(1f, 0f, 0f, 0f), 0.5f, "/s/B").size)
        assertEquals(0, store.search(floatArrayOf(1f, 0f, 0f, 0f), 0.5f, "/s/A").size)

        val persons = store.removeFilesWhere { it == "/s/B/sub/y.jpg" }
        assertEquals(setOf(7L), persons)
        assertFalse(store.isAlive(b))
        assertTrue(store.isAlive(a))
        assertTrue(store.isAlive(c))
    }
}
