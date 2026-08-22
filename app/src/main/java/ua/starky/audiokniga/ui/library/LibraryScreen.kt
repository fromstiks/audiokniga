package ua.starky.audiokniga.ui.library

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
import ua.starky.audiokniga.ui.SectionHeader
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.BookCover
import ua.starky.audiokniga.ui.components.MiniPlayer
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val current by viewModel.currentBook.collectAsStateWithLifecycle()
    val skipSeconds by viewModel.skipSeconds.collectAsStateWithLifecycle()
    val c = Neu.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        SectionHeader(
            title = "Моя полка",
            subtitle = if (books.isEmpty()) "Пока пусто" else "${books.size} ${plural(books.size)}",
            onOpenDrawer = onOpenDrawer,
            action = {
                NeuIconButton(AppIcons.Search, "Найти книгу", onOpenSearch, size = 42.dp, iconSize = 17.dp, tint = c.accent)
            },
        )

        Box(Modifier.weight(1f)) {
            if (books.isEmpty()) {
                EmptyShelf(onOpenSearch = onOpenSearch)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            title = book.title,
                            author = book.author,
                            coverUrl = book.coverUrl,
                            source = ProviderRegistry.displayName(book.providerId),
                            playing = playback.isPlaying && playback.bookId == book.id,
                            onOpen = { onOpenBook(book.id) },
                            onRemove = { viewModel.remove(book.id) },
                        )
                    }
                }
            }
        }

        // ——— свёрнутый плеер ———
        current?.let { book ->
            Spacer(Modifier.height(10.dp))
            MiniPlayer(
                book = book,
                playback = playback,
                skipSeconds = skipSeconds,
                onExpand = { onOpenBook(book.id) },
                onPlayPause = viewModel::playPause,
                onSkipBack = viewModel::skipBack,
                onSkipForward = viewModel::skipForward,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun EmptyShelf(onOpenSearch: () -> Unit) {
    val c = Neu.colors
    Column(
        Modifier.fillMaxSize().padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "На полке пока пусто",
            color = c.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Найдите книгу в открытых каталогах — LibriVox, Internet Archive, подкасты. " +
                "Скачанные главы останутся на устройстве: их можно слушать без сети, " +
                "переключив источник на «Офлайн».",
            color = c.inkMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Row(
            Modifier
                .neuRaised(RoundedCornerShape(18.dp), elevation = 6.dp)
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Search, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Text("Найти книгу", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BookRow(
    title: String,
    author: String,
    coverUrl: String?,
    source: String,
    playing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Neu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(20.dp), elevation = 6.dp)
            .clickable(onClick = onOpen)
            .padding(13.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(
            title = title,
            coverUrl = coverUrl,
            modifier = Modifier.size(62.dp).clip(RoundedCornerShape(16.dp)),
            fontSize = 7,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = c.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(author, color = c.inkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (playing) "Играет · $source" else source,
                color = if (playing) c.accent else c.inkFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
        }
        Icon(
            AppIcons.Trash,
            "Убрать из библиотеки",
            tint = c.inkFaint,
            modifier = Modifier.size(17.dp).clickable(onClick = onRemove),
        )
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
