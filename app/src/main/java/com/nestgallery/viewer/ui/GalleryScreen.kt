package com.nestgallery.viewer.ui

import android.text.format.Formatter
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
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.StorageRoot
import com.nestgallery.viewer.data.countChildren
import com.nestgallery.viewer.data.displayName
import com.nestgallery.viewer.data.isMediaFile
import com.nestgallery.viewer.data.isVideoFile
import com.nestgallery.viewer.data.listFolder
import com.nestgallery.viewer.data.storageRoots
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    pathStack: List<File>,              // empty = the "Storages" root screen
    hideAux: Boolean,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    onToggleHideAux: () -> Unit,
    onOpenFolder: (File) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onOpenMedia: (List<File>, Int) -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean
) {
    val context = LocalContext.current
    val atRoot = pathStack.isEmpty()
    val current: File? = pathStack.lastOrNull()

    // Storage volumes are only a system-service lookup (no disk listing),
    // so the root screen doesn't need the disk cache.
    var roots by remember { mutableStateOf<List<StorageRoot>?>(null) }

    // Seed straight from cache so revisiting a folder (e.g. backing out of
    // the media viewer) never shows a spinner or redoes the directory scan.
    val cacheKey = remember(current?.absolutePath, hideAux) {
        if (atRoot) "" else "${current!!.absolutePath}|hideAux=$hideAux"
    }
    var entries by remember(cacheKey) {
        mutableStateOf(if (atRoot) null else GalleryCache.getEntries(cacheKey))
    }

    LaunchedEffect(atRoot) {
        if (atRoot) {
            roots = withContext(Dispatchers.IO) { storageRoots(context) }
        }
    }

    LaunchedEffect(cacheKey) {
        if (!atRoot && GalleryCache.getEntries(cacheKey) == null) {
            val loaded = withContext(Dispatchers.IO) { listFolder(current!!, hideAux) }
            GalleryCache.putEntries(cacheKey, loaded)
            entries = loaded
        }
    }

    val shownEntries: List<File>? = if (atRoot) roots?.map { it.file } else entries
    val rootLabel: (File) -> String = { file ->
        roots?.firstOrNull { it.file == file }?.label ?: file.displayName()
    }
    val mediaOnly = remember(shownEntries) {
        shownEntries.orEmpty().filter { it.isMediaFile() }
    }

    // Avoid O(n) mediaOnly.indexOf(entry) for every visible item. With 50,000
    // files, repeated linear searches can dominate composition time.
    val mediaIndexByPath = remember(mediaOnly) {
        HashMap<String, Int>(mediaOnly.size).also { map ->
            mediaOnly.forEachIndexed { index, file ->
                map[file.absolutePath] = index
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (atRoot) "Storages" else current!!.displayName(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    }
                )
                Breadcrumb(pathStack = pathStack, onClick = onBreadcrumbClick)
            }
        }
    ) { padding ->
        val currentEntries = shownEntries
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
                items(currentEntries, key = { it.absolutePath }) { entry ->
                    when {
                        entry.isDirectory -> FolderRow(
                            entry = entry,
                            hideAux = hideAux,
                            showCount = !atRoot,
                            label = rootLabel(entry),
                            onClick = { onOpenFolder(entry) }
                        )
                        entry.isMediaFile() -> {
                            val index = mediaIndexByPath[entry.absolutePath] ?: 0
                            ImageRow(entry = entry, onClick = { onOpenMedia(mediaOnly, index) })
                        }
                        else -> FileRow(entry = entry)
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp)
            ) {
                gridItems(currentEntries, key = { it.absolutePath }) { entry ->
                    when {
                        entry.isDirectory -> FolderTile(
                            entry = entry,
                            label = rootLabel(entry),
                            onClick = { onOpenFolder(entry) }
                        )
                        entry.isMediaFile() -> {
                            val index = mediaIndexByPath[entry.absolutePath] ?: 0
                            ImageTile(entry = entry, onClick = { onOpenMedia(mediaOnly, index) })
                        }
                        else -> FileTile(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun Breadcrumb(pathStack: List<File>, onClick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (pathStack.isEmpty()) {
            Text(
                "Storages",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            pathStack.forEachIndexed { index, file ->
                val isLast = index == pathStack.lastIndex
                Text(
                    text = file.displayName(),
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
}

@Composable
private fun FolderRow(
    entry: File,
    hideAux: Boolean,
    showCount: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val countKey = remember(entry.absolutePath, hideAux) {
        "${entry.absolutePath}|hideAux=$hideAux"
    }
    var count by remember(countKey) { mutableStateOf(GalleryCache.getCount(countKey)) }

    LaunchedEffect(countKey) {
        if (showCount && GalleryCache.getCount(countKey) == null) {
            val computed = withContext(Dispatchers.IO) { countChildren(entry, hideAux) }
            GalleryCache.putCount(countKey, computed)
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
        Column {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val c = count
            if (showCount && c != null && c > 0) {
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
private fun FileRow(entry: File) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                Formatter.formatShortFileSize(context, entry.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Coil's shared ImageLoader handles both image files and video frames. */
private fun thumbnailModel(entry: File): Any = entry

@Composable
private fun ImageRow(entry: File, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = thumbnailModel(entry),
                contentDescription = entry.name,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(0.dp))
            )
            if (entry.isVideoFile()) {
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
private fun FolderTile(entry: File, label: String, onClick: () -> Unit) {
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
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun FileTile(entry: File) {
    Column(
        modifier = Modifier.padding(4.dp),
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
            Icon(
                Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
private fun ImageTile(entry: File, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = thumbnailModel(entry),
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (entry.isVideoFile()) {
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
