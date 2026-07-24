package com.melomaniac.app.download

import android.content.Context
import com.melomaniac.app.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloads and stores small cover JPEGs under filesDir/covers. */
class CoverStore(context: Context) {
    private val coversDir = File(context.applicationContext.filesDir, "covers").also { if (!it.exists()) it.mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fileFor(key: String): File {
        val safe = key.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80).ifBlank { "cover" }
        return File(coversDir, "$safe.jpg")
    }

    /**
     * Prefer [preferredUrl] (e.g. Spotify), else YouTube hqdefault for [youtubeId].
     * Returns existing file if already present.
     */
    fun obtain(key: String, preferredUrl: String?, youtubeId: String?): File? {
        val dest = fileFor(key)
        if (dest.exists() && dest.length() > 500) return dest

        val urls = buildList {
            preferredUrl?.takeIf { it.startsWith("http") }?.let { add(it) }
            youtubeId?.takeIf { it.isNotBlank() }?.let {
                add("https://i.ytimg.com/vi/$it/hqdefault.jpg")
                add("https://i.ytimg.com/vi/$it/mqdefault.jpg")
            }
        }
        for (url in urls) {
            if (download(url, dest)) {
                AppLog.i(TAG, "cover ok key=$key ← ${url.take(80)}")
                return dest
            }
        }
        AppLog.w(TAG, "cover miss key=$key")
        return null
    }

    fun deleteIfExists(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val f = File(path)
            if (f.exists() && f.absolutePath.startsWith(coversDir.absolutePath)) f.delete()
        }
    }

    private fun download(url: String, dest: File): Boolean {
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                if (bytes.size < 500) return false
                dest.outputStream().use { it.write(bytes) }
                true
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "download failed ${url.take(60)}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "CoverStore"
    }
}
