package ua.starky.audiohunter.ui.search

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
import ua.starky.audiohunter.data.provider.ProviderRegistry
import ua.starky.audiohunter.ui.components.AppIcons
import ua.starky.audiohunter.ui.components.CoverPlaceholder
import ua.starky.audiohunter.ui.components.NeuIconButton
import ua.starky.audiohunter.ui.theme.Neu
import ua.starky.audiohunter.ui.theme.neuRaised
import ua.starky.audiohunter.ui.theme.neuSunken

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
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
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NeuIconButton(AppIcons.Back, "Назад", onBack, size = 36.dp, iconSize = 15.dp)
            Text("Поиск книг", color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

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
                textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.query.isEmpty()) {
                        Text(
                            "Название, автор или ссылка на RSS",
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

        Spacer(Modifier.height(16.dp))

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            state.error != null -> Hint(state.error!!)
            state.results.isEmpty() && state.searched -> Hint("Ничего не нашлось. Попробуйте другое написание или имя автора.")
            state.results.isEmpty() -> Hint(
                "Ищем по LibriVox и Internet Archive — это книги в общественном достоянии. " +
                    "Ссылку на RSS-ленту можно вставить прямо в поле поиска. Если в настройках " +
                    "указан адрес своего сервера-агрегатора, ищем и там."
            )
            else -> LazyColumn(
                Modifier.weight(1f).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
            ) {
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
                        CoverPlaceholder(
                            title = result.book.title,
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
                            Text(result.book.author, color = c.inkMuted, fontSize = 12.sp)
                            Text(
                                buildString {
                                    append(ProviderRegistry.displayName(result.book.providerId))
                                    if (result.chaptersHint > 0) append(" · ${result.chaptersHint} глав")
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
}

@Composable
private fun Hint(text: String) {
    val c = Neu.colors
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            color = c.inkMuted,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}
