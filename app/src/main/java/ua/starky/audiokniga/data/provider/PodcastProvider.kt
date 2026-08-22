package ua.starky.audiokniga.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.SearchResult
import java.net.URLEncoder

/**
 * Каталог подкастов Apple. Ключ не нужен, поиск открытый и работает на русском.
 *
 * Для аудиокниг это неожиданно богатый источник: авторские озвучки и книги,
 * начитанные самими авторами, чаще всего публикуются именно как подкаст-лента.
 * Каталог отдаёт адрес RSS, а дальше главы читает общий разборщик лент.
 */
class PodcastProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "Подкасты и озвучки"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val url = "$BASE/search?media=podcast&entity=podcast&limit=$PAGE_SIZE" +
            "&term=${URLEncoder.encode(q, "UTF-8")}"

        val root = runCatching { json.parseToJsonElement(Http.getString(url)).jsonObject }.getOrNull()
            ?: return emptyList()
        val results = runCatching { root["results"]?.jsonArray }.getOrNull() ?: return emptyList()

        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            // Лента — это и есть идентификатор книги: по ней потом читаются главы.
            val feed = item.str("feedUrl")?.takeIf { it.startsWith("http", true) } ?: return@mapNotNull null
            SearchResult(
                Book(
                    id = composeBookId(ID, feed),
                    providerId = ID,
                    title = item.str("collectionName") ?: item.str("trackName") ?: "Без названия",
                    author = item.str("artistName") ?: "Неизвестный автор",
                    coverUrl = item.str("artworkUrl600") ?: item.str("artworkUrl100"),
                    language = item.str("country"),
                    sourceUrl = item.str("collectionViewUrl") ?: feed,
                ),
                chaptersHint = item.str("trackCount")?.toIntOrNull() ?: 0,
            )
        }
    }

    override suspend fun details(bookId: String): BookDetails {
        val feedUrl = localIdOf(bookId)
        val found = MediaScraper.scrape(Http.get(feedUrl), fallbackTitle = "Подкаст")
        return found.toBookDetails(
            bookId = bookId,
            providerId = ID,
            sourceUrl = feedUrl,
            fallbackAuthor = "Автор не указан",
        )
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" && it.isNotBlank() }

    companion object {
        const val ID = "podcast"
        private const val BASE = "https://itunes.apple.com"
        private const val PAGE_SIZE = 25
    }
}
