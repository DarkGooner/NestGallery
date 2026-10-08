package com.nestgallery.viewer.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nestgallery.viewer.data.GalleryCache

/*
 * Scroll positions that survive opening the viewer and coming back. Navigation swaps whole screens, so the screen
 * leaves composition and remember / rememberSaveable lose its LazyGridState; the position is kept in GalleryCache
 * under [key] instead (like ExploreScreen does). Content usually loads asynchronously, and a lazy list measured with
 * fewer items than the saved index clamps it to the top, so the saved position is also applied once [itemCount] can
 * hold it. Call GalleryCache.forgetScrolls(prefix) where a screen is opened afresh, so only returns keep the position.
 */

@Composable
fun rememberKeptGridState(key: String, itemCount: Int): LazyGridState {
    val saved = remember(key) { GalleryCache.getScroll(key) }
    val state = remember(key) { saved?.let { LazyGridState(it.first, it.second) } ?: LazyGridState() }
    var restored by remember(key) { mutableStateOf(saved == null) }
    LaunchedEffect(key, itemCount) {
        if (!restored && saved != null && itemCount > saved.first) {
            if (state.firstVisibleItemIndex != saved.first) state.scrollToItem(saved.first, saved.second)
            restored = true
        }
    }
    DisposableEffect(key) {
        onDispose { if (restored) GalleryCache.putScroll(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    return state
}

@Composable
fun rememberKeptListState(key: String, itemCount: Int): LazyListState {
    val saved = remember(key) { GalleryCache.getScroll(key) }
    val state = remember(key) { saved?.let { LazyListState(it.first, it.second) } ?: LazyListState() }
    var restored by remember(key) { mutableStateOf(saved == null) }
    LaunchedEffect(key, itemCount) {
        if (!restored && saved != null && itemCount > saved.first) {
            if (state.firstVisibleItemIndex != saved.first) state.scrollToItem(saved.first, saved.second)
            restored = true
        }
    }
    DisposableEffect(key) {
        onDispose { if (restored) GalleryCache.putScroll(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    return state
}

/** Keys, so the screens and the places that open them afresh agree. */
object ScrollKeys {
    fun person(personId: Long, folderPath: String?) = "person:$personId|$folderPath|"
    fun faces(rootPath: String) = "faces:$rootPath|"
    fun nsfw(rootPath: String) = "nsfw:$rootPath|"
}
