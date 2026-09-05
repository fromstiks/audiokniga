package ua.starky.audiohunter.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import ua.starky.audiohunter.data.model.Chapter
import ua.starky.audiohunter.data.model.DownloadState

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

    /** Пока что-то качается, состояние обновляется раз в секунду. */
    fun observe(): Flow<Map<String, DownloadInfo>> = callbackFlow {
        trySend(snapshot())
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                trySend(snapshot())
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                trySend(snapshot())
            }
        }
        manager.addListener(listener)

        val ticker = launch {
            while (true) {
                kotlinx.coroutines.delay(1000)
                if (manager.currentDownloads.isNotEmpty()) trySend(snapshot())
            }
        }

        awaitClose {
            manager.removeListener(listener)
            ticker.cancel()
        }
    }

    fun download(chapter: Chapter) {
        DownloadService.sendAddDownload(
            context,
            AudioDownloadService::class.java,
            DownloadModule.buildRequest(chapter.id, chapter.audioUrl),
            /* foreground = */ false,
        )
    }

    fun downloadAll(chapters: List<Chapter>) = chapters.forEach(::download)

    fun remove(chapterId: String) {
        DownloadService.sendRemoveDownload(
            context,
            AudioDownloadService::class.java,
            chapterId,
            /* foreground = */ false,
        )
    }

    fun removeAll(chapterIds: List<String>) = chapterIds.forEach(::remove)

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
