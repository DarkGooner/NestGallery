package com.nestgallery.viewer.ui.nsfw

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.nsfw.NsfwFilter
import com.nestgallery.viewer.data.nsfw.NsfwFolderIndex
import com.nestgallery.viewer.data.nsfw.NsfwLabels
import com.nestgallery.viewer.data.nsfw.NsfwScanService
import com.nestgallery.viewer.data.nsfw.NsfwScanStatus
import com.nestgallery.viewer.data.nsfw.NsfwScannerManager
import com.nestgallery.viewer.ui.FastScrollbar
import com.nestgallery.viewer.ui.HoldPreviewOverlay
import com.nestgallery.viewer.ui.MediaImageTile
import com.nestgallery.viewer.ui.ScreenInsets
import com.nestgallery.viewer.ui.TopBarInsets
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * NSFW hub for a recursive view, laid out like the face screen: top bar (scan / rescan, overflow with Settings),
 * progress card while scanning, and two tabs - **Filters** (one card per label found: histogram + range slider) and
 * **Photos** (the photos that pass every filter; tapping one opens the viewer on that set).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderNsfwScreen(
    root: DocEntry,
    files: List<DocEntry>,
    onBack: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nsfw = remember { NsfwScannerManager.getInstance(context) }
    val status by nsfw.status.collectAsState()
    val revision by nsfw.revision.collectAsState()
    val model by nsfw.model.collectAsState()
    val rootPath = root.file.absolutePath
    val filter = remember(rootPath) { NsfwFilterState.forFolder(rootPath) }
    val n = remember { NumberFormat.getInstance() }

    val photos = remember(files) { files.filter { !it.isVideo && NsfwScannerManager.isScannable(it.file) } }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }   // 0: Filters, 1: Photos
    var showMenu by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }

    BackHandler { onBack() }

    // Counts per photo; rebuilt off the main thread when results land (~1/s while scanning), the threshold or the model changes.
    val index by produceState(NsfwFolderIndex.EMPTY, photos, revision, filter.threshold, model.id) {
        value = nsfw.folderIndex(photos.map { it.file }, filter.threshold)
    }
    val active = filter.activeRanges(index.maxCounts)
    // Every narrowed slider must hold (AND); with no filter all scanned photos are listed.
    val shown = remember(index, active, photos) {
        photos.filterIndexed { i, _ -> index.counts.getOrNull(i) != null && NsfwFilter.matches(index.counts[i], active) }
    }
    val scanningHere = status.activeFolder == rootPath
    val busyElsewhere = status.activeFolder.let { it != null && it != rootPath }

    // Android 13+: the progress notification needs this permission. Scanning works either way.
    fun beginScan() = nsfw.startScan(photos.map { it.file }, rootPath)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { beginScan() }
    fun startScan() {
        if (busyElsewhere) {
            android.widget.Toast.makeText(context, "An NSFW scan of another folder is still running", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else beginScan()
    }

    Scaffold(
        contentWindowInsets = ScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                title = {
                    Column {
                        Text("NSFW in ${root.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${model.title} · ${n.format(index.scanned)} / ${n.format(photos.size)} photos scanned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { startScan() }) {
                        if (scanningHere) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Scan new and changed photos")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = { showMenu = false; onOpenSettings() }
                            )
                            DropdownMenuItem(
                                text = { Text("Forget results for this folder") },
                                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                                enabled = status.activeFolder == null && index.scanned > 0,
                                onClick = { showMenu = false; confirmForget = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                NsfwScanProgressCard(
                    status = status.takeIf { it.folderOf() == rootPath },
                    onPause = { nsfw.pauseScan() },
                    onResume = { nsfw.resumeScan() },
                    onCancel = { nsfw.stopScan() }
                )

                if (index.scanned == 0 && !scanningHere) {
                    EmptyState(root.name, photos.size, model.title, onScan = { startScan() }, onOpenSettings = onOpenSettings)
                    return@Column
                }

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Filters") },
                        icon = {
                            BadgedBox(badge = { if (active.isNotEmpty()) Badge { Text("${active.size}") } }) {
                                Icon(Icons.Default.FilterAlt, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Photos (${n.format(shown.size)})") },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }

                if (selectedTab == 0) {
                    FiltersTab(index, filter, active, shown.size, onShowPhotos = { selectedTab = 1 })
                } else {
                    PhotosTab(
                        shown = shown,
                        index = index,
                        active = active,
                        filter = filter,
                        onOpen = { i -> onOpenImage(shown, i) },
                        onHold = { previewEntry = it }
                    )
                }
            }
            previewEntry?.let { HoldPreviewOverlay(entry = it) }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget NSFW results?") },
            text = { Text("${model.title} results for ${root.name} and its subfolders are deleted. Scan again to rebuild them.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    filter.clear()
                    scope.launch { nsfw.clearFolder(rootPath) }
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } }
        )
    }
}

private fun NsfwScanStatus.folderOf(): String? = when (this) {
    is NsfwScanStatus.Scanning -> folderPath
    is NsfwScanStatus.Paused -> folderPath
    is NsfwScanStatus.Completed -> folderPath
    is NsfwScanStatus.Failed -> folderPath
    NsfwScanStatus.Idle -> null
}

@Composable
private fun FiltersTab(index: NsfwFolderIndex, filter: NsfwFilterState, active: Map<Int, IntRange>, shownCount: Int, onShowPhotos: () -> Unit) {
    val n = NumberFormat.getInstance()
    // (label index, group heading to show above it or null); ALL is ordered by group
    val rows = index.presentLabels.let { present ->
        present.mapIndexed { k, i ->
            val group = NsfwLabels.group(NsfwLabels.ALL[i])
            i to group.takeIf { k == 0 || NsfwLabels.group(NsfwLabels.ALL[present[k - 1]]) != it }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary") {
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            n.format(shownCount),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            (if (active.isEmpty()) "photos scanned · no filter" else "of ${n.format(index.scanned)} scanned photos match") +
                                if (active.size > 1) " (all ${active.size} filters)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onShowPhotos, enabled = shownCount > 0) { Text("Show photos") }
                        if (active.isNotEmpty()) OutlinedButton(onClick = { filter.clear() }) { Text("Clear filters") }
                    }
                }
            }
        }
        item(key = "confidence") { NsfwConfidenceCard(filter) }
        if (rows.isEmpty()) {
            item(key = "none") {
                Text(
                    "Nothing detected at this confidence.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
        items(rows, key = { it.first }) { (i, heading) ->
            Column {
                if (heading != null) {
                    Text(
                        heading.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp)
                    )
                }
                NsfwLabelCard(index, i, filter)
            }
        }
    }
}

@Composable
private fun PhotosTab(
    shown: List<DocEntry>,
    index: NsfwFolderIndex,
    active: Map<Int, IntRange>,
    filter: NsfwFilterState,
    onOpen: (Int) -> Unit,
    onHold: (DocEntry?) -> Unit
) {
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        if (active.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            NsfwActiveFilterChips(active, index.maxCounts, filter)
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No photos match these filters", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (active.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { filter.clear() }) { Text("Clear filters") }
                }
            }
            return@Column
        }
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp)
            ) {
                itemsIndexed(shown, key = { _, e -> e.file.absolutePath }) { i, entry ->
                    MediaImageTile(entry = entry, showNames = false, onClick = { onOpen(i) }, onHoldStart = { onHold(entry) }, onHoldEnd = { onHold(null) })
                }
            }
            FastScrollbar(
                itemCount = shown.size,
                visibleCount = grid.layoutInfo.visibleItemsInfo.size,
                firstVisibleIndex = grid.firstVisibleItemIndex,
                isScrolling = grid.isScrollInProgress,
                onDragToIndex = { scope.launch { grid.scrollToItem(it) } },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun EmptyState(folder: String, photoCount: Int, modelTitle: String, onScan: () -> Unit, onOpenSettings: () -> Unit) {
    val n = NumberFormat.getInstance()
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Text("No photos scanned for NSFW yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Detects faces and exposed or covered body parts in the ${n.format(photoCount)} photos of this folder and its " +
                    "subfolders, on this device. Then filter by how many of each a photo has.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onScan, enabled = photoCount > 0) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan photos in $folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onOpenSettings) { Text("Model: $modelTitle · change") }
        }
    }
}

/** Progress card for a scan of this folder (same look as the face scan's), plus a brief done / failed state. */
@Composable
private fun NsfwScanProgressCard(status: NsfwScanStatus?, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    AnimatedVisibility(visible = status != null && status != NsfwScanStatus.Idle) {
        val s = status ?: return@AnimatedVisibility
        val failed = s is NsfwScanStatus.Failed
        val isPaused = s is NsfwScanStatus.Paused
        val (scanned, total) = when (s) {
            is NsfwScanStatus.Scanning -> s.scannedCount to s.totalCount
            is NsfwScanStatus.Paused -> s.scannedCount to s.totalCount
            is NsfwScanStatus.Completed -> s.totalScanned to s.totalCount
            else -> 0 to 0
        }
        val detail = when (s) {
            is NsfwScanStatus.Scanning -> listOfNotNull(
                s.photosPerSecond.takeIf { it > 0.05f }?.let { "%.1f photos/s".format(it) },
                s.etaSeconds.takeIf { it >= 0 }?.let { "${NsfwScanService.formatEta(it)} left" },
                s.currentFileName
            ).joinToString(" · ")
            is NsfwScanStatus.Paused -> "Paused"
            is NsfwScanStatus.Completed -> if (total == 0) "Everything was already scanned" else "Done"
            is NsfwScanStatus.Failed -> s.message
            NsfwScanStatus.Idle -> ""
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (failed) Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                    Text(
                        when (s) {
                            is NsfwScanStatus.Paused -> "NSFW scan paused"
                            is NsfwScanStatus.Completed -> "NSFW scan complete"
                            is NsfwScanStatus.Failed -> "NSFW scan failed"
                            else -> "Scanning for NSFW…"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    if (total > 0) Text("$scanned / $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (s is NsfwScanStatus.Scanning || isPaused) {
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = if (isPaused) onResume else onPause, modifier = Modifier.size(28.dp)) {
                            Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (isPaused) "Resume" else "Pause", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (!failed) {
                    Spacer(Modifier.height(6.dp))
                    val mod = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    if (s is NsfwScanStatus.Scanning && total == 0) LinearProgressIndicator(mod)
                    else LinearProgressIndicator(progress = { if (total > 0) scanned.toFloat() / total else 1f }, modifier = mod)
                }
                Spacer(Modifier.height(4.dp))
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
