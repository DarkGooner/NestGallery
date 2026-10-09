package com.nestgallery.viewer.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Remembers videos that took the whole app down. A native crash inside LibVLC (a broken or unusual file, a decoder
 * bug) kills the process, so it cannot be caught; instead the file is written down (synchronously) while it plays
 * and crossed off when playback stops normally. Whatever is still written down at the next launch was playing when
 * the process died: [onAppStart] moves it to the "crashed" list, and the player opens such a file paused, with a
 * notice, instead of crashing again in a loop.
 *
 * The in-flight entry is also crossed off when the app goes to the background (the player pauses then), so Android
 * killing a backgrounded app is not mistaken for a crash.
 */
object PlaybackCrashGuard {
    private const val PREFS = "playback_guard"
    private const val PLAYING = "playing"
    private const val CRASHED = "crashed"
    private const val MAX_CRASHED = 200

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(file: File) = "${file.absolutePath}|${file.length()}"

    /** Call once per process start, before any video plays. */
    fun onAppStart(context: Context) {
        val p = prefs(context)
        val inFlight = p.getStringSet(PLAYING, emptySet()).orEmpty()
        if (inFlight.isEmpty()) return
        val crashed = (p.getStringSet(CRASHED, emptySet()).orEmpty() + inFlight).toList().takeLast(MAX_CRASHED).toSet()
        p.edit().putStringSet(CRASHED, crashed).remove(PLAYING).commit()
    }

    /** [file] is about to be decoded: commit() (not apply()), the process may die before an async write lands. */
    fun begin(context: Context, file: File) {
        val p = prefs(context)
        val now = p.getStringSet(PLAYING, emptySet()).orEmpty()
        val k = key(file)
        if (k !in now) p.edit().putStringSet(PLAYING, now + k).commit()
    }

    /** Playback of [file] stopped normally (closed, swiped away, app in the background). */
    fun end(context: Context, file: File) {
        val p = prefs(context)
        val now = p.getStringSet(PLAYING, emptySet()).orEmpty()
        val k = key(file)
        if (k in now) p.edit().putStringSet(PLAYING, now - k).apply()
    }

    /** True if the app died while [file] was playing (and the user hasn't chosen to try it again since). */
    fun crashedBefore(context: Context, file: File): Boolean =
        key(file) in prefs(context).getStringSet(CRASHED, emptySet()).orEmpty()

    /** The user chose "Try again": if it crashes again it is simply written down again at the next launch. */
    fun forgive(context: Context, file: File) {
        val p = prefs(context)
        val now = p.getStringSet(CRASHED, emptySet()).orEmpty()
        val k = key(file)
        if (k in now) p.edit().putStringSet(CRASHED, now - k).apply()
    }
}
