package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.draw.alpha
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

import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield

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
    onSearch: (DocEntry) -> Unit,
    onOpenFaces: (DocEntry, List<DocEntry>) -> Unit,
    /** Null while NSFW scan is switched off in Settings (then its button is hidden). */
    onOpenNsfw: ((DocEntry, List<DocEntry>) -> Unit)?,
    onOpenSettings: () -> Unit,
    onSwitchToGallery: () -> Unit,
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
                // Keep UI updates batched. Updating the Compose list once per
                // small filesystem batch is far cheaper than rebuilding and
                // caching the entire accumulated list on every emission.
                items.addAll(batch)
            }
            // Cache only the completed result. Caching a growing 50k-item list
            // on every batch creates O(n²) copying and a lot of GC pressure.
            GalleryCache.putEntries(exploreKey, items.toList())
            scanning = false
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

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

    val bar = rememberCollapsingBar()
    val topPad = bar.contentTopPadding()

    Scaffold(contentWindowInsets = ScreenInsets) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(bar.connection)) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(top = topPad), contentAlignment = Alignment.Center) {
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
                    contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
                ) {
                    itemsIndexed(items, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
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
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 4.dp, top = topPad)
                ) {
                    gridItemsIndexed(items, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
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

            NestTopBar(
                state = bar,
                title = root.name,
                subtitle = if (scanning) "Scanning… ${items.size} found" else "${items.size} items · all subfolders",
                onBack = onBack,
                listMode = listMode,
                onToggleViewMode = onToggleViewMode,
                recursive = RecursiveAction(
                    active = true,
                    busy = scanning,
                    description = "Recursive view is on. Tap to rescan this folder and all subfolders",
                    onClick = {
                        GalleryCache.invalidate(exploreKey)
                        rescanTrigger++
                    }
                ),
                searchContent = { SearchPill("Search in ${root.name}") { onSearch(root) } },
                headerActions = {
                    IconButton(onClick = { onOpenFaces(root, items.toList()) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Face, contentDescription = "Faces in this folder")
                    }
                    if (onOpenNsfw != null) {
                        IconButton(onClick = { onOpenNsfw(root, items.toList()) }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Shield, contentDescription = "NSFW scan and filters for this folder")
                        }
                    }
                },
                menu = listOf(
                    TopBarMenuItem(if (showNames) "Hide filenames" else "Show filenames",
                        if (showNames) Icons.AutoMirrored.Filled.LabelOff else Icons.AutoMirrored.Filled.Label, onToggleShowNames),
                    TopBarMenuItem(if (hideHidden) "Show hidden items" else "Hide hidden items",
                        if (hideHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, onToggleHideHidden),
                    TopBarMenuItem("Gallery view", Icons.Default.PhotoLibrary, onSwitchToGallery),
                    TopBarMenuItem("Settings", Icons.Default.Settings, onOpenSettings)
                ),
                modifier = Modifier.align(Alignment.TopCenter).alpha(if (previewEntry == null) 1f else 0f)
            )
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
