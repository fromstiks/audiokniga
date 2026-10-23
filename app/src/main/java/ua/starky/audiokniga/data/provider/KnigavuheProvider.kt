package ua.starky.audiokniga.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * КнигаВслух — русские аудиокниги, озвучки и радиопостановки с knigavuhe.org.
 *
 * Сайт не отдаёт ни API, ни ленты: карточки и главы приходится разбирать из HTML,
 * а список треков — из inline-вызова `new BookPlayer(<id>, [...])` на странице книги.
 * Порядок разбора повторяет проверенный внешний парсер этого же сайта (`kvuhe.py`),
 * поэтому селекторы здесь те же: `.bookkitem` в выдаче, `.book_title_name` и
 * `.book_blue_block` в карточке, `.book_playlist_item` — запасной список глав.
 *
 * Отдельная тонкость — мобильная вёрстка. С мобильным User-Agent сайт отдаёт совсем
 * другую разметку без этих классов, поэтому за страницами ходим с настольным.
 *
 * Ссылки на mp3 подписаны и живут около трёх суток (`url_refresh_in` в player_data),
 * поэтому источник объявлен «с истекающими ссылками»: [refreshTracks] перезапрашивает
 * их через `/ajax/book_data/<id>/` и приложение подменяет адреса глав в базе.
 */
class KnigavuheProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "КнигаВслух"
    override val shortName: String = "КнигаВслух"
    override val description: String = "Аудиокниги и озвучки на русском — каталог knigavuhe.org"

    override val linksExpire: Boolean = true

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Номер книги на сайте нужен для запроса свежих ссылок, но в адресе страницы его нет.
     * Запоминаем увиденный на странице, а после перезапуска достаём из адреса уже
     * сохранённой главы: `.../audio/<номер>/<подпись>/файл.mp3`.
     */
    private val numericIds = ConcurrentHashMap<String, Long>()

    /** Когда ссылки книги обновлялись в последний раз — чтобы не дёргать сайт зря. */
    private val refreshedAt = ConcurrentHashMap<String, Long>()

    // ——— поиск ———

    override fun acceptsLink(url: String): Boolean = bookSlug(url) != null

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // Вставленная ссылка на книгу — это уже готовая карточка, искать по ней незачем.
        bookSlug(q)?.let { slug ->
            val details = details(composeBookId(ID, slug))
            return listOf(SearchResult(details.book, details.chapters.size))
        }

        val url = buildString {
            append(BASE).append("/search/?q=").append(URLEncoder.encode(q, "UTF-8"))
            if (page > 1) append("&page=").append(page)
        }

        val doc = Jsoup.parse(Http.getString(url, userAgent = DESKTOP_UA), url)
        return parseCards(doc).mapNotNull { card ->
            card.toBook()?.let { SearchResult(it, chaptersHint = 0) }
        }
    }

    // ——— книга ———

    override suspend fun details(bookId: String): BookDetails {
        val url = pageUrl(localIdOf(bookId))
        val html = Http.getString(url, userAgent = DESKTOP_UA)
        val doc = Jsoup.parse(html, url)

        val player = playerData(html)
        player.bookId?.let { numericIds[bookId] = it }

        val cover = doc.selectFirst(".book_cover img")
            ?.let { img -> img.absUrl("src").ifBlank { absolute(img.attr("src")) } }
            ?.takeIf { it.isNotBlank() }

        // Обычно главы уже лежат в разметке страницы. Если плеер отрисован скриптом
        // и массива нет — пробуем тот же запрос, которым обновляем ссылки.
        var tracks = player.tracks
        if (tracks.isEmpty()) {
            val numeric = player.bookId ?: coverNumber(cover)
            if (numeric != null) {
                tracks = try {
                    fetchTracks(numeric, url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }
            }
        }
        if (tracks.isEmpty()) {
            throw ProviderException("У этой книги не нашлось ссылок на главы")
        }

        val title = doc.selectFirst("span.book_title_name")?.text()?.norm().orEmpty()
        val authors = namesOf(doc.select("span[itemprop='author'] a"))
            .ifEmpty { namesOf(doc.select(".book_title_elem a[href^='/author/']")) }

        val chapters = tracks.mapIndexed { index, track ->
            Chapter(
                id = "$bookId#${track.id ?: (index + 1)}",
                bookId = bookId,
                index = index,
                title = track.title.ifBlank { "Глава ${index + 1}" },
                audioUrl = track.url,
                durationMs = (track.durationSec ?: 0L) * 1000L,
            )
        }

        val fromChapters = chapters.sumOf { it.durationMs }
        val book = Book(
            id = bookId,
            providerId = ID,
            title = title.ifBlank { localIdOf(bookId) },
            author = authors.joinToString(", ").ifBlank { "Неизвестный автор" },
            coverUrl = cover,
            description = richText(doc.selectFirst(".book_description")),
            durationMs = fromChapters.takeIf { it > 0L }
                ?: ((durationOf(doc) ?: 0L) * 1000L),
            language = LANGUAGE,
            sourceUrl = url,
        )

        refreshedAt[bookId] = System.currentTimeMillis()
        return BookDetails(book, chapters)
    }

    /**
     * Свежие адреса глав. Возвращаются главы с теми же идентификаторами и, если
     * сайт что-то поменял, с новыми ссылками и длительностью.
     *
     * Обновляем не чаще раза в несколько часов: подпись живёт трое суток, а лишний
     * запрос на каждое открытие книги не нужен. null — обновлять нечего.
     */
    override suspend fun refreshTracks(bookId: String, chapters: List<Chapter>): List<Chapter>? {
        if (chapters.isEmpty()) return null

        val now = System.currentTimeMillis()
        val last = refreshedAt[bookId]
        if (last != null && now - last < REFRESH_INTERVAL_MS) return null

        val numeric = numericIds[bookId] ?: numericFromLinks(chapters) ?: return null
        val url = pageUrl(localIdOf(bookId))
        val fresh = try {
            fetchTracks(numeric, url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Сайт не ответил — остаёмся на сохранённых адресах и попробуем позже.
            emptyList()
        }
        if (fresh.isEmpty()) return null

        refreshedAt[bookId] = now
        numericIds[bookId] = numeric

        val byTrack = fresh.mapNotNull { track -> track.id?.let { it to track } }.toMap()
        return chapters.map { chapter ->
            val track = byTrack[chapter.id.substringAfterLast('#')] ?: return@map chapter
            chapter.copy(
                audioUrl = track.url,
                durationMs = track.durationSec?.times(1000L)?.takeIf { it > 0L } ?: chapter.durationMs,
            )
        }
    }

    // ——— разбор выдачи ———

    /** Карточки листинга и поиска. Разметка у них одна и та же. */
    private fun parseCards(doc: Document): List<Card> {
        val cards = mutableListOf<Card>()
        for (node in doc.select(".bookkitem")) {
            val link = node.selectFirst("a.bookkitem_cover") ?: continue
            val url = link.absUrl("href").ifBlank { absolute(link.attr("href")) }
            if (url.isBlank()) continue

            val titleTag = node.selectFirst("div.bookkitem_name a.bookkitem_name")
                ?: node.selectFirst("a.bookkitem_name")
            val cover = node.selectFirst("img.bookkitem_cover_img")
                ?.let { img -> img.absUrl("src").ifBlank { absolute(img.attr("src")) } }
                ?.takeIf { it.isNotBlank() }

            // Длительность лежит в отдельном блоке меты, рядом с иконкой часов.
            val duration = node.select("div.bookkitem_meta_block")
                .firstOrNull { block -> block.select("span.bookkitem_icon").any { it.hasClass("-time") } }
                ?.selectFirst(".bookkitem_meta_time")
                ?.text()

            cards += Card(
                url = url,
                title = titleTag?.text()?.norm().orEmpty(),
                author = namesOf(node.select("span.bookkitem_author a")).joinToString(", "),
                cover = cover,
                description = node.selectFirst("div.bookkitem_about")?.text()?.norm()
                    ?.takeIf { it.isNotBlank() },
                durationSec = humanDurationSeconds(duration),
            )
        }
        return cards
    }

    // ——— главы со страницы книги ———

    /**
     * Плеер получает список глав прямо в разметке:
     * `var player = new BookPlayer(68659, [{"id":…,"title":…,"url":…}, …]);`
     *
     * Массив вырезаем вручную и разбираем как JSON: `html.indexOf("]")` здесь
     * не годится — вложенные массивы и строки с квадратными скобками его обманут.
     */
    private fun playerData(html: String): PlayerData {
        val marker = html.indexOf(PLAYER_MARKER)
        if (marker < 0) return PlayerData(null, emptyList())

        val start = html.indexOf('[', marker)
        if (start < 0) return PlayerData(null, emptyList())
        val end = closingBracket(html, start) ?: return PlayerData(null, emptyList())

        val array = runCatching {
            json.parseToJsonElement(html.substring(start, end + 1)) as? JsonArray
        }.getOrNull()

        val numeric = PLAYER_ID
            .find(html.substring(marker, minOf(marker + 80, html.length)))
            ?.groupValues?.get(1)?.toLongOrNull()

        return PlayerData(numeric, array?.let { tracksOf(it) }.orEmpty())
    }

    /** Индекс закрывающей скобки пары, начиная с [openIndex]. Строки и экранирование учитываются. */
    private fun closingBracket(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openIndex until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun tracksOf(array: JsonArray): List<Track> = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        // Трек с ошибкой приходит без адреса — играть его нечем.
        val url = obj.string("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Track(
            id = obj.string("id") ?: obj.number("id")?.toString(),
            title = obj.string("title")?.norm().orEmpty(),
            url = url,
            durationSec = obj.number("duration") ?: obj.number("duration_float"),
        )
    }

    /**
     * Свежие ссылки на mp3. Сайт подписывает адреса и просит запрос «из плеера»:
     * без `X-Requested-With` и `Referer` он отвечает не тем, что нужно.
     */
    private suspend fun fetchTracks(bookNumericId: Long, referer: String): List<Track> {
        val body = Http.get(
            "$BASE/ajax/book_data/$bookNumericId/",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to referer,
            ),
            userAgent = DESKTOP_UA,
        ).body

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw ProviderException("Сайт ответил так, что список глав не разобрать")
        val playlist = playlistOf(root)
            ?: throw ProviderException("В ответе сайта нет списка глав")
        return tracksOf(playlist)
    }

    /** `[null, {"result": {"init_data": {"playlist": [...]}}}]` — доходим до массива. */
    private fun playlistOf(root: JsonElement): JsonArray? {
        var node: JsonElement = root
        if (node is JsonArray) node = node.lastOrNull() ?: return null
        (node as? JsonObject)?.get("result")?.let { node = it }
        (node as? JsonObject)?.get("init_data")?.let { node = it }
        return runCatching { (node as JsonObject)["playlist"] as? JsonArray }.getOrNull()
    }

    // ——— поля карточки ———

    private fun durationOf(doc: Document): Long? {
        for (block in doc.select(".book_blue_block")) {
            val label = block.selectFirst(".book_info_label") ?: continue
            if (!label.text().contains("Время звучания")) continue
            val match = CLOCK.findAll(block.text()).firstOrNull() ?: return null
            return clockSeconds(match.value)
        }
        return null
    }

    /**
     * Описание хранится разметкой: `<br>` — перенос строки, пустые строки — абзацы.
     * Простой `text()` склеил бы всё в один абзац, поэтому берём `wholeText()`,
     * в котором переносы уже расставлены.
     */
    private fun richText(element: Element?): String? {
        if (element == null) return null
        val lines = element.wholeText()
            .replace('\r', '\n')
            .split('\n')
            .map { line -> line.replace(SPACES, " ").trim() }

        val text = StringBuilder()
        var blanks = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blanks++
                continue
            }
            if (text.isNotEmpty()) text.append(if (blanks > 0) "\n\n" else "\n")
            text.append(line)
            blanks = 0
        }
        return text.toString().ifBlank { null }
    }

    private fun namesOf(nodes: List<Element>): List<String> =
        nodes.map { it.text().norm() }.filter { it.isNotBlank() }.distinct()

    // ——— мелочи ———

    private fun Card.toBook(): Book? {
        val slug = slugOf(url) ?: return null
        return Book(
            id = composeBookId(ID, slug),
            providerId = ID,
            title = title.ifBlank { slug },
            author = author.ifBlank { "Неизвестный автор" },
            coverUrl = cover,
            description = description,
            durationMs = (durationSec ?: 0L) * 1000L,
            language = LANGUAGE,
            sourceUrl = url,
        )
    }

    private fun numericFromLinks(chapters: List<Chapter>): Long? =
        chapters.firstNotNullOfOrNull { AUDIO_BOOK_ID.find(it.audioUrl)?.groupValues?.get(1)?.toLongOrNull() }

    private fun coverNumber(cover: String?): Long? =
        cover?.let { COVER_ID.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    private fun pageUrl(value: String): String {
        val target = value.trim()
        return when {
            target.startsWith("http://", true) || target.startsWith("https://", true) ->
                target.replace("://m.knigavuhe.org/", "://knigavuhe.org/")
            target.startsWith("/") -> BASE + target
            else -> "$BASE/book/${target.trim('/')}/"
        }
    }

    /** Относительные ссылки в разметке — обычное дело, достраиваем от корня сайта. */
    private fun absolute(href: String): String = when {
        href.isBlank() -> ""
        href.startsWith("http://", true) || href.startsWith("https://", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> BASE + href
        else -> "$BASE/$href"
    }

    private fun slugOf(url: String): String? = runCatching {
        URI(url).path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Ссылка на страницу книги именно этого сайта: `…/book/<slug>/`. */
    private fun bookSlug(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!host.endsWith("knigavuhe.org")) return null
        val path = uri.path.orEmpty()
        if (!path.contains("/book/")) return null
        return path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun String.norm(): String = replace(SPACES, " ").trim()

    /** Человеческая длительность из карточки: «11 часов 21 минута», «17 минут». */
    private fun humanDurationSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase().replace('\u00a0', ' ')
        val hours = HOURS.find(lower)?.groupValues?.get(1)?.toLongOrNull()
        val minutes = MINUTES.find(lower)?.groupValues?.get(1)?.toLongOrNull()
        if (hours != null || minutes != null) {
            return (hours ?: 0L) * 3600L + (minutes ?: 0L) * 60L
        }
        return clockSeconds(lower)
    }

    /** «11:21:25» или «14:28» — в секунды. */
    private fun clockSeconds(value: String): Long? {
        val parts = value.split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            else -> null
        }
    }

    private data class Card(
        val url: String,
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val durationSec: Long?,
    )

    private data class Track(
        val id: String?,
        val title: String,
        val url: String,
        val durationSec: Long?,
    )

    private data class PlayerData(val bookId: Long?, val tracks: List<Track>)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong()

    companion object {
        const val ID = "kvuhe"

        private const val BASE = "https://knigavuhe.org"
        private const val LANGUAGE = "ru"
        private const val PLAYER_MARKER = "new BookPlayer("

        /**
         * Сайт различает настольную и мобильную вёрстку по User-Agent, а классы,
         * к которым привязан разбор, есть только в настольной.
         */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Подпись в адресе живёт около трёх суток; чаще раза в шесть часов не проверяем. */
        private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L

        private val SPACES = Regex("\\s+")
        private val PLAYER_ID = Regex("new BookPlayer\\(\\s*(\\d+)")
        private val AUDIO_BOOK_ID = Regex("/audio/(\\d+)/")
        private val COVER_ID = Regex("/covers/(\\d+)/")
        private val CLOCK = Regex("\\d{1,3}:\\d{2}:\\d{2}|\\d{1,3}:\\d{2}")
        private val HOURS = Regex("(\\d+)\\s*час")
        private val MINUTES = Regex("(\\d+)\\s*мин")
    }
}
