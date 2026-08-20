package com.melomaniac.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.player.PlayerController
import com.melomaniac.app.ui.CoverArt
import com.melomaniac.app.ui.formatMs
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Background
import com.melomaniac.app.ui.theme.TextMuted
import com.melomaniac.app.ui.theme.TextSecondary
import com.melomaniac.app.ui.theme.Track
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(container: AppContainer, player: PlayerController) {
    val state by player.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        CoverArt(path = state.coverPath, size = 240.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            state.title ?: "Nada en reproducción",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(state.artist.orEmpty(), color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
        state.storageLabel?.let {
            Text(it, color = TextMuted, modifier = Modifier.padding(top = 4.dp), fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { player.toggleFavoriteCurrent() },
                enabled = state.trackId != null,
            ) {
                Icon(
                    if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorito",
                    tint = if (state.isFavorite) Accent else TextMuted,
                )
            }
            if (state.trackId != null) {
                TextButton(
                    onClick = {
                        scope.launch {
                            val id = state.trackId ?: return@launch
                            val (_, msg) = container.downloadQueue.enqueueLocalDownload(id)
                            AppLog.i("NowPlaying", msg)
                        }
                    },
                ) { Text("Descargar local", color = Accent) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = state.positionMs.toFloat().coerceAtMost(state.durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { player.seekTo(it.toLong()) },
            valueRange = 0f..(state.durationMs.toFloat().coerceAtLeast(1f)),
            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Track),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(state.positionMs), color = TextMuted, fontSize = 12.sp)
            Text(formatMs(state.durationMs), color = TextMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconButton(onClick = { player.skipPrev() }) {
                Icon(Icons.Default.SkipPrevious, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = { player.togglePlay() },
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Accent),
            ) {
                Icon(
                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null,
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = { player.skipNext() }) {
                Icon(Icons.Default.SkipNext, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(36.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = { player.toggleShuffle() }) {
                Text(if (state.shuffle) "Shuffle on" else "Shuffle", color = Accent)
            }
            TextButton(onClick = { player.cycleRepeat() }) {
                Text(
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repetir 1"
                        Player.REPEAT_MODE_ALL -> "Repetir todas"
                        else -> "Repetir off"
                    },
                    color = Accent,
                )
            }
        }
        if (state.queue.size > 1) {
            Text(
                "A continuación",
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.queue, key = { index, q -> "$index-${q.id}" }) { index, item ->
                    val current = index == state.queueIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .clickable { player.seekToIndex(index) },
                        ) {
                            Text(
                                item.title.ifBlank { "Tema" },
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                color = if (current) Accent else androidx.compose.ui.graphics.Color.Unspecified,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                item.artist.orEmpty(),
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { player.moveQueueItem(index, -1) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = TextMuted)
                        }
                        IconButton(
                            onClick = { player.moveQueueItem(index, 1) },
                            enabled = index < state.queue.lastIndex,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = TextMuted)
                        }
                    }
                }
            }
        }
    }
}
