package com.melomaniac.app.download

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.melomaniac.app.util.AppLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Resuelve metadata pública de Spotify sin Web API / OAuth.
 * Cascada: embed HTML → página abierta → WebView oculto.
 * Playlists: el embed corta en ~100 temas; se completa con Pathfinder (token anónimo del embed).
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
                    val result = finalizeResolve(mapEntity(entity, type, id), html)
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
                val result = finalizeResolve(mapEntity(entity, type, id), html)
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
            }
        }
    }

    /**
     * El embed SSR suele devolver como máximo [EMBED_TRACK_CAP] temas.
     * Playlists y álbumes se completan con Pathfinder usando el accessToken anónimo del HTML.
     */
    private fun finalizeResolve(result: SpotifyResolve, html: String): SpotifyResolve {
        if (result !is SpotifyResolve.Collection) return result
        val c = result.collection
        if (c.type != "playlist" && c.type != "album") return result

        val token = extractAccessToken(html)
        if (token.isNullOrBlank()) {
            if (c.tracks.size >= EMBED_TRACK_CAP) {
                AppLog.w(TAG, "${c.type} may be truncated at ${c.tracks.size} (sin accessToken)")
                return SpotifyResolve.Collection(c.copy(tracksMayBePartial = true))
            }
            return result
        }

        val discoveredHashes = discoverPathfinderHashes(html)
        return try {
            val (pathName, pathTracks) = when (c.type) {
                "playlist" -> fetchAllPlaylistTracks(token, c.id, discoveredHashes)
                else -> fetchAllAlbumTracks(token, c.id, discoveredHashes)
            }
            if (pathTracks.isEmpty()) {
                AppLog.w(TAG, "pathfinder returned 0 tracks; keeping embed (${c.tracks.size})")
                return if (c.tracks.size >= EMBED_TRACK_CAP) {
                    SpotifyResolve.Collection(c.copy(tracksMayBePartial = true))
                } else {
                    result
                }
            }
            AppLog.i(
                TAG,
                "pathfinder ${c.type} ${c.id}: ${pathTracks.size} tracks " +
                    "(embed had ${c.tracks.size})",
            )
            SpotifyResolve.Collection(
                c.copy(
                    name = pathName?.takeIf { it.isNotBlank() } ?: c.name,
                    tracks = pathTracks,
                    tracksMayBePartial = false,
                ),
            )
        } catch (e: Exception) {
            AppLog.w(TAG, "pathfinder failed, keeping embed: ${e.message}")
            if (c.tracks.size >= EMBED_TRACK_CAP) {
                AppLog.w(TAG, "${c.type} parcial: solo ${c.tracks.size} temas del embed")
                SpotifyResolve.Collection(c.copy(tracksMayBePartial = true))
            } else {
                result
            }
        }
    }

    private fun extractAccessToken(html: String): String? =
        Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

    /** Pull candidate sha256 hashes near fetchPlaylist / getAlbum from HTML or linked JS. */
    private fun discoverPathfinderHashes(html: String): List<String> {
        val found = LinkedHashSet<String>()
        Regex("""fetchPlaylist["'\s\S]{0,120}?([a-f0-9]{64})""").findAll(html).forEach {
            found += it.groupValues[1]
        }
        Regex("""getAlbum["'\s\S]{0,120}?([a-f0-9]{64})""").findAll(html).forEach {
            found += it.groupValues[1]
        }
        Regex(""""sha256Hash"\s*:\s*"([a-f0-9]{64})"""").findAll(html).forEach {
            found += it.groupValues[1]
        }
        // Fetch a couple of player JS bundles referenced by the page.
        val scriptSrcs = Regex("""<script[^>]+src="(https://[^"]+)"[^>]*>""")
            .findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("web-player", ignoreCase = true) || it.contains("embed", ignoreCase = true) }
            .take(3)
            .toList()
        for (src in scriptSrcs) {
            runCatching {
                val js = fetchHtml(src)
                Regex("""fetchPlaylist["'\s\S]{0,200}?([a-f0-9]{64})""").findAll(js).forEach {
                    found += it.groupValues[1]
                }
                Regex("""getAlbum["'\s\S]{0,200}?([a-f0-9]{64})""").findAll(js).forEach {
                    found += it.groupValues[1]
                }
            }.onFailure { AppLog.w(TAG, "hash discover from $src: ${it.message}") }
        }
        return found.toList()
    }

    private fun fetchAllPlaylistTracks(
        accessToken: String,
        playlistId: String,
        discoveredHashes: List<String>,
    ): Pair<String?, List<SpotifyTrackMeta>> {
        val hashes = (listOf(playlistHash) + discoveredHashes + PATHFINDER_PLAYLIST_HASH_FALLBACKS)
            .distinct()
        var lastError: Exception? = null
        for (hash in hashes) {
            try {
                return paginatePlaylist(accessToken, playlistId, hash).also {
                    playlistHash = hash
                }
            } catch (e: Exception) {
                lastError = e
                AppLog.w(TAG, "playlist hash ${hash.take(12)}… failed: ${e.message}")
            }
        }
        throw lastError ?: error("pathfinder playlist failed")
    }

    private fun paginatePlaylist(
        accessToken: String,
        playlistId: String,
        hash: String,
    ): Pair<String?, List<SpotifyTrackMeta>> {
        val out = ArrayList<SpotifyTrackMeta>()
        val seen = HashSet<String>()
        var offset = 0
        var total: Int? = null
        var playlistName: String? = null
        var pages = 0

        while (pages < PATHFINDER_MAX_PAGES) {
            pages++
            val page = pathfinderQuery(
                accessToken = accessToken,
                operationName = "fetchPlaylist",
                hash = hash,
                variables = JSONObject()
                    .put("uri", "spotify:playlist:$playlistId")
                    .put("offset", offset)
                    .put("limit", PATHFINDER_PAGE_SIZE)
                    .put("enableWatchFeedEntrypoint", false),
            )
            val pl = page.optJSONObject("data")?.optJSONObject("playlistV2")
                ?: error("pathfinder sin playlistV2")
            if (playlistName == null) {
                playlistName = pl.optString("name").takeIf { it.isNotBlank() }
            }
            val content = pl.optJSONObject("content")
                ?: error("pathfinder sin content")
            if (total == null && content.has("totalCount")) {
                total = content.optInt("totalCount")
            }
            val items = content.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                val track = mapPathfinderPlaylistItem(items.optJSONObject(i) ?: continue) ?: continue
                if (seen.add(track.id)) out += track
            }

            offset += PATHFINDER_PAGE_SIZE
            if (items.length() < PATHFINDER_PAGE_SIZE) break
            if (total != null && offset >= total) break
        }

        AppLog.i(TAG, "pathfinder pages=$pages collected=${out.size} totalCount=${total ?: "?"}")
        return playlistName to out
    }

    private fun fetchAllAlbumTracks(
        accessToken: String,
        albumId: String,
        discoveredHashes: List<String>,
    ): Pair<String?, List<SpotifyTrackMeta>> {
        val hashes = (listOf(albumHash) + discoveredHashes + PATHFINDER_ALBUM_HASH_FALLBACKS)
            .distinct()
        var lastError: Exception? = null
        for (hash in hashes) {
            try {
                return paginateAlbum(accessToken, albumId, hash).also {
                    albumHash = hash
                }
            } catch (e: Exception) {
                lastError = e
                AppLog.w(TAG, "album hash ${hash.take(12)}… failed: ${e.message}")
            }
        }
        throw lastError ?: error("pathfinder album failed")
    }

    private fun paginateAlbum(
        accessToken: String,
        albumId: String,
        hash: String,
    ): Pair<String?, List<SpotifyTrackMeta>> {
        val out = ArrayList<SpotifyTrackMeta>()
        val seen = HashSet<String>()
        var offset = 0
        var total: Int? = null
        var albumName: String? = null
        var pages = 0

        while (pages < PATHFINDER_MAX_PAGES) {
            pages++
            val page = pathfinderQuery(
                accessToken = accessToken,
                operationName = "getAlbum",
                hash = hash,
                variables = JSONObject()
                    .put("uri", "spotify:album:$albumId")
                    .put("locale", "")
                    .put("offset", offset)
                    .put("limit", PATHFINDER_PAGE_SIZE),
            )
            val album = page.optJSONObject("data")?.optJSONObject("albumUnion")
                ?: error("pathfinder sin albumUnion")
            if (albumName == null) {
                albumName = album.optString("name").takeIf { it.isNotBlank() }
            }
            val tracksV2 = album.optJSONObject("tracksV2")
                ?: error("pathfinder sin tracksV2")
            if (total == null && tracksV2.has("totalCount")) {
                total = tracksV2.optInt("totalCount")
            }
            val items = tracksV2.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) break

            for (i in 0 until items.length()) {
                val track = mapPathfinderAlbumItem(
                    items.optJSONObject(i) ?: continue,
                    albumName,
                ) ?: continue
                if (seen.add(track.id)) out += track
            }

            offset += PATHFINDER_PAGE_SIZE
            if (items.length() < PATHFINDER_PAGE_SIZE) break
            if (total != null && offset >= total) break
        }

        AppLog.i(TAG, "album pathfinder pages=$pages collected=${out.size} totalCount=${total ?: "?"}")
        return albumName to out
    }

    private fun pathfinderQuery(
        accessToken: String,
        operationName: String,
        hash: String,
        variables: JSONObject,
    ): JSONObject {
        val bodyJson = JSONObject()
            .put("operationName", operationName)
            .put("variables", variables)
            .put(
                "extensions",
                JSONObject().put(
                    "persistedQuery",
                    JSONObject()
                        .put("version", 1)
                        .put("sha256Hash", hash),
                ),
            )
            .toString()
        var lastError: String? = null
        for (url in PATHFINDER_URLS) {
            val body = bodyJson.toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("App-Platform", "WebPlayer")
                .header("Origin", "https://open.spotify.com")
                .header("Referer", "https://open.spotify.com/")
                .build()
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        lastError = "HTTP ${resp.code}"
                        return@use
                    }
                    val json = JSONObject(text)
                    if (json.has("errors")) {
                        lastError = json.optJSONArray("errors")?.optJSONObject(0)
                            ?.optString("message")
                            ?: "pathfinder errors"
                        return@use
                    }
                    return json
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        error(lastError ?: "pathfinder request failed")
    }

    private fun mapPathfinderAlbumItem(item: JSONObject, albumFallback: String?): SpotifyTrackMeta? {
        val track = item.optJSONObject("track") ?: return null
        val uri = track.optString("uri")
        val id = when {
            uri.startsWith("spotify:track:") -> uri.removePrefix("spotify:track:")
            else -> track.optString("id")
        }
        if (id.isBlank()) return null
        val name = track.optString("name")
        if (name.isBlank()) return null
        val artists = mutableListOf<String>()
        val artistItems = track.optJSONObject("artists")?.optJSONArray("items")
        if (artistItems != null) {
            for (i in 0 until artistItems.length()) {
                artistItems.optJSONObject(i)
                    ?.optJSONObject("profile")
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { artists += it }
            }
        }
        val durationMs = track.optJSONObject("duration")?.optLong("totalMilliseconds")
            ?: track.optLong("durationMs")
        return SpotifyTrackMeta(
            id = id,
            name = name,
            artists = artists,
            albumName = albumFallback.orEmpty(),
            durationMs = durationMs,
            coverUrl = null,
            externalUrl = "https://open.spotify.com/track/$id",
        )
    }

    private fun mapPathfinderPlaylistItem(item: JSONObject): SpotifyTrackMeta? {
        val data = item.optJSONObject("itemV2")?.optJSONObject("data") ?: return null
        if (!data.optString("__typename").equals("Track", ignoreCase = true)) return null
        val uri = data.optString("uri")
        val id = when {
            uri.startsWith("spotify:track:") -> uri.removePrefix("spotify:track:")
            else -> data.optString("id")
        }
        if (id.isBlank()) return null
        val name = data.optString("name")
        if (name.isBlank()) return null

        val artists = mutableListOf<String>()
        val artistItems = data.optJSONObject("artists")?.optJSONArray("items")
        if (artistItems != null) {
            for (i in 0 until artistItems.length()) {
                artistItems.optJSONObject(i)
                    ?.optJSONObject("profile")
                    ?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { artists += it }
            }
        }

        val album = data.optJSONObject("albumOfTrack")
        val albumName = album?.optString("name").orEmpty()
        val cover = album?.optJSONObject("coverArt")?.optJSONArray("sources")
            ?.let { sources ->
                // Prefer larger art when present
                var bestUrl: String? = null
                var bestArea = -1
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val url = s.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val area = s.optInt("height") * s.optInt("width")
                    if (area >= bestArea) {
                        bestArea = area
                        bestUrl = url
                    }
                }
                bestUrl
            }

        val durationMs = data.optJSONObject("trackDuration")
            ?.optLong("totalMilliseconds")
            ?: data.optLong("duration")

        return SpotifyTrackMeta(
            id = id,
            name = name,
            artists = artists,
            albumName = albumName,
            durationMs = durationMs,
            coverUrl = cover,
            externalUrl = "https://open.spotify.com/track/$id",
        )
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
        /** Soft SSR cap observed on open.spotify.com/embed playlists. */
        private const val EMBED_TRACK_CAP = 100
        private const val PATHFINDER_PAGE_SIZE = 100
        /** Spotify playlist maximum is 10_000 tracks. */
        private const val PATHFINDER_MAX_PAGES = 100
        private val PATHFINDER_PLAYLIST_HASH_FALLBACKS = listOf(
            "7982b11e21535cd2594badc40030b745671b61a1fa66766e569d45e6364f3422",
            "346811f856fb0b7e4f6c59f8ebea78dd081c6e2fb01b77c954b26259d5fc6763",
        )
        private val PATHFINDER_ALBUM_HASH_FALLBACKS = listOf(
            "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10",
        )
        private val PATHFINDER_URLS = listOf(
            "https://api-partner.spotify.com/pathfinder/v1/query",
            "https://api-partner.spotify.com/pathfinder/v2/query",
        )
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var playlistHash: String = PATHFINDER_PLAYLIST_HASH_FALLBACKS.first()

        @Volatile
        private var albumHash: String = PATHFINDER_ALBUM_HASH_FALLBACKS.first()
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
