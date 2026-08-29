package ua.starky.audiokniga.playback

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ua.starky.audiokniga.data.model.Bookmark
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.ChapterOrder
import ua.starky.audiokniga.data.model.DownloadState
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.repo.LibraryRepository
import ua.starky.audiokniga.download.DownloadTracker

/** Что нажали в виджете на рабочем столе. */
enum class WidgetAction { PLAY_PAUSE, REWIND, FORWARD }

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

    /** Шаг кнопок перемотки. Одно значение на всё приложение, включая замок и виджет. */
    private val skipMs: Long get() = SkipSettings.skipMs

    /** Таймер сна. Живёт в приложении, а не на экране: экран можно и закрыть. */
    private val _sleep = MutableStateFlow(SleepTimerState())
    val sleep: StateFlow<SleepTimerState> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

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

    /**
     * Команда из виджета.
     *
     * Виджет нельзя пускать к сессии напрямую. Он живёт и тогда, когда приложение
     * закрыто; процесс поднимается ради самого нажатия, плеер в нём пустой, и play()
     * там играть нечего — кнопка выглядит сломанной. Поэтому сначала возвращаем в плеер
     * последнюю книгу и только потом выполняем команду.
     *
     * [onDone] отпускает приёмник: до его вызова система держит процесс живым.
     */
    fun widgetCommand(action: WidgetAction, onDone: () -> Unit = {}) {
        scope.launch {
            try {
                // Приёмник нельзя держать дольше десятка секунд — иначе система его убьёт
                // как зависший. Если сессия так и не поднялась, лучше отпустить.
                withTimeoutOrNull(WIDGET_TIMEOUT_MS) {
                    ready.await()
                    val hadQueue = state.value.queueSize > 0
                    if (!hadQueue) restoreLast(play = action == WidgetAction.PLAY_PAUSE)

                    when (action) {
                        // Очередь только что зарядили и уже запустили — повторное нажатие
                        // здесь сразу поставило бы её на паузу.
                        WidgetAction.PLAY_PAUSE -> if (hadQueue) connection.playPause()
                        WidgetAction.REWIND -> connection.skipBy(-skipMs)
                        WidgetAction.FORWARD -> connection.skipBy(skipMs)
                    }
                }
            } finally {
                onDone()
            }
        }
    }

    /** Вернуть в плеер книгу, которую слушали последней. */
    private suspend fun restoreLast(play: Boolean) {
        val target = _openBookId.value ?: repo.lastOpenedBookId() ?: return
        runCatching { open(target, play = play) }
            .onFailure { _notice.value = it.message ?: "Не удалось открыть книгу" }
    }

    fun seekFraction(fraction: Float) = connection.seekToFraction(fraction)

    fun playChapter(chapterId: String) { connection.playChapterById(chapterId) }

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

    // ——— Таймер сна и отмеченные моменты ———

    /**
     * Включает или снимает таймер сна. Отсчёт идёт по часам, а не по времени
     * воспроизведения: пауза посреди ночи его не продлевает.
     */
    fun setSleepTimer(plan: SleepPlan) {
        sleepJob?.cancel()
        sleepJob = null

        when (plan) {
            SleepPlan.Off -> {
                _sleep.value = SleepTimerState()
                _notice.value = "Таймер сна выключен"
            }

            is SleepPlan.After -> {
                val total = plan.minutes * 60_000L
                val endAt = SystemClock.elapsedRealtime() + total
                _sleep.value = SleepTimerState(plan, total)
                sleepJob = scope.launch {
                    while (isActive) {
                        delay(500)
                        val left = endAt - SystemClock.elapsedRealtime()
                        _sleep.value = _sleep.value.copy(remainingMs = left.coerceAtLeast(0L))
                        if (left <= 0L) break
                    }
                    stopForSleep()
                }
                _notice.value = "Таймер сна: ${plan.minutes} мин"
            }

            SleepPlan.EndOfChapter -> {
                // Без открытой главы ждать нечего: конца у неё не наступит.
                val startedOn = state.value.chapterId
                if (startedOn == null) {
                    _sleep.value = SleepTimerState()
                    _notice.value = "Сначала запустите книгу — тогда таймер будет знать, какую главу дослушать"
                    return
                }
                _sleep.value = SleepTimerState(plan, chapterLeftMs())
                sleepJob = scope.launch {
                    while (isActive) {
                        delay(500)
                        val current = state.value
                        _sleep.value = _sleep.value.copy(remainingMs = chapterLeftMs())
                        // Глава сменилась сама или руками — в обоих случаях эта дослушана.
                        if (current.chapterId != startedOn) break
                        if (current.durationMs > 0 && chapterLeftMs() <= 700L) break
                    }
                    stopForSleep()
                }
                _notice.value = "Таймер сна: до конца главы"
            }
        }
    }

    private fun chapterLeftMs(): Long {
        val current = state.value
        if (current.durationMs <= 0L) return 0L
        return (current.durationMs - current.positionMs).coerceAtLeast(0L)
    }

    /**
     * Таймер досчитал. Ставим паузу и отмечаем момент: под сон никто не запоминает,
     * на чём заснул, а прогресс книги к утру можно и перемотать случайно.
     */
    private suspend fun stopForSleep() {
        val mark = runCatching { markCurrentPosition(Bookmark.SLEEP_LABEL) }.getOrNull()
        connection.pause()
        runCatching { saveProgress() }
        _sleep.value = SleepTimerState()
        sleepJob = null
        _notice.value = if (mark == null) {
            "Таймер сна: пауза"
        } else {
            "Таймер сна: пауза. Момент отмечен — «${mark.chapterTitle}»"
        }
    }

    /**
     * Отмечает то место, где книга стоит сейчас. Глава запоминается идентификатором:
     * её номер зависит от порядка глав и от режима источника, а идентификатор — нет.
     */
    suspend fun markCurrentPosition(label: String = Bookmark.MANUAL_LABEL): Bookmark? {
        val current = state.value
        val bookId = current.bookId ?: _openBookId.value ?: return null
        val chapterId = current.chapterId ?: return null
        return repo.addBookmark(
            bookId = bookId,
            chapterId = chapterId,
            chapterTitle = current.chapterTitle ?: "Глава",
            positionMs = current.positionMs,
            label = label,
            replaceSameLabel = label == Bookmark.SLEEP_LABEL,
        )
    }

    /** Вернуться к отмеченному моменту. */
    suspend fun jumpTo(bookmark: Bookmark) {
        open(bookmark.bookId, play = false)
        val found = connection.playChapterById(bookmark.chapterId, bookmark.positionMs)
        if (!found) {
            _notice.value = "Этой главы нет в текущем списке. В «Офлайн» она появится после загрузки."
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

    private companion object {
        /** Столько ждём плеер по команде из виджета, прежде чем отпустить приёмник. */
        const val WIDGET_TIMEOUT_MS = 8_000L
    }
}
