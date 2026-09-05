package ua.starky.audiohunter.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.starky.audiohunter.data.model.Book
import ua.starky.audiohunter.data.model.BookDetails
import ua.starky.audiohunter.data.model.Chapter
import ua.starky.audiohunter.data.model.SearchResult
import java.net.URLEncoder

/**
 * Свой сервер-агрегатор (см. server/ в корне репозитория): обходит настроенные там сайты
 * и отдаёт JSON с метаданными и прямыми ссылками на MP3. Адрес задаётся в настройках —
 * пока он пуст, источник просто не участвует в поиске.
 *
 * Идентификатор сайта-источника уже зашит в id, который вернул сервер, поэтому здесь
 * он используется как есть — приложению не нужно знать, сколько сайтов подключено на сервере.
 */
class AggregatorApiProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "Мой сервер"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val base = AggregatorConfigHolder.baseUrl
        if (base.isBlank()) return emptyList()

        val url = "$base/search?q=${URLEncoder.encode(query, "UTF-8")}"
        val root = runCatching { json.parseToJsonElement(Http.getString(url)).jsonArray }
            .getOrElse { throw ProviderException("Сервер-агрегатор не ответил: ${it.message}", it) }

        return root.mapNotNull { element -> (element as? JsonObject)?.toSearchResult() }
    }

    override suspend fun details(bookId: String): BookDetails {
        val base = AggregatorConfigHolder.baseUrl
        if (base.isBlank()) throw ProviderException("Адрес сервера-агрегатора не задан в настройках")

        val remoteId = localIdOf(bookId)
        val url = "$base/book?id=${URLEncoder.encode(remoteId, "UTF-8")}"
        val obj = runCatching { json.parseToJsonElement(Http.getString(url)).jsonObject }
            .getOrElse { throw ProviderException("Сервер-агрегатор не ответил: ${it.message}", it) }

        val chaptersJson = runCatching { obj["chapters"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
        val chapters = chaptersJson.mapIndexedNotNull { index, element ->
            val chapter = element as? JsonObject ?: return@mapIndexedNotNull null
            val audioUrl = chapter.str("mp3_url") ?: return@mapIndexedNotNull null
            Chapter(
                id = "$bookId#$index",
                bookId = bookId,
                index = index,
                title = chapter.str("title")?.takeIf { it.isNotBlank() } ?: "Часть ${index + 1}",
                audioUrl = audioUrl,
                durationMs = (chapter.str("duration_sec")?.toLongOrNull() ?: 0L) * 1000L,
            )
        }
        if (chapters.isEmpty()) throw ProviderException("Сервер не отдал ни одного аудиофайла для этой книги")

        val book = Book(
            id = bookId,
            providerId = ID,
            title = obj.str("title") ?: "Без названия",
            author = obj.str("author") ?: "Неизвестный автор",
            coverUrl = obj.str("cover_url"),
            description = obj.str("description"),
            durationMs = chapters.sumOf { it.durationMs },
            sourceUrl = obj.str("url"),
        )
        return BookDetails(book, chapters)
    }

    private fun JsonObject.toSearchResult(): SearchResult? {
        val remoteId = str("id") ?: return null
        return SearchResult(
            Book(
                id = composeBookId(ID, remoteId),
                providerId = ID,
                title = str("title") ?: "Без названия",
                author = str("author") ?: "Неизвестный автор",
                coverUrl = str("cover_url"),
                durationMs = (str("duration_sec")?.toLongOrNull() ?: 0L) * 1000L,
                sourceUrl = str("url"),
            )
        )
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    companion object {
        const val ID = "aggregator"
    }
}
