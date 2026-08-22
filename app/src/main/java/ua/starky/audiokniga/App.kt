package ua.starky.audiokniga

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.data.repo.LibraryRepository
import ua.starky.audiokniga.download.DownloadModule
import ua.starky.audiokniga.download.DownloadTracker
import ua.starky.audiokniga.playback.PlaybackController
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
            settings.skipSeconds.collectLatest { playback.skipMs = it * 1000L }
        }

        // Хранилище скачанного открывается с чтением диска. Делаем это заранее и в фоне,
        // иначе первое нажатие на «скачать» подвешивает интерфейс.
        scope.launch(Dispatchers.IO) {
            runCatching { DownloadModule.cache(this@App) }
        }
    }
}
