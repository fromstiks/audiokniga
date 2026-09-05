package ua.starky.audiohunter

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.starky.audiohunter.data.provider.AggregatorConfigHolder
import ua.starky.audiohunter.data.repo.LibraryRepository
import ua.starky.audiohunter.download.DownloadTracker
import ua.starky.audiohunter.settings.SettingsStore

class App : Application() {

    lateinit var repository: LibraryRepository
        private set
    lateinit var downloads: DownloadTracker
        private set
    lateinit var settings: SettingsStore
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(this)
        downloads = DownloadTracker(this)
        settings = SettingsStore(this)

        appScope.launch {
            settings.aggregatorBaseUrl.collect { AggregatorConfigHolder.baseUrl = it }
        }
    }
}
