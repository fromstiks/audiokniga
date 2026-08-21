package ua.starky.audiokniga.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

@Composable
fun NeuIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    iconSize: Dp = 19.dp,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = Neu.colors
    Box(
        modifier = modifier
            .size(size)
            .neuRaised(CircleShape, elevation = size.value.dp * 0.09f, pressed = pressed)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> c.inkFaint
                tint != null -> tint
                else -> c.inkMuted
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun NeuTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val c = Neu.colors
    Box(
        modifier = modifier
            .size(size)
            .neuRaised(CircleShape, elevation = 4.dp, pressed = pressed)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = tint ?: c.inkMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

data class SegmentOption(
    val label: String,
    val icon: ImageVector,
    val activeColor: Color? = null,
)

/**
 * Сегментированный переключатель в утопленном жёлобе — тот самый выбор источника.
 */
@Composable
fun NeuSegmentedControl(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neu.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neuSunken(RoundedCornerShape(percent = 50), depth = 3.dp)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selectedIndex
            val target = if (active) (option.activeColor ?: c.accent) else c.inkFaint
            val tint by animateColorAsState(target, label = "segment-tint")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .then(
                        if (active) Modifier.neuRaised(RoundedCornerShape(percent = 50), elevation = 4.dp)
                        else Modifier.clip(RoundedCornerShape(percent = 50))
                    )
                    .clickable(enabled = !active) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(option.icon, null, tint = tint, modifier = Modifier.size(15.dp))
                    Text(
                        option.label,
                        color = tint,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Полоса перемотки: утопленный жёлоб, залитая часть и выпуклая «шляпка».
 */
@Composable
fun NeuSlider(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackHeight: Dp = 6.dp,
    knobSize: Dp = 17.dp,
    activeColor: Color? = null,
) {
    val c = Neu.colors
    val fillColor = activeColor ?: c.accent
    var widthPx by remember { mutableIntStateOf(1) }
    val safe = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(safe, label = "slider")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(knobSize)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onSeek((offset.x / widthPx).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ ->
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .neuSunken(RoundedCornerShape(percent = 50), depth = 2.5.dp)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animated)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(fillColor)
            )
        }
        Box(
            Modifier
                .padding(start = 0.dp)
                .offsetForProgress(animated, widthPx, knobSize)
                .size(knobSize)
                .neuRaised(CircleShape, elevation = 3.dp)
        )
    }
}

private fun Modifier.offsetForProgress(progress: Float, widthPx: Int, knob: Dp): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val knobPx = placeable.width
            val x = ((widthPx - knobPx) * progress).toInt().coerceIn(0, (widthPx - knobPx).coerceAtLeast(0))
            layout(placeable.width, placeable.height) { placeable.placeRelative(x, 0) }
        }
    )

/** Кольцевой индикатор — используется для прогресса скачивания. */
@Composable
fun NeuProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    strokeWidth: Dp = 3.dp,
) {
    val c = Neu.colors
    val ringColor = color ?: c.offline
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "ring")
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = c.line,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = ringColor,
            startAngle = -90f,
            sweepAngle = 360f * animated,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val c = Neu.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text.uppercase(),
            color = c.inkFaint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(c.line)
        )
    }
}

@Composable
fun ProvideMutedContentColor(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides Neu.colors.inkMuted, content = content)
}

@Composable
fun CoverPlaceholder(title: String, modifier: Modifier = Modifier, fontSize: Int = 11) {
    val c = Neu.colors
    Box(
        modifier = modifier.background(
            androidx.compose.ui.graphics.Brush.linearGradient(
                listOf(Color(0xFF3C4A55), Color(0xFF1D2529), Color(0xFF2E4340))
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            color = Color(0xFFEFE6D4),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            modifier = Modifier.padding(10.dp),
        )
        if (c.isDark) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)))
    }
}
