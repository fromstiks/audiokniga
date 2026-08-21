package ua.starky.audiokniga.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.SearchResult

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        job?.cancel()
        if (query.trim().length < 3) return
        job = viewModelScope.launch {
            delay(450) // не дёргаем источники на каждую букву
            search()
        }
    }

    fun search() {
        job?.cancel()
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        job = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching { repo.search(query) }
                .onSuccess { results ->
                    _state.value = _state.value.copy(results = results, loading = false, searched = true)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        loading = false,
                        searched = true,
                        error = error.message ?: "Источники не ответили",
                    )
                }
        }
    }

    /** Кладёт книгу в библиотеку и возвращает её id для перехода в плеер. */
    fun addToLibrary(bookId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            runCatching { repo.addToLibrary(bookId) }
                .onSuccess {
                    _state.value = _state.value.copy(loading = false)
                    onDone(bookId)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(loading = false, error = error.message ?: "Не удалось открыть книгу")
                }
        }
    }
}
