package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.countMediaFast
import com.nestgallery.viewer.data.listFolderFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@Composable
fun GalleryScreen(
    pathStack: List<DocEntry>,
    hideHidden: Boolean,
    listMode: Boolean,
    showNames: Boolean,
    onToggleViewMode: () -> Unit,
    onToggleHideHidden: () -> Unit,
    onToggleShowNames: () -> Unit,
    onOpenFolder: (DocEntry) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onGoHome: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit,
    onExploreFolder: (DocEntry) -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean
) {
    val current = pathStack.last()
    val cacheKey = remember(current.file.absolutePath, hideHidden) { "${current.file.absolutePath}|hidden=$hideHidden" }

    // Seed straight from cache so revisiting a folder (e.g. backing out of the
    // image viewer) never shows a spinner or re-lists the directory.
    var entries by remember(cacheKey) { mutableStateOf(GalleryCache.getEntries(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (GalleryCache.getEntries(cacheKey) == null) {
            val loaded = withContext(Dispatchers.IO) { listFolderFast(current.file, hideHidden) }
            GalleryCache.putEntries(cacheKey, loaded)
            entries = loaded
        }
    }

    val imagesOnly = remember(entries) { entries.orEmpty().filter { !it.isDirectory } }

    // Scroll position survives navigating to the image viewer and back, or
    // between folders, the same way the entries themselves do above.
    val scrollKey = remember(cacheKey, listMode) { "$cacheKey|mode=$listMode" }
    val savedScroll = remember(scrollKey) { GalleryCache.getScroll(scrollKey) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedScroll?.first ?: 0,
        initialFirstVisibleItemScrollOffset = savedScroll?.second ?: 0
    )
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = savedScroll?.first ?: 0,
        initialFirstVisibleItemScrollOffset = savedScroll?.second ?: 0
    )

    DisposableEffect(scrollKey) {
        onDispose {
            if (listMode) {
                GalleryCache.putScroll(scrollKey, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            } else {
                GalleryCache.putScroll(scrollKey, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            }
        }
    }

    var refreshing by remember { mutableStateOf(false) }
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullRefreshState(
        refreshing = refreshing,
        onRefresh = {
            coroutineScope.launch {
                refreshing = true
                val loaded = withContext(Dispatchers.IO) { listFolderFast(current.file, hideHidden) }
                GalleryCache.putEntries(cacheKey, loaded)
                // Also clear cached item counts for the folders shown here, so
                // a pull-to-refresh picks up any changes inside them too.
                loaded.filter { it.isDirectory }.forEach { GalleryCache.invalidate(it.file.absolutePath) }
                entries = loaded
                refreshing = false
            }
        }
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onExploreFolder(current) }) {
                            Icon(Icons.Default.AccountTree, contentDescription = "Browse all media in this folder recursively")
                        }
                        IconButton(onClick = onToggleShowNames) {
                            Icon(
                                if (showNames) Icons.Default.Label else Icons.Default.LabelOff,
                                contentDescription = "Toggle filenames"
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
                        IconButton(onClick = onGoHome) {
                            Icon(Icons.Default.Home, contentDescription = "Go to storage root")
                        }
                    }
                )
                Breadcrumb(pathStack = pathStack, onClick = onBreadcrumbClick)
            }
        }
    ) { padding ->
        val currentEntries = entries
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .pullRefresh(pullRefreshState)
        ) {
            if (currentEntries == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (currentEntries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (listMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(currentEntries, key = { it.file.absolutePath }) { entry ->
                        if (entry.isDirectory) {
                            FolderRow(
                                entry = entry,
                                onClick = { onOpenFolder(entry) }
                            )
                        } else {
                            val index = imagesOnly.indexOf(entry)
                            ImageRow(entry = entry, showNames = showNames, onClick = { onOpenImage(imagesOnly, index) })
                        }
                    }
                }
                FastScrollbar(
                    itemCount = currentEntries.size,
                    visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    isScrolling = listState.isScrollInProgress,
                    onDragToIndex = { index ->
                        coroutineScope.launch { listState.scrollToItem(index) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .let { if (previewEntry != null) it.blur(18.dp) else it },
                    contentPadding = PaddingValues(4.dp)
                ) {
                    gridItems(currentEntries, key = { it.file.absolutePath }) { entry ->
                        if (entry.isDirectory) {
                            FolderTile(
                                entry = entry,
                                onClick = { onOpenFolder(entry) }
                            )
                        } else {
                            val index = imagesOnly.indexOf(entry)
                            ImageTile(
                                entry = entry,
                                showNames = showNames,
                                onClick = { onOpenImage(imagesOnly, index) },
                                onHoldStart = { previewEntry = entry },
                                onHoldEnd = { previewEntry = null }
                            )
                        }
                    }
                }
                FastScrollbar(
                    itemCount = currentEntries.size,
                    visibleCount = gridState.layoutInfo.visibleItemsInfo.size,
                    firstVisibleIndex = gridState.firstVisibleItemIndex,
                    isScrolling = gridState.isScrollInProgress,
                    onDragToIndex = { index ->
                        coroutineScope.launch { gridState.scrollToItem(index) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }

            PullRefreshIndicator(
                refreshing = refreshing,
                state = pullRefreshState,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            previewEntry?.let { entry ->
                HoldPreviewOverlay(entry = entry)
            }
        }
    }
}

@Composable
private fun Breadcrumb(pathStack: List<DocEntry>, onClick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pathStack.forEachIndexed { index, entry ->
            val isLast = index == pathStack.lastIndex
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = !isLast) { onClick(index) }
            )
            if (!isLast) {
                Text(
                    "  /  ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FolderRow(entry: DocEntry, onClick: () -> Unit) {
    val cacheKey = remember(entry.file.absolutePath) { entry.file.absolutePath }
    var count by remember(cacheKey) { mutableStateOf(GalleryCache.getCount(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (GalleryCache.getCount(cacheKey) == null) {
            val computed = withContext(Dispatchers.IO) { countMediaFast(entry.file) }
            GalleryCache.putCount(cacheKey, computed)
            count = computed
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val c = count
            // Always reserve the line (just hide it) so a row's height never
            // jumps once its async count resolves - that jump was throwing
            // off the spacing of everything below it in the list.
            Text(
                "${c ?: 0} items",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(if (c != null && c > 0) 1f else 0f)
            )
        }
    }
}

@Composable
private fun ImageRow(entry: DocEntry, showNames: Boolean, onClick: () -> Unit) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
            )
            if (entry.isVideo) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(56.dp)
                )
            }
        }
        if (showNames) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun FolderTile(entry: DocEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(4.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun ImageTile(
    entry: DocEntry,
    showNames: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit
) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .pointerInput(entry.file) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Race the release against the hold threshold. Exactly
                    // one branch runs: a quick release opens the viewer, a
                    // release that never showed up in time means the finger
                    // is still down, so switch to hold-preview and wait for
                    // the real release to dismiss it - never both.
                    val releasedInTime = withTimeoutOrNull(300) { waitForUpOrCancellation() }
                    if (releasedInTime != null) {
                        onClick()
                    } else {
                        onHoldStart()
                        waitForUpOrCancellation()
                        onHoldEnd()
                    }
                }
            }
    ) {
        AsyncImage(
            model = entry.file,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (entry.isVideo) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = "Video",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(32.dp)
            )
        }
        if (showNames) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Instagram-reel-style hold-to-preview: a dimmed, blurred-behind popup
 * showing the full image, or an auto-playing muted/looping video, while
 * the finger stays down on the tile. Purely a visual overlay - it doesn't
 * consume touches, so lifting the finger (handled by the tile's own
 * gesture) is what dismisses it.
 */
@Composable
private fun HoldPreviewOverlay(entry: DocEntry) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        if (entry.isVideo) {
            HoldPreviewVideo(entry = entry)
        } else {
            AsyncImage(
                model = entry.file,
                contentDescription = entry.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)
            )
        }
    }
}

@Composable
private fun HoldPreviewVideo(entry: DocEntry) {
    val context = LocalContext.current
    val exoPlayer = remember(entry.file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(entry.file)))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
            }
        }
    )
}
