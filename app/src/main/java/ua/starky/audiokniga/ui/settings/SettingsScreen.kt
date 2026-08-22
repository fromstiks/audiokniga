package ua.starky.audiokniga.ui.settings

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.ui.SectionHeader
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
    val c = Neu.colors

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
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

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
