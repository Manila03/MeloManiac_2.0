package com.melomaniac.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.melomaniac.app.BuildConfig
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ReleaseUpdate(
    val tagName: String,
    val versionName: String,
    val apkUrl: String,
    val notes: String?,
)

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val update: ReleaseUpdate) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

class AppUpdater(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        AppLog.i("Update", "Checking GitHub releases…")
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MeloManiac/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    AppLog.i("Update", "No releases yet")
                    return@withContext UpdateCheckResult.UpToDate
                }
                if (!response.isSuccessful) {
                    val msg = "GitHub respondió ${response.code}"
                    AppLog.e("Update", msg)
                    return@withContext UpdateCheckResult.Failed(msg)
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Failed("Respuesta vacía de GitHub")
                }
                val json = JSONObject(body)
                val tagName = json.optString("tag_name").trim()
                if (tagName.isEmpty()) {
                    return@withContext UpdateCheckResult.Failed("Release sin tag")
                }
                val remoteVersion = normalizeVersion(tagName)
                val localVersion = normalizeVersion(BuildConfig.VERSION_NAME)
                if (compareSemVer(remoteVersion, localVersion) <= 0) {
                    AppLog.i("Update", "Up to date ($localVersion)")
                    return@withContext UpdateCheckResult.UpToDate
                }
                val assets = json.optJSONArray("assets")
                    ?: return@withContext UpdateCheckResult.Failed("Release sin assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name").lowercase()
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        if (name.contains("melomaniac")) break
                    }
                }
                if (apkUrl.isNullOrBlank()) {
                    return@withContext UpdateCheckResult.Failed("No hay APK en el release")
                }
                AppLog.i("Update", "Available $remoteVersion")
                UpdateCheckResult.Available(
                    ReleaseUpdate(
                        tagName = tagName,
                        versionName = remoteVersion,
                        apkUrl = apkUrl,
                        notes = json.optString("body").takeIf { it.isNotBlank() },
                    ),
                )
            }
        } catch (e: Exception) {
            AppLog.e("Update", "Check failed", e)
            UpdateCheckResult.Failed(e.message ?: "Error al buscar actualizaciones")
        }
    }

    suspend fun downloadApk(
        update: ReleaseUpdate,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        fun progress(msg: String) {
            AppLog.i("Update", msg)
            onProgress(msg)
        }
        progress("Descargando ${update.versionName}…")
        val dir = File(context.cacheDir, "updates").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        val out = File(dir, "MeloManiac-${update.versionName}.apk")
        val request = Request.Builder()
            .url(update.apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "MeloManiac/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Descarga falló (${response.code})")
            }
            val body = response.body ?: error("APK vacío")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var readTotal = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (total > 0) {
                            val pct = (readTotal * 100 / total).toInt()
                            onProgress("Descargando ${update.versionName}… $pct%")
                        }
                    }
                }
            }
        }
        if (out.length() < 10_000L) error("APK descargado inválido")
        progress("Listo para instalar")
        out
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun intentToAllowInstalls(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    companion object {
        const val GITHUB_OWNER = "Manila03"
        const val GITHUB_REPO = "MeloManiac_2.0"
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").trim()

        /** Returns >0 if a newer than b, 0 equal, <0 if a older. */
        fun compareSemVer(a: String, b: String): Int {
            fun parts(v: String): List<Int> =
                v.split('.', '-', '+')
                    .map { it.takeWhile { ch -> ch.isDigit() } }
                    .filter { it.isNotEmpty() }
                    .map { it.toIntOrNull() ?: 0 }
                    .ifEmpty { listOf(0) }

            val pa = parts(a)
            val pb = parts(b)
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
