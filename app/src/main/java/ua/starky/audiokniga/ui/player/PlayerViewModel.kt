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
import ua.starky.audiokniga.data.model.Playlist
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
    /** Сколько глав в книге всего — в офлайне список короче, и это нужно объяснить. */
    val totalChapters: Int = 0,
    val skipSeconds: Int = SettingsStore.DEFAULT_SKIP_SECONDS,
    val loading: Boolean = true,
    /** Книга не открылась совсем — экран должен объяснить, почему, а не остаться пустым. */
    val failure: String? = null,
    val message: String? = null,
)

/** Что показывать в окне «в список»: сами списки и те, где книга уже есть. */
data class PlaylistPicker(
    val playlists: List<Playlist> = emptyList(),
    val selected: Set<String> = emptySet(),
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
        combine(loading, failure, message, playback.notice, settings.skipSeconds) { l, f, m, notice, skip ->
            Screen(l, f, m ?: notice, skip)
        },
    ) { bookAndChapters, mode, playbackState, info, screen ->
        val (book, chapters) = bookAndChapters
        // В режиме «Офлайн» на экране остаётся ровно то, что лежит на устройстве:
        // иначе обе вкладки выглядят одинаково и обещают то, чего офлайн не даёт.
        val visible = if (mode == SourceMode.OFFLINE) {
            chapters.filter { info[it.id]?.state == DownloadState.DOWNLOADED }
        } else {
            chapters
        }
        PlayerUiState(
            book = book,
            chapters = visible.map { chapter -> chapter.toUi(info, playbackState) },
            sourceMode = mode,
            playback = playbackState,
            downloadedCount = chapters.count { info[it.id]?.state == DownloadState.DOWNLOADED },
            totalChapters = chapters.size,
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

    /**
     * Списки держим отдельным потоком, а не внутри PlayerUiState: окно выбора
     * открывается редко, и незачем пересобирать весь экран на каждое изменение.
     */
    val playlistPicker: StateFlow<PlaylistPicker> = combine(
        repo.observePlaylists(),
        repo.observePlaylistMembership(),
    ) { lists, membership ->
        PlaylistPicker(
            playlists = lists,
            selected = membership.filterValues { bookId in it }.keys,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistPicker())

    init {
        viewModelScope.launch {
            downloads.observe().collect { downloadInfo.value = it }
        }
        load()
    }

    fun toggleFavorite() {
        val book = state.value.book ?: return
        viewModelScope.launch {
            repo.setFavorite(bookId, !book.favorite)
            message.value = if (book.favorite) "Убрано из избранного" else "Добавлено в избранное"
        }
    }

    fun setInPlaylist(playlistId: String, inList: Boolean) {
        viewModelScope.launch { repo.setInPlaylist(playlistId, bookId, inList) }
    }

    /** Создаёт список и сразу кладёт в него текущую книгу — иначе это два действия подряд. */
    fun createPlaylistWithBook(name: String) {
        viewModelScope.launch {
            val playlist = repo.createPlaylist(name)
            repo.setInPlaylist(playlist.id, bookId, inList = true)
            message.value = "Список «${playlist.name}» создан"
        }
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

    fun playChapter(chapter: Chapter) {
        playback.playChapter(chapter.id)
        viewModelScope.launch { repo.saveProgress(bookId, chapter.index, 0L) }
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
            playback.applySourceMode(bookId, mode)
            message.value = when (mode) {
                SourceMode.OFFLINE -> "Только скачанное, сеть не используется"
                SourceMode.ONLINE -> "Играем из сети"
                SourceMode.AUTO -> "Скачанное — с устройства, остальное — из сети"
            }
        }
    }

    fun downloadChapter(chapter: Chapter) {
        val problem = downloads.download(chapter)
        message.value = problem?.let { "Не удалось поставить в очередь: $it" }
            ?: "Качаю «${chapter.title}»"
    }

    fun removeChapter(chapterId: String) {
        downloads.remove(chapterId)?.let { message.value = "Не удалось удалить: $it" }
    }

    fun downloadAll() {
        viewModelScope.launch {
            val chapters = runCatching { repo.ensureLoaded(bookId) }
                .getOrElse {
                    message.value = it.message ?: "Оглавление не загрузилось"
                    return@launch
                }
            val info = downloadInfo.value
            val pending = chapters.filter { info[it.id]?.state != DownloadState.DOWNLOADED }
            if (pending.isEmpty()) {
                message.value = "Все главы уже на устройстве"
                return@launch
            }
            val problem = downloads.downloadAll(pending)
            message.value = problem?.let { "Не удалось поставить в очередь: $it" }
                ?: "Качаю ${pending.size} ${chapterWord(pending.size)}"
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
        // Сравниваем по идентификатору: в офлайне очередь короче и номера не совпадают.
        val isCurrent = playbackState.chapterId == id
        return ChapterUi(
            chapter = this,
            downloadState = download?.state ?: DownloadState.NONE,
            downloadProgress = download?.progress ?: 0f,
            isPlaying = isCurrent && playbackState.isPlaying,
            isCurrent = isCurrent,
        )
    }
}

private fun chapterWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "глав"
        mod10 == 1 -> "главу"
        mod10 in 2..4 -> "главы"
        else -> "глав"
    }
}
