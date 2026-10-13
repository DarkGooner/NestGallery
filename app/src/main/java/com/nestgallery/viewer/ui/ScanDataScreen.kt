package com.nestgallery.viewer.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nestgallery.viewer.data.face.FaceScannerManager
import com.nestgallery.viewer.data.face.ScanStatus
import com.nestgallery.viewer.data.nsfw.NsfwScannerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One folder row: [detail] is the counts line under its path. */
class ScanDataFolder(val path: String, val detail: String, val deleteText: String)

/** What a scan has stored, as shown at the top of [ScanDataScreen]. */
class ScanDataSnapshot(val summary: String, val storage: String, val isEmpty: Boolean, val folders: List<ScanDataFolder>)

/** Settings > Face scan data. Deleting goes through [FaceScannerManager] so SQLite and the in-memory index stay in step. */
@Composable
fun FaceDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val faces = remember { FaceScannerManager.getInstance(context) }
    val status by faces.status.collectAsState()
    ScanDataScreen(
        title = "Face scan data",
        scanning = status !is ScanStatus.Idle && status !is ScanStatus.Completed,
        scanningNote = "A face scan is running. Stop it to delete data.",
        help = "Deleting a folder forgets the faces found in its photos (the photos are not touched). People left " +
            "without faces disappear unless you named them. Scanning the folder again indexes it again.",
        deleteAllText = "Every scanned face and person is forgotten, including the names you gave and your " +
            "\"Same person?\" answers. The photos are not touched. This cannot be undone.",
        load = {
            val t = faces.database.totalStats()
            val (db, thumbs) = faces.storageBytes()
            ScanDataSnapshot(
                summary = if (t.totalPhotosIndexed == 0 && t.totalPeople == 0) "Nothing stored"
                    else "${plural(t.totalPhotosIndexed, "photo")} · ${plural(t.totalFacesFound, "face")} · " +
                        plural(t.totalPeople, "person", "people"),
                storage = "Database ${Formatter.formatShortFileSize(context, db)} · thumbnails ${Formatter.formatShortFileSize(context, thumbs)}",
                isEmpty = t.totalPhotosIndexed == 0 && t.totalPeople == 0,
                folders = faces.database.scannedFolders().map {
                    ScanDataFolder(it.path, "${plural(it.photos, "photo")} · ${plural(it.faces, "face")}",
                        "Forget the ${plural(it.faces, "face")} found in ${plural(it.photos, "photo")} of \"${folderName(it.path)}\"?")
                }
            )
        },
        deleteFolder = { faces.deleteFolderData(it) },
        deleteAll = { faces.deleteAllData() },
        onBack = onBack
    )
}

/** Settings > NSFW scan data: results of every model, per folder. */
@Composable
fun NsfwDataScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val nsfw = remember { NsfwScannerManager.getInstance(context) }
    val status by nsfw.status.collectAsState()
    ScanDataScreen(
        title = "NSFW scan data",
        scanning = status.activeFolder != null,
        scanningNote = "An NSFW scan is running. Stop it to delete data.",
        help = "Deleting a folder forgets the NSFW results of its photos (the photos are not touched). Scanning the " +
            "folder again redoes them.",
        deleteAllText = "Every NSFW result is forgotten. The photos are not touched. This cannot be undone.",
        load = {
            val folders = nsfw.database.scannedFolders()
            val photos = folders.sumOf { it.photos }
            val hits = folders.sumOf { it.withDetections }
            ScanDataSnapshot(
                summary = if (photos == 0) "Nothing stored" else "${plural(photos, "photo")} scanned · $hits with something detected",
                storage = "Database ${Formatter.formatShortFileSize(context, nsfw.storageBytes())}",
                isEmpty = photos == 0,
                folders = folders.map {
                    ScanDataFolder(it.path, "${plural(it.photos, "photo")} · ${it.withDetections} with something detected",
                        "Forget the NSFW results of ${plural(it.photos, "photo")} in \"${folderName(it.path)}\"?")
                }
            )
        },
        deleteFolder = { nsfw.deleteFolderData(it) },
        deleteAll = { nsfw.deleteAllData() },
        onBack = onBack
    )
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"

private fun folderName(path: String) = File(path).name.ifEmpty { path }

/** "/storage/emulated/14/Pictures" -> "Storage/Pictures": the user-visible part of a path, like the explorer shows it. */
private fun displayPath(path: String) = path.replaceFirst(Regex("^/storage/emulated/\\d+"), "Storage")

/** What a scan stored, per folder, with delete for one folder or everything. Refused while [scanning]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanDataScreen(
    title: String,
    scanning: Boolean,
    scanningNote: String,
    help: String,
    deleteAllText: String,
    load: () -> ScanDataSnapshot,
    deleteFolder: suspend (String) -> Boolean,
    deleteAll: suspend () -> Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<ScanDataSnapshot?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmFolder by remember { mutableStateOf<ScanDataFolder?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    BackHandler { onBack() }

    LaunchedEffect(reload, scanning) { data = withContext(Dispatchers.IO) { load() } }

    fun run(action: suspend () -> Boolean) {
        busy = true
        scope.launch {
            action()
            busy = false
            reload++
        }
    }

    Scaffold(
        contentWindowInsets = ScreenInsets,
        topBar = {
            TopAppBar(
                windowInsets = TopBarInsets,
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        val d = data
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(d.summary, style = MaterialTheme.typography.titleSmall)
                        Text(d.storage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (scanning) scanningNote else help,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (scanning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { confirmAll = true }, enabled = !scanning && !busy && !d.isEmpty) {
                                Text("Delete all", color = if (!scanning && !busy && !d.isEmpty) MaterialTheme.colorScheme.error else Color.Unspecified)
                            }
                            if (busy) {
                                CircularProgressIndicator(Modifier.padding(start = 16.dp, end = 8.dp).size(20.dp), strokeWidth = 2.dp)
                                Text("Deleting…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (d.folders.isEmpty()) item {
                Text(
                    "Nothing scanned yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(d.folders, key = { it.path }) { f ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(folderName(f.path), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(displayPath(File(f.path).parent ?: f.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(f.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { confirmFolder = f }, enabled = !scanning && !busy) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete data of ${folderName(f.path)}")
                    }
                }
            }
        }
    }

    confirmFolder?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmFolder = null },
            title = { Text("Delete this folder's data?") },
            text = { Text(f.deleteText) },
            confirmButton = {
                TextButton(onClick = { confirmFolder = null; run { deleteFolder(f.path) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFolder = null }) { Text("Cancel") } }
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Delete all?") },
            text = { Text(deleteAllText) },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; run { deleteAll() } }) {
                    Text("Delete all", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } }
        )
    }
}
