package com.melomaniac.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "artists")
data class ArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val imagePath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = ArtistEntity::class,
            parentColumns = ["id"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("artistId")],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val artistId: String? = null,
    val year: Int? = null,
    val coverPath: String? = null,
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("artistId"), Index("albumId"), Index("isFavorite"), Index("lastPlayedAt")],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistId: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0,
    val path: String,
    val format: String = "flac",
    val bitrate: Int? = null,
    val sourceUrl: String? = null,
    val sourceType: String = "youtube",
    val spotifyId: String? = null,
    val youtubeId: String? = null,
    val genre: String? = null,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverPath: String? = null,
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PlaylistTrackEntity(
    val playlistId: String,
    val trackId: String,
    val position: Int = 0,
)

@Entity(tableName = "genres")
data class GenreEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "genre_tracks",
    primaryKeys = ["genreId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = GenreEntity::class, parentColumns = ["id"], childColumns = ["genreId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class GenreTrackEntity(val genreId: String, val trackId: String)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "folder_tracks",
    primaryKeys = ["folderId", "trackId"],
    foreignKeys = [
        ForeignKey(entity = FolderEntity::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class FolderTrackEntity(val folderId: String, val trackId: String)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "download_jobs", indices = [Index("status")])
data class DownloadJobEntity(
    @PrimaryKey val id: String,
    val status: String = "queued",
    val urlOrQuery: String,
    val metaJson: String = "{}",
    val priority: Int = 0,
    val progress: Float = 0f,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

data class TrackWithMeta(
    val track: TrackEntity,
    val artistName: String?,
    val albumName: String?,
    val coverPath: String?,
)
