package com.melomaniac.app.download

import com.melomaniac.app.util.AppLog
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotifyTrackMeta(
    val id: String,
    val name: String,
    val artists: List<String>,
    val albumName: String,
    val durationMs: Long,
    val coverUrl: String?,
    val externalUrl: String,
)

data class SpotifyCollection(
    val type: String,
    val id: String,
    val name: String,
    val coverUrl: String?,
    val externalUrl: String,
    val tracks: List<SpotifyTrackMeta>,
)

sealed class SpotifyResolve {
    data class Track(val track: SpotifyTrackMeta) : SpotifyResolve()
    data class Collection(val collection: SpotifyCollection) : SpotifyResolve()
}

class SpotifyApi(
    private val auth: SpotifyAuth? = null,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var appToken: String? = null
    @Volatile private var appTokenExpiresAt: Long = 0

    fun parseUrl(input: String): Pair<String, String>? {
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

    fun resolve(input: String, clientId: String, clientSecret: String): SpotifyResolve {
        val parsed = parseUrl(input) ?: error("URL de Spotify inválida")
        AppLog.i("Spotify", "resolve ${parsed.first}/${parsed.second}")

        val access = when (parsed.first) {
            "playlist" -> {
                val user = auth?.userAccessToken(clientId, clientSecret)
                if (user.isNullOrBlank()) {
                    error(
                        "Para playlists de Spotify conectá tu cuenta en Ajustes → Conectar Spotify. " +
                            "Desde 2026 Client Credentials ya no puede leer el contenido de playlists. " +
                            "También registrá el Redirect URI: ${SpotifyAuth.REDIRECT_URI}",
                    )
                }
                user
            }
            else -> {
                // Albums/tracks: prefer user token, else app credentials.
                auth?.userAccessToken(clientId, clientSecret)
                    ?: clientCredentialsToken(clientId, clientSecret)
            }
        }

        return when (parsed.first) {
            "track" -> SpotifyResolve.Track(fetchTrack(parsed.second, access)).also {
                AppLog.i("Spotify", "track: ${it.track.name}")
            }
            "album" -> SpotifyResolve.Collection(fetchAlbum(parsed.second, access)).also {
                AppLog.i("Spotify", "album: ${it.collection.name} (${it.collection.tracks.size})")
            }
            else -> SpotifyResolve.Collection(fetchPlaylist(parsed.second, access)).also {
                AppLog.i("Spotify", "playlist: ${it.collection.name} (${it.collection.tracks.size})")
            }
        }
    }

    fun searchQuery(track: SpotifyTrackMeta): String =
        "${track.name} ${track.artists.joinToString(" ")}".trim()

    private fun clientCredentialsToken(clientId: String, clientSecret: String): String {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            error("Configurá Spotify Client ID y Secret en Ajustes")
        }
        if (appToken != null && appTokenExpiresAt > System.currentTimeMillis() + 30_000) return appToken!!
        val body = FormBody.Builder().add("grant_type", "client_credentials").build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(clientId, clientSecret))
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error(spotifyHttpError("auth", resp.code, raw))
            }
            val json = JSONObject(raw)
            appToken = json.getString("access_token")
            appTokenExpiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000
            return appToken!!
        }
    }

    private fun get(path: String, access: String): JSONObject {
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else "https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error(spotifyHttpError(path, resp.code, raw))
            }
            return JSONObject(raw)
        }
    }

    private fun spotifyHttpError(where: String, code: Int, body: String): String {
        val msg = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { !it.isNullOrBlank() }
        AppLog.e("Spotify", "$where → $code ${body.take(400)}")
        return when (code) {
            401 -> "Spotify no autorizó (401). Revisá Client ID/Secret o reconectá la cuenta."
            403 -> "Spotify 403: sin permiso. Para playlists: Conectar Spotify en Ajustes " +
                "(cuenta dueña/colaboradora), Premium en modo desarrollo, y Redirect URI " +
                "${SpotifyAuth.REDIRECT_URI}. Detalle: ${msg ?: "Forbidden"}"
            404 -> "Spotify no encontró el recurso (404). ${msg.orEmpty()}"
            else -> "Spotify API $code${if (msg != null) ": $msg" else ""}"
        }
    }

    private fun mapTrack(obj: JSONObject, albumFallback: String? = null, images: JSONArray? = null): SpotifyTrackMeta {
        val artists = obj.optJSONArray("artists")
        val names = mutableListOf<String>()
        if (artists != null) {
            for (i in 0 until artists.length()) names += artists.getJSONObject(i).optString("name")
        }
        val album = obj.optJSONObject("album")
        val imgs = images ?: album?.optJSONArray("images")
        return SpotifyTrackMeta(
            id = obj.getString("id"),
            name = obj.optString("name"),
            artists = names,
            albumName = albumFallback ?: album?.optString("name").orEmpty(),
            durationMs = obj.optLong("duration_ms"),
            coverUrl = imgs?.optJSONObject(0)?.optString("url"),
            externalUrl = obj.optJSONObject("external_urls")?.optString("spotify")
                ?: "https://open.spotify.com/track/${obj.getString("id")}",
        )
    }

    private fun mapTrackFromItem(item: JSONObject): SpotifyTrackMeta? {
        val track = item.optJSONObject("track")
            ?: item.optJSONObject("item")
            ?: return null
        if (!track.has("id") || track.isNull("id")) return null
        // Episodes / locals may lack artists
        if (track.optBoolean("is_local", false)) return null
        return mapTrack(track)
    }

    private fun fetchTrack(id: String, access: String) = mapTrack(get("/tracks/$id", access))

    private fun fetchAlbum(id: String, access: String): SpotifyCollection {
        val album = get("/albums/$id", access)
        val tracks = mutableListOf<SpotifyTrackMeta>()
        val first = album.getJSONObject("tracks")
        val items = first.getJSONArray("items")
        for (i in 0 until items.length()) {
            tracks += mapTrack(items.getJSONObject(i), album.optString("name"), album.optJSONArray("images"))
        }
        var next = first.optString("next").ifBlank { null }
        while (next != null) {
            val page = get(next, access)
            val pageItems = page.getJSONArray("items")
            for (i in 0 until pageItems.length()) {
                tracks += mapTrack(pageItems.getJSONObject(i), album.optString("name"), album.optJSONArray("images"))
            }
            next = page.optString("next").ifBlank { null }
        }
        return SpotifyCollection(
            type = "album",
            id = album.getString("id"),
            name = album.optString("name"),
            coverUrl = album.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            externalUrl = album.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
            tracks = tracks,
        )
    }

    private fun fetchPlaylist(id: String, access: String): SpotifyCollection {
        val playlist = get("/playlists/$id", access)
        val tracks = mutableListOf<SpotifyTrackMeta>()

        fun pushPage(page: JSONObject) {
            val arr = page.optJSONArray("items") ?: return
            for (i in 0 until arr.length()) {
                mapTrackFromItem(arr.getJSONObject(i))?.let { tracks += it }
            }
        }

        // Spotify Feb 2026: /tracks → /items (keep /tracks as fallback).
        val listed = runCatching {
            var next: String? = "/playlists/$id/items?limit=50"
            while (next != null) {
                val page = get(next, access)
                pushPage(page)
                next = page.optString("next").ifBlank { null }
            }
            true
        }.onFailure { AppLog.w("Spotify", "playlist /items failed, trying /tracks", it) }.getOrDefault(false)

        if (!listed || tracks.isEmpty()) {
            runCatching {
                var next: String? = "/playlists/$id/tracks?limit=50"
                while (next != null) {
                    val page = get(next, access)
                    pushPage(page)
                    next = page.optString("next").ifBlank { null }
                }
            }.onFailure { AppLog.w("Spotify", "playlist /tracks failed", it) }
        }

        // Owned playlist may embed items/tracks in the playlist payload.
        if (tracks.isEmpty()) {
            playlist.optJSONObject("items")?.let { pushPage(it) }
            playlist.optJSONObject("tracks")?.let { pushPage(it) }
        }

        if (tracks.isEmpty()) {
            error(
                "Spotify devolvió la playlist sin temas. " +
                    "Tiene que ser tuya o colaborativa (API 2026). " +
                    "Conectá la cuenta dueña en Ajustes.",
            )
        }

        return SpotifyCollection(
            type = "playlist",
            id = playlist.getString("id"),
            name = playlist.optString("name"),
            coverUrl = playlist.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            externalUrl = playlist.optJSONObject("external_urls")?.optString("spotify").orEmpty(),
            tracks = tracks,
        )
    }
}

object LinkDetector {
    fun isYouTube(input: String) =
        Regex("(?:youtube\\.com|youtu\\.be|music\\.youtube\\.com)", RegexOption.IGNORE_CASE)
            .containsMatchIn(input)

    fun isSpotify(input: String) = SpotifyApi().parseUrl(input) != null

    fun youtubeVideoId(input: String): String? {
        Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.get(1)?.let { return it }
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.get(1)?.let { return it }
        Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE)
            .find(input)?.groupValues?.get(1)?.let { return it }
        return null
    }

    fun youtubePlaylistId(input: String): String? =
        Regex("[?&]list=([a-zA-Z0-9_-]+)").find(input)?.groupValues?.get(1)

    fun isYouTubePlaylistUrl(input: String): Boolean {
        val listId = youtubePlaylistId(input) ?: return false
        if (listId.startsWith("RD", ignoreCase = true)) return false
        if (input.contains("/playlist", ignoreCase = true)) return true
        return listId.startsWith("PL", ignoreCase = true) ||
            listId.startsWith("OL", ignoreCase = true) ||
            listId.startsWith("UU", ignoreCase = true) ||
            listId.startsWith("FL", ignoreCase = true)
    }

    fun classify(input: String): String? = when {
        isSpotify(input) -> "spotify"
        isYouTube(input) -> "youtube"
        else -> null
    }
}
