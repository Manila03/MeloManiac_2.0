package com.melomaniac.app.download

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
    /** True when Pathfinder failed and embed may have truncated the list. */
    val tracksMayBePartial: Boolean = false,
)

sealed class SpotifyResolve {
    data class Track(val track: SpotifyTrackMeta) : SpotifyResolve()
    data class Collection(val collection: SpotifyCollection) : SpotifyResolve()
}

/** @deprecated Prefer [SpotifyScraper]; kept as type alias for call sites that still say SpotifyApi. */
typealias SpotifyApi = SpotifyScraper

object LinkDetector {
    fun isYouTube(input: String) =
        Regex("(?:youtube\\.com|youtu\\.be|music\\.youtube\\.com)", RegexOption.IGNORE_CASE)
            .containsMatchIn(input)

    fun isSpotify(input: String) = SpotifyUrls.parse(input) != null

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
