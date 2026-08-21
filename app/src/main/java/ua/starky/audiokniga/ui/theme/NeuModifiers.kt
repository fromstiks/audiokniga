package ua.starky.audiokniga.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private fun Shape.asPath(size: Size, density: Density, direction: LayoutDirection): Path {
    val path = Path()
    when (val outline = createOutline(size, direction, density)) {
        is Outline.Rectangle -> path.addRect(outline.rect)
        is Outline.Rounded -> path.addRoundRect(outline.roundRect)
        is Outline.Generic -> path.addPath(outline.path)
    }
    return path
}

private fun DrawScope.blurredPath(
    path: Path,
    color: Color,
    blurPx: Float,
    dx: Float,
    dy: Float,
    stroke: Float? = null,
) {
    if (color.alpha == 0f) return
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frame = paint.asFrameworkPaint()
        frame.color = color.toArgb()
        frame.isAntiAlias = true
        if (blurPx > 0f) frame.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        if (stroke != null) {
            paint.style = PaintingStyle.Stroke
            frame.strokeWidth = stroke
        }
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawPath(path, paint)
        canvas.restore()
        frame.maskFilter = null
    }
}

/**
 * Выпуклая («выдавленная») поверхность: тёмная тень снизу-справа, светлая сверху-слева.
 */
fun Modifier.neuRaised(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 7.dp,
    pressed: Boolean = false,
    background: Color? = null,
): Modifier = composed {
    val c = Neu.colors
    val bg = background ?: c.background
    if (pressed) {
        neuSunken(shape = shape, depth = elevation * 0.7f, background = bg)
    } else {
        this
            .drawBehind {
                val path = shape.asPath(size, this, layoutDirection)
                val offset = elevation.toPx()
                val blur = offset * 1.9f
                blurredPath(path, c.shadow, blur, offset, offset)
                blurredPath(path, c.highlight, blur, -offset, -offset)
            }
            .clip(shape)
            .drawBehind { drawRect(bg) }
    }
}

/**
 * Вогнутая («утопленная») поверхность: внутренние тени по краям.
 * Внутреннюю тень Compose не умеет из коробки — рисуем обводку фигуры со смещением
 * и обрезаем её той же фигурой.
 */
fun Modifier.neuSunken(
    shape: Shape = RoundedCornerShape(20.dp),
    depth: Dp = 5.dp,
    background: Color? = null,
): Modifier = composed {
    val c = Neu.colors
    val bg = background ?: c.background
    this
        .clip(shape)
        .drawBehind {
            drawRect(bg)
            val path = shape.asPath(size, this, layoutDirection)
            val d = depth.toPx()
            val blur = d * 1.6f
            val stroke = d * 2.2f
            blurredPath(path, c.shadow, blur, d, d, stroke)
            blurredPath(path, c.highlight, blur, -d, -d, stroke)
        }
}

/** То же, но тени рисуются ПОВЕРХ содержимого — для обложек и полос прогресса. */
fun Modifier.neuSunkenOverlay(
    shape: Shape = RoundedCornerShape(20.dp),
    depth: Dp = 4.dp,
): Modifier = composed {
    val c = Neu.colors
    this
        .clip(shape)
        .drawWithContent {
            drawContent()
            val path = shape.asPath(size, this, layoutDirection)
            val d = depth.toPx()
            blurredPath(path, c.shadow.copy(alpha = 0.55f), d * 1.6f, d, d, d * 2.2f)
            blurredPath(path, c.highlight.copy(alpha = 0.35f), d * 1.6f, -d, -d, d * 2.2f)
        }
}

@Composable
fun neuElevationFor(pressed: Boolean, base: Dp = 6.dp): Dp = if (pressed) base * 0.4f else base
