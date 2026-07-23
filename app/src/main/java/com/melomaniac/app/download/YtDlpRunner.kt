package com.melomaniac.app.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class YtHit(
    val id: String,
    val title: String,
    val uploader: String,
    val durationMs: Long,
    val url: String,
)

data class DownloadResult(
    val file: File,
    val format: String,
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val youtubeId: String?,
)

class YtDlpRunner(
    private val binaryManager: BinaryManager,
    private val musicDir: File,
) {
    private val progressRe = Pattern.compile("(\\d+(?:\\.\\d+)?)%")

    init {
        if (!musicDir.exists()) musicDir.mkdirs()
    }

    suspend fun search(query: String, limit: Int = 8): List<YtHit> = withContext(Dispatchers.IO) {
        binaryManager.ensureBinaries()
        val args = listOf(
            "ytsearch$limit:$query",
            "--flat-playlist",
            "--print",
            "%(.{id,title,uploader,duration,url})j",
            "--no-warnings",
        )
        val out = run(args, "search").stdout
        parseHits(out)
    }

    suspend fun listPlaylist(url: String): List<YtHit> = withContext(Dispatchers.IO) {
        binaryManager.ensureBinaries()
        val args = listOf(
            url,
            "--flat-playlist",
            "--yes-playlist",
            "--print",
            "%(.{id,title,uploader,duration,url})j",
            "--no-warnings",
        )
        parseHits(run(args, "playlist").stdout)
    }

    suspend fun downloadAudio(
        urlOrQuery: String,
        jobId: String,
        preferFlac: Boolean,
        fallbackQuality: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        binaryManager.ensureBinaries()
        val input = when {
            urlOrQuery.startsWith("http") || urlOrQuery.startsWith("ytsearch") -> urlOrQuery
            else -> "ytsearch1:$urlOrQuery"
        }
        fun attempt(flac: Boolean): DownloadResult {
            val formatArgs = if (flac) {
                listOf("-f", "bestaudio/best", "--extract-audio", "--audio-format", "flac", "--audio-quality", "0")
            } else {
                when (fallbackQuality) {
                    "320" -> listOf("-f", "bestaudio/best", "--extract-audio", "--audio-format", "mp3", "--audio-quality", "0")
                    "128" -> listOf("-f", "bestaudio[abr<=128]/bestaudio/best", "--extract-audio", "--audio-format", "m4a")
                    else -> listOf("-f", "bestaudio/best", "--extract-audio", "--audio-format", "m4a")
                }
            }
            val outTemplate = File(musicDir, "%(title).80B-%(id)s.%(ext)s").absolutePath
            val args = buildList {
                addAll(formatArgs)
                add("--no-playlist")
                add("--newline")
                add("--print-json")
                add("--no-warnings")
                add("-o")
                add(outTemplate)
                if (binaryManager.ffmpegFile.exists()) {
                    add("--ffmpeg-location")
                    add(binaryManager.ffmpegFile.absolutePath)
                }
                add(input)
            }
            val result = run(args, jobId, onProgress)
            if (result.exitCode != 0) {
                if (flac) return attempt(false)
                error(result.stdout.takeLast(800).ifBlank { "yt-dlp exit ${result.exitCode}" })
            }
            val info = extractJson(result.stdout)
            val title = info?.optString("title")?.ifBlank { null } ?: "Unknown"
            val artist = info?.optString("artist")?.ifBlank { null }
                ?: info?.optString("uploader")?.ifBlank { null }
            val youtubeId = info?.optString("id")?.ifBlank { null }
            val durationMs = ((info?.optDouble("duration") ?: 0.0) * 1000).toLong()
            val ext = if (flac) "flac" else if (fallbackQuality == "320") "mp3" else "m4a"
            val requested = info?.optJSONArray("requested_downloads")
                ?.optJSONObject(0)
                ?.optString("filepath")
            val file = when {
                !requested.isNullOrBlank() && File(requested).exists() -> File(requested)
                else -> musicDir.listFiles()
                    ?.filter { it.extension.lowercase() in listOf("flac", "m4a", "mp3", "opus", "webm") }
                    ?.maxByOrNull { it.lastModified() }
                    ?: error("Download finished but file missing")
            }
            return DownloadResult(file, ext, sanitize(title), artist, durationMs, youtubeId)
        }
        attempt(preferFlac)
    }

    private data class RunResult(val exitCode: Int, val stdout: String)

    private fun run(
        args: List<String>,
        jobId: String,
        onProgress: (Float) -> Unit = {},
    ): RunResult {
        val exe = binaryManager.ytdlpFile
        if (!exe.exists()) error("yt-dlp missing — abrí Ajustes y descargá los binarios")

        val cmd = listOf(exe.absolutePath) + args
        val pb = ProcessBuilder(cmd)
        pb.directory(binaryManager.workDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        val binPath = exe.parentFile?.absolutePath ?: ""
        env["PATH"] = "$binPath:/system/bin:" + (env["PATH"] ?: "")
        env["HOME"] = binaryManager.workDir.absolutePath
        env["TMPDIR"] = binaryManager.workDir.absolutePath
        env["XDG_CACHE_HOME"] = File(binaryManager.workDir, "cache").also { it.mkdirs() }.absolutePath

        val process = try {
            pb.start()
        } catch (e: Exception) {
            error(
                "No se pudo ejecutar yt-dlp: ${e.message}. " +
                    "En Ajustes → Reinstalar binarios. (Android no permite ejecutar desde files/)",
            )
        }
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val current = line ?: continue
                sb.append(current).append('\n')
                val m = progressRe.matcher(current)
                if (m.find()) {
                    onProgress(m.group(1)?.toFloatOrNull() ?: 0f)
                }
            }
        }
        val finished = process.waitFor(60, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            error("yt-dlp timed out ($jobId)")
        }
        Log.d("YtDlpRunner", "job=$jobId exit=${process.exitValue()}")
        return RunResult(process.exitValue(), sb.toString())
    }

    private fun parseHits(stdout: String): List<YtHit> {
        val items = mutableListOf<YtHit>()
        for (line in stdout.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("{")) continue
            try {
                val o = JSONObject(t)
                val id = o.optString("id")
                if (id.isBlank()) continue
                items += YtHit(
                    id = id,
                    title = o.optString("title", "Unknown"),
                    uploader = o.optString("uploader", o.optString("channel", "")),
                    durationMs = ((o.optDouble("duration", 0.0)) * 1000).toLong(),
                    url = o.optString("url").ifBlank { "https://www.youtube.com/watch?v=$id" },
                )
            } catch (_: Exception) {
            }
        }
        return items
    }

    private fun extractJson(stdout: String): JSONObject? {
        val lines = stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (i in lines.indices.reversed()) {
            try {
                return JSONObject(lines[i])
            } catch (_: Exception) {
            }
        }
        val start = stdout.indexOf('{')
        val end = stdout.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return try {
                JSONObject(stdout.substring(start, end + 1))
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_").trim().take(120).ifBlank { "track" }
}
