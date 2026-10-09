package com.nestgallery.viewer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which way the app opens (gallery or file explorer), the gallery's grid density, and whether NSFW scan is shown. */
class UiPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** True (the default): open in the gallery view (Albums / Photos) instead of the file explorer. */
    var galleryMode: Boolean
        get() = prefs.getBoolean("gallery_mode", true)
        set(v) { prefs.edit().putBoolean("gallery_mode", v).apply() }

    /** Photos per row in the gallery's timelines (changed by pinching). */
    var galleryColumns: Int
        get() = prefs.getInt("gallery_columns", 4)
        set(v) { prefs.edit().putInt("gallery_columns", v).apply() }

    private val _nsfwEnabled = MutableStateFlow(prefs.getBoolean("nsfw_enabled", false))
    /**
     * Settings switch, off by default: while off, NSFW scan appears nowhere (no menu entries, no shield button, no
     * Settings section, no tags in the info sheet). Results already saved are kept for when it is turned back on.
     */
    val nsfwEnabled: StateFlow<Boolean> = _nsfwEnabled.asStateFlow()

    fun setNsfwEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("nsfw_enabled", enabled).apply()
        _nsfwEnabled.value = enabled
    }

    companion object {
        @Volatile private var instance: UiPrefs? = null
        fun getInstance(context: Context): UiPrefs =
            instance ?: synchronized(this) { instance ?: UiPrefs(context).also { instance = it } }
    }
}
