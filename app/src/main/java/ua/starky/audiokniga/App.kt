package ua.starky.audiokniga

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.data.repo.LibraryRepository
import ua.starky.audiokniga.download.DownloadModule
import ua.starky.audiokniga.download.DownloadTracker
import ua.starky.audiokniga.playback.PlaybackController
import ua.starky.audiokniga.playback.SkipSettings
import ua.starky.audiokniga.widget.PlayerWidget
import ua.starky.audiokniga.widget.WidgetSnapshot
import ua.starky.audiokniga.settings.SettingsStore

class App : Application() {

    lateinit var repository: LibraryRepository
        private set
    lateinit var downloads: DownloadTracker
        private set
    lateinit var settings: SettingsStore
        private set

    /** Один плеер на приложение: его показывают и полка, и экран книги. */
    lateinit var playback: PlaybackController
        private set

    /**
     * Необработанный сбой в фоновой задаче обрушил бы приложение целиком: SupervisorJob
     * спасает соседние задачи, но не процесс. Логируем и живём дальше.
     */
    private val errors = CoroutineExceptionHandler { _, error ->
        Log.e("Audiokniga", "Фоновая задача завершилась ошибкой", error)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(this)
        downloads = DownloadTracker(this)
        settings = SettingsStore(this)
        playback = PlaybackController(this, repository, downloads, scope)

        // Источники, добавленные пользователем, должны участвовать в поиске сразу
        // после добавления — поэтому реестр подписан на таблицу, а не читает её однажды.
        scope.launch {
            repository.observeCustomSources().collectLatest { ProviderRegistry.setCustomSources(it) }
        }
        scope.launch {
            settings.disabledSources.collectLatest { ProviderRegistry.setDisabledBuiltIn(it) }
        }
        scope.launch {
            settings.skipSeconds.collectLatest { SkipSettings.skipMs = it * 1000L }
        }

        // Виджет живёт вне экранов и сам о плеере не узнает. Книгу подбираем здесь же:
        // приёмник виджета работает без доступа к базе. Полка отсортирована по времени
        // открытия, поэтому первая книга — та, которую слушали последней.
        scope.launch {
            combine(
                playback.state,
                playback.openBookId,
                repository.observeLibrary(),
            ) { state, openId, books ->
                val id = state.bookId ?: openId
                val book = books.firstOrNull { it.id == id } ?: books.firstOrNull()
                WidgetSnapshot(
                    title = book?.title,
                    subtitle = state.chapterTitle ?: book?.author,
                    playing = state.isPlaying,
                )
            }
                // Позиция обновляется дважды в секунду, а виджету от неё ни холодно
                // ни жарко: перерисовываем, только когда изменилось видимое.
                .distinctUntilChanged()
                .collectLatest { PlayerWidget.refresh(this@App, it) }
        }

        // Хранилище скачанного открывается с чтением диска. Делаем это заранее и в фоне,
        // иначе первое нажатие на «скачать» подвешивает интерфейс.
        scope.launch(Dispatchers.IO) {
            runCatching { DownloadModule.cache(this@App) }
        }
    }
}
