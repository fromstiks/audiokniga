package ua.starky.audiokniga.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.starky.audiokniga.data.model.CustomSource
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.ui.SectionHeader
import ua.starky.audiokniga.ui.components.AppIcons
import ua.starky.audiokniga.ui.components.NeuIconButton
import ua.starky.audiokniga.ui.components.SectionLabel
import ua.starky.audiokniga.ui.theme.Neu
import ua.starky.audiokniga.ui.theme.neuRaised
import ua.starky.audiokniga.ui.theme.neuSunken

/**
 * Экран источников: что подключено из коробки и что добавил сам пользователь.
 */
@Composable
fun SourcesScreen(
    viewModel: SettingsViewModel,
    onOpenDrawer: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val sources by viewModel.customSources.collectAsStateWithLifecycle()
    val disabled by viewModel.disabledSources.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val c = Neu.colors

    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var bulk by remember { mutableStateOf("") }
    var bulkMode by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        SectionHeader(
            title = "Источники",
            subtitle = "${ProviderRegistry.enabled.size} из ${ProviderRegistry.all.size} включено",
            onOpenDrawer = onOpenDrawer,
        )

        LazyColumn(
            Modifier.weight(1f).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionLabel("Встроенные") }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderRegistry.builtInProviders.forEach { provider ->
                        BuiltInRow(
                            title = provider.displayName,
                            note = provider.description,
                            enabled = provider.id !in disabled,
                            onToggle = { viewModel.setBuiltInEnabled(provider.id, provider.id in disabled) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)) }
            item { SectionLabel("Свои источники") }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeSwitch(bulkMode = bulkMode, onChange = { bulkMode = it })
                    if (bulkMode) {
                        BulkAddForm(
                            text = bulk,
                            onChange = { bulk = it },
                            onAdd = {
                                viewModel.addSourcesFromText(bulk)
                                bulk = ""
                            },
                        )
                    } else {
                        AddSourceForm(
                            name = name,
                            url = url,
                            onNameChange = { name = it },
                            onUrlChange = { url = it },
                            onAdd = {
                                viewModel.addSource(name, url)
                                name = ""; url = ""
                            },
                        )
                    }
                }
            }

            if (sources.isEmpty()) {
                item {
                    Text(
                        "Свой источник — это любой адрес, где лежит аудио: RSS-лента, страница " +
                            "с ссылками на mp3 или ответ API. Приложение само разберёт, что в нём есть.\n\n" +
                            "Если вставить в адрес ${CustomSource.QUERY_PLACEHOLDER}, туда подставится " +
                            "поисковый запрос, и источник будет участвовать в обычном поиске книг.",
                        color = c.inkMuted,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            items(sources, key = { it.id }) { source ->
                if (editingId == source.id) {
                    EditSourceRow(
                        source = source,
                        onSave = { name, url ->
                            viewModel.updateSource(source, name, url)
                            editingId = null
                        },
                        onCancel = { editingId = null },
                    )
                } else {
                    CustomSourceRow(
                        source = source,
                        busy = busy,
                        onEdit = { editingId = source.id },
                        onToggle = { viewModel.setEnabled(source, !source.enabled) },
                        onOpen = { viewModel.open(source, onOpenBook) },
                        onRemove = { viewModel.remove(source) },
                    )
                }
            }
        }

        message?.let { text ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp)
                    .neuSunken(RoundedCornerShape(16.dp), depth = 3.dp)
                    .clickable { viewModel.clearMessage() }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                Text(text, color = c.inkMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Встроенный источник. Выключенный остаётся в списке, но выпадает из поиска. */
@Composable
private fun BuiltInRow(title: String, note: String, enabled: Boolean, onToggle: () -> Unit) {
    val c = Neu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(16.dp), elevation = 4.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = if (enabled) AppIcons.Check else AppIcons.Close,
            contentDescription = if (enabled) "Выключить источник" else "Включить источник",
            tint = if (enabled) c.accent else c.inkFaint,
            modifier = Modifier.size(15.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = if (enabled) c.ink else c.inkFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (enabled) note else "Выключен — в поиске не участвует",
                color = c.inkFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

/** Один источник или сразу список — вторым удобнее переносить готовый набор. */
@Composable
private fun ModeSwitch(bulkMode: Boolean, onChange: (Boolean) -> Unit) {
    val c = Neu.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "По одному", true to "Списком").forEach { (mode, label) ->
            val selected = mode == bulkMode
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .then(
                        if (selected) Modifier.neuSunken(RoundedCornerShape(12.dp), depth = 3.dp)
                        else Modifier.neuRaised(RoundedCornerShape(12.dp), elevation = 3.dp)
                    )
                    .clickable(enabled = !selected) { onChange(mode) },
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

@Composable
private fun BulkAddForm(text: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    val c = Neu.colors
    val count = remember(text) { CustomSource.parseList(text).size }
    Column(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(20.dp), elevation = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "По одному источнику в строке: «Название | адрес» или просто адрес. " +
                "Строки, начинающиеся с #, пропускаются.",
            color = c.inkFaint,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .neuSunken(RoundedCornerShape(14.dp), depth = 3.dp)
                .padding(horizontal = 13.dp, vertical = 12.dp),
        ) {
            if (text.isEmpty()) {
                Text(
                    "Мой подкаст | https://example.com/feed.xml\nhttps://example.com/audio/",
                    color = c.inkFaint,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onChange,
                textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 12.sp, lineHeight = 18.sp),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            Modifier
                .neuRaised(RoundedCornerShape(15.dp), elevation = 4.dp)
                .clickable(enabled = count > 0, onClick = onAdd)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                AppIcons.Plus,
                null,
                tint = if (count == 0) c.inkFaint else c.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                if (count == 0) "Вставьте список" else "Добавить · $count",
                color = if (count == 0) c.inkFaint else c.ink,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AddSourceForm(
    name: String,
    url: String,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val c = Neu.colors
    Column(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(20.dp), elevation = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(value = name, onChange = onNameChange, placeholder = "Название источника")
        Field(
            value = url,
            onChange = onUrlChange,
            placeholder = "https://сайт/feed или https://сайт/search?q=${CustomSource.QUERY_PLACEHOLDER}",
            keyboardType = KeyboardType.Uri,
            onDone = onAdd,
        )
        Row(
            Modifier
                .neuRaised(RoundedCornerShape(15.dp), elevation = 4.dp)
                .clickable(enabled = url.isNotBlank(), onClick = onAdd)
                .padding(horizontal = 15.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                AppIcons.Plus,
                null,
                tint = if (url.isBlank()) c.inkFaint else c.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                "Добавить источник",
                color = if (url.isBlank()) c.inkFaint else c.ink,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null,
) {
    val c = Neu.colors
    Box(
        Modifier
            .fillMaxWidth()
            .neuSunken(RoundedCornerShape(14.dp), depth = 3.dp)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                color = c.inkFaint,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = c.ink, fontSize = 12.5.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onDone?.invoke() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Правка источника на месте: нажатие на строку раскрывает поля. */
@Composable
private fun EditSourceRow(
    source: CustomSource,
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = Neu.colors
    var name by remember(source.id) { mutableStateOf(source.name) }
    var url by remember(source.id) { mutableStateOf(source.url) }

    Column(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(17.dp), elevation = 5.dp)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Field(value = name, onChange = { name = it }, placeholder = "Название источника")
        Field(
            value = url,
            onChange = { url = it },
            placeholder = "Адрес",
            keyboardType = KeyboardType.Uri,
            onDone = { onSave(name, url) },
        )
        Text(
            if (url.contains(CustomSource.QUERY_PLACEHOLDER)) {
                "Участвует в поиске: вместо ${CustomSource.QUERY_PLACEHOLDER} подставится запрос."
            } else {
                "Без ${CustomSource.QUERY_PLACEHOLDER} источник не ищет, а открывается целиком. " +
                    "Найдите на сайте страницу поиска и вставьте ${CustomSource.QUERY_PLACEHOLDER} " +
                    "туда, где в адресе стоит искомое слово."
            },
            color = if (url.contains(CustomSource.QUERY_PLACEHOLDER)) c.accent else c.inkFaint,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier
                    .neuRaised(RoundedCornerShape(14.dp), elevation = 4.dp)
                    .clickable(enabled = url.isNotBlank()) { onSave(name, url) }
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    AppIcons.Check,
                    null,
                    tint = if (url.isBlank()) c.inkFaint else c.accent,
                    modifier = Modifier.size(14.dp),
                )
                Text("Сохранить", color = c.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier
                    .neuRaised(RoundedCornerShape(14.dp), elevation = 4.dp)
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 15.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(AppIcons.Close, null, tint = c.inkFaint, modifier = Modifier.size(13.dp))
                Text("Отмена", color = c.inkMuted, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CustomSourceRow(
    source: CustomSource,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Neu.colors
    Row(
        Modifier
            .fillMaxWidth()
            .neuRaised(RoundedCornerShape(17.dp), elevation = 5.dp)
            .clickable(onClick = onEdit)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Icon(
            imageVector = if (source.enabled) AppIcons.Link else AppIcons.Close,
            contentDescription = if (source.enabled) "Включён" else "Выключен",
            tint = if (source.enabled) c.accent else c.inkFaint,
            modifier = Modifier.size(16.dp).clickable(onClick = onToggle),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                source.name,
                color = if (source.enabled) c.ink else c.inkFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.url,
                color = c.inkFaint,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !source.enabled -> "Выключен — в поиске не участвует"
                    source.isSearchTemplate -> "Ищет · нажмите, чтобы изменить"
                    else -> "Не ищет · нажмите, чтобы добавить ${CustomSource.QUERY_PLACEHOLDER}"
                },
                color = when {
                    !source.enabled -> c.inkFaint
                    source.isSearchTemplate -> c.accent
                    else -> c.offline
                },
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp,
            )
        }
        if (!source.isSearchTemplate) {
            NeuIconButton(
                icon = AppIcons.Play,
                contentDescription = "Открыть источник",
                onClick = onOpen,
                size = 34.dp,
                iconSize = 12.dp,
                tint = c.accent,
                enabled = !busy,
            )
        }
        Icon(
            AppIcons.Trash,
            "Удалить источник",
            tint = c.inkFaint,
            modifier = Modifier.size(15.dp).clickable(onClick = onRemove),
        )
    }
}
