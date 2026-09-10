package com.nestgallery.viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.storageRootEntry
import com.nestgallery.viewer.ui.GalleryScreen
import com.nestgallery.viewer.ui.ImageViewerScreen
import com.nestgallery.viewer.ui.theme.NestGalleryTheme

private sealed class Screen {
    data object NeedsPermission : Screen()
    data object Browser : Screen()
    data class Viewer(val images: List<DocEntry>, val startIndex: Int) : Screen()
}

private fun hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // checked via runtime permission instead, see hasLegacyReadPermission
    }
}

private fun hasLegacyReadPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED
}

private fun hasAccess(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        hasStorageAccess()
    } else {
        hasLegacyReadPermission(context)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Register decoders once so every AsyncImage in the app can render
        // animated GIFs and pull a preview frame out of video files.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
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

    var granted by remember { mutableStateOf(hasAccess(context)) }
    var pathStack by remember {
        mutableStateOf(if (granted) listOf(storageRootEntry()) else emptyList())
    }
    var screen by remember {
        mutableStateOf<Screen>(if (granted) Screen.Browser else Screen.NeedsPermission)
    }
    var hideAux by remember { mutableStateOf(true) }
    var listMode by remember { mutableStateOf(true) }
    var showNames by remember { mutableStateOf(true) }

    fun onAccessGranted() {
        granted = true
        pathStack = listOf(storageRootEntry())
        screen = Screen.Browser
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasAccess(context)) onAccessGranted()
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) onAccessGranted()
    }

    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            allFilesLauncher.launch(intent)
        } else {
            legacyPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    if (screen is Screen.Browser) {
        BackHandler(enabled = pathStack.size > 1) {
            pathStack = pathStack.dropLast(1)
        }
    }

    Crossfade(targetState = screen, label = "screen") { s ->
        when (s) {
            is Screen.NeedsPermission -> PermissionPrompt(onGrant = { requestAccess() })
            is Screen.Browser -> {
                if (pathStack.isEmpty()) return@Crossfade
                GalleryScreen(
                    pathStack = pathStack,
                    hideAux = hideAux,
                    listMode = listMode,
                    showNames = showNames,
                    onToggleViewMode = { listMode = !listMode },
                    onToggleHideAux = { hideAux = !hideAux },
                    onToggleShowNames = { showNames = !showNames },
                    onOpenFolder = { folder -> pathStack = pathStack + folder },
                    onBreadcrumbClick = { index -> pathStack = pathStack.subList(0, index + 1) },
                    onGoHome = { pathStack = listOf(storageRootEntry()) },
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
private fun PermissionPrompt(onGrant: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "NestGallery needs storage access",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This lets it browse your whole device like a file explorer, " +
                    "instead of picking one folder at a time.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onGrant) {
                Text("Grant access")
            }
        }
    }
}
