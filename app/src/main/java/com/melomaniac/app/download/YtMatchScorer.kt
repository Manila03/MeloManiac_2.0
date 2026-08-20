package com.melomaniac.app.download

import com.melomaniac.app.util.AppLog
import kotlin.math.abs
import kotlin.math.max

/**
 * Picks the best YouTube search hit using duration proximity and title/artist token overlap.
 * Rejects weak matches instead of downloading the first result.
 */
object YtMatchScorer {
    private const val MIN_ACCEPT_SCORE = 0.28
    private val PENALTY_TOKENS = listOf(
        "live", "karaoke", "cover", "remix", "instrumental", "slowed", "reverb",
        "nightcore", "8d", "audio", "lyrics", "official video", "music video",
    )

    fun pickBest(
        hits: List<YtHit>,
        title: String,
        artist: String,
        durationMs: Long,
    ): YtHit {
        if (hits.isEmpty()) error("Sin coincidencia en YouTube")
        val scored = hits.map { it to score(it, title, artist, durationMs) }
            .sortedByDescending { it.second }
        val (best, bestScore) = scored.first()
        AppLog.i(
            "YtMatch",
            "best=${best.title.take(60)} score=${"%.2f".format(bestScore)} " +
                "of ${hits.size} (want ${durationMs}ms)",
        )
        if (bestScore < MIN_ACCEPT_SCORE) {
            error(
                "Ningún resultado de YouTube encaja bien con \"$title\" " +
                    "(mejor score ${"%.2f".format(bestScore)}). Probá otro link o query.",
            )
        }
        return best
    }

    fun score(hit: YtHit, title: String, artist: String, durationMs: Long): Double {
        val wantTitle = tokenize(title)
        val wantArtist = tokenize(artist)
        val hitTokens = tokenize("${hit.title} ${hit.uploader}")
        if (hitTokens.isEmpty()) return 0.0

        val titleOverlap = overlap(wantTitle, hitTokens)
        val artistOverlap = if (wantArtist.isEmpty()) 0.35 else overlap(wantArtist, hitTokens)

        var durationScore = 0.4
        if (durationMs > 0 && hit.durationMs > 0) {
            val delta = abs(hit.durationMs - durationMs).toDouble()
            val tol = max(5_000.0, durationMs * 0.08)
            durationScore = (1.0 - (delta / (tol * 3.0))).coerceIn(0.0, 1.0)
        }

        var score = titleOverlap * 0.45 + artistOverlap * 0.25 + durationScore * 0.30

        val queryBlob = "$title $artist".lowercase()
        val hitBlob = "${hit.title} ${hit.uploader}".lowercase()
        for (p in PENALTY_TOKENS) {
            if (p !in queryBlob && p in hitBlob) {
                score -= 0.12
            }
        }
        return score.coerceIn(0.0, 1.0)
    }

    private fun tokenize(s: String): Set<String> =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()

    private fun overlap(want: Set<String>, have: Set<String>): Double {
        if (want.isEmpty()) return 0.0
        val hits = want.count { it in have }
        return hits.toDouble() / want.size
    }
}
