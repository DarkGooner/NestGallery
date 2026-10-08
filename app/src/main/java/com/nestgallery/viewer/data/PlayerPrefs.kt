package com.nestgallery.viewer.data

import android.content.Context
import java.io.File

/**
 * The video player's remembered choices (hardware decoding, loop, picture fit) and per-file resume positions.
 * Resume positions are keyed by path + size, so a replaced file starts from the top; at most [MAX_RESUME] are kept.
 */
class PlayerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val resume = context.applicationContext.getSharedPreferences("player_resume", Context.MODE_PRIVATE)

    var hardwareDecoding: Boolean
        get() = prefs.getBoolean("hw", false)
        set(v) { prefs.edit().putBoolean("hw", v).apply() }

    var loop: Boolean
        get() = prefs.getBoolean("loop", false)
        set(v) { prefs.edit().putBoolean("loop", v).apply() }

    var scale: VideoScale
        get() = VideoScale.entries.firstOrNull { it.name == prefs.getString("scale", null) } ?: VideoScale.FIT
        set(v) { prefs.edit().putString("scale", v.name).apply() }

    private fun key(file: File) = "${file.absolutePath}|${file.length()}"

    /** Where playback of [file] stopped last time, or 0 if it was finished / barely started. */
    fun resumePosition(file: File): Long = resume.getLong(key(file), 0L)

    /**
     * Remembers [positionMs] for [file] unless it is within the first [MIN_MS] or the last [END_MS] (then the next
     * open starts from the top, like VLC).
     */
    fun saveResume(file: File, positionMs: Long, durationMs: Long) {
        val k = key(file)
        if (durationMs <= 0 || positionMs < MIN_MS || positionMs > durationMs - END_MS) {
            resume.edit().remove(k).apply()
            return
        }
        val all = resume.all
        val edit = resume.edit().putLong(k, positionMs)
        if (all.size >= MAX_RESUME && k !in all) all.keys.take(all.size - MAX_RESUME + 1).forEach { edit.remove(it) }
        edit.apply()
    }

    companion object {
        private const val MIN_MS = 10_000L
        private const val END_MS = 15_000L
        private const val MAX_RESUME = 500
    }
}
