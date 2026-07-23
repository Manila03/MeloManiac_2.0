package com.melomaniac.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.AppSettings
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.AppTextField
import com.melomaniac.app.ui.GhostButton
import com.melomaniac.app.ui.Muted
import com.melomaniac.app.ui.PrimaryButton
import com.melomaniac.app.ui.ProgressBar
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.SimpleListItem
import com.melomaniac.app.ui.TrackList
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Surface
import com.melomaniac.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun LibraryHomeScreen(
    container: AppContainer,
    onOpen: (String) -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit,
) {
    val tracks by container.library.observeTracks().collectAsState(initial = emptyList())
    val count by container.library.observeTrackCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("MeloManiac")
        Muted("Tu música. Offline. FLAC.")
        val links = listOf(
            "artists" to "Artistas",
            "albums" to "Álbumes",
            "playlists" to "Playlists",
            "favorites" to "Favoritos",
            "recent" to "Recientes",
            "genres" to "Géneros",
            "folders" to "Carpetas",
        )
        links.forEach { (route, label) ->
            SimpleListItem(title = label, onClick = { onOpen(route) })
        }
        Text("Temas ($count)", color = TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
        TrackList(
            tracks = tracks.take(40),
            onPlay = onPlay,
            onToggleFavorite = { id -> scope.launch { container.library.toggleFavorite(id) } },
        )
    }
}

@Composable
fun SearchScreen(container: AppContainer, onPlay: (List<TrackRow>, Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Buscar")
        Muted("Pegá un link de YouTube/Spotify o buscá por texto.")
        AppTextField(query, { query = it }, "URL o búsqueda…")
        PrimaryButton("Buscar / Encolar", onClick = {
            scope.launch {
                busy = true
                message = null
                try {
                    val q = query.trim()
                    if (q.contains("http") || q.startsWith("spotify:") || q.length > 3 && (q.contains("spotify") || q.contains("youtu"))) {
                        val (n, msg) = container.downloadQueue.enqueueFromUserInput(q)
                        message = msg
                        local = emptyList()
                    } else {
                        local = container.library.search(q)
                        // also try enqueue as remote search download option via message
                        message = if (local.isEmpty()) "Sin resultados locales. Podés encolar la búsqueda para YouTube." else null
                    }
                } catch (e: Exception) {
                    message = e.message
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy)
        if (!local.isEmpty() || query.isNotBlank()) {
            GhostButton("Encolar búsqueda en YouTube", onClick = {
                scope.launch {
                    try {
                        val (_, msg) = container.downloadQueue.enqueueFromUserInput(query)
                        message = msg
                    } catch (e: Exception) {
                        message = e.message
                    }
                }
            })
        }
        message?.let { Text(it, color = Accent, modifier = Modifier.padding(vertical = 8.dp)) }
        TrackList(local, onPlay) { id -> scope.launch { container.library.toggleFavorite(id) } }
    }
}

@Composable
fun DownloadsScreen(container: AppContainer) {
    val jobs by container.downloadDao.observeAll().collectAsState(initial = emptyList())
    val status by container.downloadQueue.status.collectAsState()
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Descargas")
        Muted(status)
        PrimaryButton("Reanudar cola", onClick = { container.downloadQueue.start() })
        GhostButton("Pausar cola", onClick = { container.downloadQueue.stop() })
        GhostButton("Limpiar terminadas", onClick = { scope.launch { container.downloadQueue.clearFinished() } })
        LazyColumn {
            items(jobs, key = { it.id }) { job ->
                DownloadJobCard(job, onRetry = { scope.launch { container.downloadQueue.retry(job.id) } })
            }
        }
    }
}

@Composable
private fun DownloadJobCard(job: DownloadJobEntity, onRetry: () -> Unit) {
    val meta = try {
        JSONObject(job.metaJson)
    } catch (_: Exception) {
        JSONObject()
    }
    val title = meta.optString("title").ifBlank { job.urlOrQuery }
    androidx.compose.material3.Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title)
            Text("${job.status.uppercase()} · ${job.progress.toInt()}%", color = TextSecondary)
            job.error?.let { Text(it, color = MaterialThemeError()) }
            ProgressBar(job.progress)
            if (job.status == "failed") {
                TextButton(onClick = onRetry) { Text("Reintentar", color = Accent) }
            }
        }
    }
}

@Composable
private fun MaterialThemeError() = androidx.compose.material3.MaterialTheme.colorScheme.error

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    var settings by remember { mutableStateOf(AppSettings()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        settings = container.settings.get()
    }

    fun save(patch: AppSettings) {
        settings = patch
        scope.launch { container.settings.update(patch) }
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Ajustes")
        Muted("Preferimos FLAC. La calidad de abajo aplica si FLAC no está disponible.")
        Text("Calidad fallback", color = TextSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("best", "320", "128").forEach { q ->
                FilterChip(
                    selected = settings.fallbackQuality == q,
                    onClick = { save(settings.copy(fallbackQuality = q)) },
                    label = { Text(q) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
                )
            }
        }
        Text("Concurrencia", color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { n ->
                FilterChip(
                    selected = settings.downloadConcurrency == n,
                    onClick = { save(settings.copy(downloadConcurrency = n)) },
                    label = { Text("$n") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
                )
            }
        }
        FilterChip(
            selected = settings.preferFlac,
            onClick = { save(settings.copy(preferFlac = !settings.preferFlac)) },
            label = { Text(if (settings.preferFlac) "FLAC activado" else "FLAC desactivado") },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
            modifier = Modifier.padding(top = 8.dp),
        )
        Text("Spotify API", color = TextSecondary, modifier = Modifier.padding(top = 16.dp))
        AppTextField(settings.spotifyClientId, { save(settings.copy(spotifyClientId = it)) }, "Client ID")
        AppTextField(settings.spotifyClientSecret, { save(settings.copy(spotifyClientSecret = it)) }, "Client Secret")
        Text(
            "Binarios yt-dlp (se instalan en code-cache, ejecutable en Android 10+)",
            color = TextSecondary,
            modifier = Modifier.padding(top = 16.dp),
        )
        PrimaryButton("Descargar / verificar binarios", onClick = {
            scope.launch {
                busy = true
                try {
                    container.binaryManager.ensureBinaries { status = it }
                } catch (e: Exception) {
                    status = e.message
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy)
        GhostButton("Reinstalar binarios", onClick = {
            scope.launch {
                busy = true
                try {
                    container.binaryManager.reinstallAll { status = it }
                } catch (e: Exception) {
                    status = e.message
                } finally {
                    busy = false
                }
            }
        })
        GhostButton("Actualizar yt-dlp", onClick = {
            scope.launch {
                busy = true
                try {
                    container.binaryManager.updateYtDlp { status = it }
                } catch (e: Exception) {
                    status = e.message
                } finally {
                    busy = false
                }
            }
        })
        status?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp)) }
    }
}
