package com.nestgallery.viewer.ui.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.nestgallery.viewer.data.FileOps
import com.nestgallery.viewer.data.UiPrefs
import com.nestgallery.viewer.data.rpa.RpaFs
import com.nestgallery.viewer.data.naturalCompare
import com.nestgallery.viewer.data.storageRootEntry
import com.nestgallery.viewer.ui.AppOverflowMenu
import com.nestgallery.viewer.ui.MenuAction
import com.nestgallery.viewer.ui.ScreenInsets
import com.nestgallery.viewer.ui.TopBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---- selection: the actions every "N selected" bar offers -----------------------------------------------------------

/**
 * The file actions of a selection bar, the same on every screen that can select photos or files (explorer, recursive
 * view, gallery, a person's photos, face search results): Move to / Copy to a folder, Delete, and in the overflow
 * Select all, Share, Rename, Cut / Copy (for Paste in the explorer) and Properties. [extra] comes first in the overflow
 * (e.g. "Not this person"). The bar handles its own dialogs; [onDone] ends the selection once an action started.
 */
@Composable
fun RowScope.FileSelectionActions(
    files: List<File>,
    onDone: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    extra: List<MenuAction> = emptyList()
) {
    val context = LocalContext.current
    val ops = remember { FileOps.getInstance(context) }
    var picker by remember { mutableStateOf<Boolean?>(null) }   // true = move, false = copy
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var extractPicker by remember { mutableStateOf(false) }
    val any = files.isNotEmpty()
    // only Ren'Py archives selected (on disk, not inside another archive): offer to extract them
    val archives = any && files.all { RpaFs.isArchiveName(it.name) && !RpaFs.isVirtual(it) }
    // files inside a Ren'Py archive can be copied out, shared and inspected, not moved, renamed or deleted
    val writable = any && files.none { RpaFs.isReadOnly(it) }
    val scope = rememberCoroutineScope()

    IconButton(onClick = { picker = true }, enabled = writable) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move to folder") }
    IconButton(onClick = { picker = false }, enabled = any) { Icon(Icons.Default.FileCopy, contentDescription = "Copy to folder") }
    IconButton(onClick = { confirmDelete = true }, enabled = writable) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
    AppOverflowMenu(
        buildList {
            addAll(extra)
            if (onSelectAll != null) add(MenuAction("Select all", Icons.Default.SelectAll, onClick = onSelectAll))
            if (archives) {
                add(MenuAction("Extract here", Icons.Default.Unarchive) { ops.extract(files, dest = null); onDone() })
                add(MenuAction("Extract to…", Icons.Default.Unarchive) { extractPicker = true })
            }
            add(MenuAction("Share", Icons.Default.Share, enabled = any) { scope.launch { shareFiles(context, onDisk(files)) } })
            add(MenuAction("Rename", Icons.Default.DriveFileRenameOutline, enabled = writable && files.size == 1) { renaming = true })
            add(MenuAction("Cut", Icons.Default.ContentCut, enabled = writable) { ops.setClip(files, cut = true); onDone() })
            add(MenuAction("Copy", Icons.Default.ContentCopy, enabled = any) { ops.setClip(files, cut = false); onDone() })
            add(MenuAction("Properties", Icons.Default.Info, enabled = any) { showInfo = true })
        },
        withGlobal = false
    )

    picker?.let { move ->
        FolderPickerDialog(
            title = "${if (move) "Move" else "Copy"} ${files.size.items()} to…",
            confirmLabel = if (move) "Move here" else "Copy here",
            start = files.firstOrNull()?.parentFile,
            onDismiss = { picker = null },
            onPick = { dest ->
                picker = null
                if (move) ops.move(files, dest) else ops.copy(files, dest)
                onDone()
            }
        )
    }
    if (extractPicker) {
        FolderPickerDialog(
            title = "Extract ${if (files.size == 1) files[0].name else "${files.size} archives"} to…",
            confirmLabel = "Extract here",
            start = files.firstOrNull()?.parentFile,
            onDismiss = { extractPicker = false },
            onPick = { dest ->
                extractPicker = false
                ops.extract(files, dest)
                onDone()
            }
        )
    }
    if (confirmDelete) {
        DeleteDialog(files, onDismiss = { confirmDelete = false }, onConfirm = { confirmDelete = false; ops.delete(files); onDone() })
    }
    if (renaming) files.singleOrNull()?.let { f ->
        NameDialog(
            title = "Rename",
            initial = f.name,
            confirmLabel = "Rename",
            selectBaseName = !f.isDirectory,
            onDismiss = { renaming = false },
            onConfirm = { name -> ops.rename(f, name).also { if (it == null) onDone() } }
        )
    }
    if (showInfo) PropertiesDialog(files, onDismiss = { showInfo = false })
}

/** The "N selected" bar that replaces a screen's title bar while selecting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    insets: Boolean = true,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit
) {
    TopAppBar(
        modifier = modifier,
        windowInsets = if (insets) TopBarInsets else androidx.compose.foundation.layout.WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") } },
        title = { Text(if (count == 0) "Select items" else "$count selected", fontWeight = FontWeight.SemiBold, maxLines = 1) },
        actions = actions
    )
}

// ---- progress, results, paste ----------------------------------------------------------------------------------------

/** Shown once, over every screen (MainActivity): the copy / move / delete progress dialog and the result toasts. */
@Composable
fun FileOpsHost() {
    val context = LocalContext.current
    val ops = remember { FileOps.getInstance(context) }
    val progress by ops.progress.collectAsState()
    LaunchedEffect(Unit) { ops.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    progress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(p.title + "…") },
            text = {
                Column {
                    if (p.total > 0) LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (p.total > 0) "${p.done} / ${p.total}  ${p.current}" else p.current,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { ops.cancel() }) { Text("Cancel") } }
        )
    }
}

/** ZArchiver-style paste bar at the bottom of the explorer while something is on the clipboard. */
@Composable
fun PasteBar(dest: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ops = remember { FileOps.getInstance(context) }
    val clip by ops.clip.collectAsState()
    val c = clip ?: return
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (c.cut) Icons.Default.ContentCut else Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "${c.files.size.items()} ${if (c.cut) "to move" else "to copy"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { ops.clearClip() }) { Text("Cancel") }
            Button(onClick = { ops.paste(dest) }) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste here")
            }
        }
    }
}

// ---- dialogs ------------------------------------------------------------------------------------------------------

/**
 * Asks for a name (new folder, rename). [onConfirm] returns null when it worked (the dialog closes) or the reason it
 * didn't (shown under the field). With [selectBaseName] the cursor range leaves the extension alone.
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    selectBaseName: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> String?
) {
    val scope = rememberCoroutineScope()
    val end = if (selectBaseName) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, end))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun submit() = scope.launch {
        val e = onConfirm(value.text)
        if (e == null) onDismiss() else error = e
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; error = null },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                label = { Text("Name") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus)
            )
        },
        confirmButton = { Button(onClick = { submit() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun DeleteDialog(files: List<File>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val n = files.size
    val folders = files.count { it.isDirectory }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (n == 1) "Delete \"${files[0].name}\"?" else "Delete $n items?") },
        text = {
            Text(
                (if (folders > 0) "Folders are deleted with everything in them. " else "") +
                    "${if (n == 1) "It" else "They"} will be permanently deleted from this phone. This can't be undone."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Name, location, size (folders: everything inside, counted off the main thread), modified time. */
@Composable
fun PropertiesDialog(files: List<File>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val stats by produceState<Pair<Long, Int>?>(null, files) {
        value = withContext(Dispatchers.IO) {
            var bytes = 0L; var count = 0
            for (f in files) {
                // inside an archive: sizes from its index, nothing is extracted
                val archived = RpaFs.sizeOf(f)
                if (archived != null) { bytes += archived.first; count += archived.second; continue }
                for (x in FileOps.walk(f)) { bytes += x.length(); count++ }
            }
            bytes to count
        }
    }
    val single = files.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(single?.name ?: "${files.size} items", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("Location", (single?.parentFile ?: files.firstOrNull()?.parentFile)?.path ?: "")
                if (single != null) InfoLine("Type", if (single.isDirectory) "Folder" else (single.extension.uppercase().ifEmpty { "File" }))
                val s = stats
                InfoLine("Size", if (s == null) "Counting…" else Formatter.formatFileSize(context, s.first) +
                    if (files.any { it.isDirectory }) " · ${s.second} files" else "")
                if (single != null) InfoLine("Modified", DateFormat.getMediumDateFormat(context).format(single.lastModified()) + " " +
                    DateFormat.getTimeFormat(context).format(single.lastModified()))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Full-screen folder chooser for Copy to / Move to / Extract to: browse from the storage root (or [start]), make a new
 * folder on the way, and confirm with [confirmLabel] ("Move here"). Nothing about the choice is remembered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerDialog(
    title: String,
    confirmLabel: String,
    start: File?,
    onDismiss: () -> Unit,
    onPick: (File) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs.getInstance(context) }
    val ops = remember { FileOps.getInstance(context) }
    val root = remember { storageRootEntry().file }
    var current by remember { mutableStateOf(start?.takeIf { it.isDirectory && it.path.startsWith(root.path) } ?: root) }
    var reload by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val folders by produceState<List<File>?>(null, current, reload) {
        value = null
        value = withContext(Dispatchers.IO) {
            (current.listFiles { f -> f.isDirectory && (!prefs.hideHidden || !f.name.startsWith(".")) } ?: emptyArray())
                .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        }
    }
    val crumbs = remember(current) {
        generateSequence(current) { f -> if (f.path == root.path) null else f.parentFile }
            .takeWhile { it.path.startsWith(root.path) }
            .toList().reversed()
    }
    fun pick(dir: File) = onPick(dir)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    windowInsets = TopBarInsets,
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Cancel") } },
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    actions = {
                        IconButton(onClick = { creating = true }) { Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder") }
                    }
                )
                // breadcrumb, tappable
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    crumbs.forEachIndexed { i, f ->
                        val last = i == crumbs.lastIndex
                        Surface(
                            shape = CircleShape,
                            color = if (last) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clip(CircleShape).clickable(enabled = !last) { current = f }
                        ) {
                            Text(
                                if (f.path == root.path) "Storage" else f.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                        if (!last) Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    val list = folders
                    when {
                        list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LinearProgressIndicator() } }
                        list.isEmpty() -> item {
                            Text("No folders here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                        }
                        else -> items(list, key = { it.path }) { f -> FolderLine(f.name, Icons.Default.Folder) { current = f } }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(ScreenInsets).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        if (current.path == root.path) "Storage" else current.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { pick(current) }) { Text(confirmLabel) }
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New folder",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                ops.createFolder(current, name).also { if (it == null) { current = File(current, name.trim()); reload++ } }
            }
        )
    }
}

@Composable
private fun FolderLine(name: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- sharing -------------------------------------------------------------------------------------------------------

/** The system share sheet for [files] (folders are skipped), as read-only content:// URIs from the app's FileProvider. */
fun shareFiles(context: Context, files: List<File>) {
    val shareable = files.filter { it.isFile }
    if (shareable.isEmpty()) { Toast.makeText(context, "Folders can't be shared", Toast.LENGTH_SHORT).show(); return }
    val authority = context.packageName + ".files"
    val uris = try {
        ArrayList(shareable.map { FileProvider.getUriForFile(context, authority, it) })
    } catch (e: IllegalArgumentException) {
        Toast.makeText(context, "These files can't be shared", Toast.LENGTH_SHORT).show(); return
    }
    val types = shareable.map { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension.lowercase()) ?: "*/*" }.distinct()
    val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.type = type
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, null))
    } catch (e: Exception) {
        Toast.makeText(context, "No app to share with", Toast.LENGTH_SHORT).show()
    }
}

private fun Int.items() = if (this == 1) "1 item" else "$this items"
