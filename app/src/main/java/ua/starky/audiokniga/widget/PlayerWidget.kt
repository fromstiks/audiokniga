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
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import ua.starky.audiokniga.MainActivity
import ua.starky.audiokniga.R
import ua.starky.audiokniga.app
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.playback.PlaybackService
import ua.starky.audiokniga.playback.SkipSettings

/**
 * Виджет на рабочем столе: что играет и три кнопки.
 *
 * Команды идут не через startService, а подключением MediaController к сессии: запуск
 * сервиса из фона на новых Android запрещён, а привязка к MediaSessionService разрешена
 * и заодно поднимает сервис, если тот выгрузился.
 *
 * Приёмник обязан быть exported: APPWIDGET_UPDATE присылает система, и без этого
 * onUpdate не вызывается вовсе — виджет остаётся с пустой разметкой и мёртвыми кнопками.
 */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = buildViews(context, lastBook)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val command = intent.action?.takeIf { it in COMMANDS } ?: return

        // Подключение асинхронное, поэтому просим у системы отсрочку.
        val pending = goAsync()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val applicationContext = context.applicationContext

        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) {
                pending.finish()
                return@addListener
            }
            runCatching { apply(controller, command) }

            // Команда уходит по IPC. Освободить контроллер сразу — значит оборвать её
            // на полпути, поэтому отпускаем его следующим шагом, а не в этот же миг.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { controller.release() }
                refresh(applicationContext, lastBook)
                pending.finish()
            }, RELEASE_DELAY_MS)
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

        /** Столько ждём доставки команды до сессии, прежде чем отпустить контроллер. */
        private const val RELEASE_DELAY_MS = 700L

        /**
         * Книга, показанная последней. Приёмник команд работает без доступа к базе,
         * а перерисовать виджет после нажатия надо — иначе кнопка play не сменит вид.
         */
        @Volatile
        private var lastBook: Book? = null

        fun refresh(context: Context, book: Book?) {
            lastBook = book
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = buildViews(context, book)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context, book: Book?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val state = runCatching { context.app.playback.state.value }.getOrNull()
            val playing = state?.isPlaying == true

            views.setTextViewText(
                R.id.widget_title,
                book?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.app_name),
            )
            views.setTextViewText(
                R.id.widget_subtitle,
                state?.chapterTitle?.takeIf { it.isNotBlank() }
                    ?: book?.author
                    ?: "Нечего слушать",
            )
            views.setImageViewResource(
                R.id.widget_play,
                if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            )
            views.setImageViewBitmap(R.id.widget_cover, coverTile(context, book?.title))

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

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, R.color.widget_accent)
                textSize = size * 0.36f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
            canvas.drawText(letters, size / 2f, baseline, text)

            return bitmap
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
