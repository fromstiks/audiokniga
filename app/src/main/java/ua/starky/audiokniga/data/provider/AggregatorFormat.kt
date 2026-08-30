package ua.starky.audiokniga.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult

/**
 * Формат ответа своего сервера-агрегатора.
 *
 * Обычный свой источник — это одна страница, и с неё получается одна книга. Сервер,
 * который ищет сразу по нескольким каталогам, должен вернуть список книг, и разбирать
 * его как «страницу со ссылками» нельзя: все главы всех книг слиплись бы в одну.
 *
 * Поэтому у ответа есть свой формат — и приложение узнаёт его само, по содержимому.
 * Никакой настройки для этого не нужно: тот же адрес со `{q}` продолжает работать
 * и для обычных сайтов, просто теперь ещё и для агрегатора.
 *
 * Минимальный ответ поиска:
 * ```json
 * { "results": [ { "id": "...", "title": "...", "chapters": [ { "audio_url": "..." } ] } ] }
 * ```
 * Ответ на запрос одной книги — то же самое с одним элементом, либо `{ "book": {...} }`,
 * либо просто сам объект книги.
 *
 * Разбор нарочно снисходительный: ключи ищутся среди привычных синонимов, время
 * понимается и числом, и строкой «10:16:00». Лучше принять чуть больше, чем заставлять
 * человека подгонять сервер под нас.
 */
object AggregatorFormat {

    /** Версия формата. Сервер может её присылать, но мы на неё не полагаемся. */
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val RESULT_KEYS = listOf("results", "books")
    private val ID_KEYS = listOf("id", "book_id", "bookid", "slug", "uid")
    private val TITLE_KEYS = listOf("title", "name", "book_title")
    private val AUTHOR_KEYS = listOf("author", "artist", "writer", "performer")
    private val COVER_KEYS = listOf("cover_url", "coverurl", "cover", "image", "thumbnail", "poster")
    private val DESCRIPTION_KEYS = listOf("description", "summary", "annotation", "about")
    private val SOURCE_KEYS = listOf("source", "provider", "site")
    private val PAGE_KEYS = listOf("page_url", "pageurl", "page", "link", "web_url")
    private val BOOK_URL_KEYS = listOf("book_url", "bookurl", "details_url", "detailsurl", "api_url")
    private val CHAPTER_LIST_KEYS = listOf("chapters", "tracks", "files", "episodes", "parts", "items")
    private val AUDIO_KEYS = listOf("audio_url", "audiourl", "audio", "url", "src", "file", "mp3", "stream")
    private val DURATION_KEYS = listOf("duration_ms", "durationms", "duration", "length", "time")
    private val SIZE_KEYS = listOf("size_bytes", "sizebytes", "size", "length_bytes", "filesize")
    private val CHAPTER_COUNT_KEYS =
        listOf("chapters_count", "chapter_count", "chapters_hint", "tracks_count", "parts")

    /**
     * Разбирает ответ поисковой ручки. `null` означает «это не наш формат» — вызывающий
     * тогда разбирает ответ как обычную страницу, как и раньше.
     */
    fun parseSearch(response: Http.Response, providerId: String): List<SearchResult>? {
        val books = booksIn(response) ?: return null
        return books.mapNotNull { entry ->
            val parsed = toDetails(entry, response.url, providerId) ?: return@mapNotNull null
            SearchResult(parsed.details.book, parsed.chaptersHint)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Разбирает ответ на запрос одной книги. [wantedId] — идентификатор внутри ответа:
     * если сервер не дал отдельного адреса книги, мы переспрашиваем поиск и выбираем
     * из его результатов нужную.
     */
    fun parseBook(response: Http.Response, providerId: String, wantedId: String?): BookDetails? {
        val books = booksIn(response) ?: return null
        val entry = books.firstOrNull { wantedId.isNullOrBlank() || it.firstString(ID_KEYS) == wantedId }
            ?: books.firstOrNull()
            ?: return null
        return toDetails(entry, response.url, providerId)?.details
    }

    /**
     * Есть ли в ответе хоть что-то похожее на наш формат. Проверка строгая нарочно:
     * случайный JSON-API сайта не должен приниматься за агрегатор.
     */
    private fun booksIn(response: Http.Response): List<JsonObject>? {
        val head = response.body.take(400).trimStart()
        if (!response.contentType.contains("json") && !head.startsWith("{") && !head.startsWith("[")) {
            return null
        }
        val root = runCatching { json.parseToJsonElement(response.body) }.getOrNull() ?: return null

        // Признак формата — список под "results"/"books" либо книга с собственным
        // списком глав. Одного «объекта с названием и ссылкой» мало: так выглядит
        // половина чужих JSON-ответов, и их надо разбирать по-старому, как ленту.
        val books = when {
            root is JsonObject && root.arrayIn(RESULT_KEYS) != null ->
                root.arrayIn(RESULT_KEYS)!!.filterIsInstance<JsonObject>()

            root is JsonObject && root.objectIn(listOf("book", "result")) != null ->
                listOfNotNull(root.objectIn(listOf("book", "result")))

            root is JsonObject && root.hasChapters() -> listOf(root)

            root is JsonArray -> root.filterIsInstance<JsonObject>().filter { it.hasChapters() }

            else -> return null
        }

        // Книга обязана иметь название и хоть какое-то аудио — иначе слушать нечего.
        return books.takeIf { candidates -> candidates.any { it.looksLikeBook() } }
    }

    /** Собственный список глав — то, чем книга агрегатора отличается от чужого JSON. */
    private fun JsonObject.hasChapters(): Boolean =
        chapterObjects().any { it.firstString(AUDIO_KEYS)?.looksLikeAudio() == true }

    /**
     * Книге в выдаче поиска глав иметь не обязательно: приложение всё равно спросит их
     * отдельно, когда книгу откроют. Достаточно названия и того, где её потом взять.
     */
    private fun JsonObject.looksLikeBook(): Boolean {
        if (firstString(TITLE_KEYS).isNullOrBlank()) return false
        if (hasChapters()) return true
        if (!firstString(BOOK_URL_KEYS).isNullOrBlank()) return true
        return firstString(AUDIO_KEYS)?.looksLikeAudio() == true
    }

    private fun String.looksLikeAudio(): Boolean =
        startsWith("http://", true) || startsWith("https://", true) || startsWith("/")

    private fun JsonObject.chapterObjects(): List<JsonObject> =
        arrayIn(CHAPTER_LIST_KEYS)?.filterIsInstance<JsonObject>().orEmpty()

    /** Разобранная книга вместе с числом глав, которое стоит показать в выдаче. */
    private data class Parsed(val details: BookDetails, val chaptersHint: Int)

    private data class Track(
        val title: String,
        val url: String,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    private fun toDetails(entry: JsonObject, requestUrl: String, providerId: String): Parsed? {
        val title = entry.firstString(TITLE_KEYS)?.takeIf { it.isNotBlank() } ?: return null
        val remoteId = entry.firstString(ID_KEYS)

        // Куда сходить за книгой в следующий раз. Сервер может дать свой адрес; если не
        // дал — переспросим тот же поиск и выберем книгу по идентификатору.
        val bookUrl = entry.firstString(BOOK_URL_KEYS)?.let { absolute(it, requestUrl) }
        val localId = bookUrl ?: buildString {
            append(requestUrl)
            if (!remoteId.isNullOrBlank()) append('#').append(remoteId)
        }
        val bookId = composeBookId(providerId, localId)

        val listed = entry.chapterObjects().mapNotNull { chapter ->
            val url = chapter.firstString(AUDIO_KEYS)?.takeIf { it.looksLikeAudio() }
                ?: return@mapNotNull null
            Track(
                title = chapter.firstString(TITLE_KEYS).orEmpty(),
                url = absolute(url, requestUrl),
                durationMs = chapter.duration(),
                sizeBytes = chapter.size(),
            )
        }
        val single = entry.firstString(AUDIO_KEYS)?.takeIf { it.looksLikeAudio() }

        val tracks = when {
            listed.isNotEmpty() -> listed
            // Книга одним файлом — тоже книга, просто из одной главы.
            single != null -> listOf(Track(title, absolute(single, requestUrl), entry.duration(), 0L))
            // Глав нет вовсе — это выдача поиска, и она законна: приложение спросит
            // главы отдельно, когда книгу откроют. Но только если сказано, где брать.
            bookUrl != null -> emptyList()
            else -> return null
        }

        val chapters = tracks.mapIndexed { index, track ->
            Chapter(
                id = "$bookId#$index",
                bookId = bookId,
                index = index,
                title = track.title.ifBlank { "Глава ${index + 1}" },
                audioUrl = track.url,
                durationMs = track.durationMs,
                sizeBytes = track.sizeBytes,
            )
        }

        val declaredDuration = entry.duration()
        val declaredChapters = entry.firstString(CHAPTER_COUNT_KEYS)?.toIntOrNull() ?: 0

        return Parsed(
            details = BookDetails(
                book = Book(
                    id = bookId,
                    providerId = providerId,
                    title = title,
                    author = entry.firstString(AUTHOR_KEYS)?.takeIf { it.isNotBlank() }
                        ?: entry.firstString(SOURCE_KEYS).orEmpty(),
                    coverUrl = entry.firstString(COVER_KEYS)?.let { absolute(it, requestUrl) },
                    description = entry.firstString(DESCRIPTION_KEYS),
                    durationMs = chapters.sumOf { it.durationMs }.takeIf { it > 0 } ?: declaredDuration,
                    sourceUrl = entry.firstString(PAGE_KEYS)?.let { absolute(it, requestUrl) } ?: bookUrl,
                ),
                chapters = chapters,
            ),
            chaptersHint = chapters.size.takeIf { it > 0 } ?: declaredChapters,
        )
    }

    /** Путь от корня сайта — обычное дело в ответах: достраиваем по адресу запроса. */
    private fun absolute(url: String, requestUrl: String): String =
        if (url.startsWith("/")) {
            runCatching { java.net.URI(requestUrl).resolve(url).toString() }.getOrDefault(url)
        } else {
            url
        }

    private fun JsonObject.duration(): Long {
        for (key in DURATION_KEYS) {
            val raw = firstString(listOf(key)) ?: continue
            val ms = when {
                key.endsWith("_ms") || key.endsWith("ms") -> raw.toLongOrNull()
                raw.contains(':') -> clockToMs(raw)
                // Секунды — самая частая запись длительности в лентах и API.
                else -> raw.toDoubleOrNull()?.let { (it * 1000).toLong() }
            }
            if (ms != null && ms > 0) return ms
        }
        return 0L
    }

    private fun JsonObject.size(): Long = firstString(SIZE_KEYS)?.toLongOrNull() ?: 0L

    /** «10:16:00» и «41:20» — обе записи встречаются одинаково часто. */
    private fun clockToMs(value: String): Long? {
        val parts = value.split(':').map { it.trim().toLongOrNull() ?: return null }
        val seconds = when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> return null
        }
        return seconds * 1000
    }

    /** Ключи ищутся без учёта регистра: сервер может отдавать и camelCase, и snake_case. */
    private fun JsonObject.firstString(keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.let { it as? JsonPrimitive }
            ?.content?.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JsonObject.arrayIn(keys: List<String>): JsonArray? = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonArray
    }

    private fun JsonObject.objectIn(keys: List<String>): JsonObject? = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonObject
    }
}
