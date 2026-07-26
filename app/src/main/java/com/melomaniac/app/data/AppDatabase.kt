package com.melomaniac.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ArtistEntity::class,
        AlbumEntity::class,
        TrackEntity::class,
        TrackSegmentEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        GenreEntity::class,
        GenreTrackEntity::class,
        FolderEntity::class,
        FolderTrackEntity::class,
        PlayHistoryEntity::class,
        DownloadJobEntity::class,
        SettingEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracks_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        artistId TEXT,
                        albumId TEXT,
                        durationMs INTEGER NOT NULL,
                        path TEXT,
                        format TEXT NOT NULL,
                        bitrate INTEGER,
                        sourceUrl TEXT,
                        sourceType TEXT NOT NULL,
                        spotifyId TEXT,
                        youtubeId TEXT,
                        genre TEXT,
                        coverPath TEXT,
                        storageMode TEXT NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        lastPlayedAt INTEGER,
                        FOREIGN KEY(artistId) REFERENCES artists(id) ON DELETE SET NULL,
                        FOREIGN KEY(albumId) REFERENCES albums(id) ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO tracks_new (
                        id, title, artistId, albumId, durationMs, path, format, bitrate,
                        sourceUrl, sourceType, spotifyId, youtubeId, genre, coverPath,
                        storageMode, isFavorite, createdAt, lastPlayedAt
                    )
                    SELECT
                        id, title, artistId, albumId, durationMs, path, format, bitrate,
                        sourceUrl, sourceType, spotifyId, youtubeId, genre, coverPath,
                        'local', isFavorite, createdAt, lastPlayedAt
                    FROM tracks
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE tracks")
                db.execSQL("ALTER TABLE tracks_new RENAME TO tracks")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_artistId ON tracks(artistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_albumId ON tracks(albumId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_isFavorite ON tracks(isFavorite)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_lastPlayedAt ON tracks(lastPlayedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_storageMode ON tracks(storageMode)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_segments (
                        trackId TEXT NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        telegramFileId TEXT NOT NULL,
                        durationSec REAL NOT NULL,
                        byteSize INTEGER,
                        PRIMARY KEY(trackId, segmentIndex),
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_segments_trackId ON track_segments(trackId)",
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "melomaniac.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
