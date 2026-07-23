package com.melomaniac.app.download

import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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

class SpotifyApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var token: String? = null
    @Volatile private var expiresAt: Long = 0

    fun parseUrl(input: String): Pair<String, String>? {
        val trimmed = input.trim()
        Regex("^spotify:(track|album|playlist):([a-zA-Z0-9]+)").find(trimmed)?.let {
            return it.groupValues[1] to it.groupValues[2]
        }
        Regex("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?(track|album|playlist)/([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
            .find(trimmed)?.let {
                return it.groupValues[1].lowercase() to it.groupValues[2]
            }
        return null
    }

    fun resolve(input: String, clientId: String, clientSecret: String): SpotifyResolve {
        val parsed = parseUrl(input) ?: error("URL de Spotify inválida")
        val access = accessToken(clientId, clientSecret)
        return when (parsed.first) {
            "track" -> SpotifyResolve.Track(fetchTrack(parsed.second, access))
            "album" -> SpotifyResolve.Collection(fetchAlbum(parsed.second, access))
            else -> SpotifyResolve.Collection(fetchPlaylist(parsed.second, access))
        }
    }

    fun searchQuery(track: SpotifyTrackMeta): String =
        "${track.name} ${track.artists.joinToString(" ")}".trim()

    private fun accessToken(clientId: String, clientSecret: String): String {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            error("Configurá Spotify Client ID y Secret en Ajustes")
        }
        if (token != null && expiresAt > System.currentTimeMillis() + 30_000) return token!!
        val body = FormBody.Builder().add("grant_type", "client_credentials").build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .header("Authorization", Credentials.basic(clientId, clientSecret))
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Spotify auth failed (${resp.code})")
            val json = JSONObject(resp.body!!.string())
            token = json.getString("access_token")
            expiresAt = System.currentTimeMillis() + json.getLong("expires_in") * 1000
            return token!!
        }
    }

    private fun get(path: String, access: String): JSONObject {
        val req = Request.Builder()
            .url(if (path.startsWith("http")) path else "https://api.spotify.com/v1$path")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Spotify API ${resp.code}")
            return JSONObject(resp.body!!.string())
        }
    }

    private fun mapTrack(obj: JSONObject, albumFallback: String? = null, images: org.json.JSONArray? = null): SpotifyTrackMeta {
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

    private fun fetchTrack(id: String, access: String) = mapTrack(get("/tracks/$id", access))

    private fun fetchAlbum(id: String, access: String): SpotifyCollection {
        val album = get("/albums/$id", access)
        val tracks = mutableListOf<SpotifyTrackMeta>()
        var next: String? = null
        val first = album.getJSONObject("tracks")
        val items = first.getJSONArray("items")
        for (i in 0 until items.length()) {
            tracks += mapTrack(items.getJSONObject(i), album.optString("name"), album.optJSONArray("images"))
        }
        next = first.optString("next").ifBlank { null }
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
        fun push(items: org.json.JSONArray) {
            for (i in 0 until items.length()) {
                val track = items.getJSONObject(i).optJSONObject("track") ?: continue
                if (track.has("id") && !track.isNull("id")) tracks += mapTrack(track)
            }
        }
        val first = playlist.getJSONObject("tracks")
        push(first.getJSONArray("items"))
        var next = first.optString("next").ifBlank { null }
        while (next != null) {
            val page = get(next, access)
            push(page.getJSONArray("items"))
            next = page.optString("next").ifBlank { null }
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
    fun isYouTube(input: String) = Regex("(?:youtube\\.com|youtu\\.be)", RegexOption.IGNORE_CASE).containsMatchIn(input)
    fun isSpotify(input: String) = SpotifyApi().parseUrl(input) != null

    fun youtubeVideoId(input: String): String? {
        Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.get(1)?.let { return it }
        Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(input)?.groupValues?.get(1)?.let { return it }
        return null
    }

    fun youtubePlaylistId(input: String): String? {
        if (youtubeVideoId(input) != null && !input.contains("playlist?list=")) {
            // watch URL with list param: treat as video unless pure playlist URL
            if (!input.contains("/playlist")) return null
        }
        return Regex("[?&]list=([a-zA-Z0-9_-]+)").find(input)?.groupValues?.get(1)
    }
}
