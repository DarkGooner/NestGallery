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

    /** File explorer: skip dotfiles / dotfolders. */
    var hideHidden: Boolean
        get() = prefs.getBoolean("hide_hidden", true)
        set(v) { prefs.edit().putBoolean("hide_hidden", v).apply() }

    /** File explorer: list every file, not only photos and videos. */
    var showAllFiles: Boolean
        get() = prefs.getBoolean("show_all_files", false)
        set(v) { prefs.edit().putBoolean("show_all_files", v).apply() }

    /** File explorer: filenames under thumbnails. */
    var showNames: Boolean
        get() = prefs.getBoolean("show_names", true)
        set(v) { prefs.edit().putBoolean("show_names", v).apply() }

    /** File explorer: list (true) or grid. */
    var listMode: Boolean
        get() = prefs.getBoolean("list_mode", true)
        set(v) { prefs.edit().putBoolean("list_mode", v).apply() }

    var sortBy: SortBy
        get() = SortBy.entries.firstOrNull { it.name == prefs.getString("sort_by", null) } ?: SortBy.NAME
        set(v) { prefs.edit().putString("sort_by", v.name).apply() }

    var sortDescending: Boolean
        get() = prefs.getBoolean("sort_desc", false)
        set(v) { prefs.edit().putBoolean("sort_desc", v).apply() }

    /** Folders recently copied / moved to, newest first, offered at the top of the folder picker. */
    var recentFolders: List<String>
        get() = prefs.getString("recent_folders", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) { prefs.edit().putString("recent_folders", v.take(MAX_RECENT).joinToString("\n")).apply() }

    fun addRecentFolder(path: String) { recentFolders = listOf(path) + recentFolders.filter { it != path } }

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
        private const val MAX_RECENT = 6
        @Volatile private var instance: UiPrefs? = null
        fun getInstance(context: Context): UiPrefs =
            instance ?: synchronized(this) { instance ?: UiPrefs(context).also { instance = it } }
    }
}
