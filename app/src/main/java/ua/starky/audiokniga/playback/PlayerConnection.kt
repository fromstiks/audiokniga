package ua.starky.audiokniga.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SourceMode

data class PlaybackState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val chapterIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val bookId: String? = null,
    val error: String? = null,
)

/**
 * Мост между интерфейсом и MediaSession. Держит одно соединение на приложение.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val selector = SourceSelectorHolder.get(context)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = pushState()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val offlineHint = selector.current == SourceMode.OFFLINE
            _state.value = _state.value.copy(
                error = if (offlineHint) {
                    "Эта глава ещё не скачана. Переключитесь на онлайн или скачайте её."
                } else {
                    error.localizedMessage ?: "Не удалось воспроизвести главу"
                }
            )
        }
    }

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) { onReady(); return }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { it.addListener(listener) }
            pushState()
            startTicker()
            onReady()
        }, MoreExecutors.directExecutor())
    }

    private fun startTicker() {
        scope.launch {
            while (true) {
                delay(500)
                val player = controller ?: continue
                if (player.isPlaying) pushState()
            }
        }
    }

    private fun pushState() {
        val player = controller ?: return
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            chapterIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
            speed = player.playbackParameters.speed,
            error = null,
        )
    }

    fun setQueue(bookId: String, chapters: List<Chapter>, startIndex: Int, startPositionMs: Long, play: Boolean) {
        val player = controller ?: return
        val items = chapters.map { chapter ->
            MediaItem.Builder()
                .setMediaId(chapter.id)
                .setUri(chapter.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setArtist(bookId)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPositionMs)
        player.prepare()
        player.playWhenReady = play
        _state.value = _state.value.copy(bookId = bookId)
    }

    /**
     * Смена источника: подменяем режим и перезапускаем текущий трек с той же секунды.
     * Плеер и очередь остаются прежними.
     */
    fun setSourceMode(mode: SourceMode) {
        selector.current = mode
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val wasPlaying = player.isPlaying
        player.seekTo(index, position)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    fun playPause() {
        val player = controller ?: return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun seekToFraction(fraction: Float) {
        val player = controller ?: return
        val duration = player.duration
        if (duration > 0) player.seekTo((duration * fraction).toLong())
    }

    fun skipBy(deltaMs: Long) {
        val player = controller ?: return
        player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    fun playChapter(index: Int) {
        val player = controller ?: return
        player.seekTo(index, 0L)
        player.prepare()
        player.play()
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
