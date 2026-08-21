package ua.starky.audiokniga

import android.app.Application
import ua.starky.audiokniga.data.repo.LibraryRepository
import ua.starky.audiokniga.download.DownloadTracker
import ua.starky.audiokniga.settings.SettingsStore

class App : Application() {

    lateinit var repository: LibraryRepository
        private set
    lateinit var downloads: DownloadTracker
        private set
    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = LibraryRepository(this)
        downloads = DownloadTracker(this)
        settings = SettingsStore(this)
    }
}
