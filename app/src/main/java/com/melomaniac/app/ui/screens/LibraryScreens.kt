package com.melomaniac.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.AppTextField
import com.melomaniac.app.ui.PrimaryButton
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.SimpleListItem
import com.melomaniac.app.ui.TrackList
import kotlinx.coroutines.launch

@Composable
fun ArtistsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val artists by container.library.observeArtists().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Artistas")
        LazyColumn {
            items(artists, key = { it.id }) { a ->
                SimpleListItem(a.name) { onOpen(a.id) }
            }
        }
    }
}

@Composable
fun AlbumsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val albums by container.library.observeAlbums().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Álbumes")
        LazyColumn {
            items(albums, key = { it.id }) { a ->
                SimpleListItem(a.name, a.artistName) { onOpen(a.id) }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val playlists by container.library.observePlaylists().collectAsState(initial = emptyList())
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Playlists")
        AppTextField(name, { name = it }, "Nueva playlist…")
        PrimaryButton("Crear") {
            scope.launch {
                if (name.isNotBlank()) {
                    container.library.createPlaylist(name)
                    name = ""
                }
            }
        }
        LazyColumn {
            items(playlists, key = { it.id }) { p ->
                SimpleListItem(p.name) { onOpen(p.id) }
            }
        }
    }
}

@Composable
fun GenresScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val genres by container.library.observeGenres().collectAsState(initial = emptyList())
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Géneros")
        LazyColumn {
            items(genres, key = { it.id }) { g ->
                SimpleListItem(g.name) { onOpen(g.id) }
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
        TrackList(tracks, onPlay) { id -> scope.launch { container.library.toggleFavorite(id) } }
    }
}

@Composable
fun RecentScreen(container: AppContainer, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeRecent().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Recientes")
        TrackList(tracks, onPlay) { id -> scope.launch { container.library.toggleFavorite(id) } }
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
        TrackList(tracks, onPlay) { tid -> scope.launch { container.library.toggleFavorite(tid) } }
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
        TrackList(tracks, onPlay) { tid -> scope.launch { container.library.toggleFavorite(tid) } }
    }
}

@Composable
fun PlaylistDetailScreen(container: AppContainer, id: String, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeTracksByPlaylist(id).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Playlist") }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(id) {
        title = container.library.getPlaylist(id)?.name ?: "Playlist"
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        TrackList(tracks, onPlay) { tid -> scope.launch { container.library.toggleFavorite(tid) } }
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
        TrackList(tracks, onPlay) { tid -> scope.launch { container.library.toggleFavorite(tid) } }
    }
}

@Composable
fun FolderDetailScreen(container: AppContainer, id: String, onPlay: (List<TrackRow>, Int) -> Unit) {
    val tracks by container.library.observeTracksByFolder(id).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("Carpeta") }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(id) {
        title = container.library.getFolder(id)?.name ?: "Carpeta"
    }
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle(title)
        TrackList(tracks, onPlay) { tid -> scope.launch { container.library.toggleFavorite(tid) } }
    }
}
