package com.melomaniac.app.data

import com.melomaniac.app.util.newId
import kotlinx.coroutines.flow.Flow

class LibraryRepository(private val dao: LibraryDao) {
    fun observeTracks() = dao.observeTracks()
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
        val playlist = PlaylistEntity(id = newId(), name = name.trim(), sourceUrl = sourceUrl)
        dao.upsertPlaylist(playlist)
        return playlist
    }

    suspend fun createFolder(name: String): FolderEntity {
        val folder = FolderEntity(id = newId(), name = name.trim())
        dao.upsertFolder(folder)
        return folder
    }

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val pos = (dao.maxPlaylistPosition(playlistId) ?: -1) + 1
        dao.insertPlaylistTrack(PlaylistTrackEntity(playlistId, trackId, pos))
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
    ): TrackEntity {
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
            if (playlistId != null) dao.setPlaylistCoverIfEmpty(playlistId, coverPath)
        }
        if (!genre.isNullOrBlank()) {
            val g = dao.findGenreByName(genre) ?: GenreEntity(newId(), genre.trim()).also { dao.upsertGenre(it) }
            dao.insertGenreTrack(GenreTrackEntity(g.id, track.id))
        }
        if (playlistId != null) addTrackToPlaylist(playlistId, track.id)
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
    /** @deprecated Habitual downloads always go to Telegram; kept for settings migration. */
    val storageMode: String = TrackEntity.STORAGE_TELEGRAM,
    val telegramBotToken: String = "",
    val telegramChannelId: String = "",
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
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
            storageMode = map["storageMode"] ?: TrackEntity.STORAGE_TELEGRAM,
            telegramBotToken = map["telegramBotToken"].orEmpty(),
            telegramChannelId = map["telegramChannelId"].orEmpty(),
            spotifyClientId = map["spotifyClientId"].orEmpty(),
            spotifyClientSecret = map["spotifyClientSecret"].orEmpty(),
        )
    }

    suspend fun set(key: String, value: String) = dao.upsert(SettingEntity(key, value))

    suspend fun update(patch: AppSettings) {
        set("fallbackQuality", patch.fallbackQuality)
        set("downloadConcurrency", patch.downloadConcurrency.toString())
        set("preferFlac", patch.preferFlac.toString())
        set("storageMode", TrackEntity.STORAGE_TELEGRAM)
        set("telegramBotToken", patch.telegramBotToken)
        set("telegramChannelId", patch.telegramChannelId)
        set("spotifyClientId", patch.spotifyClientId)
        set("spotifyClientSecret", patch.spotifyClientSecret)
    }
}
