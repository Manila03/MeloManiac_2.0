package com.melomaniac.app.data

import com.melomaniac.app.util.newId
import kotlinx.coroutines.flow.Flow

class LibraryRepository(private val dao: LibraryDao) {
    fun observeTracks() = dao.observeTracks()
    fun observeTracksByAdded() = dao.observeTracksByAdded()
    fun observeArtists() = dao.observeArtists()
    fun observeAlbums() = dao.observeAlbums()
    fun observePlaylists() = dao.observePlaylists()
    fun observeGenres() = dao.observeGenres()
    fun observeFolders() = dao.observeFolders()
    fun observeFavorites() = dao.observeFavorites()
    fun observeRecent() = dao.observeRecent()
    fun observeTrackCount() = dao.observeTrackCount()
    fun observeTracksByArtist(id: String) = dao.observeTracksByArtist(id)
    fun observeTracksByAlbum(id: String) = dao.observeTracksByAlbum(id)
    fun observeTracksByPlaylist(id: String) = dao.observeTracksByPlaylist(id)
    fun observeTracksByGenre(id: String) = dao.observeTracksByGenre(id)
    fun observeTracksByFolder(id: String) = dao.observeTracksByFolder(id)

    suspend fun search(q: String) = dao.searchTracks(q.trim())

    suspend fun getOrCreateArtist(name: String): ArtistEntity {
        val trimmed = name.trim().ifEmpty { "Unknown Artist" }
        dao.findArtistByName(trimmed)?.let { return it }
        val artist = ArtistEntity(id = newId(), name = trimmed)
        dao.upsertArtist(artist)
        return artist
    }

    suspend fun getOrCreateAlbum(name: String, artistId: String?, sourceUrl: String? = null): AlbumEntity {
        dao.findAlbum(name.trim(), artistId)?.let { return it }
        val album = AlbumEntity(id = newId(), name = name.trim(), artistId = artistId, sourceUrl = sourceUrl)
        dao.upsertAlbum(album)
        return album
    }

    suspend fun createPlaylist(name: String, sourceUrl: String? = null): PlaylistEntity {
        val url = sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null) {
            dao.findPlaylistBySourceUrl(url)?.let { existing ->
                val trimmed = name.trim()
                if (trimmed.isNotEmpty() && existing.name != trimmed) {
                    dao.renamePlaylist(existing.id, trimmed)
                    return existing.copy(name = trimmed)
                }
                return existing
            }
        }
        val playlist = PlaylistEntity(id = newId(), name = name.trim(), sourceUrl = url)
        dao.upsertPlaylist(playlist)
        return playlist
    }

    suspend fun renamePlaylist(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renamePlaylist(id, trimmed)
    }

    /**
     * Deletes the playlist row (CASCADE clears [playlist_tracks] links).
     * Tracks that are no longer in any playlist are removed from the library;
     * tracks still referenced by another playlist are kept.
     */
    suspend fun deletePlaylist(id: String): List<String> {
        val trackIds = dao.trackIdsInPlaylist(id)
        dao.deletePlaylistRow(id)
        val orphanCovers = mutableListOf<String>()
        for (trackId in trackIds) {
            if (dao.countPlaylistMemberships(trackId) > 0) continue
            deleteTrack(trackId)?.let { orphanCovers += it }
        }
        return orphanCovers
    }

    suspend fun createFolder(name: String): FolderEntity {
        val folder = FolderEntity(id = newId(), name = name.trim())
        dao.upsertFolder(folder)
        return folder
    }

    suspend fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        dao.renameFolder(id, trimmed)
    }

    suspend fun deleteFolder(id: String) {
        dao.deleteFolderRow(id)
    }

    suspend fun addTrackToFolder(folderId: String, trackId: String) {
        dao.insertFolderTrack(FolderTrackEntity(folderId, trackId))
    }

    suspend fun removeTrackFromFolder(folderId: String, trackId: String) {
        dao.removeFolderTrack(folderId, trackId)
    }

    suspend fun movePlaylistTrack(playlistId: String, trackId: String, direction: Int) {
        val ids = dao.trackIdsInPlaylist(playlistId)
        val idx = ids.indexOf(trackId)
        if (idx < 0) return
        val swapWith = idx + direction
        if (swapWith !in ids.indices) return
        val a = ids[idx]
        val b = ids[swapWith]
        dao.updatePlaylistTrackPosition(playlistId, a, swapWith)
        dao.updatePlaylistTrackPosition(playlistId, b, idx)
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val pos = (dao.maxPlaylistPosition(playlistId) ?: -1) + 1
        dao.insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, pos))
    }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        dao.removePlaylistTrack(playlistId, trackId)
    }

    suspend fun setPlaylistCoverIfEmpty(playlistId: String, coverPath: String) {
        dao.setPlaylistCoverIfEmpty(playlistId, coverPath)
    }

    /** Stable identity lookup used for download dedup / multi-playlist links. */
    suspend fun findTrackByIdentity(youtubeId: String?, spotifyId: String?): TrackEntity? {
        val yt = youtubeId?.trim()?.takeIf { it.isNotEmpty() }
        if (yt != null) dao.findTrackByYoutubeId(yt)?.let { return it }
        val sp = spotifyId?.trim()?.takeIf { it.isNotEmpty() }
        if (sp != null) dao.findTrackBySpotifyId(sp)?.let { return it }
        return null
    }

    suspend fun insertDownloadedTrack(
        title: String,
        artistName: String?,
        albumName: String?,
        albumId: String?,
        playlistId: String?,
        path: String?,
        format: String,
        durationMs: Long,
        sourceUrl: String?,
        sourceType: String,
        spotifyId: String?,
        youtubeId: String?,
        genre: String?,
        coverPath: String? = null,
        storageMode: String = TrackEntity.STORAGE_LOCAL,
        segments: List<TrackSegmentEntity> = emptyList(),
        playlistIds: List<String> = emptyList(),
    ): TrackEntity {
        val allPlaylistIds = (listOfNotNull(playlistId?.takeIf { it.isNotBlank() }) + playlistIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // Reuse an existing library row when the same YouTube/Spotify identity is already present.
        val existing = findTrackByIdentity(youtubeId, spotifyId)
        if (existing != null) {
            val artist = getOrCreateArtist(artistName ?: "Unknown Artist")
            val resolvedAlbumId = albumId
                ?: albumName?.takeIf { it.isNotBlank() }?.let { getOrCreateAlbum(it, artist.id).id }
                ?: existing.albumId
            dao.upsertTrack(
                existing.copy(
                    title = title.ifBlank { existing.title },
                    artistId = artist.id,
                    albumId = resolvedAlbumId ?: existing.albumId,
                    durationMs = if (durationMs > 0) durationMs else existing.durationMs,
                    path = path ?: existing.path,
                    format = format.ifBlank { existing.format },
                    sourceUrl = sourceUrl ?: existing.sourceUrl,
                    sourceType = sourceType.ifBlank { existing.sourceType },
                    spotifyId = spotifyId ?: existing.spotifyId,
                    youtubeId = youtubeId ?: existing.youtubeId,
                    coverPath = coverPath ?: existing.coverPath,
                    storageMode = storageMode,
                ),
            )
            if (segments.isNotEmpty()) {
                dao.deleteSegments(existing.id)
                dao.upsertSegments(segments.map { it.copy(trackId = existing.id) })
            }
            if (!coverPath.isNullOrBlank()) {
                existing.albumId?.let { dao.setAlbumCoverIfEmpty(it, coverPath) }
                resolvedAlbumId?.let { dao.setAlbumCoverIfEmpty(it, coverPath) }
                for (pid in allPlaylistIds) dao.setPlaylistCoverIfEmpty(pid, coverPath)
            }
            for (pid in allPlaylistIds) addTrackToPlaylist(pid, existing.id)
            return dao.getTrack(existing.id) ?: existing
        }

        val artist = getOrCreateArtist(artistName ?: "Unknown Artist")
        val resolvedAlbumId = albumId
            ?: albumName?.takeIf { it.isNotBlank() }?.let { getOrCreateAlbum(it, artist.id).id }
            ?: getOrCreateAlbum(title, artist.id).id
        val track = TrackEntity(
            id = newId(),
            title = title,
            artistId = artist.id,
            albumId = resolvedAlbumId,
            durationMs = durationMs,
            path = path,
            format = format,
            sourceUrl = sourceUrl,
            sourceType = sourceType,
            spotifyId = spotifyId,
            youtubeId = youtubeId,
            genre = genre,
            coverPath = coverPath,
            storageMode = storageMode,
        )
        dao.upsertTrack(track)
        if (segments.isNotEmpty()) {
            dao.upsertSegments(segments.map { it.copy(trackId = track.id) })
        }
        if (!coverPath.isNullOrBlank()) {
            dao.setAlbumCoverIfEmpty(resolvedAlbumId, coverPath)
            for (pid in allPlaylistIds) dao.setPlaylistCoverIfEmpty(pid, coverPath)
        }
        if (!genre.isNullOrBlank()) {
            val g = dao.findGenreByName(genre) ?: GenreEntity(newId(), genre.trim()).also { dao.upsertGenre(it) }
            dao.insertGenreTrack(GenreTrackEntity(g.id, track.id))
        }
        for (pid in allPlaylistIds) addTrackToPlaylist(pid, track.id)
        return track
    }

    suspend fun getSegments(trackId: String): List<TrackSegmentEntity> = dao.getSegments(trackId)

    /** Attaches a local audio file to an existing library track (offline download). */
    suspend fun attachLocalFile(trackId: String, path: String, format: String, durationMs: Long) {
        dao.attachLocalFile(trackId, path, format, durationMs, TrackEntity.STORAGE_LOCAL)
    }

    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id)

    /** Deletes DB rows and the local audio file when present. Segments cascade. */
    suspend fun deleteTrack(id: String): String? {
        val track = dao.getTrack(id) ?: return null
        val cover = track.coverPath
        dao.deletePlaylistLinks(id)
        dao.deleteGenreLinks(id)
        dao.deleteFolderLinks(id)
        dao.deletePlayHistory(id)
        dao.deleteSegments(id)
        dao.deleteTrackRow(id)
        track.path?.let { path ->
            runCatching {
                val file = java.io.File(path)
                if (file.exists()) file.delete()
            }
        }
        if (!cover.isNullOrBlank()) {
            val stillUsed = dao.countTracksWithCover(cover) + dao.countAlbumsWithCover(cover)
            if (stillUsed == 0) return cover
        }
        return null
    }

    suspend fun recordPlay(trackId: String) {
        val now = System.currentTimeMillis()
        dao.setLastPlayed(trackId, now)
        dao.insertHistory(PlayHistoryEntity(newId(), trackId, now))
    }

    suspend fun getArtist(id: String) = dao.getArtist(id)
    suspend fun getAlbum(id: String) = dao.getAlbum(id)
    suspend fun getPlaylist(id: String) = dao.getPlaylist(id)
    suspend fun getGenre(id: String) = dao.getGenre(id)
    suspend fun getFolder(id: String) = dao.getFolder(id)
    suspend fun getTrack(id: String) = dao.getTrack(id)
}

data class AppSettings(
    val fallbackQuality: String = "best",
    val downloadConcurrency: Int = 2,
    val preferFlac: Boolean = true,
    /** When true, new downloads stay on device (no Telegram upload). */
    val preferLocalStorage: Boolean = false,
    /** @deprecated Habitual downloads go to Telegram unless [preferLocalStorage]. */
    val storageMode: String = TrackEntity.STORAGE_TELEGRAM,
    val telegramBotToken: String = "",
    val telegramChannelId: String = "",
) {
    val isTelegramConfigured: Boolean
        get() = telegramBotToken.isNotBlank() && telegramChannelId.isNotBlank()
}

class SettingsRepository(private val dao: SettingsDao) {
    suspend fun get(): AppSettings {
        val map = dao.all().associate { it.key to it.value }
        return AppSettings(
            fallbackQuality = map["fallbackQuality"] ?: "best",
            downloadConcurrency = map["downloadConcurrency"]?.toIntOrNull() ?: 2,
            preferFlac = map["preferFlac"]?.toBooleanStrictOrNull() ?: true,
            preferLocalStorage = map["preferLocalStorage"]?.toBooleanStrictOrNull() ?: false,
            storageMode = map["storageMode"] ?: TrackEntity.STORAGE_TELEGRAM,
            telegramBotToken = map["telegramBotToken"].orEmpty(),
            telegramChannelId = map["telegramChannelId"].orEmpty(),
        )
    }

    suspend fun set(key: String, value: String) = dao.upsert(SettingEntity(key, value))

    suspend fun update(patch: AppSettings) {
        set("fallbackQuality", patch.fallbackQuality)
        set("downloadConcurrency", patch.downloadConcurrency.toString())
        set("preferFlac", patch.preferFlac.toString())
        set("preferLocalStorage", patch.preferLocalStorage.toString())
        set("storageMode", if (patch.preferLocalStorage) TrackEntity.STORAGE_LOCAL else TrackEntity.STORAGE_TELEGRAM)
        set("telegramBotToken", patch.telegramBotToken)
        set("telegramChannelId", patch.telegramChannelId)
    }
}
