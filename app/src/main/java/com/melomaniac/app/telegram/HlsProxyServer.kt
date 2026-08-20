package com.melomaniac.app.telegram

import com.melomaniac.app.data.LibraryRepository
import com.melomaniac.app.data.SettingsRepository
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Localhost HLS proxy: ExoPlayer hits 127.0.0.1 and we fetch segments from Telegram on demand.
 *
 * Routes:
 * - GET /hls/{trackId}/index.m3u8
 * - GET /hls/{trackId}/seg/{index}
 */
class HlsProxyServer(
    private val library: LibraryRepository,
    private val settingsRepo: SettingsRepository,
    private val diskCacheDir: File? = null,
    private val port: Int = DEFAULT_PORT,
) {
    private val running = AtomicBoolean(false)
    private val startMutex = Mutex()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val executor = Executors.newCachedThreadPool()

    /** fileId → (filePath, expiresAtMs) */
    private val pathCache = ConcurrentHashMap<String, Pair<String, Long>>()

    init {
        diskCacheDir?.mkdirs()
    }

    fun baseUrl(): String = "http://127.0.0.1:$port"

    fun playlistUrl(trackId: String): String = "${baseUrl()}/hls/$trackId/index.m3u8"

    /** Progressive (non-HLS) audio stored as a single Telegram document. */
    fun progressiveUrl(trackId: String): String = "${baseUrl()}/file/$trackId"

    suspend fun ensureStarted() {
        if (running.get()) return
        startMutex.withLock {
            if (running.get()) return
            startLocked()
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        pathCache.clear()
        AppLog.i(TAG, "stopped")
    }

    private fun startLocked() {
        val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running.set(true)
        acceptThread = thread(name = "HlsProxyAccept", isDaemon = true) {
            AppLog.i(TAG, "listening on 127.0.0.1:$port")
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    executor.execute { handleClient(socket) }
                } catch (_: SocketException) {
                    break
                } catch (e: Exception) {
                    if (running.get()) AppLog.w(TAG, "accept: ${e.message}")
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                // Drain headers
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2 || parts[0] != "GET") {
                    writeResponse(s, 405, "text/plain", "Method Not Allowed")
                    return
                }
                val path = parts[1].substringBefore('?')
                when {
                    path.matches(PLAYLIST_RE) -> {
                        val trackId = PLAYLIST_RE.matchEntire(path)!!.groupValues[1]
                        servePlaylist(s, trackId)
                    }
                    path.matches(SEGMENT_RE) -> {
                        val m = SEGMENT_RE.matchEntire(path)!!
                        serveSegment(s, m.groupValues[1], m.groupValues[2].toInt())
                    }
                    path.matches(FILE_RE) -> {
                        val trackId = FILE_RE.matchEntire(path)!!.groupValues[1]
                        serveProgressive(s, trackId)
                    }
                    else -> writeResponse(s, 404, "text/plain", "Not Found")
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "client error: ${e.message}")
                runCatching { writeResponse(s, 500, "text/plain", e.message ?: "error") }
            }
        }
    }

    private fun servePlaylist(socket: Socket, trackId: String) {
        val body = kotlinx.coroutines.runBlocking {
            buildPlaylist(trackId)
        }
        writeResponse(socket, 200, "application/vnd.apple.mpegurl", body)
    }

    private fun serveSegment(socket: Socket, trackId: String, index: Int) {
        val bytes = kotlinx.coroutines.runBlocking {
            fetchSegmentBytes(trackId, index)
        }
        writeBytes(socket, 200, "video/mp2t", bytes)
    }

    private fun serveProgressive(socket: Socket, trackId: String) {
        val (bytes, mime) = kotlinx.coroutines.runBlocking {
            val track = library.getTrack(trackId)
            val mime = mimeForFormat(track?.format)
            fetchSegmentBytes(trackId, 0) to mime
        }
        writeBytes(socket, 200, mime, bytes)
    }

    private fun mimeForFormat(format: String?): String = when (format?.lowercase()) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private suspend fun buildPlaylist(trackId: String): String = withContext(Dispatchers.IO) {
        val segments = library.getSegments(trackId)
        if (segments.isEmpty()) error("Sin segmentos para track $trackId")
        val target = segments.maxOfOrNull { it.durationSec }?.toInt()?.coerceAtLeast(1) ?: 10
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:$target")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            for (seg in segments) {
                appendLine("#EXTINF:${"%.3f".format(seg.durationSec)},")
                appendLine("/hls/$trackId/seg/${seg.segmentIndex}")
            }
            appendLine("#EXT-X-ENDLIST")
        }
    }

    private suspend fun fetchSegmentBytes(trackId: String, index: Int): ByteArray = withContext(Dispatchers.IO) {
        val segments = library.getSegments(trackId)
        val seg = segments.firstOrNull { it.segmentIndex == index }
            ?: error("Segmento $index no encontrado")
        val cacheFile = diskCacheDir?.let { File(it, "${seg.telegramFileId}.bin") }
        if (cacheFile != null && cacheFile.exists() &&
            System.currentTimeMillis() - cacheFile.lastModified() < PATH_TTL_MS
        ) {
            return@withContext cacheFile.readBytes()
        }
        val settings = settingsRepo.get()
        if (!settings.isTelegramConfigured) error("Telegram no configurado")
        val client = TelegramBotClient.fromToken(settings.telegramBotToken)
        val filePath = resolveFilePath(client, seg.telegramFileId)
        val bytes = client.downloadFile(filePath)
        if (cacheFile != null) {
            runCatching {
                trimDiskCache()
                cacheFile.writeBytes(bytes)
            }
        }
        bytes
    }

    private fun trimDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        val now = System.currentTimeMillis()
        for (f in files) {
            if (now - f.lastModified() > PATH_TTL_MS) {
                total -= f.length()
                f.delete()
            }
        }
        val remaining = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        total = remaining.sumOf { it.length() }
        var i = 0
        while (total > DISK_CACHE_MAX_BYTES && i < remaining.size) {
            total -= remaining[i].length()
            remaining[i].delete()
            i++
        }
    }

    private suspend fun resolveFilePath(client: TelegramBotClient, fileId: String): String {
        val now = System.currentTimeMillis()
        val cached = pathCache[fileId]
        if (cached != null && cached.second > now) return cached.first
        val path = client.getFilePath(fileId)
        pathCache[fileId] = path to (now + PATH_TTL_MS)
        return path
    }

    private fun writeResponse(socket: Socket, code: Int, contentType: String, body: String) {
        writeBytes(socket, code, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(socket: Socket, code: Int, contentType: String, body: ByteArray) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val status = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    companion object {
        private const val TAG = "HlsProxy"
        const val DEFAULT_PORT = 8765
        private const val PATH_TTL_MS = 50L * 60L * 1000L // 50 min
        private const val DISK_CACHE_MAX_BYTES = 200L * 1024L * 1024L // 200 MB
        private val PLAYLIST_RE = Regex("^/hls/([^/]+)/index\\.m3u8$")
        private val SEGMENT_RE = Regex("^/hls/([^/]+)/seg/(\\d+)$")
        private val FILE_RE = Regex("^/file/([^/]+)$")
    }
}
