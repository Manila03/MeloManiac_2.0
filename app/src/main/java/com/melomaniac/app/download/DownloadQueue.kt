package com.melomaniac.app.download

import android.content.Context
import com.melomaniac.app.data.DownloadDao
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.LibraryRepository
import com.melomaniac.app.data.SettingsRepository
import com.melomaniac.app.data.TrackEntity
import com.melomaniac.app.data.TrackSegmentEntity
import com.melomaniac.app.telegram.HlsPackager
import com.melomaniac.app.telegram.TelegramConfig
import com.melomaniac.app.util.AppLog
import com.melomaniac.app.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class DownloadQueue(
    private val appContext: Context,
    private val downloadDao: DownloadDao,
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val ytDlp: YtDlpRunner,
    private val spotify: SpotifyScraper,
    private val covers: CoverStore,
    private val hlsPackager: HlsPackager,
    private val telegramConfig: TelegramConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val commitMutex = Mutex()
    private var workers = 0
    private var running = false
    @Volatile private var resetGeneration = 0L
    private val inFlight = mutableSetOf<String>()
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _status = MutableStateFlow(
        if (prefs.getBoolean(PREF_QUEUE_PAUSED, false)) "Pausado" else "En espera",
    )
    val status: StateFlow<String> = _status

    fun isPaused(): Boolean = prefs.getBoolean(PREF_QUEUE_PAUSED, false)

    fun start() {
        setPaused(false)
        running = true
        _status.value = "Procesando…"
        scope.launch {
            downloadDao.resetStuck(System.currentTimeMillis())
            pump()
        }
    }

    suspend fun stop() {
        mutex.withLock {
            running = false
            resetGeneration++
            setPaused(true)
            _status.value = "Pausado"
            AppLog.i("Queue", "Pausado generation=$resetGeneration")
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        // Re-queue interrupted jobs so pause does not leave them failed/stuck.
        downloadDao.resetStuck(System.currentTimeMillis())
    }

    /**
     * Aborts in-flight work and deletes all queued/running jobs.
     * Finished history (done/failed/cancelled) is left for [clearHistory].
     */
    suspend fun clearQueue() {
        mutex.withLock {
            running = false
            resetGeneration++
            setPaused(true)
            _status.value = "Pausado"
            AppLog.i("Queue", "Vaciar cola generation=$resetGeneration")
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        commitMutex.withLock {
            downloadDao.clearActive()
        }
    }

    /**
     * Invalidates in-flight workers and prevents them from writing while [wipe] runs.
     * Destroys live yt-dlp processes so process ids cannot collide on the next run.
     */
    suspend fun resetStorage(wipe: suspend () -> Unit) {
        mutex.withLock {
            running = false
            resetGeneration++
            setPaused(false)
            _status.value = "En espera"
            AppLog.i("Queue", "Reset generation=$resetGeneration")
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        commitMutex.withLock { wipe() }
    }

    private fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(PREF_QUEUE_PAUSED, paused).apply()
    }

    suspend fun enqueueFromUserInput(input: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext 0 to "Entrada vacía"
        val settings = settingsRepo.get()
        if (!settings.isTelegramConfigured) {
            return@withContext 0 to "Configurá Telegram en Ajustes antes de descargar"
        }
        AppLog.i("Queue", "enqueue input: ${trimmed.take(120)} → telegram")

        when {
            LinkDetector.isSpotify(trimmed) -> {
                AppLog.i("Queue", "Spotify scraper: ${SpotifyUrls.parse(trimmed)?.first ?: "link"}")
                when (val resolved = spotify.resolve(trimmed)) {
                    is SpotifyResolve.Track -> {
                        enqueueSpotifyTrack(resolved.track)
                        start()
                        return@withContext 1 to "Encolado: ${resolved.track.name}"
                    }
                    is SpotifyResolve.Collection -> {
                        val c = resolved.collection
                        if (c.type == "album") {
                            val artist = library.getOrCreateArtist(c.tracks.firstOrNull()?.artists?.firstOrNull() ?: "Various Artists")
                            val album = library.getOrCreateAlbum(c.name, artist.id, c.externalUrl)
                            c.tracks.forEach { t ->
                                enqueue(
                                    spotify.searchQuery(t),
                                    meta {
                                        put("title", t.name)
                                        put("artist", t.artists.joinToString(", "))
                                        put("albumName", c.name)
                                        put("albumId", album.id)
                                        put("spotifyId", t.id)
                                        put("durationMs", t.durationMs)
                                        (t.coverUrl ?: c.coverUrl)?.let { put("coverUrl", it) }
                                    },
                                )
                            }
                            start()
                            return@withContext c.tracks.size to "Álbum Spotify \"${c.name}\": ${c.tracks.size} temas"
                        } else {
                            val playlist = library.createPlaylist(c.name, c.externalUrl)
                            c.tracks.forEach { t ->
                                enqueue(
                                    spotify.searchQuery(t),
                                    meta {
                                        put("title", t.name)
                                        put("artist", t.artists.joinToString(", "))
                                        put("albumName", t.albumName)
                                        put("playlistId", playlist.id)
                                        put("playlistName", c.name)
                                        put("spotifyId", t.id)
                                        put("durationMs", t.durationMs)
                                        (t.coverUrl ?: c.coverUrl)?.let { put("coverUrl", it) }
                                    },
                                )
                            }
                            start()
                            return@withContext c.tracks.size to "Playlist Spotify \"${c.name}\": ${c.tracks.size} temas"
                        }
                    }
                }
            }

            LinkDetector.isYouTube(trimmed) -> {
                val listId = LinkDetector.youtubePlaylistId(trimmed)
                if (listId != null && LinkDetector.isYouTubePlaylistUrl(trimmed)) {
                    val url = "https://www.youtube.com/playlist?list=$listId"
                    AppLog.i("Queue", "YouTube playlist list=$listId")
                    val listing = ytDlp.listPlaylist(url)
                    if (listing.entries.isEmpty()) error("No se pudieron listar temas de la playlist")
                    val playlistName = listing.title?.takeIf { it.isNotBlank() } ?: "YouTube Playlist"
                    val playlist = library.createPlaylist(playlistName, url)
                    listing.entries.forEach { h ->
                        enqueue(
                            h.url,
                            meta {
                                put("title", h.title)
                                put("artist", h.uploader)
                                put("playlistId", playlist.id)
                                put("youtubeId", h.id)
                                put("durationMs", h.durationMs)
                                h.thumbnailUrl?.let { put("coverUrl", it) }
                            },
                        )
                    }
                    start()
                    return@withContext listing.entries.size to
                        "Playlist de YouTube \"$playlistName\": ${listing.entries.size} temas"
                }
                val vid = LinkDetector.youtubeVideoId(trimmed)
                val url = if (vid != null) "https://www.youtube.com/watch?v=$vid" else trimmed
                enqueue(
                    url,
                    meta {
                        if (vid != null) {
                            put("youtubeId", vid)
                            put("coverUrl", "https://i.ytimg.com/vi/$vid/hqdefault.jpg")
                        }
                    },
                )
                start()
                return@withContext 1 to "Video de YouTube encolado"
            }

            else -> {
                enqueue(trimmed, meta { put("title", trimmed); put("query", trimmed) })
                start()
                return@withContext 1 to "Búsqueda encolada: $trimmed"
            }
        }
    }

    private suspend fun enqueueSpotifyTrack(t: SpotifyTrackMeta) {
        enqueue(
            spotify.searchQuery(t),
            meta {
                put("title", t.name)
                put("artist", t.artists.joinToString(", "))
                put("albumName", t.albumName)
                put("spotifyId", t.id)
                put("durationMs", t.durationMs)
                t.coverUrl?.let { put("coverUrl", it) }
            },
        )
    }

    private fun meta(block: JSONObject.() -> Unit) = JSONObject().apply(block).toString()

    private suspend fun enqueue(urlOrQuery: String, metaJson: String, priority: Int = 0) {
        val now = System.currentTimeMillis()
        downloadDao.upsert(
            DownloadJobEntity(
                id = newId(),
                status = "queued",
                urlOrQuery = urlOrQuery,
                metaJson = metaJson,
                priority = priority,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun enqueueYtHit(hit: YtHit): Pair<Int, String> = withContext(Dispatchers.IO) {
        val settings = settingsRepo.get()
        if (!settings.isTelegramConfigured) {
            return@withContext 0 to "Configurá Telegram en Ajustes antes de descargar"
        }
        enqueue(
            hit.url,
            meta {
                put("title", hit.title)
                put("artist", hit.uploader)
                put("youtubeId", hit.id)
                put("durationMs", hit.durationMs)
                hit.thumbnailUrl?.let { put("coverUrl", it) }
            },
        )
        start()
        1 to "Encolado: ${hit.title}"
    }

    /**
     * Re-downloads an existing library track to local storage (offline).
     * Does not upload to Telegram; attaches the file to [trackId].
     */
    suspend fun enqueueLocalDownload(trackId: String): Pair<Int, String> =
        enqueueLocalDownloads(listOf(trackId))

    suspend fun enqueueLocalDownloads(trackIds: List<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (trackIds.isEmpty()) return@withContext 0 to "Nada para descargar"
        var enqueued = 0
        var skipped = 0
        for (trackId in trackIds.distinct()) {
            val track = library.getTrack(trackId) ?: continue
            val existingPath = track.path
            if (!existingPath.isNullOrBlank() && File(existingPath).exists() &&
                track.storageMode == TrackEntity.STORAGE_LOCAL
            ) {
                skipped++
                continue
            }
            val urlOrQuery = when {
                !track.sourceUrl.isNullOrBlank() &&
                    (LinkDetector.isYouTube(track.sourceUrl!!) || track.sourceUrl!!.startsWith("ytsearch")) ->
                    track.sourceUrl!!
                !track.youtubeId.isNullOrBlank() ->
                    "https://www.youtube.com/watch?v=${track.youtubeId}"
                else -> {
                    val artist = track.artistId?.let { library.getArtist(it)?.name }.orEmpty()
                    "${track.title} $artist".trim()
                }
            }
            enqueue(
                urlOrQuery = urlOrQuery,
                metaJson = meta {
                    put("targetStorage", "local")
                    put("existingTrackId", track.id)
                    put("title", track.title)
                    track.youtubeId?.let { put("youtubeId", it) }
                    track.spotifyId?.let { put("spotifyId", it) }
                    if (track.durationMs > 0) put("durationMs", track.durationMs)
                },
            )
            enqueued++
        }
        if (enqueued > 0) start()
        when {
            enqueued == 0 && skipped > 0 -> 0 to "Todos ya están locales"
            enqueued == 0 -> 0 to "Nada para descargar"
            enqueued == 1 -> 1 to "Descarga local encolada"
            else -> enqueued to "$enqueued temas encolados para descarga local"
        }
    }

    suspend fun retry(id: String) {
        downloadDao.update(id, "queued", 0f, null, System.currentTimeMillis())
        start()
    }

    /** Clears done + failed history (and cancelled). Leaves queued/running alone. */
    suspend fun clearHistory() = downloadDao.clearHistory()

    @Deprecated("Use clearHistory", ReplaceWith("clearHistory()"))
    suspend fun clearFinished() = clearHistory()

    private fun ensureForeground(text: String) {
        DownloadService.startOrUpdate(appContext, text)
    }

    private suspend fun refreshForeground() {
        val active = downloadDao.countActive()
        val text = when {
            active <= 0 -> "Finalizando…"
            active == 1 -> "1 tema pendiente"
            else -> "$active temas pendientes"
        }
        ensureForeground(text)
    }

    private suspend fun pump() {
        mutex.withLock {
            if (!running) return
            val settings = settingsRepo.get()
            val concurrency = settings.downloadConcurrency.coerceIn(1, 4)
            while (running && workers < concurrency) {
                val needed = concurrency - workers
                val queued = downloadDao.nextQueued(needed).filter { it.id !in inFlight }
                if (queued.isEmpty()) break
                ensureForeground("Descargando…")
                for (job in queued) {
                    val workerGeneration = resetGeneration
                    workers++
                    inFlight += job.id
                    scope.launch {
                        try {
                            process(job, workerGeneration)
                        } finally {
                            mutex.withLock {
                                workers--
                                inFlight -= job.id
                            }
                            if (running) pump()
                        }
                    }
                }
            }
            if (workers == 0 && downloadDao.nextQueued(1).isEmpty()) {
                running = false
                _status.value = "En espera"
                DownloadService.stop(appContext)
            } else if (running) {
                _status.value = "Procesando…"
                refreshForeground()
            }
        }
    }

    private suspend fun process(job: DownloadJobEntity, workerGeneration: Long) {
        val settings = settingsRepo.get()
        val meta = try {
            JSONObject(job.metaJson)
        } catch (_: Exception) {
            JSONObject()
        }
        AppLog.i("Queue", "start job=${job.id} ${job.urlOrQuery.take(80)}")
        val started = commitIfCurrent(workerGeneration) {
            downloadDao.update(job.id, "running", 1f, null, System.currentTimeMillis())
        }
        if (!started) {
            AppLog.i("Queue", "skipped stale job=${job.id} after reset")
            return
        }
        refreshForeground()
        try {
            var url = job.urlOrQuery
            if (!LinkDetector.isYouTube(url) && !url.startsWith("ytsearch")) {
                val title = meta.optString("title").ifBlank { url }
                val artist = meta.optString("artist")
                val query = "$title $artist".trim()
                AppLog.i("Queue", "job=${job.id} matching YouTube: $query")
                val hits = ytDlp.search(query, 6)
                val best = hits.firstOrNull() ?: error("Sin coincidencia en YouTube")
                url = best.url
                meta.put("youtubeId", best.id)
                if (!meta.has("coverUrl") && !best.thumbnailUrl.isNullOrBlank()) {
                    meta.put("coverUrl", best.thumbnailUrl)
                }
            }
            // Telegram/online path always uses m4a (YouTube isn't lossless; HLS re-encodes to AAC anyway).
            // Local attach still honors the FLAC setting.
            val existingTrackId = meta.optString("existingTrackId").ifBlank { null }
            val targetLocal = meta.optString("targetStorage") == "local" && existingTrackId != null
            val preferFlac = if (targetLocal) settings.preferFlac else false
            val result = ytDlp.downloadAudio(
                urlOrQuery = url,
                jobId = job.id,
                preferFlac = preferFlac,
                fallbackQuality = settings.fallbackQuality,
                onProgress = { p ->
                    scope.launch {
                        commitIfCurrent(workerGeneration) {
                            // 0–70% yt-dlp
                            downloadDao.update(
                                job.id,
                                "running",
                                (p * 0.70f).coerceAtMost(70f),
                                null,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                },
            )
            if (workerGeneration != resetGeneration) {
                result.file.delete()
                AppLog.i("Queue", "discarded stale job=${job.id} after reset")
                return
            }
            val youtubeId = meta.optString("youtubeId").ifBlank { null } ?: result.youtubeId
            val coverKey = meta.optString("spotifyId").ifBlank { null }
                ?: youtubeId
                ?: job.id

            if (!targetLocal && !settings.isTelegramConfigured) {
                result.file.delete()
                error("Configurá Telegram en Ajustes antes de descargar")
            }

            val coverFile = covers.obtain(
                key = coverKey,
                preferredUrl = meta.optString("coverUrl").ifBlank { null },
                youtubeId = youtubeId,
            )

            if (targetLocal) {
                processLocalAttach(
                    job = job,
                    workerGeneration = workerGeneration,
                    result = result,
                    existingTrackId = existingTrackId!!,
                )
            } else {
                processOnline(
                    job = job,
                    workerGeneration = workerGeneration,
                    result = result,
                    meta = meta,
                    url = url,
                    youtubeId = youtubeId,
                    coverPath = coverFile?.absolutePath,
                )
            }
        } catch (e: Exception) {
            AppLog.e("Queue", "failed job=${job.id}", e)
            commitIfCurrent(workerGeneration) {
                downloadDao.update(job.id, "failed", 0f, e.message, System.currentTimeMillis())
            }
        }
    }

    private suspend fun processLocalAttach(
        job: DownloadJobEntity,
        workerGeneration: Long,
        result: DownloadResult,
        existingTrackId: String,
    ) {
        val existing = library.getTrack(existingTrackId)
        val durationMs = when {
            result.durationMs > 0 -> result.durationMs
            (existing?.durationMs ?: 0L) > 0 -> existing!!.durationMs
            else -> 0L
        }
        val oldPath = existing?.path
        val committed = commitIfCurrent(workerGeneration) {
            library.attachLocalFile(
                trackId = existingTrackId,
                path = result.file.absolutePath,
                format = result.format,
                durationMs = durationMs,
            )
            downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
        }
        if (!committed) {
            result.file.delete()
            AppLog.i("Queue", "discarded stale local job=${job.id}")
            return
        }
        if (!oldPath.isNullOrBlank() && oldPath != result.file.absolutePath) {
            runCatching {
                val f = File(oldPath)
                if (f.exists()) f.delete()
            }
        }
        AppLog.i("Queue", "local attach job=${job.id} → track=$existingTrackId ${result.file.name}")
    }

    private suspend fun processOnline(
        job: DownloadJobEntity,
        workerGeneration: Long,
        result: DownloadResult,
        meta: JSONObject,
        url: String,
        youtubeId: String?,
        coverPath: String?,
    ) {
        val (client, chatId) = telegramConfig.requireClient()
        commitIfCurrent(workerGeneration) {
            downloadDao.update(job.id, "running", 72f, null, System.currentTimeMillis())
        }
        val packed = hlsPackager.packageAudio(
            input = result.file,
            jobId = job.id,
            durationMsHint = when {
                result.durationMs > 0 -> result.durationMs
                meta.optLong("durationMs") > 0 -> meta.optLong("durationMs")
                else -> 0L
            },
            segmentSeconds = 45,
        )
        try {
            if (workerGeneration != resetGeneration) {
                result.file.delete()
                packed.deleteQuietly()
                AppLog.i("Queue", "discarded stale online job=${job.id}")
                return
            }
            commitIfCurrent(workerGeneration) {
                downloadDao.update(job.id, "running", 85f, null, System.currentTimeMillis())
            }
            val uploaded = mutableListOf<TrackSegmentEntity>()
            val total = packed.segments.size.coerceAtLeast(1)
            for (seg in packed.segments) {
                if (workerGeneration != resetGeneration) {
                    result.file.delete()
                    packed.deleteQuietly()
                    AppLog.i("Queue", "discarded stale online job=${job.id} mid-upload")
                    return
                }
                val ext = seg.file.extension.ifBlank { if (packed.progressive) result.format else "ts" }
                val ref = client.sendDocument(
                    chatId = chatId,
                    file = seg.file,
                    caption = "mm:${job.id}:${seg.index}",
                    fileName = "seg_${seg.index.toString().padStart(5, '0')}.$ext",
                )
                uploaded += TrackSegmentEntity(
                    trackId = "",
                    segmentIndex = seg.index,
                    telegramFileId = ref.fileId,
                    durationSec = seg.durationSec,
                    byteSize = ref.fileSize ?: seg.file.length(),
                )
                val progress = 85f + (15f * (seg.index + 1) / total)
                commitIfCurrent(workerGeneration) {
                    downloadDao.update(
                        job.id,
                        "running",
                        progress.coerceAtMost(99f),
                        null,
                        System.currentTimeMillis(),
                    )
                }
                // Pace uploads to reduce Telegram flood waits (still retries on 429).
                if (seg.index + 1 < total) {
                    delay(UPLOAD_GAP_MS)
                }
            }
            val trackFormat = if (packed.progressive) result.format else "hls"
            val committed = commitIfCurrent(workerGeneration) {
                library.insertDownloadedTrack(
                    title = meta.optString("title").ifBlank { result.title },
                    artistName = meta.optString("artist").ifBlank { null } ?: result.artist,
                    albumName = meta.optString("albumName").ifBlank { null },
                    albumId = meta.optString("albumId").ifBlank { null },
                    playlistId = meta.optString("playlistId").ifBlank { null },
                    path = null,
                    format = trackFormat,
                    durationMs = when {
                        result.durationMs > 0 -> result.durationMs
                        meta.optLong("durationMs") > 0 -> meta.optLong("durationMs")
                        else -> (uploaded.sumOf { it.durationSec } * 1000).toLong()
                    },
                    sourceUrl = url,
                    sourceType = if (meta.has("spotifyId")) "spotify" else "youtube",
                    spotifyId = meta.optString("spotifyId").ifBlank { null },
                    youtubeId = youtubeId,
                    genre = null,
                    coverPath = coverPath,
                    storageMode = TrackEntity.STORAGE_TELEGRAM,
                    segments = uploaded,
                )
                downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
            }
            if (!committed) {
                AppLog.i("Queue", "discarded stale online job=${job.id} at insert")
                return
            }
            AppLog.i(
                "Queue",
                "online job=${job.id} → ${uploaded.size} file(s) " +
                    "(${if (packed.progressive) "progressive" else "hls"}), local wiped",
            )
        } finally {
            packed.deleteQuietly()
            runCatching { result.file.delete() }
        }
    }

    private suspend fun commitIfCurrent(
        workerGeneration: Long,
        block: suspend () -> Unit,
    ): Boolean = commitMutex.withLock {
        if (workerGeneration != resetGeneration) return@withLock false
        block()
        true
    }

    companion object {
        /** Pause between Telegram segment uploads to reduce flood control. */
        private const val UPLOAD_GAP_MS = 400L
        private const val PREFS_NAME = "melomaniac_downloads"
        private const val PREF_QUEUE_PAUSED = "queue_paused"
    }
}
