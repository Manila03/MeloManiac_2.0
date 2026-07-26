package com.melomaniac.app.telegram

import android.content.Context
import com.melomaniac.app.download.BinaryManager
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class HlsSegmentFile(
    val index: Int,
    val file: File,
    val durationSec: Double,
)

data class HlsPackage(
    val playlist: File?,
    val segments: List<HlsSegmentFile>,
    val workDir: File,
    /** True when HLS mux failed and we upload the raw audio as one Telegram document. */
    val progressive: Boolean = false,
) {
    fun deleteQuietly() {
        runCatching { workDir.deleteRecursively() }
    }
}

/**
 * Converts a downloaded audio file into HLS VOD (AAC + MPEG-TS) using the
 * ffmpeg binary shipped with youtubedl-android (`libffmpeg.so`).
 *
 * Uses the same LD_LIBRARY_PATH / python launcher context as yt-dlp so native
 * deps (e.g. libc++_shared via python/ffmpeg packages) resolve.
 * Falls back to a single progressive file if HLS muxing fails.
 */
class HlsPackager(
    private val context: Context,
    private val binaryManager: BinaryManager,
    private val stagingRoot: File,
) {
    init {
        if (!stagingRoot.exists()) stagingRoot.mkdirs()
    }

    suspend fun packageAudio(
        input: File,
        jobId: String,
        durationMsHint: Long = 0L,
        segmentSeconds: Int = 10,
        audioBitrateK: Int = 256,
    ): HlsPackage = withContext(Dispatchers.IO) {
        require(input.exists()) { "Audio missing: ${input.absolutePath}" }
        binaryManager.ensureBinaries()

        val workDir = File(stagingRoot, jobId).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        try {
            packageHls(input, workDir, segmentSeconds, audioBitrateK)
        } catch (e: Exception) {
            AppLog.w(TAG, "HLS pack failed, using progressive single-file fallback", e)
            runCatching { workDir.listFiles()?.forEach { it.delete() } }
            packageProgressive(input, workDir, durationMsHint)
        }
    }

    private fun packageHls(
        input: File,
        workDir: File,
        segmentSeconds: Int,
        audioBitrateK: Int,
    ): HlsPackage {
        val playlist = File(workDir, "playlist.m3u8")
        val segmentPattern = File(workDir, "seg_%05d.ts").absolutePath

        AppLog.i(TAG, "HLS pack → ${workDir.absolutePath}")
        runFfmpeg(
            workDir = workDir,
            args = listOf(
                "-y",
                "-i", input.absolutePath,
                "-vn",
                "-c:a", "aac",
                "-b:a", "${audioBitrateK}k",
                "-ac", "2",
                "-ar", "44100",
                "-f", "hls",
                "-hls_time", segmentSeconds.toString(),
                "-hls_playlist_type", "vod",
                "-hls_segment_type", "mpegts",
                "-hls_segment_filename", segmentPattern,
                playlist.absolutePath,
            ),
        )

        if (!playlist.exists()) error("ffmpeg no generó playlist.m3u8")

        val durations = parseExtInf(playlist)
        val tsFiles = workDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ts", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (tsFiles.isEmpty()) error("ffmpeg no generó segmentos .ts")

        val segments = tsFiles.mapIndexed { index, file ->
            HlsSegmentFile(
                index = index,
                file = file,
                durationSec = durations.getOrElse(index) { segmentSeconds.toDouble() },
            )
        }
        AppLog.i(TAG, "HLS ok: ${segments.size} segments")
        return HlsPackage(playlist = playlist, segments = segments, workDir = workDir, progressive = false)
    }

    private fun packageProgressive(input: File, workDir: File, durationMsHint: Long): HlsPackage {
        val ext = input.extension.ifBlank { "m4a" }
        val dest = File(workDir, "seg_00000.$ext")
        input.copyTo(dest, overwrite = true)
        val durationSec = if (durationMsHint > 0) durationMsHint / 1000.0 else 0.0
        AppLog.i(TAG, "progressive package → ${dest.name} (${dest.length()} bytes)")
        return HlsPackage(
            playlist = null,
            segments = listOf(HlsSegmentFile(0, dest, durationSec)),
            workDir = workDir,
            progressive = true,
        )
    }

    private fun runFfmpeg(workDir: File, args: List<String>) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val pythonDir = File(packages, "python")
        val ffmpegDir = File(packages, "ffmpeg")
        val aria2cDir = File(packages, "aria2c")
        val ffmpeg = File(native, "libffmpeg.so")
        require(ffmpeg.exists()) { "libffmpeg.so no encontrado. Inicializá binarios en Ajustes." }

        val ldPath = buildLdLibraryPath(native, pythonDir, ffmpegDir, aria2cDir)
        AppLog.d(TAG, "LD_LIBRARY_PATH=$ldPath")
        logLibCpp(ldPath)

        val python = File(native, "libpython.so")
        val command = if (python.exists()) {
            // Launch via python (same host process style as yt-dlp) so the linker
            // namespace resolves rubberband → libc++_shared from package libs.
            val script = File(workDir, "run_ffmpeg.py")
            val argv = (listOf(ffmpeg.absolutePath) + args).joinToString(", ") { arg ->
                "r'''$arg'''"
            }
            script.writeText(
                """
                import subprocess, sys
                cmd = [$argv]
                raise SystemExit(subprocess.call(cmd))
                """.trimIndent(),
            )
            listOf(python.absolutePath, script.absolutePath)
        } else {
            listOf(ffmpeg.absolutePath) + args
        }

        AppLog.d(TAG, "exec: ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        pb.directory(workDir)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = ldPath
        env["PATH"] = (System.getenv("PATH") ?: "") + ":" + native.absolutePath
        env["TMPDIR"] = context.cacheDir.absolutePath
        val pythonHome = File(pythonDir, "usr")
        if (pythonHome.exists()) {
            env["PYTHONHOME"] = pythonHome.absolutePath
            env["HOME"] = pythonHome.absolutePath
        }
        val cert = File(pythonDir, "usr/etc/tls/cert.pem")
        if (cert.exists()) env["SSL_CERT_FILE"] = cert.absolutePath

        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            AppLog.e(TAG, "ffmpeg exit=$code\n${output.takeLast(800)}")
            error("ffmpeg falló (exit $code): ${output.takeLast(300)}")
        }
        if (output.isNotBlank()) AppLog.d(TAG, output.takeLast(400))
    }

    private fun buildLdLibraryPath(vararg roots: File): String {
        val dirs = LinkedHashSet<String>()
        for (root in roots) {
            val usrLib = if (root.name == "lib" || root.absolutePath.contains("nativeLibraryDir") ||
                root == File(context.applicationInfo.nativeLibraryDir)
            ) {
                root
            } else {
                File(root, "usr/lib").takeIf { it.exists() } ?: root
            }
            if (usrLib.exists()) dirs += usrLib.absolutePath
            // Also add package root usr/lib explicitly for python/ffmpeg/aria2c
            File(root, "usr/lib").takeIf { it.exists() }?.let { dirs += it.absolutePath }
            if (root.exists() && root.isDirectory) dirs += root.absolutePath
        }
        val native = File(context.applicationInfo.nativeLibraryDir)
        dirs += native.absolutePath
        // Prefer locating libc++_shared.so and putting its folder first
        val cppDir = findLibCppDir()
        val ordered = ArrayList<String>()
        if (cppDir != null) ordered += cppDir.absolutePath
        ordered += dirs
        System.getenv("LD_LIBRARY_PATH")?.split(':')?.filter { it.isNotBlank() }?.let { ordered += it }
        return ordered.distinct().joinToString(":")
    }

    private fun findLibCppDir(): File? {
        val native = File(context.applicationInfo.nativeLibraryDir, "libc++_shared.so")
        if (native.exists()) return native.parentFile
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        if (!packages.exists()) return null
        return packages.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libc++_shared.so" }
            ?.parentFile
    }

    private fun logLibCpp(ldPath: String) {
        val found = ldPath.split(':').any { dir ->
            File(dir, "libc++_shared.so").exists()
        }
        AppLog.i(TAG, "libc++_shared.so on LD path: $found")
    }

    companion object {
        private const val TAG = "HlsPackager"

        fun parseExtInf(playlist: File): List<Double> {
            val durations = mutableListOf<Double>()
            for (line in playlist.readLines()) {
                val t = line.trim()
                if (t.startsWith("#EXTINF:", ignoreCase = true)) {
                    val value = t.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                    if (value != null) durations += value
                }
            }
            return durations
        }
    }
}
