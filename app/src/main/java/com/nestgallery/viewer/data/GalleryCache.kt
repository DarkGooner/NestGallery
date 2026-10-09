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

    /** Drops every saved scroll position whose key starts with [prefix] (a screen opened afresh starts at the top). */
    fun forgetScrolls(prefix: String) {
        scrollPositions.keys.removeAll { it.startsWith(prefix) }
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

    /**
     * Drops the cached listings (scroll positions are tiny and kept) once the app is in the background and memory
     * is tight: a big recursive listing is tens of thousands of entries, and it is rebuilt on the next visit anyway.
     */
    fun trim(level: Int) {
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            entries.clear()
            counts.clear()
        }
    }

    fun clearAll() {
        entries.clear()
        counts.clear()
        scrollPositions.clear()
    }
}
