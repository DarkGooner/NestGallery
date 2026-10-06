package com.nestgallery.viewer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.AccountTree
import kotlin.math.roundToInt

/**
 * Drives the "collapsing header" behaviour. Attach [connection] with Modifier.nestedScroll to the box that
 * holds the scrolling content: scrolling down folds the header tier away, scrolling up (or reaching the top)
 * brings it back. The bar is an overlay, so folding it never re-lays-out (or jitters) the list underneath.
 */
@Stable
class CollapsingBarState {
    var headerHeightPx by mutableFloatStateOf(0f)
    var totalHeightPx by mutableFloatStateOf(0f)
    var offsetPx by mutableFloatStateOf(0f)

    val fraction: Float get() = if (headerHeightPx <= 0f) 0f else (-offsetPx / headerHeightPx).coerceIn(0f, 1f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            offsetPx = (offsetPx + available.y).coerceIn(-headerHeightPx, 0f)
            return Offset.Zero                       // never consume: the list still scrolls normally
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val target = if (fraction > 0.5f) -headerHeightPx else 0f   // snap to fully open / fully folded
            animate(offsetPx, target, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> offsetPx = v }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberCollapsingBar(): CollapsingBarState = remember { CollapsingBarState() }

/** Top padding the scrolling content needs so its first row starts just below the expanded bar. */
@Composable
fun CollapsingBarState.contentTopPadding(): Dp {
    val density = LocalDensity.current
    return if (totalHeightPx > 0f) with(density) { totalHeightPx.toDp() } + 4.dp else 148.dp
}

class TopBarMenuItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** The "recursive scan" control. [busy] draws a progress ring around it, [active] tints it. */
class RecursiveAction(
    val icon: ImageVector = Icons.Default.AccountTree,
    val active: Boolean = false,
    val busy: Boolean = false,
    val description: String,
    val onClick: () -> Unit
)

/**
 * Floating two-tier capsule used by the browser, the recursive explorer and search.
 *
 *  Header tier (folds away on scroll): back / folder badge, title + subtitle, path chips, extra actions, overflow.
 *  Dock tier (always pinned): search, recursive-scan button, animated grid/list switch.
 */
@Composable
fun NestTopBar(
    state: CollapsingBarState,
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    recursive: RecursiveAction,
    searchContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    pathChips: List<String>? = null,
    onPathClick: ((Int) -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    menu: List<TopBarMenuItem> = emptyList(),
    showHeader: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            // measured *outside* the padding so the reported height includes the capsule's margins
            .onSizeChanged { if (state.offsetPx == 0f) state.totalHeightPx = it.height.toFloat() }
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surface.copy(alpha = 0.94f),
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.40f), colors.outline.copy(alpha = 0.25f))))
        ) {
            Column {
                if (showHeader) {
                    // Collapsing tier: measured at natural height, laid out shorter and shifted up as it folds.
                    Box(
                        Modifier
                            .layout { measurable, constraints ->
                                val p = measurable.measure(constraints)
                                val h = (p.height + state.offsetPx).roundToInt().coerceAtLeast(0)
                                layout(p.width, h) { p.placeRelative(0, state.offsetPx.roundToInt()) }
                            }
                            .clipToBounds()
                            .graphicsLayer { alpha = 1f - state.fraction }
                    ) {
                        Column(Modifier.onSizeChanged { state.headerHeightPx = it.height.toFloat() }) {
                            HeaderRow(title, subtitle, onBack, headerActions, menu)
                            if (pathChips != null && pathChips.isNotEmpty()) PathChips(pathChips, onPathClick)
                        }
                    }
                }
                Dock(onBack.takeIf { !showHeader }, listMode, onToggleViewMode, recursive, searchContent)
            }
        }
    }
}

@Composable
private fun HeaderRow(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
    menu: List<TopBarMenuItem>
) {
    Row(Modifier.padding(start = 6.dp, end = 2.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        } else {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
        if (menu.isNotEmpty()) OverflowMenu(menu)
    }
}

@Composable
private fun OverflowMenu(items: List<TopBarMenuItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.label) },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    onClick = { open = false; item.onClick() }
                )
            }
        }
    }
}

/** Breadcrumb as tappable chips; the current folder is highlighted and the row follows the deepest segment. */
@Composable
private fun PathChips(path: List<String>, onClick: ((Int) -> Unit)?) {
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        path.forEachIndexed { i, name ->
            val last = i == path.lastIndex
            Surface(
                shape = CircleShape,
                color = if (last) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.clip(CircleShape).clickable(enabled = !last && onClick != null) { onClick?.invoke(i) }
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                    color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            if (!last) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Dock(
    onBack: (() -> Unit)?,
    listMode: Boolean,
    onToggleViewMode: () -> Unit,
    recursive: RecursiveAction,
    searchContent: @Composable () -> Unit
) {
    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
        Box(Modifier.weight(1f)) { searchContent() }
        RecursiveButton(recursive)
        ViewModeSwitch(listMode, onToggleViewMode)
    }
}

/** Search affordance shown on the browser/explorer: looks like a field, opens the search screen on tap. */
@Composable
fun SearchPill(placeholder: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = "Search images and videos" }
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                placeholder,
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The real, typeable version of the pill, used on the search screen. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, focusRequester: FocusRequester) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth().height(44.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun RecursiveButton(action: RecursiveAction) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (action.active) colors.primary.copy(alpha = 0.22f) else colors.surfaceVariant.copy(alpha = 0.7f), label = "recursiveBg")
    val tint by animateColorAsState(if (action.active) colors.primary else colors.onSurfaceVariant, label = "recursiveTint")
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = action.onClick)
            .semantics { contentDescription = action.description },
        contentAlignment = Alignment.Center
    ) {
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        if (action.busy) CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 2.dp, color = colors.primary)
    }
}

/** Two-state segmented switch with a sliding thumb: grid on the left, list on the right. */
@Composable
private fun ViewModeSwitch(listMode: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val thumbX by animateDpAsState(if (listMode) 42.dp else 2.dp, label = "viewThumb")
    Box(
        Modifier
            .width(84.dp)
            .height(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onToggle)
            .semantics { contentDescription = if (listMode) "List view. Tap for grid view" else "Grid view. Tap for list view" }
    ) {
        Box(Modifier.offset(x = thumbX, y = 4.dp).size(width = 40.dp, height = 36.dp).clip(CircleShape).background(colors.primary))
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.GridView, contentDescription = null, tint = if (!listMode) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.width(42.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ViewAgenda, contentDescription = null, tint = if (listMode) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
