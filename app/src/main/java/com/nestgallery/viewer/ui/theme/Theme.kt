package com.nestgallery.viewer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Ink = Color(0xFF0B0E11)
private val Surface1 = Color(0xFF14181D)
private val Surface2 = Color(0xFF1B2027)
private val Accent = Color(0xFF6FD3C7)
private val OnInk = Color(0xFFE7ECEF)
private val Muted = Color(0xFF8A94A0)
private val Outline = Color(0xFF2A3038)

private val NestDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04211D),
    background = Ink,
    onBackground = OnInk,
    surface = Surface1,
    onSurface = OnInk,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = Outline
)

@Composable
fun NestGalleryTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        NestDarkColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NestTypography,
        content = content
    )
}
