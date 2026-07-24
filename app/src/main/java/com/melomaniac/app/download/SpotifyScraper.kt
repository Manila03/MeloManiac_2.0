package com.melomaniac.app.download

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.melomaniac.app.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resuelve metadata pública de Spotify sin Web API / OAuth.
 * Cascada: embed HTML → página abierta → WebView oculto.
 */
class SpotifyScraper(
    private val appContext: Context? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun parseUrl(input: String): Pair<String, String>? = SpotifyUrls.parse(input)

    fun searchQuery(track: SpotifyTrackMeta): String =
        "${track.name} ${track.artists.joinToString(" ")}".trim()

    fun resolve(input: String): SpotifyResolve {
        val parsed = parseUrl(input) ?: error("URL de Spotify inválida")
        val (type, id) = parsed
        AppLog.i(TAG, "resolve $type/$id")

        val strategies = listOf(
            "embed" to { fetchHtml("https://open.spotify.com/embed/$type/$id") },
            "page" to { fetchHtml("https://open.spotify.com/$type/$id") },
        )

        var lastError: String? = null
        for ((name, loader) in strategies) {
            try {
                val html = loader()
                val entity = extractEntityJson(html)
                    ?: run {
                        lastError = "sin entity en $name"
                        AppLog.w(TAG, "strategy=$name: no entity json (${html.length} chars)")
                        null
                    }
                if (entity != null) {
                    val result = mapEntity(entity, type, id)
                    logResolved(name, result)
                    return result
                }
            } catch (e: Exception) {
                lastError = e.message
                AppLog.w(TAG, "strategy=$name failed: ${e.message}")
            }
        }

        if (appContext != null) {
            try {
                val html = fetchViaWebView("https://open.spotify.com/embed/$type/$id")
                val entity = extractEntityJson(html)
                    ?: error("WebView no devolvió metadata")
                val result = mapEntity(entity, type, id)
                logResolved("webview", result)
                return result
            } catch (e: Exception) {
                lastError = e.message
                AppLog.w(TAG, "strategy=webview failed: ${e.message}")
            }
        }

        error(
            lastError?.takeIf { it.contains("privada", ignoreCase = true) }
                ?: "No se pudo extraer metadata de Spotify ($type/$id). " +
                "La playlist puede ser privada o Spotify cambió el embed. ${lastError.orEmpty()}",
        )
    }

    private fun logResolved(strategy: String, result: SpotifyResolve) {
        when (result) {
            is SpotifyResolve.Track ->
                AppLog.i(TAG, "strategy=$strategy track → ${result.track.name}")
            is SpotifyResolve.Collection -> {
                val c = result.collection
                AppLog.i(TAG, "strategy=$strategy ${c.type} → ${c.tracks.size} items (${c.name})")
                if (c.tracks.size >= EMBED_SOFT_CAP) {
                    AppLog.w(TAG, "tracksMayBePartial: embed devolvió ${c.tracks.size} (posible tope)")
                }
            }
        }
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                if (resp.code == 404) error("Spotify no encontró el recurso (404)")
                if (resp.code == 401 || resp.code == 403) {
                    error("playlist privada o bloqueada (HTTP ${resp.code})")
                }
                error("HTTP ${resp.code} al pedir $url")
            }
            if (body.length < 500) {
                error("respuesta demasiado corta (${body.length}); posible bloqueo")
            }
            return body
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun fetchViaWebView(url: String): String {
        val ctx = appContext ?: error("WebView requiere Context")
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)

        Handler(Looper.getMainLooper()).post {
            try {
                val web = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = USER_AGENT
                }
                val bridge = object {
                    @JavascriptInterface
                    fun onHtml(html: String?) {
                        result.set(html)
                        latch.countDown()
                    }
                }
                web.addJavascriptInterface(bridge, "MeloBridge")
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        view?.evaluateJavascript(
                            "(function(){ try { MeloBridge.onHtml(document.documentElement.outerHTML); } catch(e) { MeloBridge.onHtml(''); } })();",
                            null,
                        )
                    }
                }
                web.loadUrl(url)
                // Safety: destroy after timeout on main thread
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { web.destroy() }
                    if (latch.count > 0) {
                        errorRef.set("timeout WebView")
                        latch.countDown()
                    }
                }, 20_000)
            } catch (e: Exception) {
                errorRef.set(e.message)
                latch.countDown()
            }
        }

        if (!latch.await(25, TimeUnit.SECONDS)) {
            error("timeout esperando WebView")
        }
        errorRef.get()?.let { error(it) }
        val html = result.get().orEmpty()
        if (html.length < 500) error("WebView HTML vacío o incompleto")
        // evaluateJavascript may JSON-encode the string
        return unwrapJsString(html)
    }

    private fun unwrapJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return runCatching {
                JSONObject("{\"v\":$trimmed}").getString("v")
            }.getOrDefault(trimmed)
        }
        return trimmed
    }

    private fun extractEntityJson(html: String): JSONObject? {
        // Next.js embed: <script type="application/json">{...props.pageProps.state.data.entity}</script>
        val jsonScript = Regex(
            """<script[^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        if (jsonScript != null) {
            runCatching {
                val root = JSONObject(jsonScript)
                entityFromNextData(root)?.let { return it }
            }.onFailure { AppLog.w(TAG, "parse application/json failed: ${it.message}") }
        }

        val nextData = Regex(
            """<script[^>]*id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        if (nextData != null) {
            runCatching {
                entityFromNextData(JSONObject(nextData))?.let { return it }
            }.onFailure { AppLog.w(TAG, "parse __NEXT_DATA__ failed: ${it.message}") }
        }

        return null
    }

    private fun entityFromNextData(root: JSONObject): JSONObject? {
        val pageProps = root.optJSONObject("props")?.optJSONObject("pageProps") ?: return null
        if (pageProps.has("status") && pageProps.optInt("status") == 404) {
            error("Spotify no encontró el recurso (404)")
        }
        val entity = pageProps.optJSONObject("state")
            ?.optJSONObject("data")
            ?.optJSONObject("entity")
        if (entity != null) return entity

        // Deep search for an object that looks like embed entity
        return findEntityDeep(pageProps)
    }

    private fun findEntityDeep(node: Any?, depth: Int = 0): JSONObject? {
        if (depth > 10 || node == null) return null
        when (node) {
            is JSONObject -> {
                val type = node.optString("type")
                if (type in setOf("track", "album", "playlist") &&
                    (node.has("trackList") || node.has("artists") || node.has("uri"))
                ) {
                    return node
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findEntityDeep(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findEntityDeep(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun mapEntity(entity: JSONObject, expectedType: String, expectedId: String): SpotifyResolve {
        val type = entity.optString("type").ifBlank { expectedType }
        val id = entity.optString("id").ifBlank {
            entity.optString("uri").substringAfterLast(':').ifBlank { expectedId }
        }
        return when (type) {
            "track" -> SpotifyResolve.Track(mapTrackEntity(entity, id))
            "album", "playlist" -> SpotifyResolve.Collection(mapCollectionEntity(entity, type, id))
            else -> error("Tipo Spotify no soportado: $type")
        }
    }

    private fun mapTrackEntity(entity: JSONObject, id: String): SpotifyTrackMeta {
        val artists = artistsFrom(entity)
        val name = entity.optString("title").ifBlank { entity.optString("name") }
        return SpotifyTrackMeta(
            id = id,
            name = name,
            artists = artists,
            albumName = entity.optString("subtitle").takeIf {
                it.isNotBlank() && artists.none { a -> a.equals(it, ignoreCase = true) }
            }.orEmpty(),
            durationMs = entity.optLong("duration"),
            coverUrl = coverUrl(entity),
            externalUrl = "https://open.spotify.com/track/$id",
        )
    }

    private fun mapCollectionEntity(entity: JSONObject, type: String, id: String): SpotifyCollection {
        val name = entity.optString("title").ifBlank { entity.optString("name") }
        val albumFallback = if (type == "album") name else null
        val cover = coverUrl(entity)
        val tracks = mutableListOf<SpotifyTrackMeta>()
        val list = entity.optJSONArray("trackList")
        if (list != null) {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                mapTrackListItem(item, albumFallback)?.let { tracks += it }
            }
        }
        if (tracks.isEmpty()) {
            error(
                if (type == "playlist") {
                    "playlist privada o bloqueada (sin temas en el embed)"
                } else {
                    "No se encontraron temas en el $type"
                },
            )
        }
        return SpotifyCollection(
            type = type,
            id = id,
            name = name,
            coverUrl = cover,
            externalUrl = "https://open.spotify.com/$type/$id",
            tracks = tracks,
        )
    }

    private fun mapTrackListItem(item: JSONObject, albumFallback: String?): SpotifyTrackMeta? {
        val uri = item.optString("uri")
        val id = when {
            uri.startsWith("spotify:track:") -> uri.removePrefix("spotify:track:")
            item.has("id") -> item.optString("id")
            else -> return null
        }
        if (id.isBlank()) return null
        if (item.optString("entityType").equals("episode", ignoreCase = true)) return null
        val title = item.optString("title").ifBlank { item.optString("name") }
        if (title.isBlank()) return null
        val artists = artistsFrom(item).ifEmpty {
            item.optString("subtitle").split(",", "&")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        return SpotifyTrackMeta(
            id = id,
            name = title,
            artists = artists,
            albumName = albumFallback.orEmpty(),
            durationMs = item.optLong("duration"),
            coverUrl = coverUrl(item),
            externalUrl = "https://open.spotify.com/track/$id",
        )
    }

    private fun artistsFrom(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("artists") ?: obj.optJSONArray("authors") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val el = arr.opt(i)
            when (el) {
                is JSONObject -> el.optString("name").takeIf { it.isNotBlank() }?.let { out += it }
                is String -> if (el.isNotBlank()) out += el
            }
        }
        return out
    }

    private fun coverUrl(obj: JSONObject): String? {
        obj.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)
            ?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        val vis = obj.optJSONObject("visualIdentity") ?: return null
        val image = vis.opt("image")
        when (image) {
            is JSONObject -> {
                image.optString("url").takeIf { it.isNotBlank() }?.let { return it }
                image.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                    ?.takeIf { it.isNotBlank() }?.let { return it }
            }
            is JSONArray -> {
                image.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            is String -> if (image.isNotBlank()) return image
        }
        return null
    }

    companion object {
        private const val TAG = "SpotifyScraper"
        private const val EMBED_SOFT_CAP = 50
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

object SpotifyUrls {
    fun parse(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        Regex("^spotify:(track|album|playlist):([a-zA-Z0-9]+)").find(trimmed)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex(
            """(?:https?://)?(?:open\.)?spotify\.com/(?:intl-[a-zA-Z]{2}/)?(track|album|playlist)/([a-zA-Z0-9]+)""",
            RegexOption.IGNORE_CASE,
        ).find(trimmed)?.let {
            return it.groupValues[1].lowercase() to it.groupValues[2]
        }
        Regex(
            """spotify\.com/(?:intl-[a-zA-Z]{2}/)?(track|album|playlist)/([a-zA-Z0-9]+)""",
            RegexOption.IGNORE_CASE,
        ).find(trimmed)?.let {
            return it.groupValues[1].lowercase() to it.groupValues[2]
        }
        return null
    }
}
