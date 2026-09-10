package com.nestgallery.viewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import com.nestgallery.viewer.data.FileEntry
import com.nestgallery.viewer.ui.GalleryScreen
import com.nestgallery.viewer.ui.ImageViewerScreen
import com.nestgallery.viewer.ui.theme.NestGalleryTheme

private const val PREFS = "nestgallery_prefs"
private const val KEY_ROOT_URI = "root_uri"

private sealed class Screen {
    data object Picker : Screen()
    data object Browser : Screen()
    data class Viewer(val images: List<FileEntry>, val startIndex: Int) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register decoders once so every AsyncImage in the app can render
        // animated GIFs and pull a preview frame out of video files.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(applicationContext.cacheDir.resolve("nestgallery_images"))
                        .maxSizeBytes(256L * 1024L * 1024L)
                        .build()
                }
                .respectCacheHeaders(false)
                .crossfade(false)
                .components {
                    add(GifDecoder.Factory())
                    add(VideoFrameDecoder.Factory())
                }
                .build()
        )

        setContent {
            NestGalleryTheme {
                NestGalleryApp()
            }
        }
    }
}

@Composable
private fun NestGalleryApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, 0) }

    var rootUri by remember {
        mutableStateOf(prefs.getString(KEY_ROOT_URI, null)?.let { Uri.parse(it) })
    }
    var pathStack by remember { mutableStateOf(listOf<DocumentFile>()) }
    var screen by remember {
        mutableStateOf<Screen>(if (rootUri == null) Screen.Picker else Screen.Browser)
    }
    var hideAux by remember { mutableStateOf(true) }
    var listMode by remember { mutableStateOf(true) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
            rootUri = uri
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null) {
                pathStack = listOf(doc)
                screen = Screen.Browser
            }
        }
    }

    LaunchedEffect(rootUri) {
        val uri = rootUri
        if (uri != null && pathStack.isEmpty()) {
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null) pathStack = listOf(doc)
        }
    }

    Crossfade(targetState = screen, label = "screen") { s ->
        when (s) {
            is Screen.Picker -> FolderPickerPrompt { pickFolder.launch(null) }
            is Screen.Browser -> {
                if (pathStack.isEmpty()) return@Crossfade
                GalleryScreen(
                    pathStack = pathStack,
                    hideAux = hideAux,
                    listMode = listMode,
                    onToggleViewMode = { listMode = !listMode },
                    onToggleHideAux = { hideAux = !hideAux },
                    onOpenFolder = { uri ->
                        val folder = DocumentFile.fromSingleUri(context, uri)
                        if (folder != null) pathStack = pathStack + folder
                    },
                    onBreadcrumbClick = { index -> pathStack = pathStack.subList(0, index + 1) },
                    onPickNewFolder = { pickFolder.launch(null) },
                    onOpenImage = { images, index -> screen = Screen.Viewer(images, index) },
                    onBack = { if (pathStack.size > 1) pathStack = pathStack.dropLast(1) },
                    canGoBack = pathStack.size > 1
                )
            }
            is Screen.Viewer -> {
                ImageViewerScreen(
                    images = s.images,
                    startIndex = s.startIndex,
                    onDismiss = { screen = Screen.Browser }
                )
            }
        }
    }
}

@Composable
private fun FolderPickerPrompt(onPick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Pick a folder to start browsing",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onPick) {
                Text("Choose folder")
            }
        }
    }
}
