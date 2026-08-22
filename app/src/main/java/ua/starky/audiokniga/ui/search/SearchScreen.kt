package ua.starky.audiokniga.ui.search

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
            subtitle = "${ProviderRegistry.searchable.size} источников",
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
            if (state.loading || state.opening) {
                CircularProgressIndicator(
                    color = c.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            when {
                state.results.isEmpty() && state.error != null -> Hint(state.error!!, isError = true)

                state.results.isEmpty() && state.searched && !state.loading ->
                    Hint("Ничего не нашлось. Попробуйте другое написание, имя автора или язык оригинала.")

                state.results.isEmpty() && !state.loading -> Hint(
                    "Ищем сразу по всем подключённым источникам: Internet Archive, LibriVox, " +
                        "каталог подкастов и ваши собственные источники из настроек.\n\n" +
                        "Ссылку на RSS-ленту можно вставить прямо сюда."
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    if (state.problems.isNotEmpty()) {
                        item { ProblemsNote(state.problems) }
                    }
                    items(state.results, key = { it.book.id }) { result ->
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
        if (state.results.isNotEmpty() && state.error != null) {
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
