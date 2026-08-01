package com.melomaniac.app.data

import android.content.Context
import com.melomaniac.app.download.BinaryManager
import com.melomaniac.app.download.CoverStore
import com.melomaniac.app.download.DownloadQueue
import com.melomaniac.app.download.SpotifyScraper
import com.melomaniac.app.download.YtDlpRunner
import com.melomaniac.app.player.PlayerController
import com.melomaniac.app.telegram.HlsPackager
import com.melomaniac.app.telegram.HlsProxyServer
import com.melomaniac.app.telegram.TelegramConfig
import com.melomaniac.app.update.AppUpdater
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)

    val library = LibraryRepository(db.libraryDao())
    val settings = SettingsRepository(db.settingsDao())
    val downloadDao = db.downloadDao()

    val binaryManager = BinaryManager(appContext)
    val appUpdater = AppUpdater(appContext)
    val musicDir = File(appContext.filesDir, "music").also { if (!it.exists()) it.mkdirs() }
    private val coversDir = File(appContext.filesDir, "covers").also { if (!it.exists()) it.mkdirs() }
    private val hlsStagingDir = File(appContext.cacheDir, "hls-staging").also { if (!it.exists()) it.mkdirs() }

    val ytDlp = YtDlpRunner(binaryManager, musicDir)
    val spotify = SpotifyScraper(appContext)
    val covers = CoverStore(appContext)
    val telegramConfig = TelegramConfig(settings)
    val hlsPackager = HlsPackager(appContext, binaryManager, hlsStagingDir)
    val hlsProxy = HlsProxyServer(library, settings)

    val downloadQueue = DownloadQueue(
        appContext = appContext,
        downloadDao = downloadDao,
        library = library,
        settingsRepo = settings,
        ytDlp = ytDlp,
        spotify = spotify,
        covers = covers,
        hlsPackager = hlsPackager,
        telegramConfig = telegramConfig,
    )

    val player = PlayerController(appContext, library, hlsProxy)

    init {
        if (downloadQueue.isPaused()) {
            // Recover rows left as `running` after process death; keep queue paused.
            downloadQueue.recoverStuckJobs()
        } else {
            downloadQueue.start()
        }
    }

    /**
     * Wipes Room tables and deletes audio/cover files under filesDir.
     * Stops the download queue and clears the player first.
     */
    suspend fun resetLibrary() = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "resetLibrary: stopping queue + player")
        withContext(Dispatchers.Main) { player.stopAndClear() }

        downloadQueue.resetStorage {
            AppLog.i(TAG, "resetLibrary: clearAllTables")
            db.clearAllTables()
            wipeDir(musicDir)
            wipeDir(coversDir)
            wipeDir(hlsStagingDir)
            musicDir.mkdirs()
            coversDir.mkdirs()
            hlsStagingDir.mkdirs()
        }
        AppLog.i(TAG, "resetLibrary: done")
    }

    private fun wipeDir(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            runCatching {
                if (!child.deleteRecursively()) {
                    AppLog.w(TAG, "could not delete ${child.absolutePath}")
                }
            }.onFailure {
                AppLog.w(TAG, "wipe failed ${child.absolutePath}: ${it.message}")
            }
        }
    }

    companion object {
        private const val TAG = "AppContainer"
    }
}
