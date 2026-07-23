package com.melomaniac.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melomaniac.app.ui.Muted
import com.melomaniac.app.ui.ScreenTitle
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Surface
import com.melomaniac.app.ui.theme.TextMuted
import com.melomaniac.app.ui.theme.TextSecondary
import com.melomaniac.app.util.AppLog
import com.melomaniac.app.util.LogEntry
import com.melomaniac.app.util.LogLevel

private enum class LogFilter { ALL, INFO, WARN, ERROR }

@Composable
fun LogsScreen() {
    val all by AppLog.entries.collectAsState()
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var follow by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val visible by remember(all, filter) {
        derivedStateOf {
            when (filter) {
                LogFilter.ALL -> all
                LogFilter.INFO -> all.filter { it.level != LogLevel.DEBUG }
                LogFilter.WARN -> all.filter { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
                LogFilter.ERROR -> all.filter { it.level == LogLevel.ERROR }
            }
        }
    }

    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) {
            listState.animateScrollToItem(visible.lastIndex)
        }
    }

    Column(Modifier.padding(16.dp).fillMaxSize()) {
        ScreenTitle("Logs")
        Muted("Procesos, descargas y errores en tiempo real.")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${visible.size} líneas", color = TextMuted, fontSize = 12.sp)
            Row {
                TextButton(onClick = { follow = !follow }) {
                    Text(if (follow) "Seguir: sí" else "Seguir: no", color = Accent)
                }
                TextButton(onClick = {
                    val text = AppLog.dumpText()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("MeloManiac logs", text))
                    Toast.makeText(context, "Logs copiados", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copiar", color = Accent)
                }
                TextButton(onClick = { AppLog.clear() }) {
                    Text("Limpiar", color = Accent)
                }
            }
        }

        if (visible.isEmpty()) {
            Text(
                "Sin logs todavía. Las descargas, yt-dlp y errores aparecerán acá.",
                color = TextSecondary,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Surface)
                    .padding(8.dp),
            ) {
                items(visible, key = { it.id }) { entry ->
                    LogLine(entry)
                }
            }
        }
    }
}

@Composable
private fun LogLine(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.DEBUG -> TextMuted
        LogLevel.INFO -> TextSecondary
        LogLevel.WARN -> Accent.copy(alpha = 0.85f)
        LogLevel.ERROR -> androidx.compose.ui.graphics.Color(0xFFEF4444)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = "${entry.timeLabel()} ${entry.level.name.first()} [${entry.tag}]",
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = entry.message,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
    }
}
