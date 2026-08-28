package ua.starky.audiokniga.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.ui.SectionHeader
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.SectionLabel
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val speed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val skipSeconds by viewModel.skipSeconds.collectAsStateWithLifecycle()
    val libraryFolder by viewModel.libraryFolder.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val c = Neu.colors

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setLibraryFolder)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        SectionHeader(title = "Настройки", subtitle = null, onOpenDrawer = onOpenDrawer)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("Оформление")
            ChoiceRow(
                title = "Тема",
                options = listOf("Как в системе", "Светлая", "Тёмная"),
                selectedIndex = themeMode,
                onSelect = viewModel::setThemeMode,
            )

            Spacer(Modifier.height(4.dp))
            SectionLabel("Книги с устройства")
            LibraryFolderCard(
                folder = libraryFolder,
                scanning = scanning,
                onPick = { pickFolder.launch(libraryFolder?.let(Uri::parse)) },
                onRescan = viewModel::rescanLibraryFolder,
                onForget = viewModel::forgetLibraryFolder,
            )

            Spacer(Modifier.height(4.dp))
            SectionLabel("Воспроизведение")
            ChoiceRow(
                title = "Шаг перемотки",
                options = SKIP_CHOICES.map { "$it с" },
                selectedIndex = SKIP_CHOICES.indexOf(skipSeconds).coerceAtLeast(0),
                onSelect = { index -> viewModel.setSkipSeconds(SKIP_CHOICES[index]) },
            )
            ChoiceRow(
                title = "Скорость",
                options = SPEED_CHOICES.map { formatSpeed(it) },
                selectedIndex = SPEED_CHOICES.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
                    .coerceAtLeast(0),
                onSelect = { index -> viewModel.setPlaybackSpeed(SPEED_CHOICES[index]) },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "Откуда играть — из сети или с устройства — выбирается отдельно для каждой книги, " +
                    "переключателем в самом верху её экрана.",
                color = c.inkFaint,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            message?.let { text ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                        .clickable { viewModel.clearMessage() }
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Text(text, color = c.inkMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Папка с книгами. Выбор запоминается: с него начинается системный проводник,
 * когда книгу добавляют с полки, и его же пересканирует кнопка ниже.
 */
@Composable
private fun LibraryFolderCard(
    folder: String?,
    scanning: Boolean,
    onPick: () -> Unit,
    onRescan: () -> Unit,
    onForget: () -> Unit,
) {
    val c = Neu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(18.dp), elevation = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                AppIcons.Folder,
                null,
                tint = if (folder == null) c.inkFaint else c.accent,
                modifier = Modifier.size(16.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Папка с книгами", color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    folder?.readableFolder() ?: "Не выбрана",
                    color = c.inkFaint,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (scanning) {
                CircularProgressIndicator(
                    color = c.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Text(
            "Каждая вложенная папка с аудио станет отдельной книгой. Выбор запоминается: " +
                "с него начинается проводник, когда книгу добавляют с полки.",
            color = c.inkFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction(
                label = if (folder == null) "Выбрать папку" else "Другая папка",
                accent = folder == null,
                enabled = !scanning,
                onClick = onPick,
            )
            if (folder != null) {
                SmallAction(
                    label = "Пересканировать",
                    accent = true,
                    enabled = !scanning,
                    onClick = onRescan,
                )
                SmallAction(label = "Забыть", accent = false, enabled = !scanning, onClick = onForget)
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, accent: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = Neu.colors
    Box(
        Modifier
            .neuRaised(RoundedCornerShape(13.dp), elevation = 3.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = when {
                !enabled -> c.inkFaint
                accent -> c.accent
                else -> c.inkMuted
            },
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Адрес папки в системе выглядит как «content://...%3AAudiobooks» — показываем хвост. */
private fun String.readableFolder(): String =
    java.net.URLDecoder.decode(this, "UTF-8")
        .substringAfterLast(':')
        .trim('/')
        .ifBlank { this }

/** Строка «название — набор вариантов». Выбранный вариант утоплен. */
@Composable
private fun ChoiceRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val c = Neu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(18.dp), elevation = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(title, color = c.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .then(
                            if (selected) Modifier.neuSunken(RoundedCornerShape(13.dp), depth = 3.dp)
                            else Modifier.neuRaised(RoundedCornerShape(13.dp), elevation = 3.dp)
                        )
                        .clickable(enabled = !selected) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) c.accent else c.inkMuted,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

private val SKIP_CHOICES = listOf(10, 15, 20, 30)
private val SPEED_CHOICES = listOf(1.0f, 1.25f, 1.5f, 2.0f)

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"
