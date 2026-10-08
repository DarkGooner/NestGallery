package com.nestgallery.viewer.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

/*
 * The app draws edge to edge, so every screen keeps its controls out of the system bars itself. safeDrawing covers the
 * status bar, the navigation bar (bottom with 3-button navigation, or the side in landscape), display cutouts (the
 * activity draws into them in landscape) and the keyboard. Material3's defaults leave cutouts out.
 */

/** Scaffold content: everything that is not the top app bar. */
val ScreenInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing

/** TopAppBar: the status bar plus whatever sits on the left / right in landscape. */
val TopBarInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
