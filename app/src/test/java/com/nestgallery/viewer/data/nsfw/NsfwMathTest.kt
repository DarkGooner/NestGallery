package com.nestgallery.viewer.data.nsfw

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NsfwMathTest {

    private val labels = NsfwLabels.NUDENET
    private fun cls(label: String) = labels.indexOf(label)

    /** A YOLO output [4 + classes, anchors] with the given anchors: (cx, cy, w, h, class, score). */
    private fun output(vararg anchors: FloatArray, classes: Int = labels.size): FloatArray {
        val n = anchors.size
        val out = FloatArray((4 + classes) * n)
        for ((a, v) in anchors.withIndex()) {
            for (k in 0 until 4) out[k * n + a] = v[k]
            out[(4 + v[4].toInt()) * n + a] = v[5]
        }
        return out
    }

    private fun anchor(cx: Float, cy: Float, w: Float, h: Float, label: String, score: Float) =
        floatArrayOf(cx, cy, w, h, cls(label).toFloat(), score)

    @Test
    fun boxesAreMappedToTheOriginalPhoto() {
        // a 1600x900 photo runs at 320x180 (padded to 320x192): every model pixel is 5 photo pixels
        val out = output(anchor(100f, 50f, 40f, 20f, "FACE_FEMALE", 0.81f))
        val d = YoloDecoder.decode(out, 1, labels, 320, 180, 1600, 900).single()
        assertEquals("FACE_FEMALE", d.label)
        assertEquals(0.81f, d.score, 1e-6f)
        assertEquals(listOf(400, 200, 200, 100), listOf(d.x, d.y, d.w, d.h))
    }

    @Test
    fun boxesAreClippedToThePhoto() {
        // centre near the bottom-right corner, half of the box in the padding
        val d = YoloDecoder.decode(output(anchor(310f, 170f, 40f, 40f, "FEET_EXPOSED", 0.6f)), 1, labels, 320, 180, 320, 180).single()
        assertEquals(listOf(290, 150, 30, 30), listOf(d.x, d.y, d.w, d.h))
    }

    @Test
    fun lowScoresAreDropped() {
        val out = output(anchor(50f, 50f, 20f, 20f, "BELLY_EXPOSED", 0.2f), anchor(150f, 50f, 20f, 20f, "BELLY_EXPOSED", 0.3f))
        assertEquals(1, YoloDecoder.decode(out, 2, labels, 320, 320, 320, 320).size)
    }

    @Test
    fun nmsMergesOverlapsOfOneRegionOnly() {
        val out = output(
            anchor(100f, 100f, 50f, 50f, "FACE_FEMALE", 0.9f),
            anchor(102f, 101f, 50f, 50f, "FACE_FEMALE", 0.7f),            // duplicate of the same face
            anchor(101f, 100f, 50f, 50f, "FACE_MALE", 0.5f),              // same face, other gender: one face, not two
            anchor(200f, 200f, 60f, 60f, "MALE_GENITALIA_EXPOSED", 0.8f),
            anchor(203f, 202f, 60f, 60f, "FEMALE_GENITALIA_EXPOSED", 0.75f), // overlapping different body part: kept
            anchor(200f, 200f, 60f, 60f, "FEMALE_GENITALIA_COVERED", 0.4f)  // same region as the exposed one: dropped
        )
        val got = YoloDecoder.decode(out, 6, labels, 320, 320, 320, 320).map { it.label }.sorted()
        assertEquals(listOf("FACE_FEMALE", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED"), got)
    }

    @Test
    fun nmsGroups() {
        assertEquals("FACE", NsfwLabels.nmsGroup("FACE_MALE"))
        assertEquals("BREAST", NsfwLabels.nmsGroup("MALE_BREAST_EXPOSED"))
        assertEquals("BREAST", NsfwLabels.nmsGroup("FEMALE_BREAST_COVERED"))
        assertEquals("FEMALE_GENITALIA", NsfwLabels.nmsGroup("FEMALE_GENITALIA_EXPOSED"))
        assertEquals("MALE_GENITALIA", NsfwLabels.nmsGroup("MALE_GENITALIA_EXPOSED"))
        assertEquals("FEET", NsfwLabels.nmsGroup("FEET_COVERED"))
    }

    @Test
    fun everyModelLabelIsListedOnce() {
        assertEquals(NsfwLabels.NUDENET.toSet(), NsfwLabels.ALL.toSet())
        assertEquals(NsfwLabels.ALL.size, NsfwLabels.ALL.toSet().size)
        assertEquals(18, NsfwLabels.NUDENET.size)
    }

    /**
     * Real NudeNet 320n outputs for three COCO photos and the detections tools/nsfw-eval/compare.py's port of the app
     * pipeline (checked against nudenet.py) makes from them: the Kotlin decoder must agree exactly.
     */
    @Test
    fun matchesThePythonPipelineOnRealOutputs() {
        val bin = javaClass.getResourceAsStream("/nsfw/nudenet_outputs.bin")!!.readBytes()
        val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        val expected = javaClass.getResourceAsStream("/nsfw/expected.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() }.map { it.split(' ') }
        val images = bb.int
        var checked = 0
        for (k in 0 until images) {
            val anchors = bb.int; val rows = bb.int; val cw = bb.int; val ch = bb.int; val w = bb.int; val h = bb.int
            val out = FloatArray(rows * anchors) { bb.float }
            val got = YoloDecoder.decode(out, anchors, labels, cw, ch, w, h)
            val want = expected.filter { it[0].toInt() == k }
            assertEquals("image $k detection count", want.size, got.size)
            for ((e, d) in want.zip(got.sortedByDescending { it.score })) {
                assertEquals(e[1], d.label)
                assertEquals(e[2].toFloat(), d.score, 1e-5f)
                assertEquals("image $k ${d.label} box", e.subList(3, 7).map { it.toInt() }, listOf(d.x, d.y, d.w, d.h))
                checked++
            }
        }
        assertTrue(checked >= 10)
    }

    // ---- counting and filtering ---------------------------------------------------------------------------------

    private fun det(label: String, score: Float = 0.8f) = NsfwDetection(label, score, 0, 0, 10, 10)
    private fun result(vararg d: NsfwDetection) = NsfwResult(1600, 900, d.toList(), 197)
    private fun i(label: String) = NsfwLabels.indexOf(label)

    @Test
    fun countsRespectTheThreshold() {
        val r = result(det("FACE_FEMALE", 0.81f), det("FACE_FEMALE", 0.77f), det("FACE_FEMALE", 0.3f), det("BELLY_EXPOSED", 0.5f))
        val c = NsfwFilter.counts(r, 0.45f)
        assertEquals(2, c[i("FACE_FEMALE")])
        assertEquals(1, c[i("BELLY_EXPOSED")])
        assertEquals(3, NsfwFilter.counts(r, 0.25f)[i("FACE_FEMALE")])
    }

    @Test
    fun tagStringAndJson() {
        val r = NsfwResult(
            1600, 900, listOf(
                NsfwDetection("FACE_FEMALE", 0.8101f, 565, 218, 248, 256),
                NsfwDetection("FACE_FEMALE", 0.7703f, 968, 136, 227, 229),
                NsfwDetection("MALE_GENITALIA_EXPOSED", 0.6718f, 1078, 174, 262, 215),
                NsfwDetection("FEMALE_BREAST_COVERED", 0.5f, 600, 500, 100, 100),
                NsfwDetection("BELLY_EXPOSED", 0.3f, 0, 0, 5, 5)
            ), 197
        )
        assertEquals("2FACE_FEMALE, 1MALE_GENITALIA_EXPOSED, 1FEMALE_BREAST_COVERED", r.tagString(0.45f))
        assertEquals(
            "{\"width\":1600,\"height\":900,\"labels\":[\"FACE_FEMALE\",\"MALE_GENITALIA_EXPOSED\",\"FEMALE_BREAST_COVERED\"]," +
                "\"detections\":[{\"label\":\"FACE_FEMALE\",\"score\":0.8101,\"box\":[565,218,248,256]}," +
                "{\"label\":\"FACE_FEMALE\",\"score\":0.7703,\"box\":[968,136,227,229]}," +
                "{\"label\":\"MALE_GENITALIA_EXPOSED\",\"score\":0.6718,\"box\":[1078,174,262,215]}," +
                "{\"label\":\"FEMALE_BREAST_COVERED\",\"score\":0.5000,\"box\":[600,500,100,100]}],\"ms\":197}",
            r.toJson(0.45f)
        )
        assertEquals("", result().tagString(0.45f))
    }

    @Test
    fun filterIsAndOverInclusiveRanges() {
        val face = i("FACE_FEMALE"); val male = i("MALE_GENITALIA_EXPOSED")
        val two = NsfwFilter.counts(result(det("FACE_FEMALE"), det("FACE_FEMALE"), det("MALE_GENITALIA_EXPOSED")), 0.45f)
        val one = NsfwFilter.counts(result(det("FACE_FEMALE")), 0.45f)
        val none = NsfwFilter.counts(result(), 0.45f)

        // "exactly 2 female faces"
        val exactly2 = mapOf(face to 2..2)
        assertTrue(NsfwFilter.matches(two, exactly2))
        assertFalse(NsfwFilter.matches(one, exactly2))
        assertFalse(NsfwFilter.matches(none, exactly2))

        // AND: 1..2 faces and at least one male genitalia
        val both = mapOf(face to 1..2, male to 1..NsfwFilter.NO_MAX)
        assertTrue(NsfwFilter.matches(two, both))
        assertFalse(NsfwFilter.matches(one, both))

        // "none of these": 0..0 keeps photos without the label
        assertTrue(NsfwFilter.matches(none, mapOf(male to 0..0)))
        assertFalse(NsfwFilter.matches(two, mapOf(male to 0..0)))

        // no active filter shows everything, even unscanned items; an active one hides them
        assertTrue(NsfwFilter.matches(null, emptyMap()))
        assertFalse(NsfwFilter.matches(null, exactly2))
    }

    @Test
    fun folderIndexGivesSliderRanges() {
        val results = listOf(
            result(det("FACE_FEMALE"), det("FACE_FEMALE"), det("FACE_FEMALE")),
            result(det("FACE_FEMALE"), det("BELLY_EXPOSED")),
            null,                                                   // a video / not scanned
            NsfwResult(0, 0, emptyList(), 5)                        // could not be decoded
        )
        val idx = NsfwFolderIndex.build(results, 0.45f)
        assertEquals(2, idx.scanned)
        assertEquals(3, idx.maxCounts[i("FACE_FEMALE")])
        assertEquals(1, idx.maxCounts[i("BELLY_EXPOSED")])
        assertEquals(0, idx.maxCounts[i("ANUS_EXPOSED")])
        assertEquals(2, idx.photosWithLabel[i("FACE_FEMALE")])
        assertEquals(listOf(i("FACE_FEMALE"), i("BELLY_EXPOSED")), idx.presentLabels)
        // photos with exactly 0, 1, 2, 3 female faces (only the 2 scanned photos count)
        assertArrayEquals(intArrayOf(0, 1, 0, 1), idx.histograms[i("FACE_FEMALE")])
        assertArrayEquals(intArrayOf(1, 1), idx.histograms[i("BELLY_EXPOSED")])
        assertTrue(idx.counts[2] == null && idx.counts[3] == null)

        assertTrue(NsfwFilter.isActive(1..3, 3))
        assertTrue(NsfwFilter.isActive(0..2, 3))
        assertFalse(NsfwFilter.isActive(0..3, 3))
        assertArrayEquals(IntArray(NsfwLabels.ALL.size), NsfwFolderIndex.build(emptyList(), 0.45f).maxCounts)
    }

    @Test
    fun rangeTexts() {
        assertEquals("Any", com.nestgallery.viewer.ui.nsfw.rangeText(0, 4, 4))
        assertEquals("None", com.nestgallery.viewer.ui.nsfw.rangeText(0, 0, 4))
        assertEquals("Exactly 2", com.nestgallery.viewer.ui.nsfw.rangeText(2, 2, 4))
        assertEquals("1 or more", com.nestgallery.viewer.ui.nsfw.rangeText(1, 4, 4))
        assertEquals("Up to 2", com.nestgallery.viewer.ui.nsfw.rangeText(0, 2, 4))
        assertEquals("1 – 3", com.nestgallery.viewer.ui.nsfw.rangeText(1, 3, 4))
    }
}
