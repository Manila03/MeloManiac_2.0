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
    private val telegramUploadMutex = Mutex()
    private var workers = 0
    private var running = false
    @Volatile private var resetGeneration = 0L
    private val inFlight = mutableSetOf<String>()
    private val cancelledIds = mutableSetOf<String>()
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val _status = MutableStateFlow(
        if (prefs.getBoolean(PREF_QUEUE_PAUSED, false)) "Pausado" else "En espera",
    )
    val status: StateFlow<String> = _status

    fun isPaused(): Boolean = prefs.getBoolean(PREF_QUEUE_PAUSED, false)

    /**
     * Resumes the queue and drains pending jobs.
     * [running] is toggled under [mutex] together with spawn logic so an idle
     * [pump] cannot clear [running] between "start" and the first spawn (that
     * race left jobs stuck in `queued` and the library empty).
     */
    fun start() {
        setPaused(false)
        scope.launch {
            downloadDao.resetStuck(System.currentTimeMillis())
            mutex.withLock {
                running = true
                _status.value = "Procesando…"
                pumpLocked()
            }
        }
    }

    /** Re-queues rows left as `running` after process death without starting the queue. */
    fun recoverStuckJobs() {
        scope.launch {
            downloadDao.resetStuck(System.currentTimeMillis())
        }
    }

    suspend fun stop() {
        mutex.withLock {
            running = false
            setPaused(true)
            _status.value = "Pausado"
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        // Invalidate workers under commitMutex so an in-flight insert either
        // fully commits or fully aborts — never tears across a pause.
        commitMutex.withLock {
            resetGeneration++
            AppLog.i("Queue", "Pausado generation=$resetGeneration")
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
            setPaused(true)
            _status.value = "Pausado"
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        commitMutex.withLock {
            resetGeneration++
            AppLog.i("Queue", "Vaciar cola generation=$resetGeneration")
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
            setPaused(false)
            _status.value = "En espera"
            ytDlp.destroyAll()
            DownloadService.stop(appContext)
        }
        commitMutex.withLock {
            resetGeneration++
            AppLog.i("Queue", "Reset generation=$resetGeneration")
            wipe()
        }
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
                        val outcome = enqueueSpotifyTrack(resolved.track)
                        return@withContext when (outcome) {
                            EnqueueOutcome.ENQUEUED -> {
                                start()
                                1 to "Encolado: ${resolved.track.name}"
                            }
                            EnqueueOutcome.LINKED_EXISTING ->
                                0 to "Ya está en la biblioteca: ${resolved.track.name}"
                            EnqueueOutcome.MERGED_ACTIVE ->
                                0 to "Ya está en la cola: ${resolved.track.name}"
                        }
                    }
                    is SpotifyResolve.Collection -> {
                        val c = resolved.collection
                        if (c.type == "album") {
                            val artist = library.getOrCreateArtist(c.tracks.firstOrNull()?.artists?.firstOrNull() ?: "Various Artists")
                            val album = library.getOrCreateAlbum(c.name, artist.id, c.externalUrl)
                            var enqueued = 0
                            var linked = 0
                            c.tracks.forEach { t ->
                                when (
                                    enqueueDeduped(
                                        urlOrQuery = spotify.searchQuery(t),
                                        youtubeId = null,
                                        spotifyId = t.id,
                                        playlistId = null,
                                        metaJson = meta {
                                            put("title", t.name)
                                            put("artist", t.artists.joinToString(", "))
                                            put("albumName", c.name)
                                            put("albumId", album.id)
                                            put("spotifyId", t.id)
                                            put("durationMs", t.durationMs)
                                            (t.coverUrl ?: c.coverUrl)?.let { put("coverUrl", it) }
                                        },
                                    )
                                ) {
                                    EnqueueOutcome.ENQUEUED -> enqueued++
                                    EnqueueOutcome.LINKED_EXISTING,
                                    EnqueueOutcome.MERGED_ACTIVE,
                                    -> linked++
                                }
                            }
                            if (enqueued > 0) start()
                            val partial = if (c.tracksMayBePartial) {
                                " (parcial: solo ${c.tracks.size} temas del embed)"
                            } else {
                                ""
                            }
                            return@withContext (enqueued + linked) to
                                "Álbum Spotify \"${c.name}\": $enqueued nuevos, $linked ya en biblioteca/cola$partial"
                        } else {
                            val playlist = library.createPlaylist(c.name, c.externalUrl)
                            var enqueued = 0
                            var linked = 0
                            c.tracks.forEach { t ->
                                when (
                                    enqueueDeduped(
                                        urlOrQuery = spotify.searchQuery(t),
                                        youtubeId = null,
                                        spotifyId = t.id,
                                        playlistId = playlist.id,
                                        metaJson = meta {
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
                                ) {
                                    EnqueueOutcome.ENQUEUED -> enqueued++
                                    EnqueueOutcome.LINKED_EXISTING,
                                    EnqueueOutcome.MERGED_ACTIVE,
                                    -> linked++
                                }
                            }
                            if (enqueued > 0) start()
                            val partial = if (c.tracksMayBePartial) {
                                " (parcial: solo ${c.tracks.size} temas del embed)"
                            } else {
                                ""
                            }
                            return@withContext (enqueued + linked) to
                                "Playlist Spotify \"${c.name}\": $enqueued nuevos, $linked ya en biblioteca/cola$partial"
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
                    var enqueued = 0
                    var linked = 0
                    listing.entries.forEach { h ->
                        when (
                            enqueueDeduped(
                                urlOrQuery = h.url,
                                youtubeId = h.id,
                                spotifyId = null,
                                playlistId = playlist.id,
                                metaJson = meta {
                                    put("title", h.title)
                                    put("artist", h.uploader)
                                    put("playlistId", playlist.id)
                                    put("youtubeId", h.id)
                                    put("durationMs", h.durationMs)
                                    h.thumbnailUrl?.let { put("coverUrl", it) }
                                },
                            )
                        ) {
                            EnqueueOutcome.ENQUEUED -> enqueued++
                            EnqueueOutcome.LINKED_EXISTING,
                            EnqueueOutcome.MERGED_ACTIVE,
                            -> linked++
                        }
                    }
                    if (enqueued > 0) start()
                    return@withContext (enqueued + linked) to
                        "Playlist de YouTube \"$playlistName\": $enqueued nuevos, $linked ya en biblioteca/cola"
                }
                val vid = LinkDetector.youtubeVideoId(trimmed)
                val url = if (vid != null) "https://www.youtube.com/watch?v=$vid" else trimmed
                when (
                    enqueueDeduped(
                        urlOrQuery = url,
                        youtubeId = vid,
                        spotifyId = null,
                        playlistId = null,
                        metaJson = meta {
                            if (vid != null) {
                                put("youtubeId", vid)
                                put("coverUrl", "https://i.ytimg.com/vi/$vid/hqdefault.jpg")
                            }
                        },
                    )
                ) {
                    EnqueueOutcome.LINKED_EXISTING ->
                        return@withContext 0 to "Ya está en la biblioteca"
                    EnqueueOutcome.MERGED_ACTIVE ->
                        return@withContext 0 to "Ya está en la cola de descargas"
                    EnqueueOutcome.ENQUEUED -> {
                        start()
                        return@withContext 1 to "Video de YouTube encolado"
                    }
                }
            }

            else -> {
                enqueue(trimmed, meta { put("title", trimmed); put("query", trimmed) })
                start()
                return@withContext 1 to "Búsqueda encolada: $trimmed"
            }
        }
    }

    private suspend fun enqueueSpotifyTrack(t: SpotifyTrackMeta): EnqueueOutcome =
        enqueueDeduped(
            urlOrQuery = spotify.searchQuery(t),
            youtubeId = null,
            spotifyId = t.id,
            playlistId = null,
            metaJson = meta {
                put("title", t.name)
                put("artist", t.artists.joinToString(", "))
                put("albumName", t.albumName)
                put("spotifyId", t.id)
                put("durationMs", t.durationMs)
                t.coverUrl?.let { put("coverUrl", it) }
            },
        )

    private fun meta(block: JSONObject.() -> Unit) = JSONObject().apply(block).toString()

    private enum class EnqueueOutcome { ENQUEUED, LINKED_EXISTING, MERGED_ACTIVE }

    /**
     * Skips download when the song already exists (by youtubeId/spotifyId) or is
     * already queued/running. For playlists, links the existing track or merges
     * the playlist id into the active job meta so one download feeds both playlists.
     */
    private suspend fun enqueueDeduped(
        urlOrQuery: String,
        youtubeId: String?,
        spotifyId: String?,
        playlistId: String?,
        metaJson: String,
        priority: Int = 0,
    ): EnqueueOutcome {
        val existing = library.findTrackByIdentity(youtubeId, spotifyId)
        if (existing != null) {
            if (playlistId != null) {
                library.addTrackToPlaylist(playlistId, existing.id)
                existing.coverPath?.let { library.setPlaylistCoverIfEmpty(playlistId, it) }
                AppLog.i("Queue", "dedup link track=${existing.id} → playlist=$playlistId")
            } else {
                AppLog.i("Queue", "dedup skip existing track=${existing.id}")
            }
            return EnqueueOutcome.LINKED_EXISTING
        }

        val active = findActiveJob(youtubeId, spotifyId)
        if (active != null) {
            if (playlistId != null) {
                appendPlaylistIdToJob(active, playlistId)
                AppLog.i("Queue", "dedup merge playlist=$playlistId into job=${active.id}")
            } else {
                AppLog.i("Queue", "dedup skip active job=${active.id}")
            }
            return EnqueueOutcome.MERGED_ACTIVE
        }

        enqueue(urlOrQuery, metaJson, priority)
        return EnqueueOutcome.ENQUEUED
    }

    private suspend fun findActiveJob(youtubeId: String?, spotifyId: String?): DownloadJobEntity? {
        val yt = youtubeId?.trim()?.takeIf { it.isNotEmpty() }
        val sp = spotifyId?.trim()?.takeIf { it.isNotEmpty() }
        if (yt == null && sp == null) return null
        for (job in downloadDao.listActive()) {
            val meta = try {
                JSONObject(job.metaJson)
            } catch (_: Exception) {
                continue
            }
            if (yt != null && meta.optString("youtubeId") == yt) return job
            if (sp != null && meta.optString("spotifyId") == sp) return job
        }
        return null
    }

    private suspend fun appendPlaylistIdToJob(job: DownloadJobEntity, playlistId: String) {
        val meta = try {
            JSONObject(job.metaJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val ids = linkedPlaylistIds(meta).toMutableList()
        if (playlistId !in ids) ids += playlistId
        meta.put("playlistId", playlistId)
        val arr = org.json.JSONArray()
        ids.forEach { arr.put(it) }
        meta.put("playlistIds", arr)
        downloadDao.upsert(
            job.copy(metaJson = meta.toString(), updatedAt = System.currentTimeMillis()),
        )
    }

    private fun linkedPlaylistIds(meta: JSONObject): List<String> {
        val out = linkedSetOf<String>()
        meta.optString("playlistId").trim().takeIf { it.isNotEmpty() }?.let { out += it }
        val arr = meta.optJSONArray("playlistIds")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out.toList()
    }

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
        when (
            enqueueDeduped(
                urlOrQuery = hit.url,
                youtubeId = hit.id,
                spotifyId = null,
                playlistId = null,
                metaJson = meta {
                    put("title", hit.title)
                    put("artist", hit.uploader)
                    put("youtubeId", hit.id)
                    put("durationMs", hit.durationMs)
                    hit.thumbnailUrl?.let { put("coverUrl", it) }
                },
            )
        ) {
            EnqueueOutcome.LINKED_EXISTING -> 0 to "Ya está en la biblioteca: ${hit.title}"
            EnqueueOutcome.MERGED_ACTIVE -> 0 to "Ya está en la cola: ${hit.title}"
            EnqueueOutcome.ENQUEUED -> {
                start()
                1 to "Encolado: ${hit.title}"
            }
        }
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
        mutex.withLock { pumpLocked() }
    }

    /** Caller must hold [mutex]. */
    private suspend fun pumpLocked() {
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
                            if (running) pumpLocked()
                        }
                    }
                }
            }
        }
        if (workers == 0 && downloadDao.nextQueued(1).isEmpty()) {
            running = false
            _status.value = if (isPaused()) "Pausado" else "En espera"
            DownloadService.stop(appContext)
        } else if (running) {
            _status.value = "Procesando…"
            refreshForeground()
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
            val existingTrackId = meta.optString("existingTrackId").ifBlank { null }
            val targetLocal = meta.optString("targetStorage") == "local" && existingTrackId != null
            if (!LinkDetector.isYouTube(url) && !url.startsWith("ytsearch")) {
                val title = meta.optString("title").ifBlank { url }
                val artist = meta.optString("artist")
                val query = "$title $artist".trim()
                AppLog.i("Queue", "job=${job.id} matching YouTube: $query")
                val hits = ytDlp.search(query, 6)
                val wantDuration = meta.optLong("durationMs")
                val best = YtMatchScorer.pickBest(
                    hits = hits,
                    title = title,
                    artist = artist,
                    durationMs = wantDuration,
                )
                url = best.url
                meta.put("youtubeId", best.id)
                if (!meta.has("coverUrl") && !best.thumbnailUrl.isNullOrBlank()) {
                    meta.put("coverUrl", best.thumbnailUrl)
                }
                // Mid-resolve dedup: if this YouTube id already exists, link and finish.
                val existingByYt = library.findTrackByIdentity(
                    best.id,
                    meta.optString("spotifyId").ifBlank { null },
                )
                if (existingByYt != null && !targetLocal) {
                    val playlistIds = linkedPlaylistIds(meta)
                    for (pid in playlistIds) library.addTrackToPlaylist(pid, existingByYt.id)
                    commitIfCurrent(workerGeneration) {
                        downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
                    }
                    AppLog.i("Queue", "job=${job.id} dedup after YT match → ${existingByYt.id}")
                    return
                }
            }
            // Telegram/online path always uses m4a unless preferLocalStorage (then honor FLAC).
            val preferLocal = settings.preferLocalStorage && !targetLocal
            val preferFlac = if (targetLocal || preferLocal) settings.preferFlac else false
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
            if (workerGeneration != resetGeneration || isCancelled(job.id)) {
                result.file.delete()
                AppLog.i("Queue", "discarded stale/cancelled job=${job.id} after reset")
                return
            }
            val youtubeId = meta.optString("youtubeId").ifBlank { null } ?: result.youtubeId
            val coverKey = meta.optString("spotifyId").ifBlank { null }
                ?: youtubeId
                ?: job.id

            if (!targetLocal && !preferLocal && !settings.isTelegramConfigured) {
                result.file.delete()
                error("Configurá Telegram en Ajustes antes de descargar")
            }

            val coverFile = covers.obtain(
                key = coverKey,
                preferredUrl = meta.optString("coverUrl").ifBlank { null },
                youtubeId = youtubeId,
            )

            when {
                targetLocal -> processLocalAttach(
                    job = job,
                    workerGeneration = workerGeneration,
                    result = result,
                    existingTrackId = existingTrackId!!,
                )
                preferLocal -> processLocalNew(
                    job = job,
                    workerGeneration = workerGeneration,
                    result = result,
                    meta = meta,
                    url = url,
                    youtubeId = youtubeId,
                    coverPath = coverFile?.absolutePath,
                )
                else -> processOnline(
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
            if (isCancelled(job.id)) {
                commitIfCurrent(workerGeneration) {
                    downloadDao.update(job.id, "cancelled", 0f, "Cancelado", System.currentTimeMillis())
                }
                clearCancelled(job.id)
                return
            }
            val attempts = meta.optInt("attempts", 0) + 1
            meta.put("attempts", attempts)
            val transient = isTransientError(e)
            if (transient && attempts < MAX_ATTEMPTS) {
                val waitMs = (1_000L * (1 shl (attempts - 1).coerceAtMost(4)))
                AppLog.w("Queue", "job=${job.id} transient fail attempt=$attempts; retry in ${waitMs}ms")
                commitIfCurrent(workerGeneration) {
                    downloadDao.updateMeta(job.id, meta.toString(), System.currentTimeMillis())
                    downloadDao.update(
                        job.id,
                        "queued",
                        0f,
                        "Reintento $attempts/$MAX_ATTEMPTS: ${e.message}",
                        System.currentTimeMillis(),
                    )
                }
                delay(waitMs)
                start()
            } else {
                commitIfCurrent(workerGeneration) {
                    downloadDao.update(job.id, "failed", 0f, e.message, System.currentTimeMillis())
                }
            }
        }
    }

    private fun isTransientError(e: Exception): Boolean {
        val msg = (e.message ?: "").lowercase()
        return msg.contains("429") ||
            msg.contains("403") ||
            msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("unable to connect") ||
            msg.contains("network") ||
            msg.contains("connection") ||
            msg.contains("temporarily") ||
            msg.contains("http 5")
    }

    private fun isCancelled(id: String): Boolean = synchronized(cancelledIds) { id in cancelledIds }

    private fun clearCancelled(id: String) {
        synchronized(cancelledIds) { cancelledIds.remove(id) }
    }

    suspend fun cancel(id: String) {
        synchronized(cancelledIds) { cancelledIds += id }
        mutex.withLock { inFlight.remove(id) }
        downloadDao.update(id, "cancelled", 0f, "Cancelado", System.currentTimeMillis())
        AppLog.i("Queue", "cancelled job=$id")
    }

    private suspend fun processLocalNew(
        job: DownloadJobEntity,
        workerGeneration: Long,
        result: DownloadResult,
        meta: JSONObject,
        url: String,
        youtubeId: String?,
        coverPath: String?,
    ) {
        val playlistIds = linkedPlaylistIds(meta)
        val committed = commitIfCurrent(workerGeneration) {
            library.insertDownloadedTrack(
                title = meta.optString("title").ifBlank { result.title },
                artistName = meta.optString("artist").ifBlank { null } ?: result.artist,
                albumName = meta.optString("albumName").ifBlank { null },
                albumId = meta.optString("albumId").ifBlank { null },
                playlistId = playlistIds.firstOrNull(),
                path = result.file.absolutePath,
                format = result.format,
                durationMs = when {
                    result.durationMs > 0 -> result.durationMs
                    meta.optLong("durationMs") > 0 -> meta.optLong("durationMs")
                    else -> 0L
                },
                sourceUrl = url,
                sourceType = if (meta.has("spotifyId")) "spotify" else "youtube",
                spotifyId = meta.optString("spotifyId").ifBlank { null },
                youtubeId = youtubeId,
                genre = null,
                coverPath = coverPath,
                storageMode = TrackEntity.STORAGE_LOCAL,
                playlistIds = playlistIds,
            )
            meta.remove("uploadedSegments")
            downloadDao.updateMeta(job.id, meta.toString(), System.currentTimeMillis())
            downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
        }
        if (!committed) {
            result.file.delete()
            AppLog.i("Queue", "discarded stale local-new job=${job.id}")
            return
        }
        AppLog.i("Queue", "local-new job=${job.id} → ${result.file.name}")
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
            val uploaded = loadUploadedCheckpoint(meta).toMutableList()
            val doneIndexes = uploaded.map { it.segmentIndex }.toHashSet()
            val total = packed.segments.size.coerceAtLeast(1)
            for (seg in packed.segments) {
                if (seg.index in doneIndexes) continue
                if (workerGeneration != resetGeneration || isCancelled(job.id)) {
                    result.file.delete()
                    packed.deleteQuietly()
                    AppLog.i("Queue", "discarded stale/cancelled online job=${job.id} mid-upload")
                    return
                }
                telegramUploadMutex.withLock {
                    val ext = seg.file.extension.ifBlank { if (packed.progressive) result.format else "ts" }
                    val ref = client.sendDocument(
                        chatId = chatId,
                        file = seg.file,
                        caption = "mm:${job.id}:${seg.index}",
                        fileName = "seg_${seg.index.toString().padStart(5, '0')}.$ext",
                    )
                    val entity = TrackSegmentEntity(
                        trackId = "",
                        segmentIndex = seg.index,
                        telegramFileId = ref.fileId,
                        durationSec = seg.durationSec,
                        byteSize = ref.fileSize ?: seg.file.length(),
                    )
                    uploaded += entity
                    doneIndexes += seg.index
                    saveUploadedCheckpoint(job.id, meta, uploaded)
                    delay(UPLOAD_GAP_MS)
                }
                val progress = 85f + (15f * (doneIndexes.size) / total)
                commitIfCurrent(workerGeneration) {
                    downloadDao.update(
                        job.id,
                        "running",
                        progress.coerceAtMost(99f),
                        null,
                        System.currentTimeMillis(),
                    )
                }
            }
            val trackFormat = if (packed.progressive) result.format else "hls"
            val playlistIds = linkedPlaylistIds(meta)
            val committed = commitIfCurrent(workerGeneration) {
                library.insertDownloadedTrack(
                    title = meta.optString("title").ifBlank { result.title },
                    artistName = meta.optString("artist").ifBlank { null } ?: result.artist,
                    albumName = meta.optString("albumName").ifBlank { null },
                    albumId = meta.optString("albumId").ifBlank { null },
                    playlistId = playlistIds.firstOrNull(),
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
                    segments = uploaded.sortedBy { it.segmentIndex },
                    playlistIds = playlistIds,
                )
                meta.remove("uploadedSegments")
                downloadDao.updateMeta(job.id, meta.toString(), System.currentTimeMillis())
                downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
            }
            if (!committed) {
                // Ensure the job is not left as `running` forever if pause/clear raced the insert.
                runCatching {
                    downloadDao.update(job.id, "queued", 0f, null, System.currentTimeMillis())
                }
                AppLog.i("Queue", "discarded stale online job=${job.id} at insert → re-queued")
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

    private fun loadUploadedCheckpoint(meta: JSONObject): List<TrackSegmentEntity> {
        val arr = meta.optJSONArray("uploadedSegments") ?: return emptyList()
        val out = ArrayList<TrackSegmentEntity>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += TrackSegmentEntity(
                trackId = "",
                segmentIndex = o.optInt("index"),
                telegramFileId = o.optString("fileId"),
                durationSec = o.optDouble("durationSec"),
                byteSize = if (o.has("byteSize")) o.optLong("byteSize") else null,
            )
        }
        return out.filter { it.telegramFileId.isNotBlank() }
    }

    private suspend fun saveUploadedCheckpoint(
        jobId: String,
        meta: JSONObject,
        uploaded: List<TrackSegmentEntity>,
    ) {
        val arr = org.json.JSONArray()
        for (s in uploaded) {
            arr.put(
                JSONObject()
                    .put("index", s.segmentIndex)
                    .put("fileId", s.telegramFileId)
                    .put("durationSec", s.durationSec)
                    .put("byteSize", s.byteSize),
            )
        }
        meta.put("uploadedSegments", arr)
        downloadDao.updateMeta(jobId, meta.toString(), System.currentTimeMillis())
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
        private const val MAX_ATTEMPTS = 3
        private const val PREFS_NAME = "melomaniac_downloads"
        private const val PREF_QUEUE_PAUSED = "queue_paused"
    }
}
