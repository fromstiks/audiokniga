package ua.starky.audiokniga.playback

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.repo.LibraryRepository

/**
 * Единственное на приложение управление воспроизведением.
 *
 * Раньше соединение с плеером создавал экран плеера, поэтому свёрнутый плеер на полке
 * ничего не знал о том, что играет. Теперь состояние живёт здесь, а экраны только
 * показывают его и дёргают команды.
 */
class PlaybackController(
    context: Context,
    private val repo: LibraryRepository,
    private val scope: CoroutineScope,
) {
    private val connection = PlayerConnection(context, scope)

    val state: StateFlow<PlaybackState> = connection.state

    /** Книга, открытая в плеере сейчас, — даже если воспроизведение ещё не начиналось. */
    private val _openBookId = MutableStateFlow<String?>(null)
    val openBookId: StateFlow<String?> = _openBookId.asStateFlow()

    /** Шаг кнопок перемотки, задаётся в настройках. */
    @Volatile
    var skipMs: Long = 20_000L

    private val ready = CompletableDeferred<Unit>()
    private val queueLock = Mutex()
    private var queuedBookId: String? = null

    init {
        connection.connect { ready.complete(Unit) }
        startProgressAutosave()
    }

    /**
     * Готовит книгу к прослушиванию. Повторный вызов для той же книги очередь не пересобирает,
     * иначе каждое открытие экрана сбрасывало бы позицию.
     */
    suspend fun open(bookId: String, play: Boolean = false) {
        ready.await()
        _openBookId.value = bookId

        queueLock.withLock {
            if (queuedBookId == bookId) {
                if (play) connection.playPause()
                return
            }
            val chapters = repo.ensureLoaded(bookId)
            if (chapters.isEmpty()) return
            val (chapterIndex, positionMs) = repo.lastPosition(bookId)
            connection.setSourceMode(repo.sourceModeOf(bookId))
            connection.setQueue(bookId, chapters, chapterIndex, positionMs, play)
            queuedBookId = bookId
        }
    }

    /** Нажатие play там, где книга ещё не загружена в плеер, — например в свёрнутом плеере. */
    fun playPause(bookId: String? = null) {
        val target = bookId ?: _openBookId.value
        if (target != null && queuedBookId != target) {
            scope.launch { open(target, play = true) }
        } else {
            connection.playPause()
        }
    }

    fun skipForward() = connection.skipBy(skipMs)

    fun skipBack() = connection.skipBy(-skipMs)

    fun skipBy(deltaMs: Long) = connection.skipBy(deltaMs)

    fun seekFraction(fraction: Float) = connection.seekToFraction(fraction)

    fun playChapter(index: Int) = connection.playChapter(index)

    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun setSourceMode(mode: SourceMode) = connection.setSourceMode(mode)

    fun clearError() = connection.clearError()

    /** Книга удалена из библиотеки — плеер не должен продолжать её показывать. */
    fun forget(bookId: String) {
        if (_openBookId.value == bookId) _openBookId.value = null
        if (queuedBookId == bookId) queuedBookId = null
    }

    /** Сохранить позицию, не дожидаясь результата: вызывается при закрытии экрана. */
    fun saveProgressNow() {
        scope.launch { runCatching { saveProgress() } }
    }

    suspend fun saveProgress() {
        val current = state.value
        val bookId = current.bookId ?: return
        repo.saveProgress(bookId, current.chapterIndex, current.positionMs)
    }

    /** Позиция сохраняется на ходу: приложение могут закрыть в любой момент. */
    private fun startProgressAutosave() {
        scope.launch {
            while (true) {
                delay(5_000)
                if (state.value.isPlaying) runCatching { saveProgress() }
            }
        }
    }
}
