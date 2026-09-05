package ua.starky.audiohunter.download

import android.app.Notification
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.NotificationUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import ua.starky.audiohunter.R

@OptIn(UnstableApi::class)
class AudioDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    0,
) {
    override fun getDownloadManager(): DownloadManager = DownloadModule.downloadManager(this)

    override fun getScheduler(): Scheduler? =
        if (android.os.Build.VERSION.SDK_INT >= 21) PlatformScheduler(this, JOB_ID) else null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val helper = notificationHelper(this)
        return helper.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            null,
            null,
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 4201
        private const val JOB_ID = 4202

        @Volatile private var helper: DownloadNotificationHelper? = null

        fun notificationHelper(context: Context): DownloadNotificationHelper =
            helper ?: synchronized(this) {
                NotificationUtil.createNotificationChannel(
                    context,
                    CHANNEL_ID,
                    R.string.download_channel_name,
                    0,
                    NotificationUtil.IMPORTANCE_LOW,
                )
                helper ?: DownloadNotificationHelper(context, CHANNEL_ID).also { helper = it }
            }
    }
}
