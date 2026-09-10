package com.nestgallery.viewer.data

/**
 * Process-lifetime cache. GalleryScreen's own composition state gets torn
 * down every time you navigate to the image viewer and back (or between
 * folders), which would otherwise force a fresh SAF listing every time.
 * Caching by URI here means a revisit is instant.
 */
object GalleryCache {
    private val entries = mutableMapOf<String, List<DocEntry>>()
    private val counts = mutableMapOf<String, Int>()
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun getEntries(key: String): List<DocEntry>? = entries[key]
    fun putEntries(key: String, value: List<DocEntry>) {
        entries[key] = value
    }

    fun getScroll(key: String): Pair<Int, Int>? = scrollPositions[key]
    fun putScroll(key: String, index: Int, offset: Int) {
        scrollPositions[key] = index to offset
    }

    fun getCount(key: String): Int? = counts[key]
    fun putCount(key: String, value: Int) {
        counts[key] = value
    }

    /** Call if the underlying folder contents may have changed on disk. */
    fun invalidate(key: String) {
        entries.remove(key)
        counts.remove(key)
        scrollPositions.remove(key)
    }

    fun clearAll() {
        entries.clear()
        counts.clear()
        scrollPositions.clear()
    }
}
