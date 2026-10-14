package com.nestgallery.viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.GalleryCache
import com.nestgallery.viewer.data.countMediaFast
import com.nestgallery.viewer.data.listFolderFast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.material.icons.filled.Face
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.nestgallery.viewer.data.FileOps
import com.nestgallery.viewer.data.SortBy
import com.nestgallery.viewer.data.UiPrefs
import com.nestgallery.viewer.ui.files.DeleteDialog
import com.nestgallery.viewer.ui.files.FileSelectionActions
import com.nestgallery.viewer.ui.files.NameDialog
import com.nestgallery.viewer.ui.files.PasteBar
import com.nestgallery.viewer.ui.files.PropertiesDialog
import com.nestgallery.viewer.ui.files.SelectionTopBar
import java.io.File
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import com.nestgallery.viewer.data.rpa.RpaFs
import com.nestgallery.viewer.ui.files.openExternally

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    pathStack: List<DocEntry>,
    hideHidden: Boolean,
    /** List every file, not only photos and videos (three-dot menu "Show non-media files"). */
    showAllFiles: Boolean,
    listMode: Boolean,
    showNames: Boolean,
    onToggleViewMode: () -> Unit,
    onOpenFolder: (DocEntry) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onGoHome: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit,
    onExploreFolder: (DocEntry) -> Unit,
    onSearch: (DocEntry) -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean,
    /** The open folder itself was renamed: replace it in the path. */
    onRenamedCurrent: (File) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs.getInstance(context) }
    val ops = remember { FileOps.getInstance(context) }
    val revision by ops.revision.collectAsState()
    val clip by ops.clip.collectAsState()
    var sortBy by remember { mutableStateOf(prefs.sortBy) }
    var sortDesc by remember { mutableStateOf(prefs.sortDescending) }
    val current = pathStack.last()
    val cacheKey = remember(current.file.absolutePath, hideHidden, sortBy, sortDesc, showAllFiles) {
        "${current.file.absolutePath}|hidden=$hideHidden|sort=$sortBy$sortDesc|all=$showAllFiles"
    }

    // Seed straight from cache so revisiting a folder (e.g. backing out of the
    // image viewer) never shows a spinner or re-lists the directory.
    var entries by remember(cacheKey) { mutableStateOf(GalleryCache.getEntries(cacheKey)) }

    // revision: a copy / move / delete / new folder dropped the cached listings, so list again
    LaunchedEffect(cacheKey, revision) {
        if (GalleryCache.getEntries(cacheKey) == null) {
            val loaded = withContext(Dispatchers.IO) { listFolderFast(current.file, hideHidden, sortBy, sortDesc, showAllFiles) }
            GalleryCache.putEntries(cacheKey, loaded)
            entries = loaded
        }
    }

    // ZArchiver-style selection: long-press a row (or a folder tile, or "Select" in the menu), then tap to add more
    var selecting by remember(current.file.absolutePath) { mutableStateOf(false) }
    var selected by remember(current.file.absolutePath) { mutableStateOf(emptySet<String>()) }
    val inSelection = selecting || selected.isNotEmpty()
    fun toggle(entry: DocEntry) {
        val p = entry.file.absolutePath
        selected = if (p in selected) selected - p else selected + p
        if (selected.isEmpty()) selecting = false
    }
    fun endSelection() { selected = emptySet(); selecting = false }
    BackHandler(enabled = inSelection) { endSelection() }
    LaunchedEffect(entries) {
        val present = entries.orEmpty().mapTo(HashSet()) { it.file.absolutePath }
        if (selected.any { it !in present }) selected = selected.filterTo(HashSet()) { it in present }
    }

    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf(false) }
    var folderInfo by remember { mutableStateOf(false) }
    var sortDialog by remember { mutableStateOf(false) }

    // what the viewer pages through: photos and videos (other files open in another app)
    val imagesOnly = remember(entries) { entries.orEmpty().filter { !it.isDirectory && it.isMedia } }
    val scope = rememberCoroutineScope()
    fun openOther(entry: DocEntry) { scope.launch { openExternally(context, entry.file) } }

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

    val bar = rememberCollapsingBar(resetKey = cacheKey)
    val topPad = bar.contentTopPadding()

    Scaffold(contentWindowInsets = ScreenInsets) { padding ->
        val currentEntries = entries

        Box(Modifier.fillMaxSize().padding(padding).nestedScroll(bar.connection)) {
        NestPullToRefresh(
            isRefreshing = refreshing,
            topInset = topPad,
            onRefresh = {
                coroutineScope.launch {
                    refreshing = true
                    val loaded = withContext(Dispatchers.IO) { listFolderFast(current.file, hideHidden, sortBy, sortDesc, showAllFiles) }
                    GalleryCache.putEntries(cacheKey, loaded)
                    // Also clear cached item counts for the folders shown here, so
                    // a pull-to-refresh picks up any changes inside them too.
                    loaded.filter { it.isDirectory }.forEach { GalleryCache.invalidate(it.file.absolutePath) }
                    entries = loaded
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        Box(Modifier.fillMaxSize()) {
            if (currentEntries == null) {
                Box(Modifier.fillMaxSize().padding(top = topPad), contentAlignment = Alignment.Center) {
                    PullTarget()
                    CircularProgressIndicator()
                }
            } else if (currentEntries.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(top = topPad), contentAlignment = Alignment.Center) {
                    PullTarget()
                    Text("Nothing here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (listMode) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = topPad, bottom = 24.dp)
                ) {
                    items(currentEntries, key = { it.file.absolutePath }) { entry ->
                        val isSel = if (inSelection) entry.file.absolutePath in selected else null
                        if (entry.isDirectory || !entry.isMedia) {
                            FolderRow(
                                entry = entry,
                                selected = isSel,
                                onClick = { if (inSelection) toggle(entry) else if (entry.isDirectory) onOpenFolder(entry) else openOther(entry) },
                                onLongClick = { toggle(entry) }
                            )
                        } else {
                            val index = imagesOnly.indexOf(entry)
                            ImageRow(
                                entry = entry, showNames = showNames, selected = isSel,
                                onClick = { if (inSelection) toggle(entry) else onOpenImage(imagesOnly, index) },
                                onLongClick = { toggle(entry) }
                            )
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = 4.dp, top = topPad)
                ) {
                    gridItems(currentEntries, key = { it.file.absolutePath }) { entry ->
                        val isSel = if (inSelection) entry.file.absolutePath in selected else null
                        if (entry.isDirectory || !entry.isMedia) {
                            FolderTile(
                                entry = entry,
                                selected = isSel,
                                onClick = { if (inSelection) toggle(entry) else if (entry.isDirectory) onOpenFolder(entry) else openOther(entry) },
                                onLongClick = { toggle(entry) }
                            )
                        } else {
                            val index = imagesOnly.indexOf(entry)
                            // holding a photo still previews it; "Select" in the menu (or a long-press in the list) selects
                            MediaImageTile(
                                entry = entry,
                                showNames = showNames,
                                selected = isSel,
                                onClick = { if (inSelection) toggle(entry) else onOpenImage(imagesOnly, index) },
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

            previewEntry?.let { entry ->
                HoldPreviewOverlay(entry = entry)
            }
        }
        }

        val folderCount = currentEntries?.count { it.isDirectory } ?: 0
        val mediaCount = currentEntries?.count { !it.isDirectory } ?: 0
        val isRoot = !canGoBack
        // a Ren'Py archive opened as a folder (or a folder inside one) is read-only; the archive itself can still be
        // renamed or deleted as a file
        val inArchive = FileOps.insideArchive(current.file)
        val archiveRoot = inArchive && !RpaFs.isVirtual(current.file)
        if (inSelection) {
            val files = remember(selected) { selected.map(::File) }
            SelectionTopBar(count = selected.size, onClose = { endSelection() }, insets = false, modifier = Modifier.align(Alignment.TopCenter)) {
                FileSelectionActions(
                    files = files,
                    onDone = { endSelection() },
                    onSelectAll = { selected = currentEntries.orEmpty().mapTo(HashSet()) { it.file.absolutePath } }
                )
            }
        } else NestTopBar(
            state = bar,
            title = current.name,
            subtitle = if (currentEntries == null) null else "${countLabel(folderCount, "folder")} · ${countLabel(mediaCount, "item")}",
            onBack = if (canGoBack) onBack else null,
            listMode = listMode,
            onToggleViewMode = onToggleViewMode,
            recursive = RecursiveAction(
                description = "Browse all media in this folder recursively",
                onClick = { onExploreFolder(current) }
            ),
            searchContent = { SearchPill("Search in ${current.name}") { onSearch(current) } },
            pathChips = pathStack.map { it.name },
            onPathClick = onBreadcrumbClick,
            headerActions = {
                IconButton(onClick = onGoHome, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Home, contentDescription = "Go to storage root")
                }
            },
            menu = buildList {
                if (!inArchive) add(MenuAction("New folder", Icons.Default.CreateNewFolder) { newFolder = true })
                add(MenuAction("Select", Icons.Default.CheckCircle, enabled = !currentEntries.isNullOrEmpty()) { selecting = true })
                if (clip != null && !inArchive) add(MenuAction("Paste here", Icons.Default.ContentPaste) { ops.paste(current.file) })
                add(MenuAction("Sort by: ${sortBy.label.lowercase()}", Icons.AutoMirrored.Filled.Sort) { sortDialog = true })
                add(MenuAction("Folder properties", Icons.Default.Info) { folderInfo = true })
                if (!isRoot && (!inArchive || archiveRoot)) {
                    val what = if (archiveRoot) "archive" else "folder"
                    add(MenuAction("Rename this $what", Icons.Default.DriveFileRenameOutline) { renameFolder = true })
                    add(MenuAction("Delete this $what", Icons.Default.Delete) { deleteFolder = true })
                }
            },
            modifier = Modifier.align(Alignment.TopCenter).alpha(if (previewEntry == null) 1f else 0f)
        )
        if (!inSelection) PasteBar(current.file, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (newFolder) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { newFolder = false },
            onConfirm = { name -> ops.createFolder(current.file, name) }
        )
    }
    if (renameFolder) {
        NameDialog(
            title = "Rename folder",
            initial = current.name,
            confirmLabel = "Rename",
            onDismiss = { renameFolder = false },
            onConfirm = { name ->
                ops.rename(current.file, name).also { if (it == null) onRenamedCurrent(File(current.file.parentFile, name.trim())) }
            }
        )
    }
    if (deleteFolder) {
        DeleteDialog(listOf(current.file), onDismiss = { deleteFolder = false }, onConfirm = {
            deleteFolder = false
            ops.delete(listOf(current.file))
            onBack()
        })
    }
    if (folderInfo) PropertiesDialog(listOf(current.file), onDismiss = { folderInfo = false })
    if (sortDialog) {
        SortDialog(sortBy, sortDesc, onDismiss = { sortDialog = false }) { by, desc ->
            sortDialog = false
            sortBy = by; sortDesc = desc
            prefs.sortBy = by; prefs.sortDescending = desc
        }
    }
}

@Composable
private fun SortDialog(by: SortBy, descending: Boolean, onDismiss: () -> Unit, onApply: (SortBy, Boolean) -> Unit) {
    var choice by remember { mutableStateOf(by) }
    var desc by remember { mutableStateOf(descending) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sort by") },
        text = {
            Column {
                for (option in SortBy.entries) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { choice = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = choice == option, onClick = { choice = option })
                        Text(option.label)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { desc = !desc }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = desc, onCheckedChange = { desc = it })
                    Text(
                        when (choice) {
                            SortBy.NAME -> "Descending (Z to A)"
                            SortBy.DATE -> "Newest first"
                            SortBy.SIZE -> "Largest first"
                        }
                    )
                }
                Text("Folders always come first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(choice, desc) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(entry: DocEntry, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cacheKey = remember(entry.file.absolutePath) { entry.file.absolutePath }
    var count by remember(cacheKey) { mutableStateOf(GalleryCache.getCount(cacheKey)) }

    LaunchedEffect(cacheKey) {
        if (entry.isDirectory && GalleryCache.getCount(cacheKey) == null) {
            val computed = withContext(Dispatchers.IO) { countMediaFast(entry.file) }
            GalleryCache.putCount(cacheKey, computed)
            count = computed
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected == true) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(entryIcon(entry), contentDescription = null, tint = entryTint(entry))
        Spacer(Modifier.width(16.dp))
        // Every row is two lines tall (an invisible placeholder reserves them) so a row's height never jumps once
        // its async count resolves - that jump was throwing off the spacing of everything below it. Without a count
        // the name is centred against the icon instead of sitting on the top line.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.alpha(0f)) {
                Text(" ", style = MaterialTheme.typography.titleMedium)
                Text(" ", style = MaterialTheme.typography.bodyMedium)
            }
            Column {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val c = count
                val context = LocalContext.current
                val detail = when {
                    !entry.isDirectory -> android.text.format.Formatter.formatShortFileSize(context, entry.size)
                    c != null && c > 0 -> (if (c == 1) "1 item" else "$c items") + if (entry.isArchive) " · archive" else ""
                    entry.isArchive -> "Ren'Py archive"
                    else -> null
                }
                if (detail != null) Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected != null) SelectMark(selected, onDark = false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageRow(entry: DocEntry, showNames: Boolean, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 12.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            AsyncImage(
                model = rememberThumbModel(entry),
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
            if (selected != null) {
                if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp)) { SelectMark(selected, onDark = true) }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(entry: DocEntry, selected: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
            Icon(entryIcon(entry), contentDescription = null, tint = entryTint(entry))
            if (selected != null) {
                if (selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SelectMark(selected, onDark = false) }
            }
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


/** Folder, Ren'Py archive (opens like a folder), or another kind of file. */
private fun entryIcon(entry: DocEntry): androidx.compose.ui.graphics.vector.ImageVector = when {
    entry.isArchive -> Icons.Default.FolderZip
    entry.isDirectory -> Icons.Default.Folder
    else -> when (entry.file.extension.lowercase()) {
        "mp3", "ogg", "opus", "wav", "flac", "m4a", "aac" -> Icons.Default.AudioFile
        "txt", "rpy", "py", "json", "xml", "md", "log", "ini", "cfg" -> Icons.Default.Description
        "zip", "rar", "7z", "tar", "gz", "rpi" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

@Composable
private fun entryTint(entry: DocEntry) =
    if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

private fun countLabel(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"
