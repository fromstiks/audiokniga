package ua.starky.audiokniga.ui.library

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.CoverPlaceholder
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    themeMode: Int,
    onToggleTheme: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val c = Neu.colors

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
