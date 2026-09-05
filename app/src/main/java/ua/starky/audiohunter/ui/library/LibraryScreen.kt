package ua.starky.audiohunter.ui.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiohunter.data.provider.ProviderRegistry
import ua.starky.audiohunter.ui.components.AppIcons
import ua.starky.audiohunter.ui.components.CoverPlaceholder
import ua.starky.audiohunter.ui.components.NeuIconButton
import ua.starky.audiohunter.ui.theme.Neu
import ua.starky.audiohunter.ui.theme.neuRaised
import ua.starky.audiohunter.ui.theme.neuSunken

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    themeMode: Int,
    onToggleTheme: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val aggregatorBaseUrl by viewModel.aggregatorBaseUrl.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    val c = Neu.colors

    if (showSettings) {
        AggregatorSettingsDialog(
            currentUrl = aggregatorBaseUrl,
            onDismiss = { showSettings = false },
            onSave = { url -> viewModel.setAggregatorBaseUrl(url); showSettings = false },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Библиотека",
                    color = c.ink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.3).sp,
                )
                Text(
                    if (books.isEmpty()) "Пока пусто" else "${books.size} ${plural(books.size)}",
                    color = c.inkFaint,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                )
            }
            NeuIconButton(
                icon = if (themeMode == 2) AppIcons.Online else AppIcons.Library,
                contentDescription = "Сменить тему",
                onClick = onToggleTheme,
                size = 40.dp,
                iconSize = 16.dp,
            )
            Spacer(Modifier.size(10.dp))
            NeuIconButton(AppIcons.Settings, "Настройки сервера", { showSettings = true }, size = 40.dp, iconSize = 16.dp)
            Spacer(Modifier.size(10.dp))
            NeuIconButton(AppIcons.Search, "Найти книгу", onOpenSearch, size = 40.dp, iconSize = 16.dp, tint = c.accent)
        }

        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = 60.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    "Нажмите на лупу и найдите книгу. Скачанные главы останутся на устройстве — " +
                        "их можно слушать без сети, переключив источник в плеере.",
                    color = c.inkMuted,
                    fontSize = 13.5.sp,
                    lineHeight = 21.sp,
                )
            }
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 22.dp),
        ) {
            items(books, key = { it.id }) { book ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .neuRaised(RoundedCornerShape(20.dp), elevation = 6.dp)
                        .clickable { onOpenBook(book.id) }
                        .padding(13.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverPlaceholder(
                        title = book.title,
                        modifier = Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)),
                        fontSize = 7,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            book.title,
                            color = c.ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(book.author, color = c.inkMuted, fontSize = 12.sp)
                        Text(
                            ProviderRegistry.displayName(book.providerId),
                            color = c.inkFaint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.9.sp,
                        )
                    }
                    Icon(
                        AppIcons.Trash,
                        "Убрать из библиотеки",
                        tint = c.inkFaint,
                        modifier = Modifier
                            .size(17.dp)
                            .clickable { viewModel.remove(book.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AggregatorSettingsDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val c = Neu.colors
    var text by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой сервер-агрегатор", color = c.ink, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "Адрес сервера из server/ (см. README). Пусто — источник «Мой сервер» выключен.",
                    color = c.inkMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .neuSunken(RoundedCornerShape(14.dp), depth = 2.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.accent),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text("http://192.168.1.10:8000", color = c.inkFaint, fontSize = 13.sp)
                            }
                            inner()
                        },
                    )
                }
            }
        },
        confirmButton = {
            Text(
                "Сохранить",
                color = c.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { onSave(text) }.padding(8.dp),
            )
        },
        dismissButton = {
            Text("Отмена", color = c.inkMuted, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        },
    )
}

private fun plural(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "книг"
        mod10 == 1 -> "книга"
        mod10 in 2..4 -> "книги"
        else -> "книг"
    }
}
