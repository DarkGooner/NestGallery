package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.exploreMediaFlow
import com.nestgallery.viewer.data.relativeDirOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    root: DocEntry,
    hideHidden: Boolean,
    showNames: Boolean,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    onToggleHideHidden: () -> Unit,
    onToggleShowNames: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit,
    onBack: () -> Unit
) {
    // A plain (non-Compose-state-backed) cache key. GalleryCache is a
    // process-lifetime singleton, unlike `remember`, so a scan already in
    // progress or finished survives leaving for the image viewer and coming
    // straight back - without it, every trip to the viewer restarted the
    // entire recursive scan from scratch.
    var rescanTrigger by remember { mutableIntStateOf(0) }
    val exploreKey = remember(root.file.absolutePath, hideHidden, rescanTrigger) {
        "explore:${root.file.absolutePath}|hidden=$hideHidden|r=$rescanTrigger"
    }

    val items = remember(exploreKey) {
        mutableStateListOf<DocEntry>().apply {
            GalleryCache.getEntries(exploreKey)?.let { addAll(it) }
        }
    }
    var scanning by remember(exploreKey) { mutableStateOf(GalleryCache.getEntries(exploreKey) == null) }

    LaunchedEffect(exploreKey) {
        if (GalleryCache.getEntries(exploreKey) == null) {
            scanning = true
            exploreMediaFlow(root.file, hideHidden).collect { batch ->
                items.addAll(batch)
                GalleryCache.putEntries(exploreKey, items.toList())
            }
            scanning = false
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }

    // Scroll position, same pattern as the regular browser: saved on dispose
    // (leaving for the viewer or navigating away), restored on return.
    val scrollKey = remember(exploreKey, listMode) { "$exploreKey|mode=$listMode" }
    val savedScroll = remember(scrollKey) { GalleryCache.getScroll(scrollKey) }
    DisposableEffect(scrollKey) {
        onDispose {
            if (listMode) {
                GalleryCache.putScroll(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            } else {
                GalleryCache.putScroll(scrollKey, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }
    }
    var scrollRestored by remember(scrollKey) { mutableStateOf(false) }
    LaunchedEffect(scrollKey, items.size) {
        // Once there's enough content to scroll to the saved position, jump
        // there exactly once - guarded so it doesn't keep yanking the view
        // back on every later batch that arrives while a scan is ongoing.
        val saved = savedScroll
        if (!scrollRestored && saved != null && items.size > saved.first) {
            if (listMode) listState.scrollToItem(saved.first, saved.second)
            else gridState.scrollToItem(saved.first, saved.second)
            scrollRestored = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (scanning) "Scanning ${root.name}… (${items.size})" else "${root.name} — ${items.size} items",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        GalleryCache.invalidate(exploreKey)
                        rescanTrigger++
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                    IconButton(onClick = onToggleShowNames) {
                        Icon(
                            if (showNames) Icons.Default.Label else Icons.Default.LabelOff,
                            contentDescription = "Toggle filenames / paths"
                        )
                    }
                    IconButton(onClick = onToggleHideHidden) {
                        Icon(
                            if (hideHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle hidden items"
                        )
                    }
                    IconButton(onClick = onToggleViewMode) {
                        Icon(
                            if (listMode) Icons.Default.GridView else Icons.Default.ViewAgenda,
                            contentDescription = "Toggle view mode"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (scanning) {
                        CircularProgressIndicator()
                    } else {
                        Text("No media found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else if (listMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(items, key = { it.file.absolutePath }) { entry ->
                        val index = items.indexOf(entry)
                        ExploreImageRow(
                            entry = entry,
                            root = root,
                            showNames = showNames,
                            onClick = { onOpenImage(items, index) }
                        )
                    }
                }
                FastScrollbar(
                    itemCount = items.size,
                    visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    isScrolling = listState.isScrollInProgress,
                    onDragToIndex = { index -> coroutineScope.launch { listState.scrollToItem(index) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            } else {
                // Same tile, gesture handling, and hold-to-preview popup as
                // the regular browser's grid view - kept in one shared
                // component (MediaImageTile / HoldPreviewOverlay) so the two
                // screens can never drift apart in behavior.
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(4.dp)
                ) {
                    gridItems(items, key = { it.file.absolutePath }) { entry ->
                        val index = items.indexOf(entry)
                        val relDir = remember(entry.file.absolutePath) { relativeDirOf(entry.file, root.file) }
                        MediaImageTile(
                            entry = entry,
                            showNames = showNames,
                            subtitle = relDir,
                            onClick = { onOpenImage(items, index) },
                            onHoldStart = { previewEntry = entry },
                            onHoldEnd = { previewEntry = null }
                        )
                    }
                }
                FastScrollbar(
                    itemCount = items.size,
                    visibleCount = gridState.layoutInfo.visibleItemsInfo.size,
                    firstVisibleIndex = gridState.firstVisibleItemIndex,
                    isScrolling = gridState.isScrollInProgress,
                    onDragToIndex = { index -> coroutineScope.launch { gridState.scrollToItem(index) } },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            if (scanning && items.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Still scanning…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            previewEntry?.let { entry ->
                HoldPreviewOverlay(entry = entry)
            }
        }
    }
}

@Composable
private fun ExploreImageRow(entry: DocEntry, root: DocEntry, showNames: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = entry.file,
                contentDescription = entry.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
            if (entry.isVideo) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(56.dp)
                )
            }
        }
        if (showNames) {
            val relDir = remember(entry.file.absolutePath) { relativeDirOf(entry.file, root.file) }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (relDir.isNotEmpty()) {
                    Text(
                        relDir,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
