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

    val books: StateFlow<List<Book>> = repo.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(bookId: String) {
        viewModelScope.launch { repo.remove(bookId) }
    }
}
