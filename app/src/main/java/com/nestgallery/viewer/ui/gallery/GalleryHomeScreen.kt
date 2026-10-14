package com.nestgallery.viewer.ui.gallery

import com.nestgallery.viewer.ui.PullTarget
import com.nestgallery.viewer.ui.NestPullToRefresh
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nestgallery.viewer.data.Album
import com.nestgallery.viewer.data.DocEntry
import com.nestgallery.viewer.data.MediaItem
import com.nestgallery.viewer.data.thumb
import com.nestgallery.viewer.data.MediaLibrary
import com.nestgallery.viewer.data.storageRootEntry
import com.nestgallery.viewer.ui.ScreenInsets
import com.nestgallery.viewer.ui.TopBarInsets
import com.nestgallery.viewer.ui.rememberKeptGridState
import com.nestgallery.viewer.ui.AppOverflowMenu
import com.nestgallery.viewer.ui.MenuAction
import com.nestgallery.viewer.ui.files.FileSelectionActions
import kotlinx.coroutines.launch

enum class GalleryTab { PHOTOS, ALBUMS }

/** What a gallery menu offers besides the screen's own actions. */
class GalleryActions(
    val onOpenImage: (List<DocEntry>, Int) -> Unit,
    val onSearch: () -> Unit,
    val onOpenFaces: (DocEntry, List<DocEntry>) -> Unit,
    /** Null while NSFW scan is switched off in Settings (then it isn't offered). */
    val onOpenNsfw: ((DocEntry, List<DocEntry>) -> Unit)?
)

/**
 * The gallery view: what people expect from a phone's gallery app rather than a file explorer. Two tabs at the
 * bottom, Albums (one per folder, the home tab) and Photos (every photo and video by date), from MediaStore ([MediaLibrary]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryHomeScreen(
    tab: GalleryTab,
    onTabChange: (GalleryTab) -> Unit,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onOpenAlbum: (String) -> Unit,
    actions: GalleryActions
) {
    val context = LocalContext.current
    val library = remember { MediaLibrary.getInstance(context) }
    LaunchedEffect(Unit) { library.start() }
    val items by library.items.collectAsState()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var selectMode by remember { mutableStateOf(false) }
    val selecting = (selectMode || selected.isNotEmpty()) && tab == GalleryTab.PHOTOS
    // deselecting the last photo ends a selection started by a long-press; the menu's "Select" stays until closed
    val onSelectedChange: (Set<Long>) -> Unit = { selected = it; if (it.isEmpty()) selectMode = false }

    BackHandler(enabled = selecting) { selected = emptySet(); selectMode = false }
    BackHandler(enabled = !selecting && tab != GalleryTab.ALBUMS) { onTabChange(GalleryTab.ALBUMS) }

    // Pinned, not a bar that hides on scroll: a hiding bar changes the content's top padding on every scroll frame,
    // which re-laid-out the whole photo grid each frame and made scrolling stutter.
    val photosBar = TopAppBarDefaults.pinnedScrollBehavior()
    val albumsBar = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollBehavior = if (tab == GalleryTab.PHOTOS) photosBar else albumsBar
    val all = items.orEmpty()
    val allPhotos = remember(all) { all.filter { !it.entry.isVideo }.map { it.entry } }
    val libraryRoot = remember { storageRootEntry().copy(name = "All photos") }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshResult by remember { mutableStateOf("Up to date") }

    Scaffold(
        contentWindowInsets = ScreenInsets,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) {
                SelectionTopBar(all, selected, onSelectedChange, onClose = { selected = emptySet(); selectMode = false }, scrollBehavior)
            } else {
                TopAppBar(
                    windowInsets = TopBarInsets,
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    ),
                    title = {
                        Text(
                            if (tab == GalleryTab.PHOTOS) "Photos" else "Albums",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(onClick = actions.onSearch) { Icon(Icons.Default.Search, contentDescription = "Search") }
                        AppOverflowMenu(
                            buildList {
                                if (tab == GalleryTab.PHOTOS && all.isNotEmpty()) {
                                    add(MenuAction("Select", Icons.Default.CheckCircle) { selectMode = true })
                                }
                                if (allPhotos.isNotEmpty()) {
                                    add(MenuAction("People", Icons.Default.Face) { actions.onOpenFaces(libraryRoot, allPhotos) })
                                    actions.onOpenNsfw?.let { open -> add(MenuAction("NSFW scan", Icons.Default.Shield) { open(libraryRoot, allPhotos) }) }
                                }
                            }
                        )
                    }
                )
            }
        },
        bottomBar = {
            if (!selecting) {
                NavigationBar {
                    NavTab(tab == GalleryTab.ALBUMS, "Albums", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary) { onTabChange(GalleryTab.ALBUMS) }
                    NavTab(tab == GalleryTab.PHOTOS, "Photos", Icons.Filled.Photo, Icons.Outlined.Photo) { onTabChange(GalleryTab.PHOTOS) }
                }
            }
        }
    ) { padding ->
        val list = items
        NestPullToRefresh(
            isRefreshing = refreshing,
            topInset = padding.calculateTopPadding(),
            doneLabel = refreshResult,
            onRefresh = {
                if (!refreshing) scope.launch {
                    refreshing = true
                    val r = library.refresh()
                    refreshResult = when {
                        r.added > 0 && r.removed > 0 -> "${r.added} new · ${r.removed} removed"
                        r.added > 0 -> "${r.added} new"
                        r.removed > 0 -> "${r.removed} removed"
                        else -> "Up to date"
                    }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyLibrary(Modifier.padding(padding))
            tab == GalleryTab.PHOTOS -> {
                val entries = remember(list) { list.map { it.entry } }
                val state = rememberKeptGridState("gallery:photos|", timelineCellBound(list.size))
                MediaTimeline(
                    items = list,
                    state = state,
                    columns = columns,
                    onColumnsChange = onColumnsChange,
                    selecting = selecting,
                    selected = selected,
                    onSelectedChange = onSelectedChange,
                    onOpen = { index -> actions.onOpenImage(entries, index) },
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp)
                )
            }
            else -> AlbumsGrid(list, padding, onOpenAlbum)
        }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NavTab(selected: Boolean, label: String, filled: ImageVector, outlined: ImageVector, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(if (selected) filled else outlined, contentDescription = null) },
        label = { Text(label) }
    )
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Box(modifier.fillMaxSize()) {
    PullTarget()
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
        Text("No photos or videos yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Photos and videos on this phone show up here. Folders with a .nomedia file are only shown in the file explorer view.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    }
}

/** Albums: a "Videos" collection, then one per folder (Camera and Screenshots first), two or more per row. */
@Composable
private fun AlbumsGrid(items: List<MediaItem>, padding: PaddingValues, onOpenAlbum: (String) -> Unit) {
    val albums = remember(items) { MediaLibrary.albums(items) }
    val videos = remember(items) { items.filter { it.entry.isVideo } }
    val state = rememberKeptGridState("gallery:albums|", albums.size + 1)
    LazyVerticalGrid(
        state = state,
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (videos.isNotEmpty()) {
            item(key = MediaLibrary.VIDEOS_ALBUM) {
                AlbumTile("Videos", videos.size, videos.first(), isCollection = true) { onOpenAlbum(MediaLibrary.VIDEOS_ALBUM) }
            }
        }
        items(albums, key = { it.id }) { album ->
            AlbumTile(album.name, album.items.size, album.cover, isCollection = false) { onOpenAlbum(album.id) }
        }
    }
}

@Composable
private fun AlbumTile(name: String, count: Int, cover: MediaItem, isCollection: Boolean, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(model = remember(cover.id) { cover.thumb() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (isCollection) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(8.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Text(
            if (count == 1) "1 item" else "$count items",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )
    }
}

/** One album (a folder, or the Videos collection) as a date timeline, with selection like the Photos tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    albumId: String,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    onBack: () -> Unit,
    actions: GalleryActions
) {
    val context = LocalContext.current
    val library = remember { MediaLibrary.getInstance(context) }
    LaunchedEffect(Unit) { library.start() }
    val all by library.items.collectAsState()
    val album: Album? = remember(all, albumId) {
        val list = all ?: return@remember null
        if (albumId == MediaLibrary.VIDEOS_ALBUM) {
            list.filter { it.entry.isVideo }.takeIf { it.isNotEmpty() }?.let { Album(albumId, "Videos", it) }
        } else {
            list.filter { it.albumId == albumId }.takeIf { it.isNotEmpty() }?.let { Album(albumId, it.first().albumName, it) }
        }
    }
    val items = album?.items.orEmpty()
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var selectMode by remember { mutableStateOf(false) }
    val selecting = selectMode || selected.isNotEmpty()
    val onSelectedChange: (Set<Long>) -> Unit = { selected = it; if (it.isEmpty()) selectMode = false }
    BackHandler { if (selecting) { selected = emptySet(); selectMode = false } else onBack() }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val photos = remember(items) { items.filter { !it.entry.isVideo }.map { it.entry } }
    val root = remember(albumId, album?.name) { DocEntry(java.io.File(albumId), album?.name ?: "", true, 0L, false) }

    Scaffold(
        contentWindowInsets = ScreenInsets,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) {
                SelectionTopBar(items, selected, onSelectedChange, onClose = { selected = emptySet(); selectMode = false }, scrollBehavior)
            } else {
                TopAppBar(
                    windowInsets = TopBarInsets,
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background
                    ),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    title = {
                        Column {
                            Text(album?.name ?: "", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (album != null) {
                                Text(
                                    if (items.size == 1) "1 item" else "${items.size} items",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        AppOverflowMenu(
                            buildList {
                                if (items.isNotEmpty()) add(MenuAction("Select", Icons.Default.CheckCircle) { selectMode = true })
                                if (photos.isNotEmpty() && albumId != MediaLibrary.VIDEOS_ALBUM) {
                                    add(MenuAction("People", Icons.Default.Face) { actions.onOpenFaces(root, photos) })
                                    actions.onOpenNsfw?.let { open -> add(MenuAction("NSFW scan", Icons.Default.Shield) { open(root, photos) }) }
                                }
                            }
                        )
                    }
                )
            }
        }
    ) { padding ->
        when {
            all == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("This album is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val entries = remember(items) { items.map { it.entry } }
                val state = rememberKeptGridState(albumScrollKey(albumId), timelineCellBound(items.size))
                MediaTimeline(
                    items = items,
                    state = state,
                    columns = columns,
                    onColumnsChange = onColumnsChange,
                    selecting = selecting,
                    selected = selected,
                    onSelectedChange = onSelectedChange,
                    onOpen = { index -> actions.onOpenImage(entries, index) },
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp)
                )
            }
        }
    }
}

fun albumScrollKey(albumId: String) = "gallery:album:$albumId|"

/**
 * An upper bound on a timeline's grid cells (every item plus at most one day header each), for rememberKeptGridState:
 * the timeline is only shown once fully loaded, so the saved position always fits.
 */
private fun timelineCellBound(items: Int) = 2 * items + 1

/** Replaces the title bar while selecting: the same file actions as everywhere else (move / copy to, delete, share ...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    items: List<MediaItem>,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
    onClose: () -> Unit,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior
) {
    val chosen = remember(items, selected) { items.filter { it.id in selected }.map { it.entry.file } }
    com.nestgallery.viewer.ui.files.SelectionTopBar(count = selected.size, onClose = onClose) {
        FileSelectionActions(
            files = chosen,
            onDone = onClose,
            onSelectAll = if (selected.size < items.size) ({ onSelectedChange(items.mapTo(HashSet()) { it.id }) }) else null
        )
    }
}
