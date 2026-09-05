package ua.starky.audiokniga.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmapOrNull
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ua.starky.audiokniga.MainActivity
import ua.starky.audiokniga.R
import ua.starky.audiokniga.app
import ua.starky.audiokniga.playback.WidgetAction

/** То немногое, что виджету нужно показать. Собирается в приложении, хранится здесь. */
data class WidgetSnapshot(
    val title: String? = null,
    val subtitle: String? = null,
    val playing: Boolean = false,
    val coverUrl: String? = null,
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
        pushViews(context, snapshot, coverFor(snapshot.coverUrl))
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
        const val ACTION_PREVIOUS_CHAPTER = "ua.starky.audiokniga.widget.PREVIOUS_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "ua.starky.audiokniga.widget.NEXT_CHAPTER"

        private const val COVER_SIZE = 132

        private val COMMANDS = mapOf(
            ACTION_PLAY_PAUSE to WidgetAction.PLAY_PAUSE,
            ACTION_REWIND to WidgetAction.REWIND,
            ACTION_FORWARD to WidgetAction.FORWARD,
            ACTION_PREVIOUS_CHAPTER to WidgetAction.PREVIOUS_CHAPTER,
            ACTION_NEXT_CHAPTER to WidgetAction.NEXT_CHAPTER,
        )

        /**
         * Показанное последним. Система может попросить перерисовать виджет в любой
         * момент — в том числе когда читать состояние плеера ещё неоткуда.
         */
        @Volatile
        private var snapshot = WidgetSnapshot()

        /** Последняя загруженная обложка вместе с адресом, по которому её взяли. */
        @Volatile
        private var cover: Pair<String, Bitmap>? = null

        /** Живёт вместе с процессом приложения — сама загрузка ничего не блокирует. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun coverFor(url: String?): Bitmap? = cover?.takeIf { it.first == url }?.second

        fun refresh(context: Context, state: WidgetSnapshot) {
            snapshot = state
            // Рисуем сразу — плиткой с инициалами или уже загруженной для этого адреса
            // обложкой, чтобы виджет не мигал пустотой, пока настоящая картинка грузится.
            pushViews(context, state, coverFor(state.coverUrl))

            val url = state.coverUrl
            if (url == null) {
                cover = null
                return
            }
            if (cover?.first == url) return

            val appContext = context.applicationContext
            scope.launch {
                val bitmap = runCatching { loadCover(appContext, url) }.getOrNull() ?: return@launch
                cover = url to bitmap
                // Пока грузили, снимок мог смениться на другую книгу — рисовать
                // устаревшую обложку поверх уже нового состояния не нужно.
                if (snapshot.coverUrl == url) pushViews(appContext, snapshot, bitmap)
            }
        }

        private fun pushViews(context: Context, state: WidgetSnapshot, cover: Bitmap?) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            val views = buildViews(context, state, cover)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context, state: WidgetSnapshot, cover: Bitmap?): RemoteViews {
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
            views.setImageViewBitmap(R.id.widget_cover, cover ?: coverTile(context, state.title))

            views.setOnClickPendingIntent(R.id.widget_previous_chapter, command(context, ACTION_PREVIOUS_CHAPTER))
            views.setOnClickPendingIntent(R.id.widget_rewind, command(context, ACTION_REWIND))
            views.setOnClickPendingIntent(R.id.widget_play, command(context, ACTION_PLAY_PAUSE))
            views.setOnClickPendingIntent(R.id.widget_forward, command(context, ACTION_FORWARD))
            views.setOnClickPendingIntent(R.id.widget_next_chapter, command(context, ACTION_NEXT_CHAPTER))
            // Нажатие на обложку и подписи открывает приложение — ожидаемый жест.
            views.setOnClickPendingIntent(R.id.widget_cover, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_title, openApp(context))
            views.setOnClickPendingIntent(R.id.widget_subtitle, openApp(context))

            return views
        }

        /**
         * Настоящая обложка — той же библиотекой (Coil), что и остальное приложение,
         * поэтому источник ей не важен: файл с устройства (content://), локальный путь
         * или адрес в сети. allowHardware(false) обязателен — аппаратный битмап нельзя
         * ни прочитать пиксель за пикселем, ни вписать в RemoteViews.
         */
        private suspend fun loadCover(context: Context, url: String): Bitmap? {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
            val source = result.drawable.toBitmapOrNull() ?: return null
            return roundedCrop(source)
        }

        /** Та же скруглённая плитка, что и у плитки с инициалами, только с фотографией. */
        private fun roundedCrop(source: Bitmap): Bitmap {
            val size = COVER_SIZE
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val radius = size * 0.24f

            // centerCrop вручную: BitmapShader не масштабирует источник сам.
            val scale = maxOf(size / source.width.toFloat(), size / source.height.toFloat())
            val dx = (size - source.width * scale) / 2f
            val dy = (size - source.height * scale) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(matrix)
                }
            }
            canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
            return output
        }

        /**
         * Плитка с инициалами — пока настоящей обложки нет вовсе или она ещё грузится.
         * Рисуется на месте, без сети: то же самое, что приложение показывает вместо
         * отсутствующей обложки на других экранах.
         */
        private fun coverTile(context: Context, title: String?): Bitmap {
            val size = COVER_SIZE
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
