package ua.starky.audiokniga.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.local.LocalImporter
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.Playlist
import ua.starky.audiokniga.data.model.ShelfFilter
import ua.starky.audiokniga.playback.PlaybackState
import ua.starky.audiokniga.settings.SettingsStore

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val playback = application.app.playback

    private val allBooks: StateFlow<List<Book>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repo.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** playlistId -> id книг в нём. */
    private val membership: StateFlow<Map<String, Set<String>>> = repo.observePlaylistMembership()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _filter = MutableStateFlow<ShelfFilter>(ShelfFilter.All)
    val filter: StateFlow<ShelfFilter> = _filter.asStateFlow()

    /** Полка после применения выбранного фильтра. */
    val books: StateFlow<List<Book>> =
        combine(allBooks, _filter, membership) { list, filter, links ->
            when (filter) {
                is ShelfFilter.All -> list
                is ShelfFilter.Favorites -> list.filter { it.favorite }
                is ShelfFilter.InPlaylist -> {
                    val ids = links[filter.playlistId].orEmpty()
                    list.filter { it.id in ids }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteCount: StateFlow<Int> = allBooks
        .map { list -> list.count { it.favorite } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val playbackState: StateFlow<PlaybackState> = playback.state

    val skipSeconds: StateFlow<Int> = application.app.settings.skipSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SECONDS)

    /**
     * Книга для свёрнутого плеера. Пока ничего не играет, показываем последнюю открытую —
     * иначе нижняя часть полки пустует и плеера будто нет вовсе.
     */
    val currentBook: StateFlow<Book?> =
        combine(allBooks, playback.state, playback.openBookId) { list, state, openId ->
            val id = state.bookId ?: openId
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Папка целиком — одна книга. Самый частый способ хранить аудиокнигу. */
    fun importFolder(uri: Uri) = importWith { LocalImporter.fromFolder(getApplication<Application>(), uri) }

    /** Отдельные файлы — когда книга разложена не по папкам. */
    fun importFiles(uris: List<Uri>) = importWith { LocalImporter.fromFiles(getApplication<Application>(), uris) }

    private fun importWith(load: suspend () -> ua.starky.audiokniga.data.model.BookDetails?) {
        viewModelScope.launch {
            _importing.value = true
            runCatching { load() }
                .onSuccess { details ->
                    _importing.value = false
                    if (details == null) {
                        _message.value = "Аудиофайлов не нашлось. Поддерживаются mp3, m4a, m4b, ogg, opus, flac и wav."
                        return@onSuccess
                    }
                    repo.importLocal(details)
                    _message.value = "Добавлено: «${details.book.title}», " +
                        "${details.chapters.size} ${chapterWord(details.chapters.size)}"
                }
                .onFailure {
                    _importing.value = false
                    _message.value = "Не удалось прочитать: ${it.message ?: "неизвестная ошибка"}"
                }
        }
    }

    fun clearMessage() { _message.value = null }

    fun setFilter(filter: ShelfFilter) {
        _filter.value = filter
    }

    fun toggleFavorite(book: Book) {
        viewModelScope.launch { repo.setFavorite(book.id, !book.favorite) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val playlist = repo.createPlaylist(name)
            _filter.value = ShelfFilter.InPlaylist(playlist.id)
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        viewModelScope.launch { repo.renamePlaylist(playlistId, name) }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repo.deletePlaylist(playlistId)
            if ((_filter.value as? ShelfFilter.InPlaylist)?.playlistId == playlistId) {
                _filter.value = ShelfFilter.All
            }
        }
    }

    /** Убрать книгу из текущего списка, не удаляя её из библиотеки. */
    fun removeFromCurrentPlaylist(book: Book) {
        val playlistId = (_filter.value as? ShelfFilter.InPlaylist)?.playlistId ?: return
        viewModelScope.launch { repo.setInPlaylist(playlistId, book.id, inList = false) }
    }

    fun playPause() {
        playback.playPause(currentBook.value?.id)
    }

    fun skipForward() = playback.skipForward()

    fun skipBack() = playback.skipBack()

    fun remove(bookId: String) {
        viewModelScope.launch {
            repo.remove(bookId)
            playback.forget(bookId)
        }
    }
}

private fun chapterWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "глав"
        mod10 == 1 -> "глава"
        mod10 in 2..4 -> "главы"
        else -> "глав"
    }
}
