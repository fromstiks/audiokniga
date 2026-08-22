package ua.starky.audiokniga.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
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

/**
 * Кисть с размытием. Создаётся один раз на размер элемента, а не на каждый кадр:
 * BlurMaskFilter и Paint — дорогие объекты, и раньше их пересоздавало каждое
 * перерисовывание каждой кнопки, каждой карточки и каждой строки списка.
 */
private fun blurPaint(color: Color, blurPx: Float, strokeWidth: Float? = null): Paint {
    val paint = Paint()
    val frame = paint.asFrameworkPaint()
    frame.color = color.toArgb()
    frame.isAntiAlias = true
    if (blurPx > 0f) frame.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
    if (strokeWidth != null) {
        paint.style = PaintingStyle.Stroke
        frame.strokeWidth = strokeWidth
    }
    return paint
}

private fun Canvas.drawShifted(path: Path, paint: Paint, dx: Float, dy: Float) {
    save()
    translate(dx, dy)
    drawPath(path, paint)
    restore()
}

/** Готовые к отрисовке тени: путь и две кисти считаются один раз на размер. */
private class NeuShadow(
    val path: Path,
    val shadow: Paint,
    val highlight: Paint,
    val offset: Float,
)

private fun CacheDrawScope.neuShadow(
    shape: Shape,
    colors: NeuColors,
    offsetPx: Float,
    blurScale: Float,
    strokeScale: Float? = null,
): NeuShadow {
    val blur = offsetPx * blurScale
    val stroke = strokeScale?.let { offsetPx * it }
    return NeuShadow(
        path = shape.asPath(size, this, layoutDirection),
        shadow = blurPaint(colors.shadow, blur, stroke),
        highlight = blurPaint(colors.highlight, blur, stroke),
        offset = offsetPx,
    )
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
            .drawWithCache {
                val s = neuShadow(shape, c, elevation.toPx(), blurScale = 1.9f)
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.drawShifted(s.path, s.shadow, s.offset, s.offset)
                        canvas.drawShifted(s.path, s.highlight, -s.offset, -s.offset)
                    }
                }
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
        .drawWithCache {
            val s = neuShadow(shape, c, depth.toPx(), blurScale = 1.6f, strokeScale = 2.2f)
            onDrawBehind {
                drawRect(bg)
                drawIntoCanvas { canvas ->
                    canvas.drawShifted(s.path, s.shadow, s.offset, s.offset)
                    canvas.drawShifted(s.path, s.highlight, -s.offset, -s.offset)
                }
            }
        }
}

/** То же, но тени рисуются ПОВЕРХ содержимого — для обложек и полос прогресса. */
fun Modifier.neuSunkenOverlay(
    shape: Shape = RoundedCornerShape(20.dp),
    depth: Dp = 4.dp,
): Modifier = composed {
    val c = Neu.colors
    val faded = c.copy(
        shadow = c.shadow.copy(alpha = 0.55f),
        highlight = c.highlight.copy(alpha = 0.35f),
    )
    this
        .clip(shape)
        .drawWithCache {
            val s = neuShadow(shape, faded, depth.toPx(), blurScale = 1.6f, strokeScale = 2.2f)
            onDrawWithContent {
                drawContent()
                drawIntoCanvas { canvas ->
                    canvas.drawShifted(s.path, s.shadow, s.offset, s.offset)
                    canvas.drawShifted(s.path, s.highlight, -s.offset, -s.offset)
                }
            }
        }
}

@Composable
fun neuElevationFor(pressed: Boolean, base: Dp = 6.dp): Dp = if (pressed) base * 0.4f else base
