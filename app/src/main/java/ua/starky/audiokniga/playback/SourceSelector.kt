package ua.starky.audiokniga.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.download.DownloadModule
import java.util.concurrent.atomic.AtomicReference

/**
 * Сердце переключателя «онлайн / офлайн».
 *
 * Плеер создаётся один раз, а решение о том, откуда читать байты, принимается заново
 * при открытии каждого файла — по текущему режиму. Поэтому переключение источника
 * не пересоздаёт плеер и не сбивает позицию: достаточно перезапустить текущий трек.
 */
@OptIn(UnstableApi::class)
class SourceSelector(context: Context) : DataSource.Factory {

    private val appContext = context.applicationContext
    private val mode = AtomicReference(SourceMode.AUTO)

    private val cacheOnly by lazy { DownloadModule.cacheOnlyFactory(appContext) }
    private val cacheAndNetwork by lazy { DownloadModule.cacheAndNetworkFactory(appContext) }
    private val networkOnly by lazy { DownloadModule.upstreamFactory(appContext) }

    var current: SourceMode
        get() = mode.get()
        set(value) { mode.set(value) }

    override fun createDataSource(): DataSource = when (mode.get()) {
        SourceMode.OFFLINE -> cacheOnly.createDataSource()
        SourceMode.ONLINE -> networkOnly.createDataSource()
        SourceMode.AUTO -> cacheAndNetwork.createDataSource()
    }
}
