package ua.starky.audiokniga.data.provider

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            throw ProviderException("по этому адресу не нашлось ни одного аудиофайла")
        }
        return found
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

    private fun fromHtml(html: String, baseUrl: String, fallbackTitle: String): Found {
        val links = LINK_PATTERN.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.looksLikeAudio() }
            .map { it.absolute(baseUrl) }
            .distinct()
            .toList()

        val tracks = links.mapIndexed { index, url ->
            Track(
                title = runCatching { URI(url).path }.getOrNull()
                    ?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?.replace('_', ' ')?.replace("%20", " ")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Часть ${index + 1}",
                url = url,
            )
        }

        val pageTitle = TITLE_PATTERN.find(html)?.groupValues?.getOrNull(1)?.stripHtml()

        return Found(
            title = pageTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            author = null,
            cover = OG_IMAGE_PATTERN.find(html)?.groupValues?.getOrNull(1)?.absolute(baseUrl),
            description = null,
            tracks = tracks,
            newestFirst = false,
        )
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

    private val LINK_PATTERN =
        Regex("""(?:href|src|data-src)\s*=\s*["']([^"'\s>]+)["']""", RegexOption.IGNORE_CASE)

    private val TITLE_PATTERN =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val OG_IMAGE_PATTERN =
        Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
}
