package ua.starky.audiokniga.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.unit.dp

/**
 * Свой набор иконок вместо material-icons-extended: линии той же толщины, что и в макете,
 * и никакого лишнего мегабайта в APK.
 */
private fun stroked(name: String, width: Float = 2f, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathData(block),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(pathData = PathData(block), fill = SolidColor(Color.Black))
    }.build()

object AppIcons {

    val Play: ImageVector = filled("Play") {
        moveTo(8f, 5.5f); lineTo(19f, 12f); lineTo(8f, 18.5f); close()
    }

    val Pause: ImageVector = filled("Pause") {
        moveTo(7f, 5f); horizontalLineToRelative(3.6f); verticalLineToRelative(14f); horizontalLineToRelative(-3.6f); close()
        moveTo(13.4f, 5f); horizontalLineToRelative(3.6f); verticalLineToRelative(14f); horizontalLineToRelative(-3.6f); close()
    }

    /** Перемотка назад: стрелка против часовой. */
    val Replay: ImageVector = stroked("Replay") {
        moveTo(11f, 5f); lineTo(6f, 9f); lineTo(11f, 13f)
        moveTo(6f, 9f); horizontalLineTo(13f)
        arcToRelative(5f, 5f, 0f, true, true, 0f, 10f)
        horizontalLineTo(8f)
    }

    /** Перемотка вперёд. */
    val Forward: ImageVector = stroked("Forward") {
        moveTo(13f, 5f); lineTo(18f, 9f); lineTo(13f, 13f)
        moveTo(18f, 9f); horizontalLineTo(11f)
        arcToRelative(5f, 5f, 0f, false, false, 0f, 10f)
        horizontalLineTo(16f)
    }

    /** Онлайн: волны Wi-Fi. */
    val Online: ImageVector = stroked("Online") {
        moveTo(2f, 8.8f); arcToRelative(15f, 15f, 0f, false, true, 20f, 0f)
        moveTo(5.5f, 12.4f); arcToRelative(10f, 10f, 0f, false, true, 13f, 0f)
        moveTo(9f, 16f); arcToRelative(5f, 5f, 0f, false, true, 6f, 0f)
        moveTo(12f, 19.4f); arcToRelative(0.6f, 0.6f, 0f, false, true, 0.01f, 0f)
    }

    /** Офлайн: файл лежит на телефоне. */
    val Offline: ImageVector = stroked("Offline") {
        moveTo(8f, 3f); horizontalLineTo(16f)
        arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
        verticalLineTo(18f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
        horizontalLineTo(8f)
        arcToRelative(3f, 3f, 0f, false, true, -3f, -3f)
        verticalLineTo(6f)
        arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
        close()
        moveTo(9.5f, 17.5f); horizontalLineTo(14.5f)
    }

    /** Скачать главу. */
    val Download: ImageVector = stroked("Download") {
        moveTo(12f, 3f); verticalLineTo(14f)
        moveTo(8f, 11f); lineTo(12f, 15f); lineTo(16f, 11f)
        moveTo(5f, 19f); horizontalLineTo(19f)
    }

    /** Скачано. */
    val Check: ImageVector = stroked("Check", width = 2.2f) {
        moveTo(20f, 6f); lineTo(9f, 17f); lineTo(4f, 12f)
    }

    val Close: ImageVector = stroked("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }

    val Back: ImageVector = stroked("Back") {
        moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
    }

    val Search: ImageVector = stroked("Search") {
        moveTo(11f, 4f); arcToRelative(7f, 7f, 0f, true, true, -0.01f, 0f); close()
        moveTo(16.2f, 16.2f); lineTo(21f, 21f)
    }

    val Library: ImageVector = stroked("Library") {
        moveTo(4f, 4f); horizontalLineTo(8f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(10f, 4f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(10f); close()
        moveTo(16.5f, 5f); lineTo(20.5f, 6f); lineTo(17.5f, 20f); lineTo(13.5f, 19f); close()
    }

    val Sleep: ImageVector = stroked("Sleep") {
        moveTo(12f, 5f); arcToRelative(8f, 8f, 0f, true, true, -0.01f, 0f); close()
        moveTo(12f, 9f); verticalLineTo(13f); lineTo(14.5f, 15f)
    }

    val Trash: ImageVector = stroked("Trash") {
        moveTo(4f, 7f); horizontalLineTo(20f)
        moveTo(9f, 7f); verticalLineTo(5f); horizontalLineTo(15f); verticalLineTo(7f)
        moveTo(6f, 7f); verticalLineTo(19f); arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
        horizontalLineTo(16f); arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); verticalLineTo(7f)
    }

    /** Настройки: шестерёнка. */
    val Settings: ImageVector = stroked("Settings") {
        moveTo(12f, 9f); arcToRelative(3f, 3f, 0f, true, true, -0.01f, 0f); close()
        moveTo(12f, 2.5f); verticalLineToRelative(2.4f)
        moveTo(12f, 19.1f); verticalLineToRelative(2.4f)
        moveTo(21.5f, 12f); horizontalLineToRelative(-2.4f)
        moveTo(4.9f, 12f); horizontalLineToRelative(-2.4f)
        moveTo(18.4f, 5.6f); lineToRelative(-1.7f, 1.7f)
        moveTo(7.3f, 16.7f); lineToRelative(-1.7f, 1.7f)
        moveTo(18.4f, 18.4f); lineToRelative(-1.7f, -1.7f)
        moveTo(7.3f, 7.3f); lineToRelative(-1.7f, -1.7f)
    }

    val Refresh: ImageVector = stroked("Refresh") {
        moveTo(20f, 12f); arcToRelative(8f, 8f, 0f, true, true, -2.4f, -5.7f)
        moveTo(20f, 4f); verticalLineTo(9f); horizontalLineTo(15f)
    }
}
