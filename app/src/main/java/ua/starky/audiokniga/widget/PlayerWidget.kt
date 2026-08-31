package ua.starky.audiokniga.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import ua.starky.audiokniga.MainActivity
import ua.starky.audiokniga.R
import ua.starky.audiokniga.app
import ua.starky.audiokniga.playback.WidgetAction

/** То немногое, что виджету нужно показать. Собирается в приложении, хранится здесь. */
data class WidgetSnapshot(
    val title: String? = null,
    val subtitle: String? = null,
    val playing: Boolean = false,
)

/**
 * Виджет на рабочем столе: что играет и три кнопки.
 *
 * Команды идут через [ua.starky.audiokniga.playback.PlaybackController] — то же самое,
 * что делают кнопки в приложении. Раньше виджет подключался к сессии сам, и это ломало
 * play: приложение могло быть закрыто, привязка поднимала пустой плеер, и play() было
 * нечего играть. Контроллер же сперва вернёт в очередь последнюю книгу.
 *
 * Приёмник обязан быть exported: APPWIDGET_UPDATE присылает система, и без этого
 * onUpdate не вызывается вовсе — виджет остаётся с пустой разметкой и мёртвыми кнопками.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context, snapshot)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = COMMANDS[intent.action] ?: return
        val playback = runCatching { context.app.playback }.getOrNull() ?: return

        // Команда выполняется не мгновенно, а после ухода из onReceive процесс могут
        // выгрузить. Отсрочка держит его до конца работы.
        val pending = goAsync()
        playback.widgetCommand(action) { pending.finish() }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "ua.starky.audiokniga.widget.PLAY_PAUSE"
        const val ACTION_REWIND = "ua.starky.audiokniga.widget.REWIND"
        const val ACTION_FORWARD = "ua.starky.audiokniga.widget.FORWARD"

        private val COMMANDS = mapOf(
            ACTION_PLAY_PAUSE to WidgetAction.PLAY_PAUSE,
            ACTION_REWIND to WidgetAction.REWIND,
            ACTION_FORWARD to WidgetAction.FORWARD,
        )

        /**
         * Показанное последним. Система может попросить перерисовать виджет в любой
         * момент — в том числе когда читать состояние плеера ещё неоткуда.
         */
        @Volatile
        private var snapshot = WidgetSnapshot()

        fun refresh(context: Context, state: WidgetSnapshot) {
            snapshot = state
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = buildViews(context, state)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context, state: WidgetSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)

            views.setTextViewText(
                R.id.widget_title,
                state.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name),
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                state.subtitle?.takeIf { it.isNotBlank() } ?: "Нечего слушать",
            )
            views.setImageViewResource(
                R.id.widget_play,
                if (state.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            )
            views.setImageViewBitmap(R.id.widget_cover, coverTile(context, state.title))

            views.setOnClickPendingIntent(R.id.widget_rewind, command(context, ACTION_REWIND))
            views.setOnClickPendingIntent(R.id.widget_play, command(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_forward, command(context, ACTION_FORWARD))
            // Нажатие на обложку и подписи открывает приложение — ожидаемый жест.
            views.setOnClickPendingIntent(R.id.widget_cover, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_title, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_subtitle, openApp(context))

            return views
        }

        /**
         * Обложка рисуется на месте, а не грузится из сети: виджет обновляется в чужом
         * процессе и ждать загрузку там нечем. Плитка с началом названия — то же, что
         * приложение показывает вместо отсутствующей обложки.
         */
        private fun coverTile(context: Context, title: String?): Bitmap {
            val size = 132
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val radius = size * 0.24f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, size.toFloat(), size.toFloat(),
                    ContextCompat.getColor(context, R.color.widget_cover_top),
                    ContextCompat.getColor(context, R.color.widget_cover_bottom),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)

            val letters = title.orEmpty()
                .split(' ', '.', '_', '-')
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifBlank { "А" }

            // Плитка тёмная в обеих темах, поэтому текст на ней всегда светлый — цветом
            // акцента здесь его красить нельзя: на тёмно-синей плитке синие буквы не
            // видны вовсе, то же ждало бы и новый красный акцент.
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.widget_cover_ink)
                textSize = size * 0.36f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(letters, size / 2f, baseline, text)

            return bitmap
        }

        private fun command(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PlayerWidget::class.java)
                .setAction(action)
                .setPackage(context.packageName)
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
