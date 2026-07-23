package com.melomaniac.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.melomaniac.app.data.AppContainer
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.ui.BusyOverlay
import com.melomaniac.app.ui.MiniPlayerBar
import com.melomaniac.app.ui.screens.AlbumDetailScreen
import com.melomaniac.app.ui.screens.AlbumsScreen
import com.melomaniac.app.ui.screens.ArtistDetailScreen
import com.melomaniac.app.ui.screens.ArtistsScreen
import com.melomaniac.app.ui.screens.DownloadsScreen
import com.melomaniac.app.ui.screens.FavoritesScreen
import com.melomaniac.app.ui.screens.FolderDetailScreen
import com.melomaniac.app.ui.screens.FoldersScreen
import com.melomaniac.app.ui.screens.GenreDetailScreen
import com.melomaniac.app.ui.screens.GenresScreen
import com.melomaniac.app.ui.screens.LibraryHomeScreen
import com.melomaniac.app.ui.screens.LogsScreen
import com.melomaniac.app.ui.screens.NowPlayingScreen
import com.melomaniac.app.ui.screens.PlaylistDetailScreen
import com.melomaniac.app.ui.screens.PlaylistsScreen
import com.melomaniac.app.ui.screens.RecentScreen
import com.melomaniac.app.ui.screens.SearchScreen
import com.melomaniac.app.ui.screens.SettingsScreen
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Background
import com.melomaniac.app.ui.theme.MeloTheme
import com.melomaniac.app.ui.theme.TextMuted
import com.melomaniac.app.util.AppBusy
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as MeloManiacApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeloTheme {
                MeloApp(
                    container = container,
                    initialIntent = intent,
                    onConsumeIntent = { handleIncoming(it) },
                )
            }
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (intent == null) return
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> intent.dataString
        } ?: return
        val link = Regex("(https?://\\S+|spotify:[a-z]+:[a-zA-Z0-9]+)", RegexOption.IGNORE_CASE)
            .find(raw)?.value ?: return
        if (!Regex("spotify|youtube|youtu\\.be", RegexOption.IGNORE_CASE).containsMatchIn(link)) return

        AppLog.i("Share", "Incoming link: $link")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            try {
                val (_, msg) = AppBusy.run("Encolando link…") {
                    container.downloadQueue.enqueueFromUserInput(link)
                }
                AppLog.i("Share", msg)
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AppLog.e("Share", "Failed to enqueue link", e)
                Toast.makeText(this@MainActivity, e.message ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
fun MeloApp(
    container: AppContainer,
    initialIntent: Intent?,
    onConsumeIntent: (Intent?) -> Unit,
) {
    val nav = rememberNavController()
    val player = container.player
    val playerState by player.state.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        player.connect()
        onDispose { player.release() }
    }

    LaunchedEffect(initialIntent) {
        // already handled in activity; keep for recomposition safety
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val showTabs = route in setOf("library", "search", "downloads", "logs", "settings")
    val busyMessage by AppBusy.message.collectAsState()

    fun play(tracks: List<TrackRow>, index: Int) {
        player.playTracks(tracks, index)
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Background,
        bottomBar = {
            Column {
                if (!route.startsWith("nowPlaying")) {
                    MiniPlayerBar(
                        title = playerState.title,
                        artist = playerState.artist,
                        playing = playerState.playing,
                        onToggle = { player.togglePlay() },
                        onOpen = { nav.navigate("nowPlaying") },
                    )
                }
                if (showTabs) {
                    NavigationBar(containerColor = Background) {
                        val items = listOf(
                            Triple("library", "Biblioteca", Icons.Default.Home),
                            Triple("search", "Buscar", Icons.Default.Search),
                            Triple("downloads", "Descargas", Icons.Default.Download),
                            Triple("logs", "Logs", Icons.Default.Terminal),
                            Triple("settings", "Ajustes", Icons.Default.Settings),
                        )
                        items.forEach { (r, label, icon) ->
                            NavigationBarItem(
                                selected = route == r,
                                onClick = {
                                    nav.navigate(r) {
                                        popUpTo("library") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(icon, label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Accent,
                                    selectedTextColor = Accent,
                                    indicatorColor = Accent.copy(alpha = 0.2f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            NavHost(navController = nav, startDestination = "library") {
                composable("library") {
                    LibraryHomeScreen(
                        container,
                        onOpen = { key ->
                            when (key) {
                                "artists" -> nav.navigate("artists")
                                "albums" -> nav.navigate("albums")
                                "playlists" -> nav.navigate("playlists")
                                "favorites" -> nav.navigate("favorites")
                                "recent" -> nav.navigate("recent")
                                "genres" -> nav.navigate("genres")
                                "folders" -> nav.navigate("folders")
                            }
                        },
                        onPlay = ::play,
                    )
                }
                composable("search") { SearchScreen(container, ::play) }
                composable("downloads") { DownloadsScreen(container) }
                composable("logs") { LogsScreen() }
                composable("settings") { SettingsScreen(container) }
                composable("nowPlaying") { NowPlayingScreen(player) }
                composable("artists") { ArtistsScreen(container) { nav.navigate("artist/$it") } }
                composable("albums") { AlbumsScreen(container) { nav.navigate("album/$it") } }
                composable("playlists") { PlaylistsScreen(container) { nav.navigate("playlist/$it") } }
                composable("genres") { GenresScreen(container) { nav.navigate("genre/$it") } }
                composable("folders") { FoldersScreen(container) { nav.navigate("folder/$it") } }
                composable("favorites") { FavoritesScreen(container, ::play) }
                composable("recent") { RecentScreen(container, ::play) }
                composable("artist/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ArtistDetailScreen(container, it.arguments!!.getString("id")!!, ::play)
                }
                composable("album/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    AlbumDetailScreen(container, it.arguments!!.getString("id")!!, ::play)
                }
                composable("playlist/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    PlaylistDetailScreen(container, it.arguments!!.getString("id")!!, ::play)
                }
                composable("genre/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    GenreDetailScreen(container, it.arguments!!.getString("id")!!, ::play)
                }
                composable("folder/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    FolderDetailScreen(container, it.arguments!!.getString("id")!!, ::play)
                }
            }
        }
    }
        busyMessage?.let { BusyOverlay(it) }
    }
}
