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
        path: String,
        format: String,
        durationMs: Long,
        sourceUrl: String?,
        sourceType: String,
        spotifyId: String?,
        youtubeId: String?,
        genre: String?,
    ): TrackEntity {
        val artist = getOrCreateArtist(artistName ?: "Unknown Artist")
        val resolvedAlbumId = albumId ?: albumName?.let { getOrCreateAlbum(it, artist.id).id }
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
        )
        dao.upsertTrack(track)
        if (!genre.isNullOrBlank()) {
            val g = dao.findGenreByName(genre) ?: GenreEntity(newId(), genre.trim()).also { dao.upsertGenre(it) }
            dao.insertGenreTrack(GenreTrackEntity(g.id, track.id))
        }
        if (playlistId != null) addTrackToPlaylist(playlistId, track.id)
        return track
    }

    suspend fun toggleFavorite(id: String) = dao.toggleFavorite(id)

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
}

data class AppSettings(
    val fallbackQuality: String = "best",
    val downloadConcurrency: Int = 2,
    val preferFlac: Boolean = true,
    val spotifyClientId: String = "",
    val spotifyClientSecret: String = "",
)

class SettingsRepository(private val dao: SettingsDao) {
    suspend fun get(): AppSettings {
        val map = dao.all().associate { it.key to it.value }
        return AppSettings(
            fallbackQuality = map["fallbackQuality"] ?: "best",
            downloadConcurrency = map["downloadConcurrency"]?.toIntOrNull() ?: 2,
            preferFlac = map["preferFlac"]?.toBooleanStrictOrNull() ?: true,
            spotifyClientId = map["spotifyClientId"].orEmpty(),
            spotifyClientSecret = map["spotifyClientSecret"].orEmpty(),
        )
    }

    suspend fun set(key: String, value: String) = dao.upsert(SettingEntity(key, value))

    suspend fun update(patch: AppSettings) {
        set("fallbackQuality", patch.fallbackQuality)
        set("downloadConcurrency", patch.downloadConcurrency.toString())
        set("preferFlac", patch.preferFlac.toString())
        set("spotifyClientId", patch.spotifyClientId)
        set("spotifyClientSecret", patch.spotifyClientSecret)
    }
}
