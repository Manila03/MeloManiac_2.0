package com.melomaniac.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    container: AppContainer,
    trackId: String,
    onDismiss: () -> Unit,
) {
    val playlists by container.library.observePlaylists().collectAsState(initial = emptyList())
    val folders by container.library.observeFolders().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) } // 0 playlist, 1 folder
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Agregar a…", fontWeight = FontWeight.Bold)
            Row {
                TextButton(onClick = { tab = 0 }) {
                    Text("Playlists", color = if (tab == 0) Accent else TextSecondary)
                }
                TextButton(onClick = { tab = 1 }) {
                    Text("Carpetas", color = if (tab == 1) Accent else TextSecondary)
                }
            }
            AppTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = if (tab == 0) "Nueva playlist…" else "Nueva carpeta…",
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Crear y agregar") {
                scope.launch {
                    val name = newName.trim()
                    if (name.isEmpty()) return@launch
                    if (tab == 0) {
                        val p = container.library.createPlaylist(name)
                        container.library.addTrackToPlaylist(p.id, trackId)
                    } else {
                        val f = container.library.createFolder(name)
                        container.library.addTrackToFolder(f.id, trackId)
                    }
                    onDismiss()
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(280.dp)) {
                if (tab == 0) {
                    if (playlists.isEmpty()) {
                        item { Muted("No hay playlists todavía.") }
                    }
                    items(playlists, key = { it.id }) { p ->
                        Text(
                            p.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.library.addTrackToPlaylist(p.id, trackId)
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                } else {
                    if (folders.isEmpty()) {
                        item { Muted("No hay carpetas todavía.") }
                    }
                    items(folders, key = { it.id }) { f ->
                        Text(
                            f.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        container.library.addTrackToFolder(f.id, trackId)
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
