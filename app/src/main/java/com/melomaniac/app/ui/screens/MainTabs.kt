package com.melomaniac.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.melomaniac.app.BuildConfig
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.AppSettings
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.AppTextField
import com.melomaniac.app.ui.GhostButton
import com.melomaniac.app.update.ReleaseUpdate
import com.melomaniac.app.update.UpdateCheckResult
import com.melomaniac.app.ui.Muted
import com.melomaniac.app.ui.PrimaryButton
import com.melomaniac.app.ui.ProgressBar
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.SimpleListItem
import com.melomaniac.app.ui.TrackList
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Surface
import com.melomaniac.app.ui.theme.TextSecondary
import com.melomaniac.app.util.AppBusy
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun LibraryHomeScreen(
    container: AppContainer,
    onBrowse: () -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit,
) {
    val tracks by container.library.observeTracks().collectAsState(initial = emptyList())
    val count by container.library.observeTrackCount().collectAsState(initial = 0)
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("MeloManiac")
        Muted("Tu música. Offline. FLAC.")
        PrimaryButton("Explorar biblioteca", onClick = onBrowse)
        Text(
            "Todas las canciones ($count)",
            color = TextSecondary,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (tracks.isEmpty()) {
            Muted("Todavía no hay temas. Usá Buscar para encolar YouTube o Spotify.")
        } else {
            TrackList(
                tracks = tracks,
                onPlay = onPlay,
                onToggleFavorite = { id -> scope.launch { container.library.toggleFavorite(id) } },
                onDelete = { id ->
                    scope.launch {
                        AppBusy.run("Borrando…") {
                            val orphan = container.library.deleteTrack(id)
                            container.covers.deleteIfExists(orphan)
                        }
                    }
                },
            )
        }
    }
}

@Composable
fun BrowseLibraryScreen(onOpen: (String) -> Unit) {
    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Biblioteca")
        Muted("Artistas, álbumes, playlists y más.")
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
    }
}





@Composable
fun DownloadsScreen(container: AppContainer) {
    val jobs by container.downloadDao.observeAll().collectAsState(initial = emptyList())
    val status by container.downloadQueue.status.collectAsState()
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Descargas")
        Muted(status)
        PrimaryButton("Reanudar cola", onClick = {
            scope.launch {
                AppBusy.run("Reanudando cola…") {
                    container.downloadQueue.start()
                }
            }
        }, enabled = globalBusy == null)
        GhostButton("Pausar cola", onClick = {
            scope.launch {
                AppBusy.run("Pausando cola…") {
                    container.downloadQueue.stop()
                }
            }
        })
        GhostButton("Limpiar historial", onClick = {
            scope.launch {
                AppBusy.run("Limpiando historial…") {
                    container.downloadQueue.clearHistory()
                }
            }
        })
        LazyColumn {
            items(jobs, key = { it.id }) { job ->
                DownloadJobCard(job, onRetry = {
                    scope.launch {
                        AppBusy.run("Reintentando…") {
                            container.downloadQueue.retry(job.id)
                        }
                    }
                })
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
    var pendingUpdate by remember { mutableStateOf<ReleaseUpdate?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updater = container.appUpdater
    val globalBusy by AppBusy.message.collectAsState()

    LaunchedEffect(Unit) {
        settings = container.settings.get()
    }

    fun save(patch: AppSettings) {
        settings = patch
        scope.launch { container.settings.update(patch) }
    }

    fun installPending(update: ReleaseUpdate) {
        scope.launch {
            try {
                AppBusy.run("Instalando actualización…") {
                    if (!updater.canInstallPackages()) {
                        status = "Permití instalar apps de MeloManiac y volvé a tocar Instalar"
                        context.startActivity(updater.intentToAllowInstalls())
                        return@run
                    }
                    val apk = updater.downloadApk(update) { status = it }
                    updater.installApk(apk)
                    status = "Seguí el instalador de Android"
                }
            } catch (e: Exception) {
                status = e.message
            }
        }
    }

    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Resetear biblioteca") },
            text = {
                Text(
                    "Se van a borrar todos los temas, playlists, historial de descargas, " +
                        "ajustes guardados y los archivos de audio/portadas en el dispositivo. " +
                        "Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        scope.launch {
                            try {
                                AppBusy.run("Reseteando biblioteca…") {
                                    container.resetLibrary()
                                }
                                settings = container.settings.get()
                                status = "Biblioteca reseteada"
                            } catch (e: Exception) {
                                status = e.message ?: "Error al resetear"
                            }
                        }
                    },
                    enabled = globalBusy == null,
                ) { Text("Resetear todo", color = MaterialThemeError()) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancelar") }
            },
        )
    }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTitle("Ajustes")
        Muted("Versión instalada: ${BuildConfig.VERSION_NAME}")
        Text("Actualizaciones", color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        PrimaryButton(
            if (pendingUpdate != null) {
                "Instalar ${pendingUpdate!!.versionName}"
            } else {
                "Buscar actualizaciones"
            },
            onClick = {
                val ready = pendingUpdate
                if (ready != null) {
                    installPending(ready)
                    return@PrimaryButton
                }
                scope.launch {
                    pendingUpdate = null
                    try {
                        AppBusy.run("Buscando actualizaciones…") {
                            status = "Consultando GitHub…"
                            when (val result = updater.checkForUpdate()) {
                                is UpdateCheckResult.UpToDate -> {
                                    status = "Ya tenés la última versión (${BuildConfig.VERSION_NAME})"
                                }
                                is UpdateCheckResult.Available -> {
                                    pendingUpdate = result.update
                                    status = "Nueva versión ${result.update.versionName} disponible"
                                }
                                is UpdateCheckResult.Failed -> {
                                    status = result.message
                                }
                            }
                        }
                    } catch (e: Exception) {
                        status = e.message
                    }
                }
            },
            enabled = globalBusy == null,
        )
        if (pendingUpdate != null) {
            GhostButton("Cancelar actualización") {
                pendingUpdate = null
                status = null
            }
        }

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
        Text("Spotify", color = TextSecondary, modifier = Modifier.padding(top = 16.dp))
        Muted(
            "Spotify se resuelve por scraper público (sin API ni login). " +
                "Pegá un link público de tema, álbum o playlist en Buscar; " +
                "las playlists privadas no se pueden leer.",
        )
        Text(
            "yt-dlp / ffmpeg (embebidos para Android 10+; no usan filesDir)",
            color = TextSecondary,
            modifier = Modifier.padding(top = 16.dp),
        )
        PrimaryButton("Inicializar / verificar binarios", onClick = {
            scope.launch {
                try {
                    AppBusy.run("Preparando binarios…") {
                        container.binaryManager.ensureBinaries { status = it }
                    }
                } catch (e: Exception) {
                    status = e.message
                }
            }
        }, enabled = globalBusy == null)
        GhostButton("Reinstalar binarios", onClick = {
            scope.launch {
                try {
                    AppBusy.run("Reinstalando binarios…") {
                        container.binaryManager.reinstallAll { status = it }
                    }
                } catch (e: Exception) {
                    status = e.message
                }
            }
        })
        GhostButton("Actualizar yt-dlp (nightly)", onClick = {
            scope.launch {
                try {
                    AppBusy.run("Actualizando yt-dlp…") {
                        container.binaryManager.updateYtDlp { status = it }
                    }
                } catch (e: Exception) {
                    status = e.message
                }
            }
        })

        Text("Datos", color = TextSecondary, modifier = Modifier.padding(top = 24.dp))
        Muted("Vacía la base de datos Room y borra audio/portadas en filesDir.")
        GhostButton("Resetear biblioteca") {
            if (globalBusy == null) confirmReset = true
        }

        status?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) }
    }
}
