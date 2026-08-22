package ua.starky.audiokniga.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.playback.PlaybackState
import ua.starky.audiokniga.settings.SettingsStore

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val playback = application.app.playback

    val books: StateFlow<List<Book>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playbackState: StateFlow<PlaybackState> = playback.state

    val skipSeconds: StateFlow<Int> = application.app.settings.skipSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SECONDS)

    /**
     * Книга для свёрнутого плеера. Пока ничего не играет, показываем последнюю открытую —
     * иначе нижняя часть полки пустует и плеера будто нет вовсе.
     */
    val currentBook: StateFlow<Book?> =
        combine(books, playback.state, playback.openBookId) { list, state, openId ->
            val id = state.bookId ?: openId
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
