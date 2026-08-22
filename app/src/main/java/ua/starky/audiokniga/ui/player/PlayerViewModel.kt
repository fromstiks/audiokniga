package ua.starky.audiokniga.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.ChapterUi
import ua.starky.audiokniga.data.model.DownloadState
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.download.DownloadInfo
import ua.starky.audiokniga.playback.PlaybackState
import ua.starky.audiokniga.settings.SettingsStore

data class PlayerUiState(
    val book: Book? = null,
    val chapters: List<ChapterUi> = emptyList(),
    val sourceMode: SourceMode = SourceMode.AUTO,
    val playback: PlaybackState = PlaybackState(),
    val downloadedCount: Int = 0,
    val skipSeconds: Int = SettingsStore.DEFAULT_SKIP_SECONDS,
    val loading: Boolean = true,
    /** Книга не открылась совсем — экран должен объяснить, почему, а не остаться пустым. */
    val failure: String? = null,
    val message: String? = null,
)

class PlayerViewModel(application: Application, private val bookId: String) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val downloads = application.app.downloads
    private val playback = application.app.playback
    private val settings = application.app.settings

    private val downloadInfo = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val failure = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<PlayerUiState> = combine(
        repo.observeBookWithChapters(bookId),
        repo.observeSourceMode(bookId),
        playback.state,
        downloadInfo,
        combine(loading, failure, message, settings.skipSeconds) { l, f, m, skip -> Screen(l, f, m, skip) },
    ) { bookAndChapters, mode, playbackState, info, screen ->
        val (book, chapters) = bookAndChapters
        PlayerUiState(
            book = book,
            chapters = chapters.map { chapter -> chapter.toUi(info, playbackState) },
            sourceMode = mode,
            playback = playbackState,
            downloadedCount = chapters.count { info[it.id]?.state == DownloadState.DOWNLOADED },
            skipSeconds = screen.skipSeconds,
            loading = screen.loading && book == null,
            failure = screen.failure.takeIf { book == null },
            message = screen.message ?: playbackState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    private data class Screen(
        val loading: Boolean,
        val failure: String?,
        val message: String?,
        val skipSeconds: Int,
    )

    init {
        viewModelScope.launch {
            downloads.observe().collect { downloadInfo.value = it }
        }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            loading.value = true
            failure.value = null
            runCatching { playback.open(bookId, play = false) }
                .onFailure { failure.value = it.message ?: "Не удалось открыть книгу" }
            loading.value = false
        }
    }

    fun retry() = load()

    fun playPause() = playback.playPause(bookId)

    fun playChapter(index: Int) {
        playback.playChapter(index)
        viewModelScope.launch { repo.saveProgress(bookId, index, 0L) }
    }

    fun seekFraction(fraction: Float) = playback.seekFraction(fraction)

    fun skipForward() = playback.skipForward()

    fun skipBack() = playback.skipBack()

    fun cycleSpeed() {
        val next = when (state.value.playback.speed) {
            in 0.99f..1.01f -> 1.25f
            in 1.24f..1.26f -> 1.5f
            in 1.49f..1.51f -> 2.0f
            else -> 1.0f
        }
        playback.setSpeed(next)
        viewModelScope.launch { settings.setPlaybackSpeed(next) }
    }

    /** Тот самый переключатель. Режим запоминается для книги. */
    fun setSourceMode(mode: SourceMode) {
        viewModelScope.launch {
            repo.setSourceMode(bookId, mode)
            playback.setSourceMode(mode)
            message.value = when (mode) {
                SourceMode.OFFLINE -> "Играем только скачанное"
                SourceMode.ONLINE -> "Играем из сети"
                SourceMode.AUTO -> "Скачанное — с устройства, остальное — из сети"
            }
        }
    }

    fun downloadChapter(chapter: Chapter) = downloads.download(chapter)

    fun removeChapter(chapterId: String) = downloads.remove(chapterId)

    fun downloadAll() {
        viewModelScope.launch {
            val chapters = runCatching { repo.ensureLoaded(bookId) }.getOrElse { return@launch }
            val info = downloadInfo.value
            val pending = chapters.filter { info[it.id]?.state != DownloadState.DOWNLOADED }
            if (pending.isEmpty()) {
                message.value = "Все главы уже на устройстве"
                return@launch
            }
            downloads.downloadAll(pending)
            message.value = "Скачиваю ${pending.size} глав"
        }
    }

    fun clearMessage() {
        message.value = null
        playback.clearError()
    }

    override fun onCleared() {
        // viewModelScope здесь уже отменяется, поэтому сохраняем через общий плеер.
        playback.saveProgressNow()
        super.onCleared()
    }

    private fun Chapter.toUi(info: Map<String, DownloadInfo>, playbackState: PlaybackState): ChapterUi {
        val download = info[id]
        val isCurrent = playbackState.bookId == bookId && playbackState.chapterIndex == index
        return ChapterUi(
            chapter = this,
            downloadState = download?.state ?: DownloadState.NONE,
            downloadProgress = download?.progress ?: 0f,
            isPlaying = isCurrent && playbackState.isPlaying,
            isCurrent = isCurrent,
        )
    }
}
