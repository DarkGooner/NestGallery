package com.nestgallery.viewer.data

import java.io.File

/**
 * Process-lifetime cache for directory listings and child counts.
 */
object GalleryCache {
    private val entries = mutableMapOf<String, List<File>>()
    private val counts = mutableMapOf<String, Int>()

    fun getEntries(key: String): List<File>? = entries[key]

    fun putEntries(key: String, value: List<File>) {
        entries[key] = value
    }

    fun getCount(key: String): Int? = counts[key]

    fun putCount(key: String, value: Int) {
        counts[key] = value
    }

    fun invalidate(key: String) {
        entries.remove(key)
        counts.remove(key)
    }

    fun clearAll() {
        entries.clear()
        counts.clear()
    }
}
