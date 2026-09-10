package com.nestgallery.viewer.data

/**
 * Process-lifetime cache. GalleryScreen's own composition state gets torn
 * down every time you navigate to the image viewer and back (or between
 * folders), which would otherwise force a fresh SAF listing every time.
 * Caching by URI here means a revisit is instant.
 */
object GalleryCache {
    private const val MAX_CACHED_FOLDERS = 12
    private val entries = object : LinkedHashMap<String, List<FileEntry>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileEntry>>?): Boolean =
            size > MAX_CACHED_FOLDERS
    }
    private val counts = mutableMapOf<String, Int>()

    fun getEntries(key: String): List<FileEntry>? = entries[key]
    fun putEntries(key: String, value: List<FileEntry>) {
        entries[key] = value
    }

    fun getCount(key: String): Int? = counts[key]
    fun putCount(key: String, value: Int) {
        counts[key] = value
    }

    /** Call if the underlying folder contents may have changed on disk. */
    fun invalidate(key: String) {
        entries.remove(key)
        counts.remove(key)
    }

    fun clearAll() {
        entries.clear()
        counts.clear()
    }
}
