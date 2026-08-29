package ua.starky.audiokniga.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import ua.starky.audiokniga.MainActivity
import ua.starky.audiokniga.R
import ua.starky.audiokniga.app
import ua.starky.audiokniga.playback.PlaybackService
import ua.starky.audiokniga.playback.SkipSettings

/**
 * Виджет на рабочем столе: что играет и три кнопки.
 *
 * Команды идут не через startService, а через подключение MediaController к сессии.
 * Запуск сервиса из фона на новых Android запрещён, а привязка к MediaSessionService
 * разрешена — и заодно поднимает сервис, если тот успел умереть.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val command = intent.action?.takeIf { it in COMMANDS } ?: return

        // Подключение асинхронное, поэтому просим у системы отсрочку.
        val pending = goAsync()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        future.addListener({
            runCatching {
                val controller = future.get()
                apply(controller, command)
                controller.release()
            }
            refresh(context)
            pending.finish()
        }, MoreExecutors.directExecutor())
    }

    private fun apply(controller: MediaController, command: String) {
        val step = SkipSettings.skipMs
        when (command) {
            ACTION_PLAY_PAUSE ->
                if (controller.isPlaying) controller.pause() else controller.play()

            ACTION_REWIND ->
                controller.seekTo((controller.currentPosition - step).coerceAtLeast(0L))

            ACTION_FORWARD ->
                controller.seekTo(controller.currentPosition + step)
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "ua.starky.audiokniga.widget.PLAY_PAUSE"
        const val ACTION_REWIND = "ua.starky.audiokniga.widget.REWIND"
        const val ACTION_FORWARD = "ua.starky.audiokniga.widget.FORWARD"

        private val COMMANDS = setOf(ACTION_PLAY_PAUSE, ACTION_REWIND, ACTION_FORWARD)

        /** Перерисовать все размещённые виджеты. Зовётся при смене состояния плеера. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)

            val state = runCatching { context.app.playback.state.value }.getOrNull()
            val seconds = SkipSettings.seconds
            views.setTextViewText(
                R.id.widget_title,
                state?.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.app_name),
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                if (state?.isPlaying == true) "Играет · шаг $seconds с" else "Пауза · шаг $seconds с",
            )
            views.setImageViewResource(
                R.id.widget_play,
                if (state?.isPlaying == true) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            )

            views.setOnClickPendingIntent(R.id.widget_rewind, command(context, ACTION_REWIND))
            views.setOnClickPendingIntent(R.id.widget_play, command(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_forward, command(context, ACTION_FORWARD))
            // Нажатие на подпись открывает приложение — самый ожидаемый жест.
            views.setOnClickPendingIntent(R.id.widget_title, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_subtitle, openApp(context))

            return views
        }

        private fun command(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags())
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, 0, intent, flags())
        }

        private fun flags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
    }
}
