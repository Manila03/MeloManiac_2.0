package com.melomaniac.app.download

import com.melomaniac.app.util.AppLog
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
    init {
        if (!musicDir.exists()) musicDir.mkdirs()
    }

    suspend fun search(query: String, limit: Int = 8): List<YtHit> = withContext(Dispatchers.IO) {
        AppLog.i("yt-dlp", "search: $query (limit=$limit)")
        binaryManager.ensureBinaries()
        val request = YoutubeDLRequest("ytsearch$limit:$query")
        request.addOption("--flat-playlist")
        request.addOption("--print", "%(.{id,title,uploader,duration,url})j")
        request.addOption("--no-warnings")
        val out = execute(request, "search").out
        val hits = parseHits(out)
        AppLog.i("yt-dlp", "search → ${hits.size} hits")
        hits
    }

    suspend fun listPlaylist(url: String): List<YtHit> = withContext(Dispatchers.IO) {
        AppLog.i("yt-dlp", "list playlist: $url")
        binaryManager.ensureBinaries()
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("--yes-playlist")
        request.addOption("--print", "%(.{id,title,uploader,duration,url})j")
        request.addOption("--no-warnings")
        val hits = parseHits(execute(request, "playlist").out)
        AppLog.i("yt-dlp", "playlist → ${hits.size} items")
        hits
    }

    suspend fun downloadAudio(
        urlOrQuery: String,
        jobId: String,
        preferFlac: Boolean,
        fallbackQuality: String,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        AppLog.i("yt-dlp", "download job=$jobId preferFlac=$preferFlac quality=$fallbackQuality")
        AppLog.d("yt-dlp", "input=$urlOrQuery")
        binaryManager.ensureBinaries()
        val input = when {
            urlOrQuery.startsWith("http") || urlOrQuery.startsWith("ytsearch") -> urlOrQuery
            else -> "ytsearch1:$urlOrQuery"
        }

        fun attempt(flac: Boolean): DownloadResult {
            if (!flac) AppLog.w("yt-dlp", "job=$jobId fallback (no FLAC)")
            val before = musicDir.listFiles()?.map { it.absolutePath }?.toSet().orEmpty()
            val outTemplate = File(musicDir, "%(title).80B-%(id)s.%(ext)s").absolutePath
            val request = YoutubeDLRequest(input)
            if (flac) {
                request.addOption("-f", "bestaudio/best")
                request.addOption("--extract-audio")
                request.addOption("--audio-format", "flac")
                request.addOption("--audio-quality", "0")
            } else {
                when (fallbackQuality) {
                    "320" -> {
                        request.addOption("-f", "bestaudio/best")
                        request.addOption("--extract-audio")
                        request.addOption("--audio-format", "mp3")
                        request.addOption("--audio-quality", "0")
                    }
                    "128" -> {
                        request.addOption("-f", "bestaudio[abr<=128]/bestaudio/best")
                        request.addOption("--extract-audio")
                        request.addOption("--audio-format", "m4a")
                    }
                    else -> {
                        request.addOption("-f", "bestaudio/best")
                        request.addOption("--extract-audio")
                        request.addOption("--audio-format", "m4a")
                    }
                }
            }
            request.addOption("--no-playlist")
            request.addOption("--newline")
            request.addOption("--print-json")
            request.addOption("--no-warnings")
            request.addOption("-o", outTemplate)

            val response = try {
                execute(request, jobId) { progress, _, line ->
                    if (progress > 0f) onProgress(progress)
                    val trimmed = line?.trim().orEmpty()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("{")) {
                        AppLog.d("yt-dlp", "[$jobId] $trimmed")
                    }
                }
            } catch (e: Exception) {
                AppLog.e("yt-dlp", "job=$jobId failed", e)
                if (flac) return attempt(false)
                throw e
            }

            val info = extractJson(response.out)
            val title = info?.optString("title")?.ifBlank { null } ?: "Unknown"
            val artist = info?.optString("artist")?.ifBlank { null }
                ?: info?.optString("uploader")?.ifBlank { null }
            val youtubeId = info?.optString("id")?.ifBlank { null }
            val durationMs = ((info?.optDouble("duration") ?: 0.0) * 1000).toLong()
            val ext = if (flac) "flac" else if (fallbackQuality == "320") "mp3" else "m4a"
            val requested = info?.optJSONArray("requested_downloads")
                ?.optJSONObject(0)
                ?.optString("filepath")
            val after = musicDir.listFiles().orEmpty()
            val file = when {
                !requested.isNullOrBlank() && File(requested).exists() -> File(requested)
                else -> after
                    .filter { it.absolutePath !in before }
                    .filter { it.extension.lowercase() in listOf("flac", "m4a", "mp3", "opus", "webm") }
                    .maxByOrNull { it.lastModified() }
                    ?: after
                        .filter { it.extension.lowercase() in listOf("flac", "m4a", "mp3", "opus", "webm") }
                        .maxByOrNull { it.lastModified() }
                    ?: error("Download finished but file missing")
            }
            AppLog.i("yt-dlp", "job=$jobId ok → ${file.name} ($ext)")
            return DownloadResult(file, ext, sanitize(title), artist, durationMs, youtubeId)
        }
        attempt(preferFlac)
    }

    private data class ExecResult(val out: String, val err: String, val exitCode: Int)

    private fun execute(
        request: YoutubeDLRequest,
        processId: String,
        callback: ((Float, Long, String?) -> Unit)? = null,
    ): ExecResult {
        AppLog.d("yt-dlp", "exec[$processId]")
        val response = if (callback != null) {
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                callback(progress, eta, line)
            }
        } else {
            YoutubeDL.getInstance().execute(request, processId, null)
        }
        AppLog.i("yt-dlp", "job=$processId exit=${response.exitCode}")
        if (response.exitCode != 0) {
            val detail = (response.err.ifBlank { response.out }).takeLast(800)
            error("yt-dlp exit ${response.exitCode}: $detail")
        }
        return ExecResult(response.out, response.err, response.exitCode)
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
