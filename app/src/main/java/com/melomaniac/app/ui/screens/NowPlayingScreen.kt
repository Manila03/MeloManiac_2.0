package com.melomaniac.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.melomaniac.app.player.PlayerController
import com.melomaniac.app.ui.formatMs
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Background
import com.melomaniac.app.ui.theme.Surface
import com.melomaniac.app.ui.theme.TextMuted
import com.melomaniac.app.ui.theme.TextSecondary
import com.melomaniac.app.ui.theme.Track

@Composable
fun NowPlayingScreen(player: PlayerController) {
    val state by player.state.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .size(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", fontSize = 72.sp, color = Accent)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            state.title ?: "Nada en reproducción",
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
        )
        Text(state.artist.orEmpty(), color = TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { player.toggleShuffle() }) {
                Text("Shuffle: ${if (state.shuffle) "on" else "off"}", color = Accent)
            }
            TextButton(onClick = { player.cycleRepeat() }) {
                val label = when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repetir: 1"
                    Player.REPEAT_MODE_ALL -> "Repetir: todas"
                    else -> "Repetir: off"
                }
                Text(label, color = Accent)
            }
        }
    }
}
