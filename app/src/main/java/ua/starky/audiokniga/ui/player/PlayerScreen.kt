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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.data.model.Bookmark
import ua.starky.audiokniga.data.model.ChapterUi
import ua.starky.audiokniga.data.model.DownloadState
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.playback.SleepPlan
import ua.starky.audiokniga.playback.SleepTimerState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Плеер и оглавление на одном экране. Переключатель источника — первое, что видно сверху.
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker by viewModel.playlistPicker.collectAsStateWithLifecycle()
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val marks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val c = Neu.colors
    var pickerOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var marksOpen by remember { mutableStateOf(false) }

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
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            NeuIconButton(AppIcons.Back, "Назад", onBack, size = 36.dp, iconSize = 14.dp)
            Spacer(Modifier.weight(1f))
            NeuIconButton(
                icon = if (state.book?.favorite == true) AppIcons.StarFilled else AppIcons.Star,
                contentDescription = if (state.book?.favorite == true) "Убрать из избранного" else "В избранное",
                onClick = viewModel::toggleFavorite,
                size = 36.dp,
                iconSize = 15.dp,
                tint = if (state.book?.favorite == true) c.offline else null,
            )
            NeuIconButton(
                icon = AppIcons.Playlist,
                contentDescription = "Добавить в список",
                onClick = { pickerOpen = true },
                size = 36.dp,
                iconSize = 15.dp,
                tint = if (picker.selected.isNotEmpty()) c.accent else null,
            )
            NeuTextButton(
                text = formatSpeed(state.playback.speed),
                onClick = viewModel::cycleSpeed,
                size = 36.dp,
                tint = if (state.playback.speed > 1.01f) c.accent else null,
            )
            if (!state.isLocal) {
                NeuIconButton(
                    AppIcons.Download,
                    "Скачать все главы",
                    viewModel::downloadAll,
                    size = 36.dp,
                    iconSize = 14.dp,
                )
            }
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
        // Книге с устройства выбирать не из чего: сеть ей не нужна вовсе.
        if (!state.isLocal) {
            SourceSwitch(mode = state.sourceMode, onSelect = viewModel::setSourceMode)
            Spacer(Modifier.height(18.dp))
        } else {
            Spacer(Modifier.height(4.dp))
        }

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
                    text = state.book?.let { ProviderRegistry.displayName(it.providerId) }.orEmpty(),
                    color = c.inkFaint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                )
                Text(
                    text = if (state.isLocal) "Играет с устройства, без сети"
                    else downloadedLabel(state.downloadedCount, state.chapters.size),
                    color = if (state.isLocal || state.downloadedCount > 0) c.offline else c.inkFaint,
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

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ToolChip(
                icon = AppIcons.Sleep,
                text = sleep.label,
                accent = sleep.armed,
                onClick = { sleepOpen = true },
                modifier = Modifier.weight(1f),
            )
            ToolChip(
                icon = if (marks.isEmpty()) AppIcons.Bookmark else AppIcons.BookmarkFilled,
                text = if (marks.isEmpty()) "Отметить момент" else "Метки · ${marks.size}",
                accent = marks.isNotEmpty(),
                onClick = { marksOpen = true },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f)) {
                SectionLabel(
                    if (!state.isLocal && state.sourceMode == SourceMode.OFFLINE) {
                        "На устройстве · ${state.chapters.size} из ${state.totalChapters}"
                    } else {
                        "Главы · ${state.chapters.size}"
                    }
                )
            }
            Row(
                Modifier
                    .neuRaised(RoundedCornerShape(percent = 50), elevation = 3.dp)
                    .clickable(onClick = viewModel::cycleChapterOrder)
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(AppIcons.Sort, "Изменить порядок глав", tint = c.inkMuted, modifier = Modifier.size(12.dp))
                Text(
                    state.chapterOrder.label,
                    color = c.inkMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
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
                        local = state.isLocal,
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

    if (sleepOpen) {
        SleepDialog(
            sleep = sleep,
            onSelect = { plan ->
                viewModel.setSleepTimer(plan)
                sleepOpen = false
            },
            onDismiss = { sleepOpen = false },
        )
    }

    if (marksOpen) {
        BookmarksDialog(
            bookmarks = marks,
            onJump = { bookmark ->
                viewModel.jumpTo(bookmark)
                marksOpen = false
            },
            onDelete = viewModel::deleteBookmark,
            onMarkNow = viewModel::markCurrentPosition,
            onDismiss = { marksOpen = false },
        )
    }

    if (pickerOpen) {
        PlaylistDialog(
            picker = picker,
            onToggle = viewModel::setInPlaylist,
            onCreate = viewModel::createPlaylistWithBook,
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Широкая кнопка-плашка под управлением воспроизведением. */
@Composable
private fun ToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neu.colors
    Row(
        modifier
            .then(
                if (accent) Modifier.neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                else Modifier.neuRaised(RoundedCornerShape(16.dp), elevation = 4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = if (accent) c.accent else c.inkFaint, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            color = if (accent) c.ink else c.inkMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Таймер сна. Отсчёт идёт по часам, а не по времени звучания, поэтому в подписи
 * честно написано, когда книга замолчит.
 */
@Composable
private fun SleepDialog(
    sleep: SleepTimerState,
    onSelect: (SleepPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Neu.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .neuRaised(RoundedCornerShape(24.dp), elevation = 8.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Таймер сна", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (sleep.armed) {
                    "Осталось ${sleep.label}. Когда время выйдет, книга встанет на паузу, " +
                        "а место, где вы заснули, само отметится закладкой."
                } else {
                    "Книга встанет на паузу сама, а место, на котором это случилось, " +
                        "отметится закладкой — чтобы утром было куда вернуться."
                },
                color = c.inkMuted,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
            )

            // Раскладываем по три в ряд руками: FlowRow всё ещё за opt-in.
            SleepPlan.presets.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { minutes ->
                        val selected = (sleep.plan as? SleepPlan.After)?.minutes == minutes
                        SleepOption(
                            text = "$minutes мин",
                            selected = selected,
                            onClick = { onSelect(SleepPlan.After(minutes)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Последний ряд короче — добиваем пустотой, иначе кнопки разъедутся.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            SleepOption(
                text = "До конца главы",
                selected = sleep.plan == SleepPlan.EndOfChapter,
                onClick = { onSelect(SleepPlan.EndOfChapter) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (sleep.armed) {
                SleepOption(
                    text = "Выключить таймер",
                    selected = false,
                    onClick = { onSelect(SleepPlan.Off) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SleepOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Neu.colors
    Box(
        modifier
            .then(
                if (selected) Modifier.neuSunken(RoundedCornerShape(14.dp), depth = 2.5.dp)
                else Modifier.neuRaised(RoundedCornerShape(14.dp), elevation = 3.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) c.accent else c.inkMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Отмеченные моменты. Метку таймера сна видно сразу: именно к ней возвращаются утром.
 */
@Composable
private fun BookmarksDialog(
    bookmarks: List<Bookmark>,
    onJump: (Bookmark) -> Unit,
    onDelete: (String) -> Unit,
    onMarkNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Neu.colors
    val stamp = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .neuRaised(RoundedCornerShape(24.dp), elevation = 8.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Отмеченные моменты", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            if (bookmarks.isEmpty()) {
                Text(
                    "Пока пусто. Отметьте место сами или включите таймер сна — " +
                        "он поставит метку там, где остановит книгу.",
                    color = c.inkMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .neuRaised(RoundedCornerShape(13.dp), elevation = 3.dp)
                                .clickable { onJump(bookmark) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (bookmark.automatic) AppIcons.Sleep else AppIcons.Bookmark,
                                contentDescription = null,
                                tint = if (bookmark.automatic) c.accent else c.inkFaint,
                                modifier = Modifier.size(14.dp),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    text = bookmark.chapterTitle,
                                    color = c.ink,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatTime(bookmark.positionMs) +
                                        " · " + bookmark.label.lowercase() +
                                        " · " + stamp.format(Date(bookmark.createdAt)),
                                    color = c.inkFaint,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                AppIcons.Trash,
                                "Удалить метку",
                                tint = c.inkFaint,
                                modifier = Modifier.size(14.dp).clickable { onDelete(bookmark.id) },
                            )
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolChip(
                    icon = AppIcons.Plus,
                    text = "Отметить сейчас",
                    accent = false,
                    onClick = onMarkNow,
                    modifier = Modifier.weight(1f),
                )
                ToolChip(
                    icon = AppIcons.Check,
                    text = "Готово",
                    accent = false,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Окно «в какие списки положить книгу». Отмечать можно сразу несколько,
 * тут же создаётся новый список.
 */
@Composable
private fun PlaylistDialog(
    picker: PlaylistPicker,
    onToggle: (String, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Neu.colors
    var newName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .neuRaised(RoundedCornerShape(24.dp), elevation = 8.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("В список", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

            if (picker.playlists.isEmpty()) {
                Text(
                    "Списков пока нет. Придумайте название — книга сразу попадёт в новый список.",
                    color = c.inkMuted,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(picker.playlists, key = { it.id }) { playlist ->
                        val checked = playlist.id in picker.selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (checked) Modifier.neuSunken(RoundedCornerShape(13.dp), depth = 2.5.dp)
                                    else Modifier.neuRaised(RoundedCornerShape(13.dp), elevation = 3.dp)
                                )
                                .clickable { onToggle(playlist.id, !checked) }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = if (checked) AppIcons.Check else AppIcons.Playlist,
                                contentDescription = null,
                                tint = if (checked) c.accent else c.inkFaint,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                playlist.name,
                                color = if (checked) c.ink else c.inkMuted,
                                fontSize = 13.sp,
                                fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                playlist.bookCount.toString(),
                                color = c.inkFaint,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .neuSunken(RoundedCornerShape(14.dp), depth = 3.dp)
                    .padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (newName.isEmpty()) {
                        Text("Новый список", color = c.inkFaint, fontSize = 12.5.sp)
                    }
                    BasicTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 12.5.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (newName.isNotBlank()) { onCreate(newName); newName = "" } },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Icon(
                    AppIcons.Plus,
                    "Создать список",
                    tint = if (newName.isBlank()) c.inkFaint else c.accent,
                    modifier = Modifier
                        .size(15.dp)
                        .clickable(enabled = newName.isNotBlank()) { onCreate(newName); newName = "" },
                )
            }

            Row(
                Modifier
                    .align(Alignment.End)
                    .neuRaised(RoundedCornerShape(14.dp), elevation = 4.dp)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text("Готово", color = c.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
    local: Boolean,
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

        if (local) {
            // Файл и так на устройстве — предлагать его скачать было бы враньём.
            Icon(
                AppIcons.Offline,
                "На устройстве",
                tint = c.offline,
                modifier = Modifier.size(15.dp),
            )
            return@Row
        }

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
