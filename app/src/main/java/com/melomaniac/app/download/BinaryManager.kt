package com.melomaniac.app.download

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android blocks executing binaries from writable [Context.getFilesDir] (error=13).
 * We install into [Context.getCodeCacheDir], which allows execution, and chmod via Os.
 */
class BinaryManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /** Writable staging area (downloads land here first). */
    private val stagingDir: File
        get() = File(context.filesDir, "bin-staging").also { if (!it.exists()) it.mkdirs() }

    /**
     * Executable install dir. codeCacheDir is allowed for exec on modern Android;
     * filesDir/bin is not (SELinux / W^X → Permission denied).
     */
    private val binDir: File
        get() = File(context.codeCacheDir, "bin").also { if (!it.exists()) it.mkdirs() }

    /** Working HOME for yt-dlp config/cookies (must be writable). */
    val workDir: File
        get() = File(context.filesDir, "ytdlp-home").also { if (!it.exists()) it.mkdirs() }

    val ytdlpFile: File get() = File(binDir, "ytdlp")
    val ffmpegFile: File get() = File(binDir, "ffmpeg")

    fun isReady(): Boolean = ytdlpFile.exists() && ytdlpFile.canExecute() && ytdlpFile.length() > 1_000_000

    suspend fun ensureBinaries(onStatus: (String) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        cleanupLegacyFilesDirBin()

        if (!isReady()) {
            onStatus("Descargando yt-dlp…")
            installBinary(
                url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64",
                dest = ytdlpFile,
            )
        } else {
            makeExecutable(ytdlpFile)
        }

        if (!ffmpegFile.exists() || ffmpegFile.length() < 100_000) {
            onStatus("Descargando ffmpeg…")
            try {
                installBinary(
                    url = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/ffmpeg-linux-arm64",
                    dest = ffmpegFile,
                )
            } catch (e: Exception) {
                Log.w(TAG, "ffmpeg download failed", e)
                onStatus("ffmpeg no se pudo descargar automáticamente")
            }
        } else {
            makeExecutable(ffmpegFile)
        }

        verifyYtDlpRuns()
        onStatus("Binarios listos")
    }

    suspend fun updateYtDlp(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onStatus("Actualizando yt-dlp…")
        if (ytdlpFile.exists()) ytdlpFile.delete()
        installBinary(
            url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux_aarch64",
            dest = ytdlpFile,
        )
        verifyYtDlpRuns()
        onStatus("yt-dlp actualizado")
    }

    /** Force re-download (call from Settings if exec still fails). */
    suspend fun reinstallAll(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        onStatus("Reinstalando binarios…")
        ytdlpFile.delete()
        ffmpegFile.delete()
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        binDir.mkdirs()
        ensureBinaries(onStatus)
    }

    private fun cleanupLegacyFilesDirBin() {
        val legacy = File(context.filesDir, "bin")
        if (legacy.exists()) {
            legacy.deleteRecursively()
            Log.i(TAG, "Removed legacy filesDir/bin (not executable on Android 10+)")
        }
    }

    private fun installBinary(url: String, dest: File) {
        val staging = File(stagingDir, dest.name + ".download")
        if (staging.exists()) staging.delete()
        download(url, staging)

        if (dest.exists()) dest.delete()
        dest.parentFile?.mkdirs()
        if (!staging.renameTo(dest)) {
            staging.copyTo(dest, overwrite = true)
            staging.delete()
        }
        makeExecutable(dest)
        if (!dest.exists() || dest.length() == 0L) {
            error("Binary install failed for ${dest.name}")
        }
    }

    private fun makeExecutable(file: File) {
        if (!file.exists()) return
        try {
            // 0755
            Os.chmod(file.absolutePath, "0755".toInt(8))
        } catch (e: Exception) {
            Log.w(TAG, "Os.chmod failed for ${file.name}, trying File API", e)
            file.setExecutable(true, false)
            file.setReadable(true, false)
        }
        // Confirm; if still not executable, last-resort chmod via toybox
        if (!file.canExecute()) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("chmod", "755", file.absolutePath))
                p.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "chmod process failed", e)
            }
        }
    }

    private fun verifyYtDlpRuns() {
        if (!ytdlpFile.exists()) error("yt-dlp no instalado")
        makeExecutable(ytdlpFile)
        val pb = ProcessBuilder(ytdlpFile.absolutePath, "--version")
        pb.directory(workDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["HOME"] = workDir.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        env["PATH"] = binDir.absolutePath + ":" + (env["PATH"] ?: "/system/bin")
        try {
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                error("yt-dlp no arranca (exit $code): ${out.take(300)}")
            }
            Log.i(TAG, "yt-dlp ok: ${out.trim().take(80)}")
        } catch (e: Exception) {
            throw IllegalStateException(
                "No se puede ejecutar yt-dlp (${e.message}). " +
                    "En Ajustes tocá «Reinstalar binarios». " +
                    "path=${ytdlpFile.absolutePath}",
                e,
            )
        }
    }

    private fun download(url: String, dest: File) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed ${resp.code} for $url")
            val body = resp.body ?: error("Empty body")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    companion object {
        private const val TAG = "BinaryManager"
    }
}
