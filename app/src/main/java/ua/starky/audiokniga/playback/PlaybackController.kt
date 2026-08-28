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
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.ChapterOrder
import ua.starky.audiokniga.data.model.DownloadState
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.repo.LibraryRepository
import ua.starky.audiokniga.download.DownloadTracker

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
    private val downloads: DownloadTracker,
    private val scope: CoroutineScope,
) {
    private val connection = PlayerConnection(context, scope)

    val state: StateFlow<PlaybackState> = connection.state

    /** Книга, открытая в плеере сейчас, — даже если воспроизведение ещё не начиналось. */
    private val _openBookId = MutableStateFlow<String?>(null)
    val openBookId: StateFlow<String?> = _openBookId.asStateFlow()

    /** Последняя причина, по которой очередь оказалась пустой. Её показывает экран книги. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Шаг кнопок перемотки, задаётся в настройках. */
    @Volatile
    var skipMs: Long = 20_000L

    private val ready = CompletableDeferred<Unit>()
    private val queueLock = Mutex()
    private var queuedBookId: String? = null
    private var queuedMode: SourceMode? = null

    init {
        connection.connect { ready.complete(Unit) }
        startProgressAutosave()
    }

    /**
     * Готовит книгу к прослушиванию. Повторный вызов для той же книги и того же режима
     * очередь не пересобирает, иначе каждое открытие экрана сбрасывало бы позицию.
     */
    suspend fun open(bookId: String, play: Boolean = false) {
        ready.await()
        _openBookId.value = bookId
        val mode = repo.sourceModeOf(bookId)

        queueLock.withLock {
            if (queuedBookId == bookId && queuedMode == mode) {
                if (play) connection.playPause()
                return
            }
            fillQueue(bookId, mode, play = play, keepPosition = false)
        }
    }

    /**
     * Смена режима источника. В «Офлайн» очередь пересобирается только из скачанного —
     * иначе плеер спотыкался бы о главы, которых нет на устройстве, и продолжал бы
     * лезть в сеть. Текущая глава сохраняется, если она пережила отбор.
     */
    suspend fun applySourceMode(bookId: String, mode: SourceMode) {
        ready.await()
        connection.setSourceMode(mode)
        queueLock.withLock {
            fillQueue(bookId, mode, play = state.value.isPlaying, keepPosition = true)
        }
    }

    /** Пересобрать очередь под текущие настройки книги, сохранив место в ней. */
    suspend fun rebuildQueue(bookId: String) {
        ready.await()
        queueLock.withLock {
            fillQueue(bookId, repo.sourceModeOf(bookId), play = state.value.isPlaying, keepPosition = true)
        }
    }

    private suspend fun fillQueue(bookId: String, mode: SourceMode, play: Boolean, keepPosition: Boolean) {
        val chapters = queueFor(bookId, mode)

        if (chapters.isEmpty()) {
            queuedBookId = null
            queuedMode = null
            connection.clearQueue()
            _notice.value = if (mode == SourceMode.OFFLINE) {
                "Ни одна глава ещё не скачана. Переключитесь на «Онлайн» и нажмите загрузку."
            } else {
                "У этой книги нет доступных файлов"
            }
            return
        }

        val startIndex: Int
        val startPosition: Long
        if (keepPosition) {
            val currentId = state.value.chapterId
            val found = chapters.indexOfFirst { it.id == currentId }
            startIndex = found.coerceAtLeast(0)
            // Позицию внутри главы имеет смысл сохранять только если это та же глава.
            startPosition = if (found >= 0) state.value.positionMs else 0L
        } else {
            val (savedIndex, savedPosition) = repo.lastPosition(bookId)
            val found = chapters.indexOfFirst { it.index == savedIndex }
            startIndex = found.coerceAtLeast(0)
            startPosition = if (found >= 0) savedPosition else 0L
        }

        connection.setSourceMode(mode)
        connection.setQueue(bookId, chapters, startIndex, startPosition, play)
        queuedBookId = bookId
        queuedMode = mode
        _notice.value = null
    }

    /**
     * В офлайне в очередь попадает только то, что действительно лежит на устройстве:
     * скачанное загрузчиком либо файл, выбранный пользователем с самого телефона —
     * последний и так локальный, фильтровать его по кэшу загрузок бессмысленно.
     */
    private suspend fun queueFor(bookId: String, mode: SourceMode): List<Chapter> {
        // Очередь обязана совпадать с тем, что человек видит на экране.
        val all = ChapterOrder.sort(repo.ensureLoaded(bookId), repo.chapterOrderOf(bookId))
        if (mode != SourceMode.OFFLINE) return all
        val downloaded = downloads.snapshotAsync()
        return all.filter {
            it.audioUrl.isOnDeviceUrl() || downloaded[it.id]?.state == DownloadState.DOWNLOADED
        }
    }

    /**
     * Нажатие play там, где книга ещё не загружена в плеер, — например в свёрнутом плеере.
     * Сбой здесь нельзя терять: кнопка просто «не работала бы», а причина не доехала бы
     * ни до экрана, ни до пользователя.
     */
    fun playPause(bookId: String? = null) {
        val target = bookId ?: _openBookId.value
        if (target != null && queuedBookId != target) {
            scope.launch {
                runCatching { open(target, play = true) }
                    .onFailure { _notice.value = it.message ?: "Не удалось открыть книгу" }
            }
        } else {
            connection.playPause()
        }
    }

    fun skipForward() = connection.skipBy(skipMs)

    fun skipBack() = connection.skipBy(-skipMs)

    fun seekFraction(fraction: Float) = connection.seekToFraction(fraction)

    fun playChapter(chapterId: String) = connection.playChapterById(chapterId)

    fun setSpeed(speed: Float) = connection.setSpeed(speed)

    fun clearError() {
        connection.clearError()
        _notice.value = null
    }

    /** Книга удалена из библиотеки — плеер не должен продолжать её показывать. */
    fun forget(bookId: String) {
        if (_openBookId.value == bookId) _openBookId.value = null
        if (queuedBookId == bookId) {
            queuedBookId = null
            queuedMode = null
            connection.clearQueue()
        }
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
