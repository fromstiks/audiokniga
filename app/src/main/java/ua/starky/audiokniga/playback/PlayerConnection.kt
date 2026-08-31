package ua.starky.audiokniga.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
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
    /** Идентификатор текущей главы. Сравнивать по нему надёжнее, чем по номеру:
     *  в режиме «Офлайн» очередь короче, и номера перестают совпадать. */
    val chapterId: String? = null,
    val chapterTitle: String? = null,
    /** Сколько глав сейчас заряжено в плеер. Ноль — играть нечего, нужна очередь. */
    val queueSize: Int = 0,
    val error: String? = null,
) {
    /** Есть ли что показывать в свёрнутом плеере. */
    val hasQueue: Boolean get() = bookId != null
}

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
            // Процесс приложения могли убить, пока сервис играл дальше. Тогда очередь
            // ставили не мы, и книгу остаётся узнать по метаданным главы.
            bookId = _state.value.bookId ?: player.currentMediaItem?.mediaMetadata?.artist?.toString(),
            chapterId = player.currentMediaItem?.mediaId,
            chapterTitle = player.currentMediaItem?.mediaMetadata?.title?.toString(),
            queueSize = player.mediaItemCount,
            error = null,
        )
    }

    /**
     * [coverUrl] уходит в артворк каждой главы — экран блокировки и уведомление берут
     * обложку из метаданных текущего MediaItem, а не откуда-то ещё, и без нее там
     * пусто, даже если сама книга обложку давно показывает.
     */
    fun setQueue(
        bookId: String,
        chapters: List<Chapter>,
        startIndex: Int,
        startPositionMs: Long,
        play: Boolean,
        coverUrl: String? = null,
    ) {
        val player = controller ?: return
        val artwork = coverUrl?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val items = chapters.map { chapter ->
            MediaItem.Builder()
                .setMediaId(chapter.id)
                .setUri(chapter.audioUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setArtist(bookId)
                        .setArtworkUri(artwork)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        player.setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startPositionMs)
        player.prepare()
        player.playWhenReady = play
        _state.value = _state.value.copy(bookId = bookId, queueSize = items.size)
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

    fun pause() { controller?.pause() }

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

    fun playChapter(index: Int, positionMs: Long = 0L) {
        val player = controller ?: return
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, positionMs)
        player.prepare()
        player.play()
    }

    /**
     * Глава ищется по идентификатору: её место в очереди зависит от режима источника.
     * Возвращает false, если такой главы в очереди нет — например в офлайне она
     * не скачана, и об этом надо сказать, а не молчать.
     */
    fun playChapterById(chapterId: String, positionMs: Long = 0L): Boolean {
        val player = controller ?: return false
        for (index in 0 until player.mediaItemCount) {
            if (player.getMediaItemAt(index).mediaId == chapterId) {
                playChapter(index, positionMs)
                return true
            }
        }
        return false
    }

    fun clearQueue() {
        val player = controller ?: return
        player.stop()
        player.clearMediaItems()
        _state.value = _state.value.copy(
            bookId = null,
            chapterId = null,
            chapterTitle = null,
            queueSize = 0,
        )
    }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
