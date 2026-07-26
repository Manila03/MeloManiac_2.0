package com.melomaniac.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(artist: ArtistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(album: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<TrackSegmentEntity>)

    @Query("SELECT * FROM track_segments WHERE trackId = :trackId ORDER BY segmentIndex ASC")
    suspend fun getSegments(trackId: String): List<TrackSegmentEntity>

    @Query("DELETE FROM track_segments WHERE trackId = :trackId")
    suspend fun deleteSegments(trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrack(row: PlaylistTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGenre(genre: GenreEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenreTrack(row: GenreTrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: FolderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderTrack(row: FolderTrackEntity)

    @Insert
    suspend fun insertHistory(row: PlayHistoryEntity)

    @Query("SELECT * FROM artists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findArtistByName(name: String): ArtistEntity?

    @Query("SELECT * FROM albums WHERE name = :name COLLATE NOCASE AND IFNULL(artistId,'') = IFNULL(:artistId,'') LIMIT 1")
    suspend fun findAlbum(name: String, artistId: String?): AlbumEntity?

    @Query("SELECT * FROM genres WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findGenreByName(name: String): GenreEntity?

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName,
               COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        ORDER BY t.title COLLATE NOCASE
        """,
    )
    fun observeTracks(): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName,
               COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE t.title LIKE '%' || :q || '%' OR a.name LIKE '%' || :q || '%' OR al.name LIKE '%' || :q || '%'
        ORDER BY t.title COLLATE NOCASE LIMIT 100
        """,
    )
    suspend fun searchTracks(q: String): List<TrackRow>

    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE")
    fun observeArtists(): Flow<List<ArtistEntity>>

    @Query(
        """
        SELECT al.*, a.name AS artistName FROM albums al
        LEFT JOIN artists a ON a.id = al.artistId
        ORDER BY al.name COLLATE NOCASE
        """,
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM genres ORDER BY name COLLATE NOCASE")
    fun observeGenres(): Flow<List<GenreEntity>>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE t.isFavorite = 1 ORDER BY t.title COLLATE NOCASE
        """,
    )
    fun observeFavorites(): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE t.lastPlayedAt IS NOT NULL
        ORDER BY t.lastPlayedAt DESC LIMIT 50
        """,
    )
    fun observeRecent(): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE t.artistId = :artistId ORDER BY t.title
        """,
    )
    fun observeTracksByArtist(artistId: String): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE t.albumId = :albumId ORDER BY t.title
        """,
    )
    fun observeTracksByAlbum(albumId: String): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE pt.playlistId = :playlistId ORDER BY pt.position
        """,
    )
    fun observeTracksByPlaylist(playlistId: String): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        INNER JOIN genre_tracks gt ON gt.trackId = t.id
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE gt.genreId = :genreId ORDER BY t.title
        """,
    )
    fun observeTracksByGenre(genreId: String): Flow<List<TrackRow>>

    @Query(
        """
        SELECT t.*, a.name AS artistName, al.name AS albumName, COALESCE(t.coverPath, al.coverPath) AS coverPathJoined
        FROM tracks t
        INNER JOIN folder_tracks ft ON ft.trackId = t.id
        LEFT JOIN artists a ON a.id = t.artistId
        LEFT JOIN albums al ON al.id = t.albumId
        WHERE ft.folderId = :folderId ORDER BY t.title
        """,
    )
    fun observeTracksByFolder(folderId: String): Flow<List<TrackRow>>

    @Query("UPDATE albums SET coverPath = :coverPath WHERE id = :id AND (coverPath IS NULL OR coverPath = '')")
    suspend fun setAlbumCoverIfEmpty(id: String, coverPath: String)

    @Query("UPDATE playlists SET coverPath = :coverPath WHERE id = :id AND (coverPath IS NULL OR coverPath = '')")
    suspend fun setPlaylistCoverIfEmpty(id: String, coverPath: String)

    @Query("SELECT COUNT(*) FROM albums WHERE coverPath = :path")
    suspend fun countAlbumsWithCover(path: String): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE coverPath = :path")
    suspend fun countTracksWithCover(path: String): Int

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun getArtist(id: String): ArtistEntity?

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getAlbum(id: String): AlbumEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: String): PlaylistEntity?

    @Query("SELECT * FROM genres WHERE id = :id")
    suspend fun getGenre(id: String): GenreEntity?

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolder(id: String): FolderEntity?

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTrackCount(): Flow<Int>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: String): TrackEntity?

    @Query("DELETE FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun deletePlaylistLinks(trackId: String)

    @Query("DELETE FROM genre_tracks WHERE trackId = :trackId")
    suspend fun deleteGenreLinks(trackId: String)

    @Query("DELETE FROM folder_tracks WHERE trackId = :trackId")
    suspend fun deleteFolderLinks(trackId: String)

    @Query("DELETE FROM play_history WHERE trackId = :trackId")
    suspend fun deletePlayHistory(trackId: String)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrackRow(id: String)

    @Query("UPDATE tracks SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE tracks SET lastPlayedAt = :at WHERE id = :id")
    suspend fun setLastPlayed(id: String, at: Long)

    @Query(
        """
        UPDATE tracks SET path = :path, format = :format, durationMs = :durationMs,
        storageMode = :storageMode WHERE id = :id
        """,
    )
    suspend fun attachLocalFile(
        id: String,
        path: String,
        format: String,
        durationMs: Long,
        storageMode: String,
    )

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPlaylistPosition(playlistId: String): Int?
}

data class TrackRow(
    @Embedded val track: TrackEntity,
    @ColumnInfo(name = "artistName") val artistName: String?,
    @ColumnInfo(name = "albumName") val albumName: String?,
    @ColumnInfo(name = "coverPathJoined") val coverPath: String?,
) {
    val id get() = track.id
    val title get() = track.title
    val path get() = track.path
    val durationMs get() = track.durationMs
    val isFavorite get() = track.isFavorite
    val storageMode get() = track.storageMode
    val format get() = track.format
    val isOnline get() = track.storageMode == TrackEntity.STORAGE_TELEGRAM
    val isHls get() = format.equals("hls", ignoreCase = true)
    val hasLocalFile: Boolean
        get() = !path.isNullOrBlank() && java.io.File(path!!).exists()
    /** Needs the "download locally" action. */
    val needsLocalDownload: Boolean get() = !hasLocalFile
}

data class AlbumRow(
    @Embedded val album: AlbumEntity,
    @ColumnInfo(name = "artistName") val artistName: String?,
) {
    val id get() = album.id
    val name get() = album.name
}

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: DownloadJobEntity)

    @Query("SELECT * FROM download_jobs ORDER BY priority DESC, createdAt ASC")
    fun observeAll(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE status = 'queued' ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun nextQueued(limit: Int): List<DownloadJobEntity>

    @Query("UPDATE download_jobs SET status = :status, progress = :progress, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: String, status: String, progress: Float, error: String?, updatedAt: Long)

    @Query("UPDATE download_jobs SET status = 'queued', progress = 0, error = NULL, updatedAt = :updatedAt WHERE status = 'running'")
    suspend fun resetStuck(updatedAt: Long)

    @Query("SELECT COUNT(*) FROM download_jobs WHERE status IN ('queued','running')")
    suspend fun countActive(): Int

    @Query("DELETE FROM download_jobs WHERE status IN ('done','cancelled')")
    suspend fun clearFinished()

    @Query("DELETE FROM download_jobs WHERE status IN ('done','failed','cancelled')")
    suspend fun clearHistory()

    @Query("DELETE FROM download_jobs WHERE status != 'running'")
    suspend fun clearAllExceptRunning()

    @Query("DELETE FROM download_jobs WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: SettingEntity)

    @Query("SELECT value FROM settings WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?
}
