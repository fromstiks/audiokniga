package ua.starky.audiokniga.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.CustomSource
import ua.starky.audiokniga.settings.SettingsStore

/** Общая модель для «Источников» и «Настроек»: и то и другое — про подготовку приложения. */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = application.app.repository
    private val settings = application.app.settings

    val themeMode: StateFlow<Int> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val playbackSpeed: StateFlow<Float> = settings.playbackSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val skipSeconds: StateFlow<Int> = settings.skipSeconds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SECONDS)

    val customSources: StateFlow<List<CustomSource>> = repo.observeCustomSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Встроенные источники, убранные из поиска. */
    val disabledSources: StateFlow<Set<String>> = settings.disabledSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setBuiltInEnabled(providerId: String, enabled: Boolean) =
        viewModelScope.launch { settings.setSourceEnabled(providerId, enabled) }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun setThemeMode(mode: Int) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setSkipSeconds(seconds: Int) = viewModelScope.launch { settings.setSkipSeconds(seconds) }

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            settings.setPlaybackSpeed(speed)
            getApplication<Application>().app.playback.setSpeed(speed)
        }
    }

    fun addSource(name: String, url: String) {
        val address = url.trim()
        if (!address.startsWith("http", ignoreCase = true)) {
            _message.value = "Адрес должен начинаться с http:// или https://"
            return
        }
        viewModelScope.launch {
            repo.addCustomSource(name, address)
            _message.value = if (address.contains(CustomSource.QUERY_PLACEHOLDER)) {
                "Источник добавлен и участвует в поиске"
            } else {
                "Источник добавлен. Откройте его кнопкой справа"
            }
        }
    }

    /**
     * Добавляет источники списком: по строке на источник, «Название | адрес» или просто адрес.
     * Вбивать два десятка адресов по одному — работа не для человека.
     */
    fun addSourcesFromText(text: String) {
        val parsed = CustomSource.parseList(text)
        if (parsed.isEmpty()) {
            _message.value = "Не нашёл ни одного адреса. Каждый источник — с новой строки, " +
                "адрес начинается с http:// или https://"
            return
        }
        viewModelScope.launch {
            val added = repo.addCustomSources(parsed)
            val lines = text.lineSequence().count { it.isNotBlank() && !it.trim().startsWith("#") }
            _message.value = buildString {
                append("Добавлено ")
                append(added)
                append(' ')
                append(sourceWord(added))
                if (lines > added) append(", пропущено строк: ${lines - added}")
            }
        }
    }

    fun updateSource(source: CustomSource, name: String, url: String) {
        val address = url.trim()
        if (!address.startsWith("http", ignoreCase = true)) {
            _message.value = "Адрес должен начинаться с http:// или https://"
            return
        }
        viewModelScope.launch {
            repo.updateCustomSource(source.id, name, address)
            _message.value = if (address.contains(CustomSource.QUERY_PLACEHOLDER)) {
                "Источник сохранён и участвует в поиске"
            } else {
                "Источник сохранён. Без {q} в адресе он не ищет, а открывается целиком"
            }
        }
    }

    fun setEnabled(source: CustomSource, enabled: Boolean) =
        viewModelScope.launch { repo.setCustomSourceEnabled(source.id, enabled) }

    fun remove(source: CustomSource) =
        viewModelScope.launch { repo.removeCustomSource(source.id) }

    /** Читает источник целиком и кладёт его в библиотеку — так проверяется, что адрес рабочий. */
    fun open(source: CustomSource, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repo.openCustomSource(source) }
                .onSuccess { bookId ->
                    _busy.value = false
                    onOpened(bookId)
                }
                .onFailure { error ->
                    _busy.value = false
                    _message.value = "${source.name} ${error.message ?: "не открылся"}"
                }
        }
    }

    fun clearMessage() { _message.value = null }
}

private fun sourceWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "источников"
        mod10 == 1 -> "источник"
        mod10 in 2..4 -> "источника"
        else -> "источников"
    }
}
