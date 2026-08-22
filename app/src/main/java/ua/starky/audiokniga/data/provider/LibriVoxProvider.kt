package ua.starky.audiokniga.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult
import java.net.URLEncoder

/**
 * LibriVox — аудиокниги в общественном достоянии, открытый JSON API,
 * загрузка файлов разрешена. https://librivox.org/api/info
 */
class LibriVoxProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "LibriVox"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * У LibriVox нет поиска «по вхождению»: без префикса `^` поле сравнивается точно,
     * с ним — «начинается с». Поэтому один запрос почти всегда возвращает пустоту.
     * Идём несколькими попытками и склеиваем результат, убирая повторы.
     */
    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val offset = (page - 1) * PAGE_SIZE
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val attempts = listOf(
            "title=^${q.encode()}",
            "author=^${q.encode()}",
            "title=^${q.substringBefore(' ').encode()}",
            "author=^${q.substringAfterLast(' ').encode()}",
        ).distinct()

        val collected = LinkedHashMap<String, SearchResult>()
        for (attempt in attempts) {
            if (collected.size >= PAGE_SIZE) break
            val url = "$BASE/api/feed/audiobooks/?format=json&limit=$PAGE_SIZE&offset=$offset&$attempt"
            // Одна неудачная попытка не должна ронять весь поиск.
            val body = runCatching { Http.getString(url) }.getOrNull() ?: continue
            val books = parseBooks(body) ?: continue
            for (element in books) {
                val obj = element as? JsonObject ?: continue
                val book = obj.toBook() ?: continue
                collected.getOrPut(book.id) {
                    SearchResult(book, obj.str("num_sections")?.toIntOrNull() ?: 0)
                }
            }
        }
        return collected.values.toList()
    }

    override suspend fun details(bookId: String): BookDetails {
        val localId = localIdOf(bookId)
        val url = "$BASE/api/feed/audiobooks/?format=json&extended=1&id=$localId"
        val books = parseBooks(Http.getString(url))
            ?: throw ProviderException("LibriVox не вернул книгу $localId")
        val obj = books.firstOrNull()?.jsonObject
            ?: throw ProviderException("Книга $localId не найдена")
        val book = obj.toBook() ?: throw ProviderException("Не разобрал данные книги $localId")

        val sections = obj["sections"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: JsonArray(emptyList())
        val chapters = sections.mapIndexedNotNull { index, element ->
            val section = element as? JsonObject ?: return@mapIndexedNotNull null
            val audio = section.str("listen_url")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val number = section.str("section_number")?.toIntOrNull() ?: (index + 1)
            Chapter(
                id = "$bookId#$number",
                bookId = bookId,
                index = index,
                title = section.str("title")?.takeIf { it.isNotBlank() } ?: "Глава $number",
                audioUrl = audio,
                durationMs = (section.str("playtime")?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
        if (chapters.isEmpty()) throw ProviderException("У книги нет доступных файлов")
        return BookDetails(book.copy(durationMs = chapters.sumOf { it.durationMs }), chapters)
    }

    private fun parseBooks(body: String): JsonArray? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root.containsKey("error")) return null
        return runCatching { root["books"]?.jsonArray }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.toBook(): Book? {
        val localId = str("id") ?: return null
        val title = str("title") ?: return null
        val authors = runCatching { this["authors"]?.jsonArray }.getOrNull()
        val author = authors?.firstOrNull()?.jsonObject?.let { a ->
            listOfNotNull(a.str("first_name"), a.str("last_name"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }?.takeIf { it.isNotBlank() } ?: "Неизвестный автор"

        return Book(
            id = composeBookId(ID, localId),
            providerId = ID,
            title = title,
            author = author,
            coverUrl = null,
            description = str("description")?.stripHtml(),
            durationMs = (str("totaltimesecs")?.toLongOrNull() ?: 0L) * 1000L,
            language = str("language"),
            sourceUrl = str("url_librivox"),
        )
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        const val ID = "librivox"
        private const val BASE = "https://librivox.org"
        private const val PAGE_SIZE = 25
    }
}
