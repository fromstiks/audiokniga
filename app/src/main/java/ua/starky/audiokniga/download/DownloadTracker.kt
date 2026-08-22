package ua.starky.audiokniga.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.DownloadState

data class DownloadInfo(
    val state: DownloadState,
    val progress: Float,
    val bytesDownloaded: Long,
)

/**
 * Тонкая обёртка над Media3 DownloadManager: отдаёт состояние загрузок потоком,
 * чтобы список глав обновлялся сам.
 */
@OptIn(UnstableApi::class)
class DownloadTracker(private val context: Context) {

    private val manager: DownloadManager get() = DownloadModule.downloadManager(context)

    fun snapshot(): Map<String, DownloadInfo> {
        val result = mutableMapOf<String, DownloadInfo>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                result[download.request.id] = download.toInfo()
            }
        }
        return result
    }

    suspend fun snapshotAsync(): Map<String, DownloadInfo> = withContext(Dispatchers.IO) { snapshot() }

    /**
     * Пока что-то качается, состояние обновляется раз в секунду.
     *
     * Слушателя DownloadManager обязан регистрировать поток, на котором менеджер создан
     * (главный), а вот чтение индекса — это работа с диском, и её мы уводим в фон:
     * раньше она шла в главном потоке и подтормаживала список глав.
     */
    fun observe(): Flow<Map<String, DownloadInfo>> = changes()
        .buffer(1, BufferOverflow.DROP_OLDEST)
        .map { snapshotAsync() }

    private fun changes(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(Unit)
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                trySend(Unit)
            }
        }
        manager.addListener(listener)

        val ticker = launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (manager.currentDownloads.isNotEmpty()) trySend(Unit)
            }
        }

        awaitClose {
            manager.removeListener(listener)
            ticker.cancel()
        }
    }

    /**
     * Ставит главу в очередь загрузки.
     *
     * @return null, если всё в порядке, иначе — причина, которую можно показать.
     * Раньше сбой запуска сервиса терялся, и нажатие на «скачать» просто ничего не делало.
     */
    fun download(chapter: Chapter): String? = runCatching {
        DownloadService.sendAddDownload(
            context,
            AudioDownloadService::class.java,
            DownloadModule.buildRequest(chapter.id, chapter.audioUrl),
            // Загрузка должна пережить сворачивание приложения, а с Android 8
            // это возможно только для сервиса переднего плана.
            /* foreground = */ true,
        )
    }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }

    /** Ставит в очередь все главы и возвращает первую ошибку, не бросая остальные. */
    fun downloadAll(chapters: List<Chapter>): String? {
        var problem: String? = null
        for (chapter in chapters) {
            val error = download(chapter)
            if (error != null && problem == null) problem = error
        }
        return problem
    }

    fun remove(chapterId: String): String? = runCatching {
        DownloadService.sendRemoveDownload(
            context,
            AudioDownloadService::class.java,
            chapterId,
            /* foreground = */ false,
        )
    }.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName }

    fun removeAll(chapterIds: List<String>) = chapterIds.forEach { remove(it) }

    private fun Download.toInfo(): DownloadInfo {
        val state = when (this.state) {
            Download.STATE_COMPLETED -> DownloadState.DOWNLOADED
            Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING
            Download.STATE_QUEUED, Download.STATE_RESTARTING -> DownloadState.QUEUED
            Download.STATE_FAILED -> DownloadState.FAILED
            else -> DownloadState.NONE
        }
        val percent = percentDownloaded
        return DownloadInfo(
            state = state,
            progress = if (percent.isNaN()) 0f else (percent / 100f).coerceIn(0f, 1f),
            bytesDownloaded = bytesDownloaded,
        )
    }
}
