package ua.starky.audiokniga.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.playback.PlaybackState
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

/**
 * Обложка книги. Пока картинка не загрузилась или её нет вовсе, под ней
 * остаётся заглушка с названием — пустых серых прямоугольников на экране не бывает.
 */
@Composable
fun BookCover(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    fontSize: Int = 9,
) {
    Box(modifier) {
        CoverPlaceholder(title = title, modifier = Modifier.fillMaxSize(), fontSize = fontSize)
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Свёрнутый плеер внизу полки: что играет, полоса прогресса и три кнопки.
 * Нажатие на карточку разворачивает полный экран книги.
 */
@Composable
fun MiniPlayer(
    book: Book,
    playback: PlaybackState,
    skipSeconds: Int,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neu.colors
    val shape = RoundedCornerShape(22.dp)
    val progress = if (playback.durationMs > 0) {
        (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    } else 0f

    // Нижняя часть с плеером укрупнена вдвое — по ней чаще всего попадают на ходу,
    // не глядя внимательно, поэтому именно тут точность нажатия важнее всего.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .neuRaised(shape, elevation = 8.dp)
            .clickable(onClick = onExpand)
            .padding(horizontal = 28.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            BookCover(
                title = book.title,
                coverUrl = book.coverUrl,
                modifier = Modifier.size(92.dp).clip(RoundedCornerShape(26.dp)),
                fontSize = 12,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = playback.chapterTitle?.takeIf { it.isNotBlank() } ?: book.title,
                    color = c.ink,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.title.takeIf { playback.chapterTitle != null } ?: book.author,
                    color = c.inkFaint,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (playback.durationMs > 0) {
                    formatClock(playback.positionMs) + " / " + formatClock(playback.durationMs)
                } else "--:--",
                color = c.inkFaint,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Тонкий жёлоб вместо полноценного слайдера: здесь он только показывает,
        // а перематывают кнопками или на полном экране.
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .neuSunken(RoundedCornerShape(percent = 50), depth = 3.dp)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(c.accent)
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(seconds = skipSeconds, forward = false, onClick = onSkipBack, size = 84.dp)
            NeuIconButton(
                icon = if (playback.isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (playback.isPlaying) "Пауза" else "Слушать",
                onClick = onPlayPause,
                size = 96.dp,
                iconSize = 38.dp,
                tint = c.accent,
            )
            SkipButton(seconds = skipSeconds, forward = true, onClick = onSkipForward, size = 84.dp)
        }
    }
}

/** Кнопка перемотки с подписанным шагом — чтобы «20» не приходилось угадывать. */
@Composable
fun SkipButton(
    seconds: Int,
    forward: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 42.dp,
) {
    val c = Neu.colors
    Box(
        modifier = Modifier
            .size(size)
            .neuRaised(CircleShape, elevation = 4.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                imageVector = if (forward) AppIcons.Forward else AppIcons.Replay,
                contentDescription = if (forward) "Вперёд на $seconds секунд" else "Назад на $seconds секунд",
                tint = c.inkMuted,
                modifier = Modifier.size(size * 0.36f),
            )
            Text(
                text = seconds.toString(),
                color = c.inkFaint,
                // Подпись растёт вместе с самой кнопкой — иначе на крупной кнопке
                // остаётся мелкая цифра, будто она с другой кнопки поменьше.
                fontSize = (size.value * 0.19f).sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

internal fun formatClock(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
