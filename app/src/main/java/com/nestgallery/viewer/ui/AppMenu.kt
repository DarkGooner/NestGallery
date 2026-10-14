package com.nestgallery.viewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One entry of a three-dot menu; with [checked] set it is an on / off switch with a check box. */
class MenuAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val onClick: () -> Unit
)

/**
 * The app-wide half of every three-dot menu, provided once by MainActivity through [LocalAppMenu]: the same entries, in
 * the same order, on every screen (explorer, recursive view, gallery, albums, faces, a person, NSFW).
 */
class AppMenuState(
    val galleryMode: Boolean,
    val onSwitchView: () -> Unit,
    val hideHidden: Boolean,
    val onToggleHidden: () -> Unit,
    val showNames: Boolean,
    val onToggleNames: () -> Unit,
    /** File explorer: list every file, not only photos and videos. */
    val showAllFiles: Boolean,
    val onToggleAllFiles: () -> Unit,
    /** File explorer: open Ren'Py archives (.rpa) as folders. */
    val openArchives: Boolean,
    val onToggleArchives: () -> Unit,
    val onOpenSettings: () -> Unit
) {
    fun actions(): List<MenuAction> = listOf(
        if (galleryMode) MenuAction("File explorer view", Icons.Default.FolderOpen, onClick = onSwitchView)
        else MenuAction("Gallery view", Icons.Default.PhotoLibrary, onClick = onSwitchView),
        MenuAction("Show hidden items", if (hideHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            checked = !hideHidden, onClick = onToggleHidden),
        MenuAction("Show non-media files", Icons.AutoMirrored.Filled.InsertDriveFile, checked = showAllFiles, onClick = onToggleAllFiles),
        MenuAction("Open .rpa archives as folders", Icons.Default.FolderZip, checked = openArchives, onClick = onToggleArchives),
        MenuAction("Show filenames", if (showNames) Icons.AutoMirrored.Filled.Label else Icons.AutoMirrored.Filled.LabelOff,
            checked = showNames, onClick = onToggleNames),
        MenuAction("Settings", Icons.Default.Settings, onClick = onOpenSettings)
    )
}

val LocalAppMenu = compositionLocalOf<AppMenuState?> { null }

/**
 * The three-dot button and its menu: this screen's own [items] first (what applies here: new folder, select, sort,
 * regroup people ...), then a divider and the app-wide entries from [LocalAppMenu], which never change between screens.
 */
@Composable
fun AppOverflowMenu(
    items: List<MenuAction>,
    buttonSize: Dp = 48.dp,
    tint: Color = LocalContentColor.current,
    /** False only for a selection bar's overflow, which holds actions on the selected items. */
    withGlobal: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    val global = if (withGlobal) LocalAppMenu.current?.actions().orEmpty() else emptyList()
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(buttonSize)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = tint)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) MenuRow(item) { open = false }
            if (items.isNotEmpty() && global.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            for (item in global) MenuRow(item) { open = false }
        }
    }
}

@Composable
private fun MenuRow(item: MenuAction, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text(item.label) },
        leadingIcon = { Icon(item.icon, contentDescription = null, tint = if (item.enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) },
        trailingIcon = item.checked?.let { on ->
            {
                Icon(
                    if (on) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (on) "On" else "Off",
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        enabled = item.enabled,
        onClick = { close(); item.onClick() }
    )
}
