package com.melomaniac.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.download.LinkDetector
import com.melomaniac.app.download.SpotifyUrls
import com.melomaniac.app.download.YtHit
import com.melomaniac.app.ui.AppTextField
import com.melomaniac.app.ui.CoverArt
import com.melomaniac.app.ui.GhostButton
import com.melomaniac.app.ui.Muted
import com.melomaniac.app.ui.PrimaryButton
import com.melomaniac.app.ui.RemoteCoverArt
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.formatMs
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Surface
import com.melomaniac.app.ui.theme.TextSecondary
import com.melomaniac.app.util.AppBusy
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray

@Composable
fun SearchScreen(container: AppContainer, onPlay: (List<TrackRow>, Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var local by remember { mutableStateOf<List<TrackRow>>(emptyList()) }
    var youtubeHits by remember { mutableStateOf<List<YtHit>>(emptyList()) }
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val globalBusy by AppBusy.message.collectAsState()
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("melomaniac_search", android.content.Context.MODE_PRIVATE)
    }
    val detected = remember(query) { LinkDetector.classify(query.trim()) }
    val isLink = detected != null ||
        query.trim().startsWith("spotify:") ||
        query.trim().contains("http", ignoreCase = true)

    LaunchedEffect(Unit) {
        history = loadHistory(prefs)
    }

    // Debounced library-only search while typing (not for links).
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty() || isLink) {
            if (q.isEmpty()) local = emptyList()
            return@LaunchedEffect
        }
        delay(280)
        local = runCatching { container.library.search(q) }.getOrDefault(emptyList())
    }

    fun rememberQuery(q: String) {
        val next = (listOf(q) + history.filter { !it.equals(q, true) }).take(8)
        history = next
        saveHistory(prefs, next)
    }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenTitle("Buscar")
        Muted("Escribí un tema/artista (biblioteca al instante), o pegá un link / tocá Buscar para YouTube.")
        AppTextField(query, { query = it }, "URL o búsqueda…")
        when (detected) {
            "spotify" -> {
                val kind = SpotifyUrls.parse(query.trim())?.first ?: "link"
                Text(
                    "Detectado: Spotify ($kind) — se encola directo",
                    color = Accent,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            "youtube" -> {
                val kind = if (LinkDetector.isYouTubePlaylistUrl(query.trim())) "playlist" else "video"
                Text("Detectado: YouTube ($kind)", color = Accent, modifier = Modifier.padding(top = 4.dp))
            }
        }
        PrimaryButton(
            if (isLink) "Encolar link" else "Buscar en YouTube",
            onClick = {
                scope.launch {
                    message = null
                    youtubeHits = emptyList()
                    try {
                        val q = query.trim()
                        if (q.isEmpty()) return@launch
                        AppBusy.run(
                            when {
                                detected == "spotify" -> "Resolviendo Spotify…"
                                isLink -> "Encolando…"
                                else -> "Buscando en YouTube…"
                            },
                        ) {
                            if (isLink) {
                                val (_, msg) = container.downloadQueue.enqueueFromUserInput(q)
                                message = msg
                                local = emptyList()
                            } else {
                                rememberQuery(q)
                                local = container.library.search(q)
                                youtubeHits = container.ytDlp.search(q, 8)
                                message = when {
                                    local.isEmpty() && youtubeHits.isEmpty() -> "Sin resultados"
                                    youtubeHits.isNotEmpty() -> {
                                        val yt = "${youtubeHits.size} en YouTube"
                                        if (local.isNotEmpty()) "$yt · ${local.size} en biblioteca" else yt
                                    }
                                    else -> "${local.size} en biblioteca"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.e("Search", "Buscar / Encolar failed", e)
                        message = e.message
                    }
                }
            },
            enabled = globalBusy == null && query.isNotBlank(),
        )
        if (!isLink && history.isNotEmpty() && query.isBlank()) {
            Text("Recientes", color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
            history.forEach { h ->
                Text(
                    h,
                    color = Accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { query = h }
                        .padding(vertical = 6.dp),
                )
            }
        }
        if (!isLink && youtubeHits.isNotEmpty()) {
            GhostButton("Encolar el primero de YouTube", onClick = {
                scope.launch {
                    try {
                        val hit = youtubeHits.first()
                        val (_, msg) = AppBusy.run("Encolando…") {
                            container.downloadQueue.enqueueYtHit(hit)
                        }
                        message = msg
                    } catch (e: Exception) {
                        AppLog.e("Search", "Encolar primero failed", e)
                        message = e.message
                    }
                }
            })
        }
        message?.let { Text(it, color = Accent, modifier = Modifier.padding(vertical = 8.dp)) }

        if (local.isNotEmpty()) {
            Text("En biblioteca", color = TextSecondary, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            local.forEachIndexed { index, track ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onPlay(local, index) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverArt(path = track.coverPath, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                listOfNotNull(
                                    track.artistName,
                                    formatMs(track.durationMs).ifBlank { null },
                                ).joinToString(" · "),
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        if (youtubeHits.isNotEmpty()) {
            Text("YouTube", color = TextSecondary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            youtubeHits.forEach { hit ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteCoverArt(url = hit.thumbnailUrl, size = 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    hit.uploader.ifBlank { null },
                                    formatMs(hit.durationMs).ifBlank { null },
                                ).joinToString(" · "),
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val (_, msg) = AppBusy.run("Encolando…") {
                                            container.downloadQueue.enqueueYtHit(hit)
                                        }
                                        message = msg
                                    } catch (e: Exception) {
                                        message = e.message
                                    }
                                }
                            },
                            enabled = globalBusy == null,
                        ) {
                            Text("Encolar", color = Accent)
                        }
                    }
                }
            }
        }
    }
}

private fun loadHistory(prefs: android.content.SharedPreferences): List<String> {
    val raw = prefs.getString("history", "[]") ?: "[]"
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }.getOrDefault(emptyList())
}

private fun saveHistory(prefs: android.content.SharedPreferences, items: List<String>) {
    val arr = JSONArray()
    items.forEach { arr.put(it) }
    prefs.edit().putString("history", arr.toString()).apply()
}
