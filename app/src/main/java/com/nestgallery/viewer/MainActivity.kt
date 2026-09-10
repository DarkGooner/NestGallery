package com.nestgallery.viewer

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import com.nestgallery.viewer.ui.GalleryScreen
import com.nestgallery.viewer.ui.ImageViewerScreen
import com.nestgallery.viewer.ui.theme.NestGalleryTheme
import java.io.File

private sealed class Screen {
    data object Permission : Screen()
    data object Browser : Screen()
    data class Viewer(val media: List<File>, val startIndex: Int) : Screen()
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

/**
 * Direct file browsing needs "All files access" on Android 11+ (the same
 * special access ZArchiver asks for); older versions need the runtime
 * storage permission pair instead.
 */
private fun hasFileAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun NestGalleryApp() {
    val context = LocalContext.current

    var hasAccess by remember { mutableStateOf(hasFileAccess(context)) }
    var pathStack by remember { mutableStateOf(listOf<File>()) }
    var screen by remember {
        mutableStateOf<Screen>(if (hasAccess) Screen.Browser else Screen.Permission)
    }
    var hideAux by remember { mutableStateOf(true) }
    var listMode by remember { mutableStateOf(true) }

    // The "All files access" toggle lives in a system settings screen, so
    // re-check automatically every time the user returns to the app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasFileAccess(context)
                hasAccess = granted
                if (granted && screen is Screen.Permission) {
                    screen = Screen.Browser
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val legacyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val granted = hasFileAccess(context)
        hasAccess = granted
        if (granted) screen = Screen.Browser
    }

    // System back: close the viewer first, then walk up folders, then exit.
    BackHandler(enabled = screen is Screen.Viewer) {
        screen = Screen.Browser
    }
    BackHandler(enabled = screen is Screen.Browser && pathStack.isNotEmpty()) {
        pathStack = pathStack.dropLast(1)
    }

    Crossfade(targetState = screen, label = "screen") { s ->
        when (s) {
            is Screen.Permission -> PermissionPrompt(
                onGrant = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    } else {
                        legacyPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                }
            )

            is Screen.Browser -> GalleryScreen(
                pathStack = pathStack,
                hideAux = hideAux,
                listMode = listMode,
                onToggleViewMode = { listMode = !listMode },
                onToggleHideAux = { hideAux = !hideAux },
                onOpenFolder = { folder -> pathStack = pathStack + folder },
                onBreadcrumbClick = { index -> pathStack = pathStack.subList(0, index + 1) },
                onOpenMedia = { media, index -> screen = Screen.Viewer(media, index) },
                onBack = { pathStack = pathStack.dropLast(1) },
                canGoBack = pathStack.isNotEmpty()
            )

            is Screen.Viewer -> ImageViewerScreen(
                media = s.media,
                startIndex = s.startIndex,
                onDismiss = { screen = Screen.Browser }
            )
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
            Text("Allow file access", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "NestGallery browses your files directly, like a file manager, " +
                    "so it needs the “All files access” permission.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) {
                Text("Grant access")
            }
        }
    }
}
