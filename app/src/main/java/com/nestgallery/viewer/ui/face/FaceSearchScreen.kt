package com.nestgallery.viewer.ui.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.nestgallery.viewer.data.face.DetectedFaceResult
import com.nestgallery.viewer.data.face.FaceDatabase
import com.nestgallery.viewer.data.face.FaceDetectorHelper
import com.nestgallery.viewer.data.face.FaceEmbeddingHelper
import com.nestgallery.viewer.data.face.FaceMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceSearchScreen(
    initialQueryFile: File? = null,
    initialFaceBitmap: Bitmap? = null,
    onBack: () -> Unit,
    onOpenImage: (List<DocEntry>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { FaceDatabase.getInstance(context) }
    val detector = remember { FaceDetectorHelper() }
    val embedder = remember { FaceEmbeddingHelper(context) }

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(initialFaceBitmap) }
    var detectedFacesInQuery by remember { mutableStateOf<List<DetectedFaceResult>>(emptyList()) }
    var activeFaceIndex by remember { mutableStateOf(0) }
    var similarityThreshold by remember { mutableFloatStateOf(0.68f) }
    var isSearching by remember { mutableStateOf(false) }
    var matchResults by remember { mutableStateOf<List<FaceMatch>>(emptyList()) }
    var matchedDocEntries by remember { mutableStateOf<List<DocEntry>>(emptyList()) }

    fun runFaceSearch(faceBitmap: Bitmap, threshold: Float) {
        scope.launch {
            isSearching = true
            withContext(Dispatchers.Default) {
                val embedding = embedder.extractEmbedding(faceBitmap)
                if (embedding != null) {
                    val matches = db.searchByEmbedding(embedding, minSimilarity = threshold)
                    val docs = matches.mapNotNull { m ->
                        val f = File(m.filePath)
                        if (f.exists()) {
                            DocEntry(f, f.name, false, f.length(), false)
                        } else null
                    }
                    withContext(Dispatchers.Main) {
                        matchResults = matches
                        matchedDocEntries = docs
                        isSearching = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        matchResults = emptyList()
                        matchedDocEntries = emptyList()
                        isSearching = false
                    }
                }
            }
        }
    }

    fun processQueryImage(sourceBitmap: Bitmap) {
        scope.launch {
            isSearching = true
            withContext(Dispatchers.Default) {
                val faces = detector.detectFaces(sourceBitmap)
                val finalFaces = if (faces.isNotEmpty()) {
                    faces
                } else {
                    // Fallback edge case: user provided a pre-cropped face or avatar
                    listOf(detector.extractFallbackFace(sourceBitmap))
                }

                withContext(Dispatchers.Main) {
                    detectedFacesInQuery = finalFaces
                    activeFaceIndex = 0
                    val primaryFace = finalFaces[0].faceBitmap
                    selectedBitmap = primaryFace
                    runFaceSearch(primaryFace, similarityThreshold)
                }
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isSearching = true
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openInputStream(it)
                    val bmp = BitmapFactory.decodeStream(stream)
                    stream?.close()
                    if (bmp != null) {
                        processQueryImage(bmp)
                    }
                }
            }
        }
    }

    LaunchedEffect(initialQueryFile, initialFaceBitmap) {
        if (initialFaceBitmap != null) {
            selectedBitmap = initialFaceBitmap
            runFaceSearch(initialFaceBitmap, similarityThreshold)
        } else if (initialQueryFile != null && initialQueryFile.exists()) {
            val bmp = detector.decodeSampledBitmap(initialQueryFile, 1024)
            if (bmp != null) {
                processQueryImage(bmp)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find Photos by Face") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Pick Photo")
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
            // Query Face Section Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Face Thumbnail preview
                        val currentFace = selectedBitmap
                        if (currentFace != null) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
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
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Face,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (selectedBitmap != null) "Searching for this person" else "Input a person's face",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (selectedBitmap != null) {
                                    if (isSearching) "Analyzing gallery..." else "Found ${matchedDocEntries.size} matching photos"
                                } else {
                                    "Pick any photo to find all images with this person"
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
                            Text(if (selectedBitmap != null) "Change" else "Pick Photo")
                        }
                    }

                    // Multi-face selector if image has multiple people
                    if (detectedFacesInQuery.size > 1) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Multiple faces detected (${detectedFacesInQuery.size}). Tap to select:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detectedFacesInQuery.forEachIndexed { idx, faceResult ->
                                val isSelected = idx == activeFaceIndex
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable {
                                            activeFaceIndex = idx
                                            selectedBitmap = faceResult.faceBitmap
                                            runFaceSearch(faceResult.faceBitmap, similarityThreshold)
                                        }
                                ) {
                                    Image(
                                        bitmap = faceResult.faceBitmap.asImageBitmap(),
                                        contentDescription = "Face $idx",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Matching Sensitivity Threshold Chips
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Accuracy:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = similarityThreshold == 0.75f,
                            onClick = {
                                similarityThreshold = 0.75f
                                selectedBitmap?.let { runFaceSearch(it, 0.75f) }
                            },
                            label = { Text("High (75%)") }
                        )
                        FilterChip(
                            selected = similarityThreshold == 0.68f,
                            onClick = {
                                similarityThreshold = 0.68f
                                selectedBitmap?.let { runFaceSearch(it, 0.68f) }
                            },
                            label = { Text("Balanced (68%)") }
                        )
                        FilterChip(
                            selected = similarityThreshold == 0.60f,
                            onClick = {
                                similarityThreshold = 0.60f
                                selectedBitmap?.let { runFaceSearch(it, 0.60f) }
                            },
                            label = { Text("Broad (60%)") }
                        )
                    }
                }
            }

            // Results Section
            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Scanning face embeddings...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (selectedBitmap == null) {
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
                        Icon(
                            Icons.Default.PersonSearch,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Select a photo to search",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Input any image containing a person's face. The app will detect the face and instantly find every matching photo in your gallery.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose Photo")
                        }
                    }
                }
            } else if (matchedDocEntries.isEmpty()) {
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
                        Text(
                            "No matching photos found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Try switching the sensitivity to 'Broad (60%)' or make sure photos of this person have been scanned into your gallery database.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
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
                        val matchPercent = match?.let { (it.similarity * 100).roundToInt() } ?: 0

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

                            // Similarity badge
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(
                                        color = if (matchPercent >= 80) Color(0xFF1B5E20).copy(alpha = 0.85f)
                                        else if (matchPercent >= 70) Color(0xFF00695C).copy(alpha = 0.85f)
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
