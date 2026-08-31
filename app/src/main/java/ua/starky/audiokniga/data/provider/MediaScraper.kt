package ua.starky.audiokniga.data.provider

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

/**
 * Универсальный извлекатель аудио из ответа сайта.
 *
 * Нужен, чтобы источник можно было добавить, ничего не программируя: пользователь
 * даёт адрес, а разбирать приходится то, что вернёт сервер. Поддержаны три формы:
 *
 * - XML — RSS или Atom с вложениями `<enclosure>` / `<link rel="enclosure">`;
 * - JSON — дерево обходится целиком, берутся все строки, похожие на ссылки на аудио;
 * - HTML — со страницы снимаются ссылки на аудиофайлы и теги `<audio src>`.
 *
 * Разбор нарочно снисходительный: лучше найти часть глав, чем ничего.
 */
object MediaScraper {

    data class Track(
        val title: String,
        val url: String,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
    )

    data class Found(
        val title: String,
        val author: String?,
        val cover: String?,
        val description: String?,
        val tracks: List<Track>,
        /** Ленты отдают свежее первым — книгу удобнее слушать с начала. */
        val newestFirst: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val AUDIO_EXTENSIONS = listOf(
        ".mp3", ".m4a", ".m4b", ".ogg", ".oga", ".opus", ".aac", ".wav", ".flac",
    )

    fun scrape(response: Http.Response, fallbackTitle: String): Found {
        val body = response.body
        val head = body.take(400).trimStart()
        val type = response.contentType

        val found = when {
            type.contains("json") || head.startsWith("{") || head.startsWith("[") ->
                fromJson(body, response.url, fallbackTitle)

            type.contains("xml") || head.startsWith("<?xml") ||
                head.contains("<rss", true) || head.contains("<feed", true) ->
                fromXml(body, response.url, fallbackTitle)

            else -> fromHtml(body, response.url, fallbackTitle)
        }

        if (found.tracks.isEmpty()) {
            throw ProviderException(blockReason(response) ?: "по этому адресу не нашлось ни одного аудиофайла")
        }
        return found
    }

    /** Известные строки защиты от ботов: Cloudflare, PerimeterX, DataDome и их аналоги. */
    private val BOT_CHALLENGE_MARKERS = listOf(
        "just a moment", "checking your browser before accessing",
        "cf-browser-verification", "cf_chl_opt", "attention required",
        "verify you are human", "enable javascript and cookies to continue",
        "perimeterx", "datadome", "распознан как робот",
        "подтвердите, что вы не робот",
    )

    /**
     * Иногда пустой результат объясняется не содержимым страницы, а тем, что до
     * него не добрались вовсе. Защита от ботов подменяет ответ проверочной
     * страницей, а многие современные сайты собирают содержимое в браузере через
     * JavaScript — простой запрос получает пустой каркас без единой ссылки внутри.
     * Оба случая выглядят как «аудио нет», но разбирать в них нечего: дело не в
     * разборе, а в том, что настоящая страница до приложения не дошла.
     */
    internal fun blockReason(response: Http.Response): String? {
        val head = response.body.take(6000).lowercase()
        if (BOT_CHALLENGE_MARKERS.any { head.contains(it) }) {
            return "сайт защищён от автоматических запросов (проверка на робота) — " +
                "простым запросом до содержимого не добраться"
        }

        if (!response.contentType.contains("html") && !head.contains("<html")) return null
        val doc = runCatching { Jsoup.parse(response.body, response.url) }.getOrNull() ?: return null
        val visibleText = doc.body()?.text()?.trim().orEmpty()
        val scripts = doc.select("script[src]").size
        return if (visibleText.length < 80 && scripts >= 2) {
            "страница собирается в браузере через JavaScript — простой запрос " +
                "получает пустой каркас без содержимого"
        } else {
            null
        }
    }

    // ——— RSS и Atom ———

    private fun fromXml(xml: String, baseUrl: String, fallbackTitle: String): Found {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var feedTitle: String? = null
        var feedAuthor: String? = null
        var cover: String? = null
        var description: String? = null

        val tracks = mutableListOf<Track>()
        var inItem = false
        var itemTitle: String? = null
        var itemUrl: String? = null
        var itemLength = 0L
        var itemDuration = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when (name) {
                    "item", "entry" -> {
                        inItem = true
                        itemTitle = null; itemUrl = null; itemLength = 0L; itemDuration = 0L
                    }

                    "title" -> {
                        val text = parser.textOrNull()
                        if (inItem) itemTitle = text
                        else if (feedTitle == null) feedTitle = text
                    }

                    "author", "itunes:author", "dc:creator" -> {
                        val text = parser.textOrNull()
                        if (!inItem && !text.isNullOrBlank()) feedAuthor = text
                    }

                    "description", "itunes:summary", "subtitle" -> {
                        val text = parser.textOrNull()
                        if (!inItem && description == null) description = text
                    }

                    "itunes:image" -> if (!inItem && cover == null) {
                        cover = parser.getAttributeValue(null, "href")
                    }

                    "url" -> if (!inItem && cover == null) cover = parser.textOrNull()

                    "itunes:duration" -> if (inItem) {
                        itemDuration = parser.textOrNull().orEmpty().toDurationMs()
                    }

                    "enclosure" -> if (inItem) {
                        val mime = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && (mime.startsWith("audio") || url.looksLikeAudio())) {
                            itemUrl = url.absolute(baseUrl)
                            itemLength = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }

                    // Atom: <link rel="enclosure" type="audio/mpeg" href="..."/>
                    "link" -> if (inItem && itemUrl == null) {
                        val mime = parser.getAttributeValue(null, "type").orEmpty()
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null && (mime.startsWith("audio") || href.looksLikeAudio())) {
                            itemUrl = href.absolute(baseUrl)
                        }
                    }
                }

                XmlPullParser.END_TAG -> if (name == "item" || name == "entry") {
                    val url = itemUrl
                    if (url != null) {
                        tracks += Track(
                            title = itemTitle?.takeIf { it.isNotBlank() } ?: "Часть ${tracks.size + 1}",
                            url = url,
                            durationMs = itemDuration,
                            sizeBytes = itemLength,
                        )
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }

        return Found(
            title = feedTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = feedAuthor,
            cover = cover,
            description = description?.stripHtml(),
            tracks = tracks,
            newestFirst = true,
        )
    }

    /** `nextText()` падает, если внутри тега вложенные элементы — для чужих лент это норма. */
    private fun XmlPullParser.textOrNull(): String? =
        runCatching { nextText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ——— Произвольный JSON ———

    private fun fromJson(body: String, baseUrl: String, fallbackTitle: String): Found {
        val root = runCatching { json.parseToJsonElement(body) }.getOrElse {
            throw ProviderException("вернул JSON, который не удалось разобрать")
        }

        val tracks = mutableListOf<Track>()
        collectFromJson(root, baseUrl, tracks)

        return Found(
            title = root.findString(TITLE_KEYS)?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = root.findString(AUTHOR_KEYS),
            cover = root.findString(COVER_KEYS),
            description = root.findString(listOf("description", "summary"))?.stripHtml(),
            tracks = tracks.distinctBy { it.url },
            newestFirst = false,
        )
    }

    /**
     * Обход дерева целиком: у каждого объекта смотрим, нет ли внутри ссылки на аудио,
     * и если есть — берём в качестве названия соседнее поле с именем.
     */
    private fun collectFromJson(element: JsonElement, baseUrl: String, into: MutableList<Track>) {
        when (element) {
            is JsonArray -> element.forEach { collectFromJson(it, baseUrl, into) }

            is JsonObject -> {
                val audio = element.entries.firstNotNullOfOrNull { (key, value) ->
                    val text = (value as? JsonPrimitive)?.contentOrNull()
                    if (text != null && text.looksLikeAudio() && key.lowercase() !in IGNORED_KEYS) text else null
                }
                if (audio != null) {
                    into += Track(
                        title = element.firstString(TITLE_KEYS)
                            ?: audio.substringAfterLast('/').substringBeforeLast('.')
                                .replace('_', ' ').replace('-', ' ').trim()
                                .ifBlank { "Часть ${into.size + 1}" },
                        url = audio.absolute(baseUrl),
                        durationMs = element.firstString(listOf("duration", "length", "playtime"))
                            .orEmpty().toDurationMs(),
                        sizeBytes = element.firstString(listOf("size", "filesize"))?.toLongOrNull() ?: 0L,
                    )
                }
                element.values.forEach { collectFromJson(it, baseUrl, into) }
            }

            else -> Unit
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it != "null" && it.isNotBlank() }

    private fun JsonObject.firstString(keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, true) }
            ?.value?.let { it as? JsonPrimitive }?.contentOrNull()
    }

    /** Ищет поле по всему дереву — верхний уровень у всех API разный. */
    private fun JsonElement.findString(keys: List<String>): String? = when (this) {
        is JsonObject -> firstString(keys) ?: values.firstNotNullOfOrNull { it.findString(keys) }
        is JsonArray -> firstNotNullOfOrNull { it.findString(keys) }
        else -> null
    }

    // ——— Обычная веб-страница ———

    /**
     * Обычная веб-страница разбирается через настоящий DOM: регулярками по разметке
     * не отличить `<audio><source>` от текста в комментарии и не развернуть
     * относительный адрес. Jsoup делает и то и другое, а заодно переживает кривую
     * вёрстку, которой на книжных сайтах хватает.
     */
    private fun fromHtml(html: String, baseUrl: String, fallbackTitle: String): Found {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return Found(fallbackTitle, null, null, null, emptyList(), newestFirst = false)

        // Порядок обхода документа — он же порядок глав.
        val urls = LinkedHashSet<String>()
        for (element in doc.allElements) {
            for (attribute in AUDIO_ATTRIBUTES) {
                if (!element.hasAttr(attribute)) continue
                // absUrl сам достраивает относительный путь до полного.
                val value = element.absUrl(attribute).ifBlank { element.attr(attribute) }
                if (value.looksLikeAudio()) urls += value
            }
        }

        // Плеер часто получает файл из встроенного скрипта: в DOM такой ссылки нет
        // вовсе, поэтому вторым заходом просматриваем исходный текст страницы.
        urls += audioUrlsIn(html, baseUrl)

        val tracks = urls.mapIndexed { index, url ->
            Track(
                title = runCatching { URI(url).path }.getOrNull()
                    ?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?.replace('_', ' ')?.replace("%20", " ")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Часть ${index + 1}",
                url = url,
            )
        }

        return Found(
            title = doc.title().trim().takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() },
            cover = doc.selectFirst("meta[property=og:image]")?.absUrl("content")?.takeIf { it.isNotBlank() },
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.stripHtml(),
            tracks = tracks,
            newestFirst = false,
        )
    }

    /**
     * Поиск адресов аудио в сыром тексте — там, куда DOM не заглядывает.
     * Внутри скриптов и JSON слэши экранированы: "https:\/\/site.ru\/file.mp3",
     * и пока их не развернуть, ссылка не распознаётся.
     */
    private fun audioUrlsIn(raw: String, baseUrl: String): List<String> {
        val text = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("&amp;", "&")

        val hits = mutableListOf<Pair<Int, String>>()
        ABSOLUTE_AUDIO_PATTERN.findAll(text).forEach { hits += it.range.first to it.value }
        // Путь от корня берём только в начале строкового литерала, иначе выражение
        // выхватит хвост уже найденного полного адреса и создаст дубликат.
        RELATIVE_AUDIO_PATTERN.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.let { hits += match.range.first to it }
        }

        return hits.sortedBy { it.first }.map { it.second.absolute(baseUrl) }.distinct()
    }

    // ——— Мелочи ———

    fun String.looksLikeAudio(): Boolean {
        val path = substringBefore('?').substringBefore('#').lowercase()
        return AUDIO_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Ссылки в лентах и на страницах часто относительные. */
    private fun String.absolute(baseUrl: String): String {
        if (startsWith("http://", true) || startsWith("https://", true)) return this
        return runCatching { URI(baseUrl).resolve(this).toString() }.getOrDefault(this)
    }

    /** "1:02:03", "3723" и "3723.5" — всё это встречается в разных API. */
    fun String.toDurationMs(): Long {
        val text = trim()
        if (text.isEmpty()) return 0L
        return if (text.contains(':')) {
            val parts = text.split(':').mapNotNull { it.trim().toDoubleOrNull() }
            when (parts.size) {
                3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
                2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
                else -> 0L
            }
        } else {
            val number = text.toDoubleOrNull() ?: return 0L
            // Некоторые API отдают миллисекунды, некоторые секунды.
            if (number > 100_000) number.toLong() else (number * 1000).toLong()
        }
    }

    fun String.stripHtml(): String =
        replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val TITLE_KEYS = listOf("title", "name", "trackName", "collectionName")
    private val AUTHOR_KEYS = listOf("author", "artist", "creator", "artistName")
    private val COVER_KEYS = listOf("cover", "image", "artwork", "artworkUrl600", "thumbnail")

    /** Поля-«соседи», которые не являются самим файлом. */
    private val IGNORED_KEYS = setOf("cover", "image", "artwork", "thumbnail", "poster")

    /** Атрибуты, в которых плееры держат адрес файла. */
    private val AUDIO_ATTRIBUTES = listOf(
        "src", "href", "data-src", "data-file", "data-url", "data-track", "data-mp3", "data-audio",
    )

    private const val AUDIO_ALTERNATIVES = "mp3|m4a|m4b|ogg|oga|opus|aac|wav|flac"

    /** Полный адрес аудиофайла в любом месте страницы, включая тело скрипта. */
    private val ABSOLUTE_AUDIO_PATTERN = Regex(
        """https?://[^\s"'<>()\\]+?\.(?:$AUDIO_ALTERNATIVES)(?:\?[^\s"'<>()\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Путь от корня сайта. Требуем кавычку или скобку перед ним: иначе выражение
     * выхватывало бы хвост уже найденного полного адреса и плодило дубликаты.
     */
    private val RELATIVE_AUDIO_PATTERN = Regex(
        """["'(](/[^\s"'<>()\\]+?\.(?:$AUDIO_ALTERNATIVES)(?:\?[^\s"'<>()\\]*)?)""",
        RegexOption.IGNORE_CASE,
    )

}
