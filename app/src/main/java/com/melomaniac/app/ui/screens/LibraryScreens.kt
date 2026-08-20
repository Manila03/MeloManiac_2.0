package com.melomaniac.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.AddToCollectionSheet
import com.melomaniac.app.ui.AppTextField
import com.melomaniac.app.ui.GhostButton
import com.melomaniac.app.ui.Muted
import com.melomaniac.app.ui.PrimaryButton
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.SimpleListItem
import com.melomaniac.app.ui.TrackList
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.TextMuted
import com.melomaniac.app.util.AppBusy
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun List<TrackRow>.filterByQuery(query: String): List<TrackRow> {
    val q = query.trim()
    if (q.isEmpty()) return this
    return filter { track ->
        track.title.contains(q, ignoreCase = true) ||
            track.artistName.orEmpty().contains(q, ignoreCase = true) ||
            track.albumName.orEmpty().contains(q, ignoreCase = true)
    }
}

@Composable
private fun LibraryTrackList(
    container: AppContainer,
    tracks: List<TrackRow>,
    onPlay: (List<TrackRow>, Int) -> Unit,
    scope: CoroutineScope,
    onDelete: ((String) -> Unit)? = null,
    onMoveUp: ((String) -> Unit)? = null,
    onMoveDown: ((String) -> Unit)? = null,
    emptyMessage: String = "Sin temas",
    deleteDialogTitle: String = "Borrar canción",
    deleteDialogText: ((TrackRow) -> String)? = null,
    deleteConfirmLabel: String = "Borrar",
) {
    var addTrackId by remember { mutableStateOf<String?>(null) }
    addTrackId?.let { tid ->
        AddToCollectionSheet(container, tid) { addTrackId = null }
    }
    TrackList(
        tracks = tracks,
        onPlay = onPlay,
        onToggleFavorite = { id -> scope.launch { container.library.toggleFavorite(id) } },
        onDelete = onDelete ?: { id ->
            scope.launch {
                AppBusy.run("Borrando…") {
                    val orphan = container.library.deleteTrack(id)
                    container.covers.deleteIfExists(orphan)
                }
            }
            Unit
        },
        onDownloadLocal = { id ->
            scope.launch {
                val (_, msg) = container.downloadQueue.enqueueLocalDownload(id)
                AppLog.i("Library", msg)
            }
            Unit
        },
        onAddToCollection = { addTrackId = it },
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        emptyMessage = emptyMessage,
        deleteDialogTitle = deleteDialogTitle,
        deleteDialogText = deleteDialogText,
        deleteConfirmLabel = deleteConfirmLabel,
    )
}

@Composable
fun ArtistsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val artists by container.library.observeArtists().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Artistas")
        if (artists.isEmpty()) {
            Muted("Todavía no hay artistas. Descargá temas desde Buscar.")
        } else {
            LazyColumn {
                items(artists, key = { it.id }) { a ->
                    SimpleListItem(a.name) { onOpen(a.id) }
                }
            }
        }
    }
}

@Composable
fun AlbumsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val albums by container.library.observeAlbums().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Álbumes")
        if (albums.isEmpty()) {
            Muted("Todavía no hay álbumes.")
        } else {
            LazyColumn {
                items(albums, key = { it.id }) { a ->
                    SimpleListItem(a.name, a.artistName) { onOpen(a.id) }
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val playlists by container.library.observePlaylists().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()
    val pending = playlists.firstOrNull { it.id == pendingDeleteId }

    if (pending != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Eliminar playlist") },
            text = {
                Text(
                    "Se elimina \"$pendingDeleteName\" y los temas que solo pertenecen a esta playlist. " +
                        "Los temas que también están en otras playlists se conservan.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = pending.id
                        pendingDeleteId = null
                        scope.launch {
                            AppBusy.run("Eliminando playlist…") {
                                val orphans = container.library.deletePlaylist(id)
                                orphans.forEach { container.covers.deleteIfExists(it) }
                            }
                        }
                    },
                    enabled = globalBusy == null,
                ) {
                    Text("Eliminar", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancelar") }
            },
        )
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Playlists")
        AppTextField(name, { name = it }, "Nueva playlist…")
        PrimaryButton("Crear", enabled = globalBusy == null) {
            scope.launch {
                if (name.isNotBlank()) {
                    container.library.createPlaylist(name)
                    name = ""
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (playlists.isEmpty()) {
            Muted("No hay playlists. Creá una o importá un link de Spotify/YouTube.")
        }
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                SimpleListItem(
                    title = p.name,
                    subtitle = "Tocá para abrir",
                    onClick = { onOpen(p.id) },
                    trailing = {
                        IconButton(
                            onClick = {
                                if (globalBusy == null) {
                                    pendingDeleteName = p.name
                                    pendingDeleteId = p.id
                                }
                            },
                            enabled = globalBusy == null,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Eliminar playlist",
                                tint = TextMuted,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun GenresScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val genres by container.library.observeGenres().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Géneros")
        if (genres.isEmpty()) {
            Muted("Todavía no hay géneros.")
        } else {
            LazyColumn {
                items(genres, key = { it.id }) { g ->
                    SimpleListItem(g.name) { onOpen(g.id) }
                }
            }
        }
    }
}

@Composable
fun FoldersScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val folders by container.library.observeFolders().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Carpetas")
        AppTextField(name, { name = it }, "Nueva carpeta…")
        PrimaryButton("Crear") {
            scope.launch {
                if (name.isNotBlank()) {
                    container.library.createFolder(name)
                    name = ""
                }
            }
        }
        if (folders.isEmpty()) {
            Muted("No hay carpetas. Creá una para organizar temas.")
        }
        LazyColumn {
            items(folders, key = { it.id }) { f ->
                SimpleListItem(f.name) { onOpen(f.id) }
            }
        }
    }
}

@Composable
fun FavoritesScreen(container: AppContainer, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeFavorites().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Favoritos")
        LibraryTrackList(
            container,
            tracks,
            onPlay,
            scope,
            emptyMessage = "Marcá temas con el corazón para verlos acá.",
        )
    }
}

@Composable
fun RecentScreen(container: AppContainer, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeRecent().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Recientes")
        LibraryTrackList(
            container,
            tracks,
            onPlay,
            scope,
            emptyMessage = "Todavía no reproduciste nada.",
        )
    }
}

@Composable
fun ArtistDetailScreen(container: AppContainer, id: String, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeTracksByArtist(id).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Artista") }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(id) {
        title = container.library.getArtist(id)?.name ?: "Artista"
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        LibraryTrackList(container, tracks, onPlay, scope)
    }
}

@Composable
fun AlbumDetailScreen(container: AppContainer, id: String, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeTracksByAlbum(id).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Álbum") }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(id) {
        title = container.library.getAlbum(id)?.name ?: "Álbum"
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        LibraryTrackList(container, tracks, onPlay, scope)
    }
}

@Composable
fun PlaylistDetailScreen(
    container: AppContainer,
    id: String,
    onPlay: (List<TrackRow>, Int) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val tracks by container.library.observeTracksByPlaylist(id).collectAsState(initial = emptyList())
    val allTracks by container.library.observeTracks().collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Playlist") }
    var status by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var editingTracks by remember { mutableStateOf(false) }
    var trackPickerQuery by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()

    androidx.compose.runtime.LaunchedEffect(id) {
        val playlist = container.library.getPlaylist(id)
        title = playlist?.name ?: "Playlist"
        nameDraft = playlist?.name.orEmpty()
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar playlist") },
            text = {
                Text(
                    "Se elimina \"$title\" y los temas que solo pertenecen a esta playlist. " +
                        "Los temas que también están en otras playlists se conservan.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            AppBusy.run("Eliminando playlist…") {
                                val orphans = container.library.deletePlaylist(id)
                                orphans.forEach { container.covers.deleteIfExists(it) }
                            }
                            onDeleted()
                        }
                    },
                    enabled = globalBusy == null,
                ) { Text("Eliminar", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }

    val needsLocal = tracks.filter { it.needsLocalDownload }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        if (editingName) {
            AppTextField(nameDraft, { nameDraft = it }, "Nombre de la playlist")
            PrimaryButton("Guardar nombre", enabled = globalBusy == null) {
                scope.launch {
                    AppBusy.run("Guardando…") {
                        container.library.renamePlaylist(id, nameDraft)
                        title = nameDraft.trim().ifBlank { title }
                        editingName = false
                    }
                }
            }
            GhostButton("Cancelar") { editingName = false }
            Spacer(Modifier.height(8.dp))
        } else {
            Row(Modifier.fillMaxWidth()) {
                GhostButton("Renombrar", modifier = Modifier.weight(1f)) {
                    nameDraft = title
                    editingName = true
                }
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    if (editingTracks) "Listo" else "Editar temas",
                    modifier = Modifier.weight(1f),
                ) {
                    editingTracks = !editingTracks
                    if (!editingTracks) trackPickerQuery = ""
                }
            }
            GhostButton("Eliminar playlist") {
                if (globalBusy == null) confirmDelete = true
            }
            Spacer(Modifier.height(8.dp))
        }

        if (editingTracks) {
            Muted("Marcá para agregar o quitar temas de esta playlist (no borra de la biblioteca).")
            Spacer(Modifier.height(8.dp))
            AppTextField(trackPickerQuery, { trackPickerQuery = it }, "Filtrar temas…")
            Spacer(Modifier.height(8.dp))
            val inPlaylist = tracks.map { it.id }.toSet()
            val pickerTracks = remember(allTracks, trackPickerQuery) {
                allTracks.filterByQuery(trackPickerQuery)
            }
            if (pickerTracks.isEmpty()) {
                Muted(if (trackPickerQuery.isBlank()) "No hay temas en la biblioteca." else "Sin coincidencias.")
            }
            LazyColumn(Modifier.weight(1f)) {
                items(pickerTracks, key = { it.id }) { track ->
                    val checked = track.id in inPlaylist
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                scope.launch {
                                    if (on) {
                                        container.library.addTrackToPlaylist(id, track.id)
                                    } else {
                                        container.library.removeTrackFromPlaylist(id, track.id)
                                    }
                                }
                            },
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(track.title)
                            Muted(listOfNotNull(track.artistName, track.albumName).joinToString(" · "))
                        }
                    }
                }
            }
        } else {
            if (needsLocal.isNotEmpty()) {
                PrimaryButton("Descargar playlist localmente (${needsLocal.size})") {
                    scope.launch {
                        val (_, msg) = container.downloadQueue.enqueueLocalDownloads(
                            needsLocal.map { it.id },
                        )
                        status = msg
                        AppLog.i("Library", msg)
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else if (tracks.isNotEmpty()) {
                Muted("Todos los temas ya están locales.")
                Spacer(Modifier.height(8.dp))
            }
            status?.let {
                Text(it, color = Accent, modifier = Modifier.padding(bottom = 8.dp))
            }
            LibraryTrackList(
                container = container,
                tracks = tracks,
                onPlay = onPlay,
                scope = scope,
                onDelete = { trackId ->
                    scope.launch {
                        AppBusy.run("Sacando de la playlist…") {
                            container.library.removeTrackFromPlaylist(id, trackId)
                        }
                    }
                },
                onMoveUp = { trackId ->
                    scope.launch { container.library.movePlaylistTrack(id, trackId, -1) }
                },
                onMoveDown = { trackId ->
                    scope.launch { container.library.movePlaylistTrack(id, trackId, 1) }
                },
                emptyMessage = "Esta playlist está vacía. Usá Editar temas.",
                deleteDialogTitle = "Quitar de la playlist",
                deleteDialogText = { t ->
                    "¿Sacar \"${t.title}\" de esta playlist? El tema sigue en la biblioteca."
                },
                deleteConfirmLabel = "Quitar",
            )
        }
    }
}

@Composable
fun GenreDetailScreen(container: AppContainer, id: String, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeTracksByGenre(id).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Género") }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(id) {
        title = container.library.getGenre(id)?.name ?: "Género"
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        LibraryTrackList(container, tracks, onPlay, scope)
    }
}

@Composable
fun FolderDetailScreen(
    container: AppContainer,
    id: String,
    onPlay: (List<TrackRow>, Int) -> Unit,
    onDeleted: () -> Unit = {},
) {
    val tracks by container.library.observeTracksByFolder(id).collectAsState(initial = emptyList())
    val allTracks by container.library.observeTracks().collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Carpeta") }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }
    var editingTracks by remember { mutableStateOf(false) }
    var trackPickerQuery by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()

    androidx.compose.runtime.LaunchedEffect(id) {
        val folder = container.library.getFolder(id)
        title = folder?.name ?: "Carpeta"
        nameDraft = folder?.name.orEmpty()
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar carpeta") },
            text = { Text("Se elimina \"$title\". Los temas permanecen en la biblioteca.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            AppBusy.run("Eliminando carpeta…") {
                                container.library.deleteFolder(id)
                            }
                            onDeleted()
                        }
                    },
                    enabled = globalBusy == null,
                ) { Text("Eliminar", color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") }
            },
        )
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        if (editingName) {
            AppTextField(nameDraft, { nameDraft = it }, "Nombre de la carpeta")
            PrimaryButton("Guardar nombre", enabled = globalBusy == null) {
                scope.launch {
                    AppBusy.run("Guardando…") {
                        container.library.renameFolder(id, nameDraft)
                        title = nameDraft.trim().ifBlank { title }
                        editingName = false
                    }
                }
            }
            GhostButton("Cancelar") { editingName = false }
            Spacer(Modifier.height(8.dp))
        } else {
            Row(Modifier.fillMaxWidth()) {
                GhostButton("Renombrar", modifier = Modifier.weight(1f)) {
                    nameDraft = title
                    editingName = true
                }
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    if (editingTracks) "Listo" else "Editar temas",
                    modifier = Modifier.weight(1f),
                ) {
                    editingTracks = !editingTracks
                    if (!editingTracks) trackPickerQuery = ""
                }
            }
            GhostButton("Eliminar carpeta") {
                if (globalBusy == null) confirmDelete = true
            }
            Spacer(Modifier.height(8.dp))
        }

        if (editingTracks) {
            Muted("Marcá para agregar o quitar temas de esta carpeta.")
            Spacer(Modifier.height(8.dp))
            AppTextField(trackPickerQuery, { trackPickerQuery = it }, "Filtrar temas…")
            Spacer(Modifier.height(8.dp))
            val inFolder = tracks.map { it.id }.toSet()
            val pickerTracks = remember(allTracks, trackPickerQuery) {
                allTracks.filterByQuery(trackPickerQuery)
            }
            LazyColumn(Modifier.weight(1f)) {
                items(pickerTracks, key = { it.id }) { track ->
                    val checked = track.id in inFolder
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                scope.launch {
                                    if (on) container.library.addTrackToFolder(id, track.id)
                                    else container.library.removeTrackFromFolder(id, track.id)
                                }
                            },
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(track.title)
                            Muted(listOfNotNull(track.artistName, track.albumName).joinToString(" · "))
                        }
                    }
                }
            }
        } else {
            LibraryTrackList(
                container = container,
                tracks = tracks,
                onPlay = onPlay,
                scope = scope,
                onDelete = { trackId ->
                    scope.launch {
                        container.library.removeTrackFromFolder(id, trackId)
                    }
                },
                emptyMessage = "Esta carpeta está vacía.",
                deleteDialogTitle = "Quitar de la carpeta",
                deleteDialogText = { t -> "¿Sacar \"${t.title}\" de esta carpeta?" },
                deleteConfirmLabel = "Quitar",
            )
        }
    }
}
