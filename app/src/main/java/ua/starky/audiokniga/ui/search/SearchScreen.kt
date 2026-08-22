package ua.starky.audiokniga.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.data.repo.SourceOutcome
import ua.starky.audiokniga.ui.SectionHeader
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.BookCover
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenDrawer: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = Neu.colors

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        SectionHeader(
            title = "Поиск книг",
            subtitle = sourcesLabel(ProviderRegistry.searchable.size),
            onOpenDrawer = onOpenDrawer,
        )

        Row(
            Modifier
                .fillMaxWidth()
                .neuSunken(RoundedCornerShape(18.dp), depth = 3.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(AppIcons.Search, null, tint = c.inkFaint, modifier = Modifier.size(17.dp))
            BasicTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = c.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) {
                        Text(
                            "Название, автор или ссылка на ленту",
                            color = c.inkFaint,
                            fontSize = 13.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }

        if (state.sources.isNotEmpty()) {
            Spacer(Modifier.height(11.dp))
            SourceTabs(
                sources = state.sources,
                selected = state.selectedSource,
                totalResults = state.sources.flatMap { it.results }.distinctBy { it.book.id }.size,
                onSelect = viewModel::selectSource,
            )
        }

        if (state.loading || state.opening) {
            Spacer(Modifier.height(10.dp))
            SearchProgressBar(
                answered = state.answered,
                total = state.askedSources,
                opening = state.opening,
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            val visible = state.visibleResults
            when {
                // На вкладке источника его собственная беда важнее общей картины.
                visible.isEmpty() && state.selectedProblem != null ->
                    Hint(state.selectedProblem!!, isError = true)

                visible.isEmpty() && state.error != null -> Hint(state.error!!, isError = true)

                visible.isEmpty() && state.searched && !state.loading ->
                    Hint(
                        if (state.selectedSource != null) {
                            "Этот источник ничего не нашёл. Посмотрите другие вкладки."
                        } else {
                            "Ничего не нашлось. Попробуйте одно слово вместо нескольких, " +
                                "имя автора или название на языке оригинала."
                        }
                    )

                visible.isEmpty() && !state.loading -> Hint(
                    "Ищем сразу по всем подключённым источникам: Internet Archive, LibriVox, " +
                        "каталог подкастов и ваши собственные источники из настроек.\n\n" +
                        "Ссылку на RSS-ленту можно вставить прямо сюда."
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    if (state.selectedSource == null && state.problems.isNotEmpty()) {
                        item { ProblemsNote(state.problems) }
                    }
                    items(visible, key = { it.book.id }) { result ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .neuRaised(RoundedCornerShape(18.dp), elevation = 5.dp)
                                .clickable { viewModel.addToLibrary(result.book.id, onOpenBook) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BookCover(
                                title = result.book.title,
                                coverUrl = result.book.coverUrl,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)),
                                fontSize = 7,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    result.book.title,
                                    color = c.ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    result.book.author,
                                    color = c.inkMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(ProviderRegistry.displayName(result.book.providerId))
                                        if (result.chaptersHint > 0) append(" · ${result.chaptersHint} частей")
                                    },
                                    color = c.inkFaint,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Ошибка при открытии книги не должна прятать уже найденный список.
        if (state.visibleResults.isNotEmpty() && state.error != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                    .clickable { viewModel.clearError() }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(state.error!!, color = c.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Вкладки по источникам. Подпись короткая — места мало, а знать, кто что нашёл, нужно.
 * Цифра — сколько нашлось, точка — источник ещё думает, крестик — не ответил.
 */
@Composable
private fun SourceTabs(
    sources: List<SourceOutcome>,
    selected: String?,
    totalResults: Int,
    onSelect: (String?) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        item {
            SourceTab(
                label = "Все",
                count = totalResults,
                selected = selected == null,
                pending = false,
                failed = false,
                onClick = { onSelect(null) },
            )
        }
        items(sources, key = { it.providerId }) { source ->
            SourceTab(
                label = source.shortName,
                count = source.results.size,
                selected = selected == source.providerId,
                pending = !source.done,
                failed = source.problem != null,
                onClick = { onSelect(source.providerId) },
            )
        }
    }
}

@Composable
private fun SourceTab(
    label: String,
    count: Int,
    selected: Boolean,
    pending: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
) {
    val c = Neu.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .then(
                if (selected) Modifier.neuSunken(shape, depth = 3.dp)
                else Modifier.neuRaised(shape, elevation = 4.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            color = when {
                selected -> c.ink
                failed -> c.inkFaint
                else -> c.inkMuted
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            pending -> CircularProgressIndicator(
                color = c.accent,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(9.dp),
            )
            failed -> Icon(AppIcons.Close, null, tint = c.offline, modifier = Modifier.size(10.dp))
            count > 0 -> Text(
                count.toString(),
                color = if (selected) c.accent else c.inkFaint,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            else -> Text("0", color = c.inkFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Видимый ход поиска: сколько источников уже ответило. Раньше о том, идёт ли поиск,
 * можно было только догадываться.
 */
@Composable
private fun SearchProgressBar(answered: Int, total: Int, opening: Boolean) {
    val c = Neu.colors
    val progress = if (total > 0) answered.toFloat() / total else 0f
    Column(
        Modifier
            .fillMaxWidth()
            .neuSunken(RoundedCornerShape(15.dp), depth = 2.5.dp)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CircularProgressIndicator(
                color = c.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = when {
                    opening -> "Открываем книгу…"
                    total > 0 -> "Ищем в источниках · $answered из $total"
                    else -> "Ищем…"
                },
                color = c.inkMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!opening && total > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(c.line)
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(percent = 50))
                        .background(c.accent)
                )
            }
        }
    }
}

private fun sourcesLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "источников"
        mod10 == 1 -> "источник"
        mod10 in 2..4 -> "источника"
        else -> "источников"
    }
    return "$count $word"
}

/** Кто из источников промолчал. Полезно, когда результат есть, но неполный. */
@Composable
private fun ProblemsNote(problems: List<String>) {
    val c = Neu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .neuSunken(RoundedCornerShape(15.dp), depth = 2.5.dp)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Ответили не все источники",
            color = c.offline,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        problems.forEach { problem ->
            Text(problem, color = c.inkFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    val c = Neu.colors
    Box(Modifier.fillMaxSize().padding(top = 30.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            color = if (isError) c.offline else c.inkMuted,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
