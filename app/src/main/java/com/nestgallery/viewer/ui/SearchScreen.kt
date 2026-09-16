package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.exploreMediaFlow
import com.nestgallery.viewer.data.listFolderFast
import com.nestgallery.viewer.data.relativeDirOf
import kotlinx.coroutines.delay

private fun matchesQuery(entry: DocEntry, query: String, root: DocEntry, recursive: Boolean): Boolean {
    if (entry.name.contains(query, ignoreCase = true)) return true
    if (!recursive) return false
    return relativeDirOf(entry.file, root.file).contains(query, ignoreCase = true)
}

/**
 * Global search, entered from any folder. "Recursive scan mode" searches
 * every file under [root] (sharing its cache/streaming with ExploreScreen,
 * so a folder already explored returns instantly); turning it off searches
 * only the files directly inside [root]. Either way, filtering itself is
 * always a cheap in-memory pass - what varies is how much of the tree has
 * been scanned into memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    root: DocEntry,
    hideHidden: Boolean,
    showNames: Boolean,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var recursive by remember { mutableStateOf(true) }
    var debouncedQuery by remember { mutableStateOf("") }

    // Debounce keystrokes: filtering only runs once typing pauses, instead
    // of on every character.
    LaunchedEffect(query) {
        delay(150)
        debouncedQuery = query.trim()
    }

    // Non-recursive pool: a single directory read, already fast on its own.
    val localEntries = remember(root.file.absolutePath, hideHidden) {
        listFolderFast(root.file, hideHidden).filter { !it.isDirectory }
    }

    // Recursive pool: shares its cache key with ExploreScreen's default
    // (unrescanned) scan, so searching a folder you've already explored is
    // instant instead of re-walking the filesystem.
    val exploreKey = remember(root.file.absolutePath, hideHidden) {
        "explore:${root.file.absolutePath}|hidden=$hideHidden|r=0"
    }
    val scanned = remember(exploreKey) {
        mutableStateListOf<DocEntry>().apply {
            GalleryCache.getEntries(exploreKey)?.let { addAll(it) }
        }
    }
    var scanning by remember(exploreKey) {
        mutableStateOf(GalleryCache.getEntries(exploreKey) == null)
    }
    // Results are appended incrementally as batches stream in (cheap), and
    // only fully recomputed when the query itself changes (a single O(n)
    // pass) - never re-filtering the whole growing list on every batch.
    val results = remember(exploreKey) { mutableStateListOf<DocEntry>() }

    LaunchedEffect(exploreKey, recursive) {
        if (recursive && GalleryCache.getEntries(exploreKey) == null) {
            scanning = true
            exploreMediaFlow(root.file, hideHidden).collect { batch ->
                scanned.addAll(batch)
                if (debouncedQuery.isNotEmpty()) {
                    results.addAll(batch.filter { matchesQuery(it, debouncedQuery, root, recursive) })
                }
            }
            GalleryCache.putEntries(exploreKey, scanned.toList())
            scanning = false
        }
    }

    LaunchedEffect(debouncedQuery, recursive, localEntries) {
        val pool = if (recursive) scanned.toList() else localEntries
        results.clear()
        if (debouncedQuery.isNotEmpty()) {
            results.addAll(pool.filter { matchesQuery(it, debouncedQuery, root, recursive) })
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                        placeholder = { Text("Search images & videos") },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { recursive = !recursive }) {
                        Icon(
                            if (recursive) Icons.Default.AccountTree else Icons.Default.Folder,
                            contentDescription = if (recursive) {
                                "Recursive scan mode: searching ${root.name} and all subfolders"
                            } else {
                                "Searching only ${root.name} directly"
                            }
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
            when {
                debouncedQuery.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (recursive) "Type to search ${root.name} and its subfolders"
                            else "Type to search ${root.name}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                results.isEmpty() && !scanning -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matches for \"$debouncedQuery\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                listMode -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        itemsIndexed(results, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
                            val relDir = remember(entry.file.absolutePath) { relativeDirOf(entry.file, root.file) }
                            SearchResultRow(
                                entry = entry,
                                showNames = showNames,
                                relDir = relDir,
                                onClick = { onOpenImage(results, index) }
                            )
                        }
                    }
                }
                else -> {
                    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 108.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        gridItemsIndexed(results, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
                            val relDir = remember(entry.file.absolutePath) { relativeDirOf(entry.file, root.file) }
                            MediaImageTile(
                                entry = entry,
                                showNames = showNames,
                                subtitle = relDir,
                                onClick = { onOpenImage(results, index) },
                                onHoldStart = { previewEntry = entry },
                                onHoldEnd = { previewEntry = null }
                            )
                        }
                    }
                    previewEntry?.let { HoldPreviewOverlay(entry = it) }
                }
            }

            if (scanning) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                    Box(
                        Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), color = Color.White)
                            Text("Still scanning…", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(entry: DocEntry, showNames: Boolean, relDir: String, onClick: () -> Unit) {
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
