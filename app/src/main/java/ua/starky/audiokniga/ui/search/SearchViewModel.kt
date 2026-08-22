package ua.starky.audiokniga.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.SearchResult
import ua.starky.audiokniga.data.repo.SourceOutcome

data class SearchUiState(
    val query: String = "",
    /** Ответ каждого источника отдельно — по нему рисуются вкладки. */
    val sources: List<SourceOutcome> = emptyList(),
    /** null — вкладка «Все», иначе id источника. */
    val selectedSource: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val answered: Int = 0,
    val askedSources: Int = 0,
    val searched: Boolean = false,
    val opening: Boolean = false,
) {
    /** Что показывать в списке с учётом выбранной вкладки. */
    val visibleResults: List<SearchResult>
        get() = selectedSource
            ?.let { id -> sources.firstOrNull { it.providerId == id }?.results.orEmpty() }
            ?: sources.flatMap { it.results }.distinctBy { it.book.id }

    /** Ошибка выбранного источника — на вкладке она важнее общего списка проблем. */
    val selectedProblem: String?
        get() = selectedSource?.let { id -> sources.firstOrNull { it.providerId == id }?.problem }

    val problems: List<String>
        get() = sources.mapNotNull { source -> source.problem?.let { "${source.name} $it" } }
}

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /**
     * Две независимые задачи. Раньше была одна, и `search()`, вызванный из отложенной
     * задачи, первым делом отменял её же — то есть сам себя. Поиск не доходил до сети,
     * а пользователь видел «StandaloneCoroutine was cancelled» вместо результатов.
     */
    private var debounce: Job? = null
    private var searching: Job? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        debounce?.cancel()
        if (query.trim().length < MIN_QUERY) return
        debounce = viewModelScope.launch {
            delay(500) // не дёргаем источники на каждую букву
            search()
        }
    }

    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        debounce?.cancel()
        searching?.cancel()

        searching = viewModelScope.launch {
            _state.value = _state.value.copy(
                loading = true,
                error = null,
                answered = 0,
                askedSources = 0,
                sources = emptyList(),
            )
            try {
                // Источники отвечают с разной скоростью, поэтому результаты
                // показываются по мере поступления, а не после самого медленного.
                repo.search(query).collect { progress ->
                    _state.value = _state.value.copy(
                        sources = progress.sources,
                        answered = progress.answered,
                        askedSources = progress.askedSources,
                        loading = !progress.finished,
                        searched = progress.finished,
                        error = progress.summaryError(),
                    )
                }
            } catch (e: CancellationException) {
                // Пользователь набрал что-то ещё — это не ошибка, показывать нечего.
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    searched = true,
                    error = e.message ?: "Источники не ответили",
                )
            }
        }
    }

    /** Кладёт книгу в библиотеку и возвращает её id для перехода в плеер. */
    fun addToLibrary(bookId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(opening = true, error = null)
            try {
                repo.addToLibrary(bookId)
                _state.value = _state.value.copy(opening = false)
                onDone(bookId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    opening = false,
                    error = e.message ?: "Не удалось открыть книгу",
                )
            }
        }
    }

    /** Выбор вкладки. null — «Все». */
    fun selectSource(providerId: String?) {
        _state.value = _state.value.copy(selectedSource = providerId)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val MIN_QUERY = 3
    }
}
