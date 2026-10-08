package com.nestgallery.viewer.ui.face

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.nestgallery.viewer.ui.FastScrollbar
import com.nestgallery.viewer.ui.HoldPreviewOverlay
import com.nestgallery.viewer.ui.holdPreviewGestures
import coil.request.ImageRequest
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.face.FaceDatabase
import com.nestgallery.viewer.data.face.FaceScannerManager
import com.nestgallery.viewer.data.face.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.nestgallery.viewer.ui.ScreenInsets
import com.nestgallery.viewer.ui.TopBarInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: Long,
    folderPath: String? = null,
    listMode: Boolean = false,
    onToggleViewMode: () -> Unit = {},
    onBack: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit
) {
    val context = LocalContext.current
    val db = remember { FaceDatabase.getInstance(context) }
    val scanner = remember { FaceScannerManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var person by remember { mutableStateOf<PersonEntity?>(null) }
    var mediaEntries by remember { mutableStateOf<List<DocEntry>>(emptyList()) }
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }
    // "Select" mode: tap photos, then "Not this person" removes them (and the grouping remembers the correction)
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    fun loadData() {
        scope.launch {
            val (p, entries) = withContext(Dispatchers.IO) {
                val p = db.getPersonById(personId)
                val entries = db.getImagePathsForPerson(personId, folderPath).mapNotNull { path ->
                    val file = File(path)
                    if (file.exists()) DocEntry(file = file, name = file.name, isDirectory = false, size = file.length(), isVideo = false)
                    else null
                }
                p to entries
            }
            person = p
            mediaEntries = entries
            selected = selected.filter { path -> entries.any { it.file.absolutePath == path } }.toSet()
        }
    }

    LaunchedEffect(personId) {
        loadData()
    }

    val currentPerson = person

    Scaffold(
        contentWindowInsets = ScreenInsets,
        topBar = {
            if (selecting) TopAppBar(
                windowInsets = TopBarInsets,
                title = { Text(if (selected.isEmpty()) "Select photos" else "${selected.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { selecting = false; selected = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Done selecting")
                    }
                },
                actions = {
                    TextButton(onClick = { showRemoveDialog = true }, enabled = selected.isNotEmpty()) {
                        Icon(Icons.Default.PersonRemove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Not this person")
                    }
                }
            ) else TopAppBar(
                windowInsets = TopBarInsets,
                title = {
                    Text(
                        currentPerson?.name ?: "Person",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onToggleViewMode) {
                        if (listMode) Icon(Icons.Default.GridView, contentDescription = "Grid view")
                        else Icon(Icons.Default.ViewAgenda, contentDescription = "List view")
                    }
                    TextButton(onClick = { selecting = true }, enabled = mediaEntries.isNotEmpty()) { Text("Select") }
                    IconButton(
                        onClick = {
                            renameInput = currentPerson?.name ?: ""
                            showRenameDialog = true
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename Person")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove Person")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Person Header Info
            currentPerson?.let { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!p.coverThumbnailPath.isNullOrEmpty() && File(p.coverThumbnailPath).exists()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(File(p.coverThumbnailPath))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = p.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${mediaEntries.size} photos",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (mediaEntries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No photos found for this person",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                fun open(index: Int) = onOpenImage(mediaEntries, index)
                Box(Modifier.fillMaxSize()) {
                    if (listMode) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                            itemsIndexed(mediaEntries, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
                                PersonPhoto(
                                    entry = entry, square = false, selecting = selecting,
                                    isSelected = entry.file.absolutePath in selected,
                                    onToggleSelect = { selected = selected.toggle(entry.file.absolutePath) },
                                    onClick = { open(index) },
                                    onHoldStart = { previewEntry = entry }, onHoldEnd = { previewEntry = null },
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                        FastScrollbar(
                            itemCount = mediaEntries.size,
                            visibleCount = listState.layoutInfo.visibleItemsInfo.size,
                            firstVisibleIndex = listState.firstVisibleItemIndex,
                            isScrolling = listState.isScrollInProgress,
                            onDragToIndex = { index -> scope.launch { listState.scrollToItem(index) } },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    } else {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            gridItemsIndexed(mediaEntries, key = { _, entry -> entry.file.absolutePath }) { index, entry ->
                                PersonPhoto(
                                    entry = entry, square = true, selecting = selecting,
                                    isSelected = entry.file.absolutePath in selected,
                                    onToggleSelect = { selected = selected.toggle(entry.file.absolutePath) },
                                    onClick = { open(index) },
                                    onHoldStart = { previewEntry = entry }, onHoldEnd = { previewEntry = null }
                                )
                            }
                        }
                        FastScrollbar(
                            itemCount = mediaEntries.size,
                            visibleCount = gridState.layoutInfo.visibleItemsInfo.size,
                            firstVisibleIndex = gridState.firstVisibleItemIndex,
                            isScrolling = gridState.isScrollInProgress,
                            onDragToIndex = { index -> scope.launch { gridState.scrollToItem(index) } },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
        previewEntry?.let { HoldPreviewOverlay(entry = it) }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Person") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameInput.isNotBlank()) {
                            val name = renameInput.trim()
                            scope.launch { scanner.renamePerson(personId, name); loadData() }
                        }
                        showRenameDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Remove Person") },
            text = {
                Text("This person is hidden and their faces won't be grouped again. Your actual photos will not be deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch { scanner.deletePerson(personId); onBack() }
                    }
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRemoveDialog) {
        val n = selected.size
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Not ${currentPerson?.name ?: "this person"}?") },
            text = {
                Text("$n ${if (n == 1) "photo is" else "photos are"} removed from this person and won't be grouped with them again. Your photos are not deleted.")
            },
            confirmButton = {
                Button(onClick = {
                    showRemoveDialog = false
                    val paths = selected.toList()
                    scope.launch {
                        scanner.removePhotosFromPerson(personId, paths)
                        selected = emptySet(); selecting = false
                        loadData()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") } }
        )
    }
}

private fun Set<String>.toggle(path: String) = if (path in this) this - path else this + path

/**
 * One of the person's photos: a square crop in the grid, the whole photo at full width in the list. In select mode a
 * tap toggles it; otherwise tap opens it and holding shows the preview.
 */
@Composable
private fun PersonPhoto(
    entry: DocEntry,
    square: Boolean,
    selecting: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (square) Modifier.aspectRatio(1f).clip(RoundedCornerShape(4.dp)) else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (selecting) Modifier.clickable(onClick = onToggleSelect)
                else Modifier.holdPreviewGestures(key = entry.file, onClick = onClick, onHoldStart = onHoldStart, onHoldEnd = onHoldEnd)
            )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(entry.file).crossfade(true).build(),
            contentDescription = entry.name,
            contentScale = if (square) ContentScale.Crop else ContentScale.FillWidth,
            modifier = if (square) Modifier.fillMaxSize() else Modifier.fillMaxWidth()
        )
        if (entry.isVideo) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(if (square) 28.dp else 56.dp))
            }
        }
        if (selecting) {
            if (isSelected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
            )
        }
    }
}
