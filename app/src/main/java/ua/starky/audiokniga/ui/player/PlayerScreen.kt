package ua.starky.audiokniga.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.data.model.ChapterUi
import ua.starky.audiokniga.data.model.DownloadState
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.BookCover
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.components.NeuProgressRing
import ua.starky.audiokniga.ui.components.NeuSegmentedControl
import ua.starky.audiokniga.ui.components.NeuSlider
import ua.starky.audiokniga.ui.components.NeuTextButton
import ua.starky.audiokniga.ui.components.SectionLabel
import ua.starky.audiokniga.ui.components.SegmentOption
import ua.starky.audiokniga.ui.components.SkipButton
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

/**
 * Плеер и оглавление на одном экране. Переключатель источника — первое, что видно сверху.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = Neu.colors

    LaunchedEffect(state.message) {
        if (state.message != null) {
            kotlinx.coroutines.delay(5000)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeuIconButton(AppIcons.Back, "Назад", onBack, size = 38.dp, iconSize = 15.dp)
            Text(
                text = state.book?.let { ProviderRegistry.displayName(it.providerId) }.orEmpty(),
                color = c.inkFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f),
            )
            NeuTextButton(
                text = formatSpeed(state.playback.speed),
                onClick = viewModel::cycleSpeed,
                size = 38.dp,
                tint = if (state.playback.speed > 1.01f) c.accent else null,
            )
            Spacer(Modifier.size(2.dp))
            NeuIconButton(
                AppIcons.Download,
                "Скачать все главы",
                viewModel::downloadAll,
                size = 38.dp,
                iconSize = 15.dp,
            )
        }

        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.accent)
                }
                return@Column
            }

            state.failure != null -> {
                LoadFailure(reason = state.failure!!, onRetry = viewModel::retry, onBack = onBack)
                return@Column
            }
        }

        // ——— выбор источника ———
        SourceSwitch(mode = state.sourceMode, onSelect = viewModel::setSourceMode)

        Spacer(Modifier.height(18.dp))

        // ——— книга ———
        Row(horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Box(Modifier.size(104.dp).neuRaised(RoundedCornerShape(22.dp), elevation = 6.dp)) {
                BookCover(
                    title = state.book?.title.orEmpty(),
                    coverUrl = state.book?.coverUrl,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)),
                    fontSize = 9,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = state.book?.title.orEmpty(),
                    color = c.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.book?.author.orEmpty(),
                    color = c.inkMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = downloadedLabel(state.downloadedCount, state.chapters.size),
                    color = if (state.downloadedCount > 0) c.offline else c.inkFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        val duration = state.playback.durationMs
        val position = state.playback.positionMs
        NeuSlider(
            progress = if (duration > 0) position.toFloat() / duration else 0f,
            onSeek = viewModel::seekFraction,
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(position), color = c.inkFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (duration > 0) "−${formatTime(duration - position)}" else "--:--",
                color = c.inkFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(seconds = state.skipSeconds, forward = false, onClick = viewModel::skipBack)
            Spacer(Modifier.size(14.dp))
            NeuIconButton(
                icon = if (state.playback.isPlaying) AppIcons.Pause else AppIcons.Play,
                contentDescription = if (state.playback.isPlaying) "Пауза" else "Слушать",
                onClick = viewModel::playPause,
                size = 62.dp,
                iconSize = 24.dp,
                tint = c.accent,
            )
            Spacer(Modifier.size(14.dp))
            SkipButton(seconds = state.skipSeconds, forward = true, onClick = viewModel::skipForward)
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(
            if (state.sourceMode == SourceMode.OFFLINE) {
                "На устройстве · ${state.chapters.size} из ${state.totalChapters}"
            } else {
                "Главы · ${state.chapters.size}"
            }
        )
        Spacer(Modifier.height(11.dp))

        if (state.chapters.isEmpty() && state.sourceMode == SourceMode.OFFLINE) {
            Box(Modifier.weight(1f).navigationBarsPadding(), contentAlignment = Alignment.TopStart) {
                Text(
                    "Здесь пока пусто: ни одна глава не скачана. Переключитесь на «Онлайн», " +
                        "нажмите стрелку загрузки в шапке — и главы появятся в этом списке. " +
                        "После этого в «Офлайн» книга играет полностью без сети.",
                    color = c.inkMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 18.dp),
            ) {
                items(state.chapters, key = { it.chapter.id }) { item ->
                    ChapterRow(
                        item = item,
                        onPlay = { viewModel.playChapter(item.chapter) },
                        onDownload = { viewModel.downloadChapter(item.chapter) },
                        onRemove = { viewModel.removeChapter(item.chapter.id) },
                    )
                }
            }
        }

        state.message?.let { text ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(text, color = c.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Книга не открылась. Пустой экран здесь хуже всего — объясняем и предлагаем повтор. */
@Composable
private fun LoadFailure(reason: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val c = Neu.colors
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Книга не открылась", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(reason, color = c.inkMuted, fontSize = 13.sp, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextAction(text = "Попробовать снова", icon = AppIcons.Refresh, onClick = onRetry, accent = true)
            TextAction(text = "Назад", icon = AppIcons.Back, onClick = onBack, accent = false)
        }
    }
}

@Composable
private fun TextAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    accent: Boolean,
) {
    val c = Neu.colors
    Row(
        Modifier
            .neuRaised(RoundedCornerShape(16.dp), elevation = 5.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(icon, null, tint = if (accent) c.accent else c.inkMuted, modifier = Modifier.size(15.dp))
        Text(text, color = c.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SourceSwitch(mode: SourceMode, onSelect: (SourceMode) -> Unit) {
    val c = Neu.colors
    // Наружу — два понятных положения. AUTO значит «онлайн, но скачанное берём с диска».
    val selected = if (mode == SourceMode.OFFLINE) 1 else 0
    NeuSegmentedControl(
        options = listOf(
            SegmentOption("Онлайн", AppIcons.Online, c.accent),
            SegmentOption("Офлайн", AppIcons.Offline, c.offline),
        ),
        selectedIndex = selected,
        onSelect = { index -> onSelect(if (index == 1) SourceMode.OFFLINE else SourceMode.AUTO) },
    )
}

@Composable
private fun ChapterRow(
    item: ChapterUi,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Neu.colors
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (item.isCurrent) Modifier.neuSunken(shape, depth = 3.dp)
                else Modifier.neuRaised(shape, elevation = 4.dp)
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            text = "%02d".format(item.chapter.index + 1),
            color = if (item.isCurrent) c.accent else c.inkFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.size(width = 20.dp, height = 16.dp),
        )
        Text(
            text = item.chapter.title,
            color = c.ink,
            fontSize = 12.5.sp,
            fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatTime(item.chapter.durationMs),
            color = c.inkFaint,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
        )

        when (item.downloadState) {
            DownloadState.DOWNLOADED -> Icon(
                AppIcons.Check,
                "Скачано, доступно офлайн",
                tint = c.offline,
                modifier = Modifier.size(16.dp).clip(CircleShape).clickable(onClick = onRemove),
            )
            DownloadState.DOWNLOADING, DownloadState.QUEUED -> Box(
                Modifier.size(17.dp).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                NeuProgressRing(
                    progress = item.downloadProgress,
                    modifier = Modifier.fillMaxSize(),
                    color = c.offline,
                    strokeWidth = 2.dp,
                )
            }
            DownloadState.FAILED -> Icon(
                AppIcons.Refresh,
                "Ошибка загрузки, повторить",
                tint = c.inkMuted,
                modifier = Modifier.size(16.dp).clickable(onClick = onDownload),
            )
            DownloadState.NONE -> Icon(
                AppIcons.Download,
                "Скачать главу",
                tint = c.inkFaint,
                modifier = Modifier.size(16.dp).clickable(onClick = onDownload),
            )
        }
    }
}

private fun downloadedLabel(downloaded: Int, total: Int): String = when {
    total == 0 -> "Оглавление загружается"
    downloaded == 0 -> "Ничего не скачано"
    downloaded == total -> "Вся книга на устройстве"
    else -> "$downloaded из $total глав на устройстве"
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
