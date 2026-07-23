package com.melomaniac.app.data

import android.content.Context
import com.melomaniac.app.download.BinaryManager
import com.melomaniac.app.download.DownloadQueue
import com.melomaniac.app.download.SpotifyApi
import com.melomaniac.app.download.YtDlpRunner
import com.melomaniac.app.player.PlayerController
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)

    val library = LibraryRepository(db.libraryDao())
    val settings = SettingsRepository(db.settingsDao())
    val downloadDao = db.downloadDao()

    val binaryManager = BinaryManager(appContext)
    val musicDir = File(appContext.filesDir, "music").also { if (!it.exists()) it.mkdirs() }
    val ytDlp = YtDlpRunner(binaryManager, musicDir)
    val spotify = SpotifyApi()

    val downloadQueue = DownloadQueue(
        downloadDao = downloadDao,
        library = library,
        settingsRepo = settings,
        ytDlp = ytDlp,
        spotify = spotify,
    )

    val player = PlayerController(appContext, library)

    init {
        downloadQueue.start()
    }
}
