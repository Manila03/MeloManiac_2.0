package com.melomaniac.app.download

import com.melomaniac.app.data.DownloadDao
import com.melomaniac.app.data.DownloadJobEntity
import com.melomaniac.app.data.LibraryRepository
import com.melomaniac.app.data.SettingsRepository
import com.melomaniac.app.util.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class DownloadQueue(
    private val downloadDao: DownloadDao,
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val ytDlp: YtDlpRunner,
    private val spotify: SpotifyApi,
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
    }

    suspend fun enqueueFromUserInput(input: String): Pair<Int, String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return 0 to "Entrada vacía"
        val settings = settingsRepo.get()

        when {
            LinkDetector.isSpotify(trimmed) -> {
                when (val resolved = spotify.resolve(trimmed, settings.spotifyClientId, settings.spotifyClientSecret)) {
                    is SpotifyResolve.Track -> {
                        enqueueSpotifyTrack(resolved.track)
                        start()
                        return 1 to "Encolado: ${resolved.track.name}"
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
                                    },
                                )
                            }
                            start()
                            return c.tracks.size to "Álbum \"${c.name}\": ${c.tracks.size} temas"
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
                                    },
                                )
                            }
                            start()
                            return c.tracks.size to "Playlist \"${c.name}\": ${c.tracks.size} temas"
                        }
                    }
                }
            }

            LinkDetector.isYouTube(trimmed) -> {
                val listId = LinkDetector.youtubePlaylistId(trimmed)
                if (listId != null && trimmed.contains("/playlist")) {
                    val url = "https://www.youtube.com/playlist?list=$listId"
                    val hits = ytDlp.listPlaylist(url)
                    val playlist = library.createPlaylist("YouTube Playlist $listId", url)
                    if (hits.isEmpty()) error("No se pudieron listar temas de la playlist")
                    hits.forEach { h ->
                        enqueue(
                            h.url,
                            meta {
                                put("title", h.title)
                                put("artist", h.uploader)
                                put("playlistId", playlist.id)
                                put("youtubeId", h.id)
                                put("durationMs", h.durationMs)
                            },
                        )
                    }
                    start()
                    return hits.size to "Playlist de YouTube: ${hits.size} temas"
                }
                val vid = LinkDetector.youtubeVideoId(trimmed)
                val url = if (vid != null) "https://www.youtube.com/watch?v=$vid" else trimmed
                enqueue(url, meta { if (vid != null) put("youtubeId", vid) })
                start()
                return 1 to "Video de YouTube encolado"
            }

            else -> {
                enqueue(trimmed, meta { put("title", trimmed); put("query", trimmed) })
                start()
                return 1 to "Búsqueda encolada: $trimmed"
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

    suspend fun retry(id: String) {
        downloadDao.update(id, "queued", 0f, null, System.currentTimeMillis())
        start()
    }

    suspend fun clearFinished() = downloadDao.clearFinished()

    private suspend fun pump() {
        mutex.withLock {
            if (!running) return
            val settings = settingsRepo.get()
            val concurrency = settings.downloadConcurrency.coerceIn(1, 4)
            while (running && workers < concurrency) {
                val needed = concurrency - workers
                val queued = downloadDao.nextQueued(needed).filter { it.id !in inFlight }
                if (queued.isEmpty()) break
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
            } else if (running) {
                _status.value = "Procesando…"
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
        downloadDao.update(job.id, "running", 1f, null, System.currentTimeMillis())
        try {
            var url = job.urlOrQuery
            if (!LinkDetector.isYouTube(url) && !url.startsWith("ytsearch")) {
                val title = meta.optString("title").ifBlank { url }
                val artist = meta.optString("artist")
                val query = "$title $artist".trim()
                val hits = ytDlp.search(query, 6)
                val best = hits.firstOrNull() ?: error("Sin coincidencia en YouTube")
                url = best.url
                meta.put("youtubeId", best.id)
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
                youtubeId = meta.optString("youtubeId").ifBlank { null } ?: result.youtubeId,
                genre = null,
            )
            downloadDao.update(job.id, "done", 100f, null, System.currentTimeMillis())
        } catch (e: Exception) {
            downloadDao.update(job.id, "failed", 0f, e.message, System.currentTimeMillis())
        }
    }
}
