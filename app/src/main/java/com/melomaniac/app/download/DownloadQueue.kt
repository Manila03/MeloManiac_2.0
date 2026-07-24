package com.melomaniac.app.download

import android.content.Context
import com.melomaniac.app.data.DownloadDao
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.LibraryRepository
import com.melomaniac.app.data.SettingsRepository
import com.melomaniac.app.util.AppLog
import com.melomaniac.app.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadQueue(
    private val appContext: Context,
    private val downloadDao: DownloadDao,
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val ytDlp: YtDlpRunner,
    private val spotify: SpotifyScraper,
    private val covers: CoverStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var workers = 0
    private var running = false
    private val inFlight = mutableSetOf<String>()

    private val _status = MutableStateFlow("En espera")
    val status: StateFlow<String> = _status

    fun start() {
        running = true
        scope.launch {
            downloadDao.resetStuck(System.currentTimeMillis())
            pump()
        }
    }

    fun stop() {
        running = false
        _status.value = "Pausado"
        AppLog.i("Queue", "Pausado")
        DownloadService.stop(appContext)
    }

    suspend fun enqueueFromUserInput(input: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return@withContext 0 to "Entrada vacía"
        AppLog.i("Queue", "enqueue input: ${trimmed.take(120)}")

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
                    val hits = ytDlp.listPlaylist(url)
                    if (hits.isEmpty()) error("No se pudieron listar temas de la playlist")
                    val playlist = library.createPlaylist("YouTube Playlist", url)
                    hits.forEach { h ->
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
                    return@withContext hits.size to "Playlist de YouTube: ${hits.size} temas"
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
                    workers++
                    inFlight += job.id
                    scope.launch {
                        try {
                            process(job)
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

    private suspend fun process(job: DownloadJobEntity) {
        val settings = settingsRepo.get()
        val meta = try {
            JSONObject(job.metaJson)
        } catch (_: Exception) {
            JSONObject()
        }
        AppLog.i("Queue", "start job=${job.id} ${job.urlOrQuery.take(80)}")
        downloadDao.update(job.id, "running", 1f, null, System.currentTimeMillis())
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
            val result = ytDlp.downloadAudio(
                urlOrQuery = url,
                jobId = job.id,
                preferFlac = settings.preferFlac,
                fallbackQuality = settings.fallbackQuality,
                onProgress = { p ->
                    scope.launch {
                        downloadDao.update(job.id, "running", p.coerceAtMost(99f), null, System.currentTimeMillis())
                    }
                },
            )
            val youtubeId = meta.optString("youtubeId").ifBlank { null } ?: result.youtubeId
            val coverKey = meta.optString("spotifyId").ifBlank { null }
                ?: youtubeId
                ?: job.id
            val coverFile = covers.obtain(
                key = coverKey,
                preferredUrl = meta.optString("coverUrl").ifBlank { null },
                youtubeId = youtubeId,
            )
            library.insertDownloadedTrack(
                title = meta.optString("title").ifBlank { result.title },
                artistName = meta.optString("artist").ifBlank { null } ?: result.artist,
                albumName = meta.optString("albumName").ifBlank { null },
                albumId = meta.optString("albumId").ifBlank { null },
                playlistId = meta.optString("playlistId").ifBlank { null },
                path = result.file.absolutePath,
                format = result.format,
                durationMs = if (meta.optLong("durationMs") > 0) meta.optLong("durationMs") else result.durationMs,
                sourceUrl = url,
                sourceType = if (meta.has("spotifyId")) "spotify" else "youtube",
                spotifyId = meta.optString("spotifyId").ifBlank { null },
                youtubeId = youtubeId,
                genre = null,
                coverPath = coverFile?.absolutePath,
            )
            downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
            AppLog.i("Queue", "done job=${job.id} → ${result.file.name}")
        } catch (e: Exception) {
            AppLog.e("Queue", "failed job=${job.id}", e)
            downloadDao.update(job.id, "failed", 0f, e.message, System.currentTimeMillis())
        }
    }
}
