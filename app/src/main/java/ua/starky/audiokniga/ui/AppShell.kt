package ua.starky.audiokniga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

/** Разделы, между которыми переключает боковое меню. */
enum class AppSection(val title: String, val icon: ImageVector, val route: String) {
    SHELF("Моя полка", AppIcons.Shelf, Routes.LIBRARY),
    SEARCH("Поиск книг", AppIcons.Search, Routes.SEARCH),
    SOURCES("Источники", AppIcons.Link, Routes.SOURCES),
    SETTINGS("Настройки", AppIcons.Settings, Routes.SETTINGS),
}

/**
 * Каркас основных экранов: выдвижное меню слева плюс шапка с кнопкой-гамбургером.
 * Экран плеера сюда не входит — он открывается поверх и закрывается стрелкой «назад».
 */
@Composable
fun AppShell(
    section: AppSection,
    onNavigate: (AppSection) -> Unit,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    content: @Composable (openDrawer: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val c = Neu.colors

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimColor = Color.Black.copy(alpha = if (c.isDark) 0.55f else 0.32f),
        drawerContent = {
            DrawerContent(
                section = section,
                onSelect = { target ->
                    scope.launch { drawerState.close() }
                    if (target != section) onNavigate(target)
                },
            )
        },
    ) {
        content { scope.launch { drawerState.open() } }
    }
}

@Composable
private fun DrawerContent(section: AppSection, onSelect: (AppSection) -> Unit) {
    val c = Neu.colors
    Column(
        Modifier
            .width(282.dp)
            .fillMaxHeight()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Аудиокнига",
            color = c.ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
        )
        Text(
            "Слушать онлайн или с устройства",
            color = c.inkFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))

        AppSection.entries.forEach { item ->
            DrawerItem(item = item, selected = item == section, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun DrawerItem(item: AppSection, selected: Boolean, onClick: () -> Unit) {
    val c = Neu.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // Выбранный пункт «утоплен» — тот же язык, что у текущей главы в оглавлении.
                if (selected) Modifier.neuSunken(shape, depth = 3.dp)
                else Modifier.neuRaised(shape, elevation = 4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = if (selected) c.accent else c.inkMuted,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = item.title,
            color = if (selected) c.ink else c.inkMuted,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** Общая шапка разделов: кнопка меню слева, заголовок, необязательное действие справа. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String?,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val c = Neu.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NeuIconButton(AppIcons.Menu, "Открыть меню", onOpenDrawer, size = 42.dp, iconSize = 17.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = c.ink,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.3).sp,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = c.inkFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                )
            }
        }
        if (action != null) {
            Box { action() }
        }
    }
}
