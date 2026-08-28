package ua.starky.audiokniga.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.Playlist
import ua.starky.audiokniga.data.model.ShelfFilter
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.ui.SectionHeader
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.BookCover
import ua.starky.audiokniga.ui.components.MiniPlayer
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val current by viewModel.currentBook.collectAsStateWithLifecycle()
    val skipSeconds by viewModel.skipSeconds.collectAsStateWithLifecycle()
    val c = Neu.colors

    val message by viewModel.message.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val libraryFolder by viewModel.libraryFolder.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // Системный выбор: разрешение на «все файлы» не нужно, доступ даёт сам пользователь.
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importFolder)
    }
    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        SectionHeader(
            title = filter.title(playlists),
            subtitle = if (books.isEmpty()) "Пока пусто" else "${books.size} ${plural(books.size)}",
            onOpenDrawer = onOpenDrawer,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NeuIconButton(
                        icon = AppIcons.Folder,
                        contentDescription = "Добавить книгу с устройства",
                        onClick = { pickFolder.launch(libraryFolder) },
                        size = 42.dp,
                        iconSize = 17.dp,
                        enabled = !importing,
                    )
                    NeuIconButton(
                        AppIcons.Search, "Найти книгу", onOpenSearch,
                        size = 42.dp, iconSize = 17.dp, tint = c.accent,
                    )
                }
            },
        )

        FilterStrip(
            filter = filter,
            playlists = playlists,
            favorites = favorites,
            onSelect = viewModel::setFilter,
            onCreate = { creating = true; newName = "" },
        )

        if (creating) {
            Spacer(Modifier.height(10.dp))
            NewPlaylistField(
                name = newName,
                onChange = { newName = it },
                onDone = {
                    viewModel.createPlaylist(newName)
                    creating = false; newName = ""
                },
                onCancel = { creating = false; newName = "" },
            )
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            when {
                books.isEmpty() && filter is ShelfFilter.All -> EmptyShelf(
                    onOpenSearch = onOpenSearch,
                    onPickFolder = { pickFolder.launch(libraryFolder) },
                    onPickFiles = { pickFiles.launch(arrayOf("audio/*")) },
                )

                books.isEmpty() -> EmptyFilter(filter = filter, onShowAll = { viewModel.setFilter(ShelfFilter.All) })

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(books, key = { it.id }) { book ->
                        BookRow(
                            book = book,
                            source = ProviderRegistry.displayName(book.providerId),
                            playing = playback.isPlaying && playback.bookId == book.id,
                            inPlaylist = filter is ShelfFilter.InPlaylist,
                            onOpen = { onOpenBook(book.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(book) },
                            onRemove = {
                                if (filter is ShelfFilter.InPlaylist) viewModel.removeFromCurrentPlaylist(book)
                                else viewModel.remove(book.id)
                            },
                        )
                    }
                }
            }
        }

        if (importing || message != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                    .clickable { viewModel.clearMessage() }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (importing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = c.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = if (importing) "Читаю файлы…" else message.orEmpty(),
                    color = c.inkMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 17.sp,
                )
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

/** Полоса «Все · Избранное · списки · +». Прокручивается, списков может быть много. */
@Composable
private fun FilterStrip(
    filter: ShelfFilter,
    playlists: List<Playlist>,
    favorites: Int,
    onSelect: (ShelfFilter) -> Unit,
    onCreate: () -> Unit,
) {
    val c = Neu.colors
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Chip(
                label = "Все",
                selected = filter is ShelfFilter.All,
                onClick = { onSelect(ShelfFilter.All) },
            )
        }
        item {
            Chip(
                label = "Избранное",
                count = favorites,
                icon = AppIcons.StarFilled,
                accent = c.offline,
                selected = filter is ShelfFilter.Favorites,
                onClick = { onSelect(ShelfFilter.Favorites) },
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            Chip(
                label = playlist.name,
                count = playlist.bookCount,
                icon = AppIcons.Playlist,
                selected = (filter as? ShelfFilter.InPlaylist)?.playlistId == playlist.id,
                onClick = { onSelect(ShelfFilter.InPlaylist(playlist.id)) },
            )
        }
        item {
            Chip(label = "Новый список", icon = AppIcons.Plus, selected = false, onClick = onCreate)
        }
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accent: androidx.compose.ui.graphics.Color? = null,
) {
    val c = Neu.colors
    val shape = RoundedCornerShape(percent = 50)
    val tint = accent ?: c.accent
    Row(
        Modifier
            .then(
                if (selected) Modifier.neuSunken(shape, depth = 3.dp)
                else Modifier.neuRaised(shape, elevation = 4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = if (selected) tint else c.inkFaint,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            label,
            color = if (selected) c.ink else c.inkMuted,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count != null && count > 0) {
            Text(
                count.toString(),
                color = if (selected) tint else c.inkFaint,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
    }
}

@Composable
private fun NewPlaylistField(
    name: String,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Neu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .neuSunken(RoundedCornerShape(15.dp), depth = 3.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(AppIcons.Playlist, null, tint = c.accent, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f)) {
            if (name.isEmpty()) {
                Text("Название списка", color = c.inkFaint, fontSize = 13.sp)
            }
            BasicTextField(
                value = name,
                onValueChange = onChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Icon(
            AppIcons.Check,
            "Создать список",
            tint = if (name.isBlank()) c.inkFaint else c.accent,
            modifier = Modifier.size(16.dp).clickable(enabled = name.isNotBlank(), onClick = onDone),
        )
        Icon(
            AppIcons.Close,
            "Отменить",
            tint = c.inkFaint,
            modifier = Modifier.size(15.dp).clickable(onClick = onCancel),
        )
    }
}

@Composable
private fun EmptyShelf(
    onOpenSearch: () -> Unit,
    onPickFolder: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val c = Neu.colors
    Column(
        Modifier.fillMaxSize().padding(top = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("На полке пока пусто", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Если книги уже лежат на телефоне — добавьте папку с главами. Приложение " +
                "прочитает теги, расставит главы по порядку и будет помнить, где вы " +
                "остановились. Сеть для этого не нужна вовсе.",
            color = c.inkMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        ShelfAction(icon = AppIcons.Folder, label = "Папка с книгой", accent = true, onClick = onPickFolder)
        ShelfAction(icon = AppIcons.Note, label = "Отдельные файлы", accent = false, onClick = onPickFiles)

        Spacer(Modifier.height(4.dp))
        Text(
            "Либо поищите в открытых каталогах: Internet Archive, LibriVox, подкасты.",
            color = c.inkFaint,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
        )
        ShelfAction(icon = AppIcons.Search, label = "Найти книгу", accent = false, onClick = onOpenSearch)
    }
}

@Composable
private fun ShelfAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    val c = Neu.colors
    Row(
        Modifier
            .neuRaised(RoundedCornerShape(17.dp), elevation = 5.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (accent) c.accent else c.inkMuted, modifier = Modifier.size(16.dp))
        Text(label, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyFilter(filter: ShelfFilter, onShowAll: () -> Unit) {
    val c = Neu.colors
    Column(
        Modifier.fillMaxSize().padding(top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            when (filter) {
                is ShelfFilter.Favorites -> "В избранном пусто"
                else -> "В этом списке пусто"
            },
            color = c.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when (filter) {
                is ShelfFilter.Favorites ->
                    "Нажмите звёздочку на карточке книги — она появится здесь."
                else ->
                    "Откройте книгу и добавьте её в список кнопкой со списком в шапке плеера."
            },
            color = c.inkMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
        Row(
            Modifier
                .neuRaised(RoundedCornerShape(16.dp), elevation = 5.dp)
                .clickable(onClick = onShowAll)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(AppIcons.Shelf, null, tint = c.accent, modifier = Modifier.size(15.dp))
            Text("Показать всю полку", color = c.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    source: String,
    playing: Boolean,
    inPlaylist: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
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
            title = book.title,
            coverUrl = book.coverUrl,
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
            Text(book.author, color = c.inkMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (playing) "Играет · $source" else source,
                color = if (playing) c.accent else c.inkFaint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.9.sp,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (book.favorite) AppIcons.StarFilled else AppIcons.Star,
                contentDescription = if (book.favorite) "Убрать из избранного" else "В избранное",
                tint = if (book.favorite) c.offline else c.inkFaint,
                modifier = Modifier.size(17.dp).clickable(onClick = onToggleFavorite),
            )
            Icon(
                imageVector = if (inPlaylist) AppIcons.Close else AppIcons.Trash,
                contentDescription = if (inPlaylist) "Убрать из списка" else "Убрать из библиотеки",
                tint = c.inkFaint,
                modifier = Modifier.size(16.dp).clickable(onClick = onRemove),
            )
        }
    }
}

private fun ShelfFilter.title(playlists: List<Playlist>): String = when (this) {
    is ShelfFilter.All -> "Моя полка"
    is ShelfFilter.Favorites -> "Избранное"
    is ShelfFilter.InPlaylist -> playlists.firstOrNull { it.id == playlistId }?.name ?: "Список"
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
