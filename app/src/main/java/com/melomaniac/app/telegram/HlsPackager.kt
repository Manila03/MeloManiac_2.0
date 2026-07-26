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
    val playlist: File,
    val segments: List<HlsSegmentFile>,
    val workDir: File,
) {
    fun deleteQuietly() {
        runCatching { workDir.deleteRecursively() }
    }
}

/**
 * Converts a downloaded audio file into HLS VOD (AAC + MPEG-TS) using the
 * ffmpeg binary shipped with youtubedl-android (`libffmpeg.so`).
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
        segmentSeconds: Int = 10,
        audioBitrateK: Int = 256,
    ): HlsPackage = withContext(Dispatchers.IO) {
        require(input.exists()) { "Audio missing: ${input.absolutePath}" }
        binaryManager.ensureBinaries()

        val workDir = File(stagingRoot, jobId).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val playlist = File(workDir, "playlist.m3u8")
        val segmentPattern = File(workDir, "seg_%05d.ts").absolutePath

        AppLog.i(TAG, "HLS pack job=$jobId → ${workDir.absolutePath}")
        runFfmpeg(
            listOf(
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

        if (!playlist.exists()) {
            workDir.deleteRecursively()
            error("ffmpeg no generó playlist.m3u8")
        }

        val durations = parseExtInf(playlist)
        val tsFiles = workDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("ts", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

        if (tsFiles.isEmpty()) {
            workDir.deleteRecursively()
            error("ffmpeg no generó segmentos .ts")
        }

        val segments = tsFiles.mapIndexed { index, file ->
            HlsSegmentFile(
                index = index,
                file = file,
                durationSec = durations.getOrElse(index) { segmentSeconds.toDouble() },
            )
        }
        AppLog.i(TAG, "HLS ok: ${segments.size} segments")
        HlsPackage(playlist = playlist, segments = segments, workDir = workDir)
    }

    private fun runFfmpeg(args: List<String>) {
        val ffmpeg = resolveFfmpegBinary()
        val ffmpegLibDir = resolveFfmpegLibDir()
        val command = ArrayList<String>(args.size + 1).apply {
            add(ffmpeg.absolutePath)
            addAll(args)
        }
        AppLog.d(TAG, "exec: ${command.joinToString(" ")}")
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        val ld = buildString {
            append(ffmpegLibDir.absolutePath)
            val native = File(context.applicationInfo.nativeLibraryDir)
            append(':').append(native.absolutePath)
            System.getenv("LD_LIBRARY_PATH")?.let { append(':').append(it) }
        }
        env["LD_LIBRARY_PATH"] = ld
        env["PATH"] = (System.getenv("PATH") ?: "") + ":" + File(context.applicationInfo.nativeLibraryDir).absolutePath

        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) {
            AppLog.e(TAG, "ffmpeg exit=$code\n${output.takeLast(800)}")
            error("ffmpeg falló (exit $code): ${output.takeLast(300)}")
        }
        if (output.isNotBlank()) {
            AppLog.d(TAG, output.takeLast(400))
        }
    }

    private fun resolveFfmpegBinary(): File {
        val native = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (native.exists()) return native
        // Fallback: search under unpacked packages dir
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg")
        val candidates = packages.walkTopDown()
            .filter { it.isFile && (it.name == "ffmpeg" || it.name == "libffmpeg.so") }
            .toList()
        return candidates.firstOrNull()
            ?: error("No se encontró el binario ffmpeg. Inicializá los binarios en Ajustes.")
    }

    private fun resolveFfmpegLibDir(): File {
        val lib = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        return if (lib.exists()) lib else File(context.applicationInfo.nativeLibraryDir)
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
