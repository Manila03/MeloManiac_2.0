package com.melomaniac.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.melomaniac.app.data.LibraryRepository
import com.melomaniac.app.data.TrackEntity
import com.melomaniac.app.data.TrackRow
import com.melomaniac.app.telegram.HlsProxyServer
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class QueueItem(
    val id: String,
    val title: String,
    val artist: String?,
)

data class PlayerUiState(
    val connected: Boolean = false,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val coverPath: String? = null,
    val trackId: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueItem> = emptyList(),
    val queueIndex: Int = 0,
    val isFavorite: Boolean = false,
    val storageLabel: String? = null,
)

class PlayerController(
    private val context: Context,
    private val library: LibraryRepository,
    private val hlsProxy: HlsProxyServer,
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture!!.addListener({
            controller = controllerFuture!!.get()
            controller?.addListener(listener)
            _state.value = _state.value.copy(connected = true)
            syncFromPlayer()
            startProgress()
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        progressJob?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        hlsProxy.stop()
    }

    fun playTracks(tracks: List<TrackRow>, startIndex: Int = 0) {
        val c = controller ?: return
        scope.launch {
            if (tracks.any { !it.hasLocalFile }) {
                try {
                    hlsProxy.ensureStarted()
                } catch (e: Exception) {
                    AppLog.e(TAG, "HLS proxy failed to start", e)
                }
            }
            val items = tracks.map { it.toMediaItem() }
            c.setMediaItems(items, startIndex, 0)
            c.prepare()
            c.play()
            tracks.getOrNull(startIndex)?.let {
                library.recordPlay(it.id)
            }
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Stops playback and clears the queue (e.g. before wiping the library). */
    fun stopAndClear() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        _state.value = PlayerUiState(connected = _state.value.connected)
    }

    fun skipNext() = controller?.seekToNextMediaItem()
    fun skipPrev() {
        val c = controller ?: return
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekToIndex(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.seekTo(index, 0)
            c.play()
        }
    }

    fun playNext(track: TrackRow) {
        val c = controller ?: return
        scope.launch {
            if (!track.hasLocalFile) {
                runCatching { hlsProxy.ensureStarted() }
            }
            val index = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
            c.addMediaItem(index, track.toMediaItem())
            syncFromPlayer()
        }
    }

    fun toggleFavoriteCurrent() {
        val id = _state.value.trackId ?: return
        scope.launch {
            library.toggleFavorite(id)
            val t = library.getTrack(id)
            _state.value = _state.value.copy(isFavorite = t?.isFavorite == true)
        }
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    fun cycleRepeat() {
        val c = controller ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        _state.value = _state.value.copy(repeatMode = next)
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        _state.value = _state.value.copy(shuffle = c.shuffleModeEnabled)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(playing = isPlaying)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncFromPlayer()
            mediaItem?.mediaId?.let { id ->
                scope.launch { library.recordPlay(id) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncFromPlayer()
        }
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val artwork = item?.mediaMetadata?.artworkUri?.path
        val queue = buildList {
            for (i in 0 until c.mediaItemCount) {
                val mi = c.getMediaItemAt(i)
                add(
                    QueueItem(
                        id = mi.mediaId,
                        title = mi.mediaMetadata.title?.toString().orEmpty(),
                        artist = mi.mediaMetadata.artist?.toString(),
                    ),
                )
            }
        }
        val trackId = item?.mediaId
        _state.value = _state.value.copy(
            playing = c.isPlaying,
            title = item?.mediaMetadata?.title?.toString(),
            artist = item?.mediaMetadata?.artist?.toString(),
            coverPath = artwork,
            trackId = trackId,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            queueIndex = c.currentMediaItemIndex.coerceAtLeast(0),
        )
        if (trackId != null) {
            scope.launch {
                val t = library.getTrack(trackId)
                _state.value = _state.value.copy(
                    isFavorite = t?.isFavorite == true,
                    storageLabel = when {
                        t == null -> null
                        t.storageMode == TrackEntity.STORAGE_LOCAL -> "Local"
                        else -> "Online"
                    },
                )
            }
        }
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition,
                        durationMs = c.duration.coerceAtLeast(0),
                        playing = c.isPlaying,
                    )
                }
                delay(500)
            }
        }
    }

    private fun TrackRow.toMediaItem(): MediaItem {
        val localPath = path?.takeIf { it.isNotBlank() }
        val localFile = localPath?.let { File(it) }?.takeIf { it.exists() }
        val playUri = when {
            localFile != null -> android.net.Uri.fromFile(localFile)
            localPath != null && (localPath.startsWith("file://") || localPath.startsWith("content://")) ->
                android.net.Uri.parse(localPath)
            isHls -> android.net.Uri.parse(hlsProxy.playlistUrl(id))
            else -> android.net.Uri.parse(hlsProxy.progressiveUrl(id))
        }
        val useHls = localFile == null && isHls
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName ?: "Unknown")
            .setAlbumTitle(albumName)
        val cover = coverPath?.takeIf { File(it).exists() }
        if (cover != null) {
            meta.setArtworkUri(android.net.Uri.fromFile(File(cover)))
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(playUri)
            .apply {
                if (useHls) setMimeType("application/x-mpegURL")
            }
            .setMediaMetadata(meta.build())
            .build()
    }

    companion object {
        private const val TAG = "Player"
    }
}
