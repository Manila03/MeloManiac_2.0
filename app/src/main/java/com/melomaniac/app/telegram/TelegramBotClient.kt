package com.melomaniac.app.telegram

import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class TelegramFileRef(
    val fileId: String,
    val fileUniqueId: String? = null,
    val fileSize: Long? = null,
)

data class TelegramBotInfo(
    val id: Long,
    val username: String?,
    val firstName: String?,
)

/**
 * Thin Bot API client (OkHttp). Stores blobs in a private channel the bot administers.
 */
class TelegramBotClient(
    private val token: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val baseUrl = "https://api.telegram.org/bot$token"
    private val fileBaseUrl = "https://api.telegram.org/file/bot$token"

    suspend fun getMe(): TelegramBotInfo = withContext(Dispatchers.IO) {
        val json = apiGet("getMe")
        val result = json.getJSONObject("result")
        TelegramBotInfo(
            id = result.getLong("id"),
            username = result.optString("username").ifBlank { null },
            firstName = result.optString("first_name").ifBlank { null },
        )
    }

    /** Sends a plain text message (used to verify channel access). */
    suspend fun sendMessage(chatId: String, text: String): Long = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", text)
            .build()
        val json = apiPost("sendMessage", body)
        json.getJSONObject("result").getLong("message_id")
    }

    suspend fun sendDocument(
        chatId: String,
        file: File,
        caption: String? = null,
        fileName: String = file.name,
    ): TelegramFileRef = withContext(Dispatchers.IO) {
        require(file.exists() && file.isFile) { "File missing: ${file.absolutePath}" }
        AppLog.i(TAG, "upload ${file.name} (${file.length()} bytes) → $chatId")
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(
                "document",
                fileName,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
        if (!caption.isNullOrBlank()) {
            builder.addFormDataPart("caption", caption.take(1024))
        }
        val json = apiPost("sendDocument", builder.build())
        val doc = json.getJSONObject("result").getJSONObject("document")
        TelegramFileRef(
            fileId = doc.getString("file_id"),
            fileUniqueId = doc.optString("file_unique_id").ifBlank { null },
            fileSize = if (doc.has("file_size")) doc.getLong("file_size") else file.length(),
        )
    }

    /**
     * Resolves a temporary download path for [fileId].
     * Telegram file links typically expire after ~1 hour.
     */
    suspend fun getFilePath(fileId: String): String = withContext(Dispatchers.IO) {
        val json = apiGet("getFile", "file_id" to fileId)
        json.getJSONObject("result").getString("file_path")
    }

    suspend fun downloadFile(filePath: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "$fileBaseUrl/$filePath"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("Telegram download failed HTTP ${resp.code}")
            }
            resp.body?.bytes() ?: error("Empty Telegram file body")
        }
    }

    private fun apiGet(method: String, vararg params: Pair<String, String>): JSONObject {
        val url = StringBuilder("$baseUrl/$method")
        if (params.isNotEmpty()) {
            url.append('?')
            params.forEachIndexed { i, (k, v) ->
                if (i > 0) url.append('&')
                url.append(java.net.URLEncoder.encode(k, "UTF-8"))
                url.append('=')
                url.append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        val req = Request.Builder().url(url.toString()).get().build()
        return parseResponse(req)
    }

    private fun apiPost(method: String, body: MultipartBody): JSONObject {
        val req = Request.Builder()
            .url("$baseUrl/$method")
            .post(body)
            .build()
        return parseResponse(req)
    }

    private fun parseResponse(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLog.e(TAG, "HTTP ${resp.code}: ${text.take(400)}")
                error("Telegram HTTP ${resp.code}: ${text.take(200)}")
            }
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) {
                val desc = json.optString("description", "unknown error")
                AppLog.e(TAG, "API error: $desc")
                error("Telegram: $desc")
            }
            return json
        }
    }

    companion object {
        private const val TAG = "TelegramBot"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

        fun fromToken(token: String): TelegramBotClient =
            TelegramBotClient(token.trim())
    }
}
