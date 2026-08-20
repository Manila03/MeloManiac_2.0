package com.melomaniac.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melomaniac.app.BuildConfig
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.AppSettings
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.AddToCollectionSheet
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryHomeScreen(
    container: AppContainer,
    onBrowse: () -> Unit,
    onOpenSection: (String) -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    // Fresh Flow collection each composition entry so returning to Home after
    // downloads always picks up Room invalidations (not a stale empty snapshot).
    val tracks by remember(container) { container.library.observeTracksByAdded() }
        .collectAsState(initial = emptyList())
    val count by remember(container) { container.library.observeTrackCount() }
        .collectAsState(initial = 0)
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.ADDED) }
    var addTrackId by remember { mutableStateOf<String?>(null) }
    val filtered = remember(tracks, query, sort) {
        val base = tracks.filterByQuery(query)
        when (sort) {
            LibrarySort.ADDED -> base
            LibrarySort.TITLE -> base.sortedBy { it.title.lowercase() }
            LibrarySort.ARTIST -> base.sortedBy { it.artistName.orEmpty().lowercase() }
        }
    }
    val scope = rememberCoroutineScope()
    val settings by produceSettings(container)

    addTrackId?.let { tid ->
        AddToCollectionSheet(container, tid) { addTrackId = null }
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Biblioteca")
        Muted("Buscá un tema suelto o abrí playlists y favoritos.")
        SetupChecklistBanner(settings, onOpenSettings)
        Spacer(Modifier.height(8.dp))
        AppTextField(query, { query = it }, "Filtrar canciones…")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibrarySort.entries.forEach { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { sort = s },
                    label = {
                        Text(
                            when (s) {
                                LibrarySort.ADDED -> "Agregado"
                                LibrarySort.TITLE -> "Título"
                                LibrarySort.ARTIST -> "Artista"
                            },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LibraryQuickChips(onBrowse = onBrowse, onOpenSection = onOpenSection)
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                query.isBlank() -> "Canciones ($count)"
                else -> "${filtered.size} de $count"
            },
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (tracks.isEmpty()) {
            Muted("Todavía no hay temas. Usá Buscar para encolar YouTube o Spotify.")
        } else if (filtered.isEmpty()) {
            Muted("Ningún tema coincide con \"$query\".")
        } else {
            TrackList(
                tracks = filtered,
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
                onDownloadLocal = { id ->
                    scope.launch {
                        val (_, msg) = container.downloadQueue.enqueueLocalDownload(id)
                        AppLog.i("Library", msg)
                    }
                },
                onAddToCollection = { addTrackId = it },
            )
        }
    }
}

private enum class LibrarySort { ADDED, TITLE, ARTIST }

@Composable
private fun produceSettings(container: AppContainer): androidx.compose.runtime.State<AppSettings> {
    val state = remember { mutableStateOf(AppSettings()) }
    LaunchedEffect(Unit) {
        state.value = container.settings.get()
    }
    return state
}

@Composable
private fun SetupChecklistBanner(
    settings: AppSettings,
    onOpenSettings: () -> Unit,
) {
    val needsTelegram = !settings.preferLocalStorage && !settings.isTelegramConfigured
    if (!needsTelegram) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Muted("Para empezar: configurá Telegram en Ajustes (o activá modo solo local).")
        GhostButton("Ir a Ajustes", onClick = onOpenSettings)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryQuickChips(
    onBrowse: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    val chips = listOf(
        "playlists" to "Playlists",
        "favorites" to "Favoritos",
        "recent" to "Recientes",
        "artists" to "Artistas",
        "albums" to "Álbumes",
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        chips.forEach { (key, label) ->
            FilterChip(
                selected = false,
                onClick = { onOpenSection(key) },
                label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Surface,
                    labelColor = TextSecondary,
                ),
            )
        }
        FilterChip(
            selected = false,
            onClick = onBrowse,
            label = { Text("Más…") },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Surface,
                labelColor = TextSecondary,
            ),
        )
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





@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(container: AppContainer) {
    val jobs by container.downloadDao.observeAll().collectAsState(initial = emptyList())
    val status by container.downloadQueue.status.collectAsState()
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()
    var confirmClearQueue by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DownloadFilter.ACTIVE) }

    if (confirmClearQueue) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClearQueue = false },
            title = { Text("Vaciar cola") },
            text = {
                Text(
                    "Se cancelarán y eliminarán todos los temas pendientes y en curso. " +
                        "El historial de descargas finalizadas no se borra.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearQueue = false
                        scope.launch {
                            AppBusy.run("Vaciando cola…") {
                                container.downloadQueue.clearQueue()
                            }
                        }
                    },
                    enabled = globalBusy == null,
                ) { Text("Vaciar cola", color = MaterialThemeError()) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearQueue = false }) { Text("Cancelar") }
            },
        )
    }

    val visible = remember(jobs, filter) {
        when (filter) {
            DownloadFilter.ACTIVE -> jobs.filter { it.status == "queued" || it.status == "running" }
            DownloadFilter.FAILED -> jobs.filter { it.status == "failed" }
            DownloadFilter.DONE -> jobs.filter { it.status == "done" || it.status == "cancelled" }
            DownloadFilter.ALL -> jobs
        }
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Descargas")
        Muted(status)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(
                            when (f) {
                                DownloadFilter.ACTIVE -> "Activas"
                                DownloadFilter.FAILED -> "Fallidas"
                                DownloadFilter.DONE -> "Hechas"
                                DownloadFilter.ALL -> "Todas"
                            },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
        GhostButton("Vaciar cola", onClick = {
            if (globalBusy == null) confirmClearQueue = true
        })
        GhostButton("Limpiar historial", onClick = {
            scope.launch {
                AppBusy.run("Limpiando historial…") {
                    container.downloadQueue.clearHistory()
                }
            }
        })
        if (visible.isEmpty()) {
            Muted(
                when (filter) {
                    DownloadFilter.ACTIVE -> "No hay descargas activas."
                    DownloadFilter.FAILED -> "No hay fallos."
                    DownloadFilter.DONE -> "Sin historial todavía."
                    DownloadFilter.ALL -> "La cola está vacía."
                },
            )
        }
        LazyColumn {
            items(visible, key = { it.id }) { job ->
                DownloadJobCard(
                    job = job,
                    onRetry = {
                        scope.launch {
                            AppBusy.run("Reintentando…") {
                                container.downloadQueue.retry(job.id)
                            }
                        }
                    },
                    onCancel = {
                        scope.launch {
                            container.downloadQueue.cancel(job.id)
                        }
                    },
                )
            }
        }
    }
}

private enum class DownloadFilter { ACTIVE, FAILED, DONE, ALL }

@Composable
private fun DownloadJobCard(
    job: DownloadJobEntity,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val meta = remember(job.metaJson) {
        runCatching { JSONObject(job.metaJson) }.getOrNull()
    }
    val title = meta?.optString("title")?.ifBlank { null } ?: job.urlOrQuery.take(60)
    val attempts = meta?.optInt("attempts") ?: 0
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(
                buildString {
                    append(job.status)
                    if (attempts > 0) append(" · reintento $attempts/3")
                    job.error?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                color = TextSecondary,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            )
            if (job.status == "running" || job.status == "queued") {
                ProgressBar(job.progress)
            }
            Row {
                if (job.status == "failed") {
                    TextButton(onClick = onRetry) { Text("Reintentar", color = Accent) }
                }
                if (job.status == "queued" || job.status == "running") {
                    TextButton(onClick = onCancel) { Text("Cancelar", color = Accent) }
                }
            }
        }
    }
}

@Composable
private fun MaterialThemeError() = androidx.compose.material3.MaterialTheme.colorScheme.error

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenLogs: () -> Unit = {},
) {
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
        FilterChip(
            selected = settings.preferLocalStorage,
            onClick = { save(settings.copy(preferLocalStorage = !settings.preferLocalStorage)) },
            label = {
                Text(
                    if (settings.preferLocalStorage) {
                        "Guardar en el teléfono (sin Telegram)"
                    } else {
                        "Almacenar en Telegram (online)"
                    },
                )
            },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
            modifier = Modifier.padding(top = 8.dp),
        )

        Text("Telegram (almacenamiento online)", color = TextSecondary, modifier = Modifier.padding(top = 16.dp))
        Muted(
            if (settings.preferLocalStorage) {
                "Modo local activo: las descargas nuevas se guardan en el teléfono. " +
                    "Telegram sigue disponible para temas ya online."
            } else {
                "Las descargas van a Telegram (HLS). Usá «Descargar localmente» en la biblioteca " +
                    "para guardar un tema o playlist offline en el teléfono. " +
                    "Creá un bot con @BotFather, un canal privado, agregá el bot como admin " +
                    "y pegá el token + channel ID (ej. -100…)."
            },
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = settings.telegramBotToken,
            onValueChange = { save(settings.copy(telegramBotToken = it.trim())) },
            placeholder = "Bot token",
        )
        Spacer(Modifier.height(8.dp))
        AppTextField(
            value = settings.telegramChannelId,
            onValueChange = { save(settings.copy(telegramChannelId = it.trim())) },
            placeholder = "Channel ID (ej. -100123…)",
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            "Probar conexión Telegram",
            onClick = {
                scope.launch {
                    try {
                        AppBusy.run("Probando Telegram…") {
                            status = container.telegramConfig.testConnection()
                        }
                    } catch (e: Exception) {
                        status = e.message
                    }
                }
            },
            enabled = globalBusy == null &&
                settings.telegramBotToken.isNotBlank() &&
                settings.telegramChannelId.isNotBlank(),
        )
        if (settings.isTelegramConfigured) {
            Muted("Telegram listo ✓")
        }

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

        Text("Diagnóstico", color = TextSecondary, modifier = Modifier.padding(top = 24.dp))
        GhostButton("Ver logs", onClick = onOpenLogs)

        Text("Datos", color = TextSecondary, modifier = Modifier.padding(top = 24.dp))
        Muted("Vacía la base de datos Room y borra audio/portadas en filesDir.")
        GhostButton("Resetear biblioteca") {
            if (globalBusy == null) confirmReset = true
        }

        status?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)) }
    }
}
