package com.melomaniac.app.download

import android.content.Context
import com.melomaniac.app.util.AppLog
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Initializes Android-compatible yt-dlp + ffmpeg via youtubedl-android.
 * Binaries live in the APK native lib dir (executable on Android 10+), not in filesDir.
 */
class BinaryManager(private val context: Context) {
    private val mutex = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureBinaries(onStatus: (String) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            fun status(msg: String) {
                AppLog.i(TAG, msg)
                onStatus(msg)
            }
            if (ready) {
                status("Binarios listos")
                return@withLock
            }
            status("Inicializando yt-dlp…")
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                status("Inicializando ffmpeg…")
                FFmpeg.getInstance().init(context.applicationContext)
                ready = true
                status("Binarios listos")
            } catch (e: YoutubeDLException) {
                AppLog.e(TAG, "init failed", e)
                throw IllegalStateException(
                    "No se pudo inicializar yt-dlp/ffmpeg: ${e.message}",
                    e,
                )
            } catch (e: Exception) {
                AppLog.e(TAG, "init failed", e)
                throw e
            }
        }
    }

    suspend fun updateYtDlp(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fun status(msg: String) {
                AppLog.i(TAG, msg)
                onStatus(msg)
            }
            ensureLocked(onStatus)
            status("Actualizando yt-dlp…")
            try {
                val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(
                    context.applicationContext,
                    YoutubeDL.UpdateChannel.STABLE,
                )
                status("yt-dlp: $updateStatus")
            } catch (e: Exception) {
                AppLog.e(TAG, "update failed", e)
                throw e
            }
        }
    }

    /** Re-init native packages (clears ready flag and initializes again). */
    suspend fun reinstallAll(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fun status(msg: String) {
                AppLog.i(TAG, msg)
                onStatus(msg)
            }
            status("Reinstalando binarios…")
            ready = false
            // Delete legacy ProcessBuilder installs if any remain.
            FileCleanup.deleteQuietly(context.filesDir.resolve("bin"))
            FileCleanup.deleteQuietly(context.filesDir.resolve("bin-staging"))
            FileCleanup.deleteQuietly(context.codeCacheDir.resolve("bin"))
            ensureLocked(onStatus)
        }
    }

    private fun ensureLocked(onStatus: (String) -> Unit) {
        if (ready) {
            onStatus("Binarios listos")
            return
        }
        onStatus("Inicializando yt-dlp…")
        YoutubeDL.getInstance().init(context.applicationContext)
        onStatus("Inicializando ffmpeg…")
        FFmpeg.getInstance().init(context.applicationContext)
        ready = true
        onStatus("Binarios listos")
        AppLog.i(TAG, "init ok")
    }

    private object FileCleanup {
        fun deleteQuietly(file: java.io.File) {
            if (!file.exists()) return
            runCatching { file.deleteRecursively() }
            AppLog.i(TAG, "cleaned ${file.absolutePath}")
        }
    }

    companion object {
        private const val TAG = "BinaryManager"
    }
}
