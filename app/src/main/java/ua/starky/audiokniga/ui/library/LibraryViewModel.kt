package ua.starky.audiokniga.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.Book

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val settings = application.app.settings

    val books: StateFlow<List<Book>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val aggregatorBaseUrl: StateFlow<String> = settings.aggregatorBaseUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun remove(bookId: String) {
        viewModelScope.launch { repo.remove(bookId) }
    }

    fun setAggregatorBaseUrl(url: String) {
        viewModelScope.launch { settings.setAggregatorBaseUrl(url) }
    }
}
