package com.nestgallery.viewer.ui.face

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.face.FaceImageLoader
import com.nestgallery.viewer.data.face.FaceMath
import com.nestgallery.viewer.data.face.QueryFace
import com.nestgallery.viewer.data.face.FaceDatabase
import com.nestgallery.viewer.data.face.FaceMatch
import com.nestgallery.viewer.data.face.FaceScannerManager
import com.nestgallery.viewer.data.face.PersonEntity
import com.nestgallery.viewer.data.face.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Scoped Face Recognition screen for Recursive Explorer.
 * Runs face scanning, ArcFace-based grouping, and reverse face search
 * strictly within the explored folder tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderFaceScreen(
    root: DocEntry,
    files: List<DocEntry>,
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanner = remember { FaceScannerManager.getInstance(context) }
    val db = remember { FaceDatabase.getInstance(context) }
    val scanStatus by scanner.status.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: People, 1: Search by Face
    var people by remember { mutableStateOf<List<PersonEntity>>(emptyList()) }
    var personToRename by remember { mutableStateOf<PersonEntity?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // Reverse Face Search state
    var queryBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedFacesInQuery by remember { mutableStateOf<List<QueryFace>>(emptyList()) }
    var activeFaceIndex by remember { mutableIntStateOf(0) }
    // Raw ArcFace cosine. Measured: same person median ~0.74 (min ~0.45), different people <= ~0.25.
    var similarityThreshold by remember { mutableFloatStateOf(0.42f) }
    var isSearching by remember { mutableStateOf(false) }
    var matchResults by remember { mutableStateOf<List<FaceMatch>>(emptyList()) }
    var matchedDocEntries by remember { mutableStateOf<List<DocEntry>>(emptyList()) }

    fun refreshPeople() {
        people = db.getPeopleInFolder(root.file.absolutePath)
    }

    LaunchedEffect(root.file.absolutePath) {
        refreshPeople()
    }

    LaunchedEffect(scanStatus) {
        if (scanStatus is ScanStatus.Completed || scanStatus is ScanStatus.Idle) {
            refreshPeople()
        }
    }

    fun runFaceSearch(embedding: FloatArray, threshold: Float) {
        scope.launch {
            isSearching = true
            val all = scanner.searchFaces(embedding, root.file.absolutePath, threshold)
            // drop photos that no longer exist, keeping matches and tiles index-aligned (stat calls off the main thread)
            val (matches, docs) = withContext(Dispatchers.IO) {
                val kept = all.filter { File(it.filePath).exists() }
                kept to kept.map { m -> File(m.filePath).let { f -> DocEntry(f, f.name, false, f.length(), false) } }
            }
            matchResults = matches
            matchedDocEntries = docs
            isSearching = false
        }
    }

    fun selectQueryFace(index: Int) {
        val face = detectedFacesInQuery.getOrNull(index) ?: return
        activeFaceIndex = index
        queryBitmap = face.aligned
        runFaceSearch(face.embedding, similarityThreshold)
    }

    fun processQueryImage(sourceBitmap: Bitmap) {
        scope.launch {
            isSearching = true
            val faces = scanner.analyzeQueryImage(sourceBitmap)
            detectedFacesInQuery = faces
            selectQueryFace(0)
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isSearching = true
                val bmp = withContext(Dispatchers.IO) { FaceImageLoader.decodeUri(context, it) }
                if (bmp != null) processQueryImage(bmp) else isSearching = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Faces in ${root.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${files.size} photos scanned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val candidateFiles = files.map { it.file }
                            scanner.startScanForFiles(candidateFiles, root.file.absolutePath)
                        }
                    ) {
                        if (scanStatus is ScanStatus.Scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan / Rescan Faces")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Scan progress banner
            FolderScanProgressBanner(
                status = scanStatus,
                onPause = { scanner.pauseScan() },
                onResume = { scanner.resumeScan() },
                onCancel = { scanner.stopScan() }
            )

            // Primary Tabs: People Clusters vs Search by Face
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("People (${people.size})") },
                    icon = { Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Find by Face") },
                    icon = { Icon(Icons.Default.PersonSearch, contentDescription = null, modifier = Modifier.size(20.dp)) }
                )
            }

            if (selectedTab == 0) {
                // Tab 0: People Clusters
                if (people.isEmpty() && scanStatus !is ScanStatus.Scanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Face,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "No faces scanned in this folder yet",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Scan the photos collected by this recursive explorer to automatically group people on this device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    val candidateFiles = files.map { it.file }
                                    scanner.startScanForFiles(candidateFiles, root.file.absolutePath)
                                }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan Faces in ${root.name}")
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 105.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(people, key = { it.id }) { person ->
                            FolderPersonGridItem(
                                person = person,
                                onClick = { onOpenPerson(person.id) },
                                onRename = {
                                    personToRename = person
                                    renameInput = person.name
                                }
                            )
                        }
                    }
                }
            } else {
                // Tab 1: Find by Face (Reverse Search in this folder)
                Column(modifier = Modifier.fillMaxSize()) {
                    // Query card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val currentFace = queryBitmap
                                if (currentFace != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = currentFace.asImageBitmap(),
                                            contentDescription = "Target Face",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Face,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        if (queryBitmap != null) "Searching in ${root.name}" else "Select a face to find",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        if (queryBitmap != null) {
                                            if (isSearching) "Matching..." else "Found ${matchedDocEntries.size} photos"
                                        } else {
                                            "Search photos inside this folder tree"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                ) {
                                    Text(if (queryBitmap != null) "Change" else "Pick Photo")
                                }
                            }

                            // Which face of the picked photo to search for (only shown when there are several)
                            if (detectedFacesInQuery.size > 1) {
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.horizontalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        "Faces in photo:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    detectedFacesInQuery.forEachIndexed { i, face ->
                                        Image(
                                            bitmap = face.aligned.asImageBitmap(),
                                            contentDescription = "Face ${i + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .border(
                                                    if (i == activeFaceIndex) 2.dp else 1.dp,
                                                    if (i == activeFaceIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                                    CircleShape
                                                )
                                                .clickable { selectQueryFace(i) }
                                        )
                                    }
                                }
                            }

                            // Sensitivity = raw ArcFace cosine cut-off (same person: median ~0.74; different people: <= ~0.25)
                            Spacer(Modifier.height(12.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                Text(
                                    "Sensitivity:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                listOf("Strict" to 0.52f, "Balanced" to 0.42f, "Broad" to 0.34f).forEach { (label, value) ->
                                    FilterChip(
                                        selected = similarityThreshold == value,
                                        onClick = {
                                            similarityThreshold = value
                                            detectedFacesInQuery.getOrNull(activeFaceIndex)?.let { runFaceSearch(it.embedding, value) }
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }
                    }

                    // Results
                    if (isSearching) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("Searching ${root.name}…")
                            }
                        }
                    } else if (queryBitmap == null) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Pick any photo above to search for matching faces within this folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (matchedDocEntries.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No matches found in ${root.name}.\nTry setting sensitivity to 'Broad' or rescan faces.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 110.dp),
                            contentPadding = PaddingValues(2.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(matchedDocEntries, key = { _, doc -> doc.file.absolutePath }) { index, entry ->
                                val match = matchResults.getOrNull(index)
                                val matchPercent = match?.let { (FaceMath.matchProbability(it.similarity) * 100).roundToInt() } ?: 0

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { onOpenImage(matchedDocEntries, index) }
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(entry.file)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = entry.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp)
                                            .background(
                                                color = if (matchPercent >= 75) Color(0xFF1B5E20).copy(alpha = 0.85f)
                                                else if (matchPercent >= 50) Color(0xFF00695C).copy(alpha = 0.85f)
                                                else Color(0xFFE65100).copy(alpha = 0.85f),
                                                shape = RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "$matchPercent%",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename dialog
    personToRename?.let { person ->
        AlertDialog(
            onDismissRequest = { personToRename = null },
            title = { Text("Name this person") },
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
                            db.renamePerson(person.id, renameInput.trim())
                            refreshPeople()
                        }
                        personToRename = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { personToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FolderPersonGridItem(
    person: PersonEntity,
    onClick: () -> Unit,
    onRename: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!person.coverThumbnailPath.isNullOrEmpty() && File(person.coverThumbnailPath).exists()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(File(person.coverThumbnailPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                person.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Edit,
                contentDescription = "Rename",
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRename() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        Text(
            "${person.faceCount} ${if (person.faceCount == 1) "photo" else "photos"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FolderScanProgressBanner(
    status: ScanStatus,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    AnimatedVisibility(
        visible = status is ScanStatus.Scanning || status is ScanStatus.Paused
    ) {
        val (scanned, total, faces, isPaused, currentFile) = when (status) {
            is ScanStatus.Scanning -> Tuple5(
                status.scannedCount,
                status.totalCount,
                status.facesFound,
                false,
                status.currentFileName
            )
            is ScanStatus.Paused -> Tuple5(
                status.scannedCount,
                status.totalCount,
                status.facesFound,
                true,
                "Paused"
            )
            else -> Tuple5(0, 0, 0, false, "")
        }

        val progress = if (total > 0) scanned.toFloat() / total.toFloat() else 0f

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isPaused) "Indexing Paused" else "Scanning Faces...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$scanned / $total ($faces faces)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = if (isPaused) onResume else onPause,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isPaused) "Resume" else "Pause",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    currentFile,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
