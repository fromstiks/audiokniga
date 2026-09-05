package ua.starky.audiohunter.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiohunter.app
import ua.starky.audiohunter.data.model.Book
import ua.starky.audiohunter.data.model.Chapter
import ua.starky.audiohunter.data.model.ChapterUi
import ua.starky.audiohunter.data.model.DownloadState
import ua.starky.audiohunter.data.model.SourceMode
import ua.starky.audiohunter.download.DownloadInfo
import ua.starky.audiohunter.playback.PlaybackState
import ua.starky.audiohunter.playback.PlayerConnection

data class PlayerUiState(
    val book: Book? = null,
    val chapters: List<ChapterUi> = emptyList(),
    val sourceMode: SourceMode = SourceMode.AUTO,
    val playback: PlaybackState = PlaybackState(),
    val downloadedCount: Int = 0,
    val loading: Boolean = true,
    val message: String? = null,
)

class PlayerViewModel(application: Application, private val bookId: String) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val downloads = application.app.downloads
    private val connection = PlayerConnection(application, viewModelScope)

    private val downloadInfo = MutableStateFlow<Map<String, DownloadInfo>>(emptyMap())
    private val loading = MutableStateFlow(true)
    private val message = MutableStateFlow<String?>(null)

    private var queueLoadedFor: String? = null

    val state: StateFlow<PlayerUiState> = combine(
        repo.observeBookWithChapters(bookId),
        repo.observeSourceMode(bookId),
        connection.state,
        downloadInfo,
        combine(loading, message) { l, m -> l to m },
    ) { bookAndChapters, mode, playback, info, loadingAndMessage ->
        val (book, chapters) = bookAndChapters
        val (isLoading, msg) = loadingAndMessage
        PlayerUiState(
            book = book,
            chapters = chapters.map { chapter -> chapter.toUi(info, playback) },
            sourceMode = mode,
            playback = playback,
            downloadedCount = chapters.count { info[it.id]?.state == DownloadState.DOWNLOADED },
            loading = isLoading && book == null,
            message = msg ?: playback.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        viewModelScope.launch {
            downloads.observe().collect { downloadInfo.value = it }
        }
        viewModelScope.launch {
            runCatching { repo.ensureLoaded(bookId) }
                .onFailure { message.value = it.message ?: "Не удалось открыть книгу" }
            loading.value = false
            connection.connect { prepareQueue(play = false) }
        }
    }

    private fun prepareQueue(play: Boolean) {
        viewModelScope.launch {
            if (queueLoadedFor == bookId && !play) return@launch
            val chapters = runCatching { repo.ensureLoaded(bookId) }.getOrElse { return@launch }
            if (chapters.isEmpty()) return@launch
            val (chapterIndex, positionMs) = repo.lastPosition(bookId)
            connection.setSourceMode(currentMode)
            connection.setQueue(bookId, chapters, chapterIndex, positionMs, play)
            queueLoadedFor = bookId
        }
    }

    private val currentMode: SourceMode get() = state.value.sourceMode

    fun playPause() {
        if (queueLoadedFor == null) prepareQueue(play = true) else connection.playPause()
    }

    fun playChapter(index: Int) {
        if (queueLoadedFor == null) {
            prepareQueue(play = true)
        } else {
            connection.playChapter(index)
        }
        viewModelScope.launch { repo.saveProgress(bookId, index, 0L) }
    }

    fun seekFraction(fraction: Float) = connection.seekToFraction(fraction)

    fun skip(deltaMs: Long) = connection.skipBy(deltaMs)

    fun cycleSpeed() {
        val next = when (state.value.playback.speed) {
            in 0.99f..1.01f -> 1.2f
            in 1.19f..1.21f -> 1.5f
            in 1.49f..1.51f -> 2.0f
            else -> 1.0f
        }
        connection.setSpeed(next)
    }

    /** Тот самый переключатель. Режим запоминается для книги. */
    fun setSourceMode(mode: SourceMode) {
        viewModelScope.launch {
            repo.setSourceMode(bookId, mode)
            connection.setSourceMode(mode)
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
            downloads.downloadAll(chapters.filter { info[it.id]?.state != DownloadState.DOWNLOADED })
            message.value = "Скачиваю ${chapters.size} глав"
        }
    }

    fun clearMessage() {
        message.value = null
        connection.clearError()
    }

    fun saveProgressNow() {
        val playback = state.value.playback
        viewModelScope.launch { repo.saveProgress(bookId, playback.chapterIndex, playback.positionMs) }
    }

    override fun onCleared() {
        saveProgressNow()
        connection.release()
        super.onCleared()
    }

    private fun Chapter.toUi(info: Map<String, DownloadInfo>, playback: PlaybackState): ChapterUi {
        val download = info[id]
        val isCurrent = playback.chapterIndex == index
        return ChapterUi(
            chapter = this,
            downloadState = download?.state ?: DownloadState.NONE,
            downloadProgress = download?.progress ?: 0f,
            isPlaying = isCurrent && playback.isPlaying,
            isCurrent = isCurrent,
        )
    }
}
