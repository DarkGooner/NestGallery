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

    /**
     * The decoder the player's settings were set to, or null while the user never chose one (then [hardwareFor]
     * decides per file). Stored under a new key: the old "hw" was also written by the error card's one-off retry.
     */
    var hardwareDecoding: Boolean?
        get() = if (prefs.contains("hw_choice")) prefs.getBoolean("hw_choice", false) else null
        set(v) { prefs.edit().apply { if (v == null) remove("hw_choice") else putBoolean("hw_choice", v) }.apply() }

    /**
     * Hardware or software decoding for [file]: the user's choice if they made one; otherwise hardware (MediaCodec)
     * for containers / streams that are VP8 / VP9 / AV1 / HEVC (WebM, raw HEVC): far too heavy to decode in software
     * at 1080p+ on a phone (a 4K VP9 WebM played black, then froze). Everything else starts in software, the
     * compatibility path for old AVI / WMV and the like. Either way the player switches decoder by itself when one fails.
     */
    fun hardwareFor(file: File): Boolean = hardwareDecoding ?: (file.extension.lowercase() in HARDWARE_FIRST)

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
        private val HARDWARE_FIRST = setOf("webm", "hevc", "h265", "265", "ivf", "av1", "obu")
        private const val MIN_MS = 10_000L
        private const val END_MS = 15_000L
        private const val MAX_RESUME = 500
    }
}
