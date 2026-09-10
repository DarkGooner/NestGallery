package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.FileEntry
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.countMedia
import com.nestgallery.viewer.data.listEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    pathStack: List<DocumentFile>,
    hideAux: Boolean,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    onToggleHideAux: () -> Unit,
    onOpenFolder: (DocumentFile) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onPickNewFolder: () -> Unit,
    onOpenImage: (List<FileEntry>, Int) -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean
) {
    val current = pathStack.last()
    val cacheKey = remember(current, hideAux) { "${current.uri}|hideAux=$hideAux" }

    // Seed straight from cache so revisiting a folder (e.g. backing out of the
    // image viewer) never shows a spinner or redoes the SAF listing.
    var entries by remember(cacheKey) { mutableStateOf(GalleryCache.getEntries(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (GalleryCache.getEntries(cacheKey) == null) {
            val loaded = withContext(Dispatchers.IO) { current.listEntries(hideAux) }
            GalleryCache.putEntries(cacheKey, loaded)
            entries = loaded
        }
    }

    val imagesOnly = remember(entries) { entries.orEmpty().filter { !it.isDirectory } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(current.name ?: "Gallery", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onToggleHideAux) {
                            Icon(
                                if (hideAux) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle _thumb / _locked files"
                            )
                        }
                        IconButton(onClick = onToggleViewMode) {
                            Icon(
                                if (listMode) Icons.Default.GridView else Icons.Default.ViewAgenda,
                                contentDescription = "Toggle view mode"
                            )
                        }
                        IconButton(onClick = onPickNewFolder) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Pick a different root folder")
                        }
                    }
                )
                Breadcrumb(pathStack = pathStack, onClick = onBreadcrumbClick)
            }
        }
    ) { padding ->
        val currentEntries = entries
        if (currentEntries == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (currentEntries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (listMode) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(currentEntries, key = { it.doc.uri.toString() }) { entry ->
                    if (entry.isDirectory) {
                        FolderRow(entry = entry, onClick = { onOpenFolder(entry.doc) })
                    } else {
                        val index = imagesOnly.indexOf(entry)
                        ImageRow(entry = entry, onClick = { onOpenImage(imagesOnly, index) })
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp)
            ) {
                gridItems(currentEntries, key = { it.doc.uri.toString() }) { entry ->
                    if (entry.isDirectory) {
                        FolderTile(entry = entry, onClick = { onOpenFolder(entry.doc) })
                    } else {
                        val index = imagesOnly.indexOf(entry)
                        ImageTile(entry = entry, onClick = { onOpenImage(imagesOnly, index) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(pathStack: List<DocumentFile>, onClick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pathStack.forEachIndexed { index, doc ->
            val isLast = index == pathStack.lastIndex
            Text(
                text = doc.name ?: "root",
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
private fun FolderRow(entry: FileEntry, onClick: () -> Unit) {
    val cacheKey = remember(entry.doc.uri) { entry.doc.uri.toString() }
    var count by remember(cacheKey) { mutableStateOf(GalleryCache.getCount(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (GalleryCache.getCount(cacheKey) == null) {
            val computed = withContext(Dispatchers.IO) { countMedia(entry.doc) }
            GalleryCache.putCount(cacheKey, computed)
            count = computed
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.padding(0.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val c = count
            if (c != null && c > 0) {
                Text(
                    "$c items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImageRow(entry: FileEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = entry.doc.uri,
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

@Composable
private fun FolderTile(entry: FileEntry, onClick: () -> Unit) {
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
private fun ImageTile(entry: FileEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = entry.doc.uri,
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
    }
}
