package ua.starky.audiokniga.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
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

    var current: SourceMode
        get() = mode.get()
        set(value) { mode.set(value) }

    override fun createDataSource(): DataSource = ModeAwareDataSource(appContext, mode)
}

/**
 * Выбор происходит в момент открытия файла, а не при создании источника: до этого
 * адрес неизвестен, а он решает. Файл с самого устройства читается напрямую при любом
 * режиме — он уже офлайн, и гонять его через кэш загрузок бессмысленно и вредно:
 * в режиме «Офлайн» кэш его не найдёт и книга просто не заиграет.
 */
@OptIn(UnstableApi::class)
private class ModeAwareDataSource(
    private val context: Context,
    private val mode: AtomicReference<SourceMode>,
) : DataSource {

    private val device by lazy { DefaultDataSource.Factory(context).createDataSource() }
    private val cacheOnly by lazy { DownloadModule.cacheOnlyFactory(context).createDataSource() }
    private val cacheAndNetwork by lazy { DownloadModule.cacheAndNetworkFactory(context).createDataSource() }
    private val networkOnly by lazy { DownloadModule.upstreamFactory(context).createDataSource() }

    private val listeners = mutableListOf<TransferListener>()
    private val listenersAttachedTo = mutableSetOf<DataSource>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        // Уже созданным источникам слушателя нужно отдать сразу.
        listenersAttachedTo.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = select(dataSpec.uri)
        if (listenersAttachedTo.add(source)) {
            listeners.forEach { source.addTransferListener(it) }
        }
        active = source
        return source.open(dataSpec)
    }

    private fun select(uri: Uri): DataSource = when {
        uri.isOnDevice() -> device
        mode.get() == SourceMode.OFFLINE -> cacheOnly
        mode.get() == SourceMode.ONLINE -> networkOnly
        else -> cacheAndNetwork
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}

/** Файл с устройства: выбранный через системный проводник или лежащий рядом. */
internal fun Uri.isOnDevice(): Boolean =
    scheme == null || scheme.equals("content", true) || scheme.equals("file", true)

/** То же самое, но по строке — очередь хранит адреса текстом. */
internal fun String.isOnDeviceUrl(): Boolean =
    startsWith("content://", true) || startsWith("file://", true) || startsWith("/")
