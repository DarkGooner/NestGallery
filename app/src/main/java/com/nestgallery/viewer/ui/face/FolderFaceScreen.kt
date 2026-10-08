package com.nestgallery.viewer.ui.face

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nestgallery.viewer.ui.HoldPreviewOverlay
import com.nestgallery.viewer.ui.holdPreviewGestures
import com.nestgallery.viewer.data.face.FaceMath
import com.nestgallery.viewer.data.face.QueryFace
import com.nestgallery.viewer.data.face.FaceDatabase
import com.nestgallery.viewer.data.face.FaceMatch
import com.nestgallery.viewer.data.face.FaceScannerManager
import com.nestgallery.viewer.data.face.MergeSuggestion
import com.nestgallery.viewer.data.face.PersonEntity
import com.nestgallery.viewer.data.face.PersonMatch
import com.nestgallery.viewer.data.face.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * "Same person?" progress in one folder. Opening a person from the card replaces this screen and coming back builds it
 * anew, so the count and the skipped questions live here until the user leaves the folder's face screen.
 */
private class ReviewSession(val folder: String) {
    var answered by mutableIntStateOf(0)
    var skipped: Set<Pair<Long, Long>> = emptySet()
}

private var reviewSession: ReviewSession? = null

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
    var previewEntry by remember { mutableStateOf<DocEntry?>(null) }
    var detectedFacesInQuery by remember { mutableStateOf<List<QueryFace>>(emptyList()) }
    var activeFaceIndex by remember { mutableIntStateOf(0) }
    // Raw cosine cut-off; the chips map to the calibrated bands in FaceMath.
    var similarityThreshold by remember { mutableFloatStateOf(FaceMath.MATCH_LIKELY) }
    var suggestedPeople by remember { mutableStateOf<List<PersonMatch>>(emptyList()) }

    // People-tab selection (long-press) for merging, and the overflow menu
    var selectedPeople by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showRebuildDialog by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var matchResults by remember { mutableStateOf<List<FaceMatch>>(emptyList()) }
    var matchedDocEntries by remember { mutableStateOf<List<DocEntry>>(emptyList()) }

    // "Same person?" questions (best first); skipped ones stay hidden until the user leaves this folder's face screen
    var suggestions by remember { mutableStateOf<List<MergeSuggestion>>(emptyList()) }
    val review = remember(root.file.absolutePath) {
        reviewSession?.takeIf { it.folder == root.file.absolutePath } ?: ReviewSession(root.file.absolutePath).also { reviewSession = it }
    }
    val peopleGrid = rememberLazyGridState()

    fun refreshPeople() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { db.getPeopleInFolder(root.file.absolutePath) }
            val questions = scanner.mergeSuggestions(root.file.absolutePath).filter { (it.a.id to it.b.id) !in review.skipped }
            // set together: people first and the card a moment later kept the grid on the first person, card above it
            people = loaded
            suggestions = questions
            if (questions.isEmpty()) review.answered = 0
            selectedPeople = selectedPeople.filter { id -> people.any { it.id == id } }.toSet()
        }
    }

    fun leave() {
        reviewSession = null
        onBack()
    }
    BackHandler { leave() }

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
            suggestedPeople = scanner.suggestPeople(embedding, root.file.absolutePath)
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

    // Android 13+: the foreground-service progress notification needs this permission. Scanning works either way;
    // without it the progress just isn't shown in the notification shade.
    fun beginScan() = scanner.startScanForFiles(files.map { it.file }, root.file.absolutePath)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { beginScan() }
    fun startScan() {
        val needs = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needs) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else beginScan()
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
            if (selectedPeople.isNotEmpty()) TopAppBar(
                title = { Text("${selectedPeople.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { selectedPeople = emptySet() }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear selection")
                    }
                },
                actions = {
                    TextButton(onClick = { showMergeDialog = true }, enabled = selectedPeople.size >= 2) {
                        Icon(Icons.Default.CallMerge, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Merge")
                    }
                }
            ) else TopAppBar(
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
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { startScan() }
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Regroup people") },
                                onClick = { showMenu = false; showRebuildDialog = true },
                                enabled = scanStatus is ScanStatus.Idle || scanStatus is ScanStatus.Completed
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                                onClick = { startScan() }
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Scan Faces in ${root.name}")
                            }
                        }
                    }
                } else {
                    val idle = scanStatus is ScanStatus.Idle || scanStatus is ScanStatus.Completed
                    val question = suggestions.firstOrNull()
                    val showQuestion = question != null && idle && selectedPeople.isEmpty()
                    // the card is inserted above the people; if the grid was at the top, keep it at the top so the card shows
                    LaunchedEffect(showQuestion) {
                        if (showQuestion && peopleGrid.firstVisibleItemIndex == 0) peopleGrid.scrollToItem(0)
                    }
                    LazyVerticalGrid(
                        state = peopleGrid,
                        columns = GridCells.Adaptive(minSize = 105.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (showQuestion && question != null) {
                            item(key = "same-person", span = { GridItemSpan(maxLineSpan) }) {
                                SamePersonCard(
                                    suggestion = question,
                                    number = review.answered + 1,
                                    total = review.answered + suggestions.size,
                                    onOpenPerson = onOpenPerson,
                                    onAnswer = { same ->
                                        review.answered++
                                        suggestions = suggestions.drop(1)
                                        scope.launch { scanner.answerSuggestion(question, same); refreshPeople() }
                                    },
                                    onSkip = {
                                        review.answered++
                                        review.skipped = review.skipped + (question.a.id to question.b.id)
                                        suggestions = suggestions.drop(1)
                                    }
                                )
                            }
                        }
                        items(people, key = { it.id }) { person ->
                            val selected = person.id in selectedPeople
                            FolderPersonGridItem(
                                person = person,
                                selected = selected,
                                onClick = {
                                    if (selectedPeople.isNotEmpty()) {
                                        selectedPeople = if (selected) selectedPeople - person.id else selectedPeople + person.id
                                    } else onOpenPerson(person.id)
                                },
                                onLongClick = { selectedPeople = selectedPeople + person.id },
                                onRename = {
                                    personToRename = person
                                    renameInput = person.name
                                }
                            )
                        }
                    }
                }
            } else {
                // Tab 1: Find by Face (Reverse Search in this folder). The query card is the first item of the results
                // grid, so it scrolls away with the results instead of staying pinned over half the screen.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
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

                            // "Looks like": people whose faces match the query on average (the clusterer's own score)
                            if (suggestedPeople.isNotEmpty() && !isSearching) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Looks like",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowItems(suggestedPeople, key = { it.person.id }) { m ->
                                        SuggestedPersonChip(m, onClick = { onOpenPerson(m.person.id) })
                                    }
                                }
                            }

                            // Sensitivity = raw cosine cut-off, mapped to the calibrated bands in FaceMath
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
                                listOf("Strict" to FaceMath.MATCH_STRONG, "Balanced" to FaceMath.MATCH_LIKELY, "Broad" to FaceMath.MATCH_POSSIBLE).forEach { (label, value) ->
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
                    }

                    // Results
                    val message: String? = when {
                        isSearching -> null
                        queryBitmap == null -> "Pick any photo above to search for matching faces within this folder."
                        matchedDocEntries.isEmpty() -> "No matches found in ${root.name}.\nTry setting sensitivity to 'Broad' or rescan faces."
                        else -> null
                    }
                    if (isSearching) {
                        item(key = "searching", span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth().padding(32.dp)
                            ) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("Searching ${root.name}…")
                            }
                        }
                    } else if (message != null) {
                        item(key = "message", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp)
                            )
                        }
                    } else {
                            itemsIndexed(matchedDocEntries, key = { _, doc -> doc.file.absolutePath }) { index, entry ->
                                val match = matchResults.getOrNull(index)
                                val sim = match?.similarity ?: 0f
                                val matchPercent = (FaceMath.matchProbability(sim) * 100).roundToInt()

                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .holdPreviewGestures(
                                            key = entry.file,
                                            onClick = { onOpenImage(matchedDocEntries, index) },
                                            onHoldStart = { previewEntry = entry },
                                            onHoldEnd = { previewEntry = null }
                                        )
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
                                                color = bandColor(sim),
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
        previewEntry?.let { HoldPreviewOverlay(entry = it) }
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
                            val name = renameInput.trim()
                            scope.launch { scanner.renamePerson(person.id, name); refreshPeople() }
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

    if (showMergeDialog) {
        // Merge into the person with a name, else the one with the most photos.
        val chosen = people.filter { it.id in selectedPeople }
        val target = chosen.firstOrNull { !it.name.startsWith("Person ") } ?: chosen.maxByOrNull { it.faceCount }
        AlertDialog(
            onDismissRequest = { showMergeDialog = false },
            title = { Text("Merge ${chosen.size} people?") },
            text = {
                Text("Their photos will be combined under \"${target?.name ?: ""}\". Use this when the same person was split into several groups.")
            },
            confirmButton = {
                Button(onClick = {
                    showMergeDialog = false
                    if (target != null) scope.launch {
                        scanner.mergePeople(target.id, chosen.map { it.id })
                        selectedPeople = emptySet()
                        refreshPeople()
                    }
                }) { Text("Merge") }
            },
            dismissButton = { TextButton(onClick = { showMergeDialog = false }) { Text("Cancel") } }
        )
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("Regroup people?") },
            text = {
                Text("Every person you have not named is grouped again from scratch. Named people and your corrections are kept. Photos are not rescanned.")
            },
            confirmButton = {
                Button(onClick = {
                    showRebuildDialog = false
                    scanner.rebuildPeople(root.file.absolutePath)
                }) { Text("Regroup") }
            },
            dismissButton = { TextButton(onClick = { showRebuildDialog = false }) { Text("Cancel") } }
        )
    }
}

/** Badge colour for a raw cosine: strong / likely / possible match bands. */
private fun bandColor(similarity: Float): Color = when {
    similarity >= FaceMath.MATCH_STRONG -> Color(0xFF1B5E20)
    similarity >= FaceMath.MATCH_LIKELY -> Color(0xFF00695C)
    else -> Color(0xFFE65100)
}.copy(alpha = 0.85f)

@Composable
private fun SuggestedPersonChip(match: PersonMatch, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, bandColor(match.similarity), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val thumb = match.person.coverThumbnailPath
            if (!thumb.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(File(thumb)).build(),
                    contentDescription = match.person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            match.person.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${(FaceMath.matchProbability(match.similarity) * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * "Same person?" - two people whose faces match well but not well enough to merge on their own (the same character in
 * different lighting / expression looks like this, but so do two look-alike characters). Yes merges them, No keeps
 * them apart for good; either way both are left as the user confirmed them.
 */
@Composable
private fun SamePersonCard(
    suggestion: MergeSuggestion,
    number: Int,
    total: Int,
    onOpenPerson: (Long) -> Unit,
    onAnswer: (same: Boolean) -> Unit,
    onSkip: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Same person?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (total > 1) Text("$number / $total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                for ((i, p) in listOf(suggestion.a, suggestion.b).withIndex()) {
                    if (i == 1) Text("?", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp).clickable { onOpenPerson(p.id) }) {
                        Box(
                            Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            val thumb = p.coverThumbnailPath
                            if (!thumb.isNullOrEmpty() && File(thumb).exists()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(File(thumb)).build(),
                                    contentDescription = p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                )
                            } else Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.faceCount} photos", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text("Skip") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("Different") }
                Button(onClick = { onAnswer(true) }) { Text("Same person") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderPersonGridItem(
    person: PersonEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    if (selected) 3.dp else 2.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                ),
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
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
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
        visible = status is ScanStatus.Scanning || status is ScanStatus.Paused || status is ScanStatus.Grouping
    ) {
        val grouping = status is ScanStatus.Grouping
        val (scanned, total, faces, isPaused, currentFile) = when (status) {
            is ScanStatus.Scanning -> Tuple5(status.scannedCount, status.totalCount, status.facesFound, false, status.currentFileName)
            is ScanStatus.Paused -> Tuple5(status.scannedCount, status.totalCount, status.facesFound, true, "Paused")
            is ScanStatus.Grouping -> Tuple5(status.done, status.total, 0, false, "Matching faces to people. This can take a minute for large libraries.")
            else -> Tuple5(0, 0, 0, false, "")
        }
        val rate = (status as? ScanStatus.Scanning)?.photosPerSecond ?: 0f
        val etaText = if (!grouping && rate > 0.05f && total > scanned) {
            val sec = ((total - scanned) / rate).toInt()
            " · " + (if (sec >= 3600) "${sec / 3600}h ${(sec % 3600) / 60}m" else if (sec >= 60) "${sec / 60} min" else "${sec}s") + " left"
        } else ""
        val rateText = if (!grouping && rate > 0.05f) "%.1f photos/s".format(rate) + etaText + " · " else ""
        val progress = if (total > 1) scanned.toFloat() / total.toFloat() else 0f

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
                        if (isPaused) "Indexing Paused" else if (grouping) "Grouping people..." else "Scanning Faces...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (grouping) "${if (total > 0) scanned * 100 / total else 0}%" else "$scanned / $total ($faces faces)",
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
                    rateText + currentFile,
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
