package ua.starky.audiokniga.data.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult
import java.net.URLEncoder

/**
 * Internet Archive: открытый поиск, открытые метаданные, прямые ссылки на файлы.
 * https://archive.org/developers
 */
class ArchiveOrgProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "Internet Archive"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Internet Archive — самый большой из открытых источников: здесь лежит весь LibriVox,
     * старые радиопостановки и множество любительских озвучек.
     *
     * Ищем в два захода. Сначала по книжным коллекциям — там результат заведомо по теме.
     * Если нашлось мало, повторяем запрос по всему аудио: у неанглоязычных книг коллекция
     * часто проставлена как попало, и узкий фильтр их прячет.
     */
    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val escaped = q.replace("\"", " ")

        val collected = LinkedHashMap<String, SearchResult>()

        val focused = "($escaped) AND mediatype:(audio) AND collection:($BOOK_COLLECTIONS)"
        collected.putAll(runQuery(focused, page))

        if (collected.size < BROADEN_BELOW) {
            val broad = "($escaped) AND mediatype:(audio)"
            for ((id, result) in runQuery(broad, page)) collected.getOrPut(id) { result }
        }

        return collected.values.toList()
    }

    private suspend fun runQuery(q: String, page: Int): Map<String, SearchResult> {
        val url = buildString {
            append("$BASE/advancedsearch.php?q=${q.encode()}")
            append("&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=language&fl[]=runtime")
            append("&rows=$PAGE_SIZE&page=$page&output=json")
        }
        val body = runCatching { Http.getString(url) }.getOrNull() ?: return emptyMap()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val docs = runCatching { root["response"]?.jsonObject?.get("docs")?.jsonArray }.getOrNull()
            ?: return emptyMap()

        val out = LinkedHashMap<String, SearchResult>()
        for (element in docs) {
            val doc = element as? JsonObject ?: continue
            val identifier = doc.str("identifier") ?: continue
            val bookId = composeBookId(ID, identifier)
            out[bookId] = SearchResult(
                Book(
                    id = bookId,
                    providerId = ID,
                    title = doc.firstOf("title") ?: identifier,
                    author = doc.firstOf("creator") ?: "Неизвестный автор",
                    coverUrl = "$BASE/services/img/$identifier",
                    language = doc.firstOf("language"),
                    sourceUrl = "$BASE/details/$identifier",
                )
            )
        }
        return out
    }

    override suspend fun details(bookId: String): BookDetails {
        val identifier = localIdOf(bookId)
        val root = runCatching { json.parseToJsonElement(Http.getString("$BASE/metadata/$identifier")).jsonObject }
            .getOrNull() ?: throw ProviderException("Не удалось прочитать метаданные $identifier")

        val meta = root["metadata"]?.jsonObject ?: throw ProviderException("Нет метаданных у $identifier")
        val server = root.str("server") ?: "archive.org"
        val dir = root.str("dir").orEmpty()

        val files = runCatching { root["files"]?.jsonArray }.getOrNull() ?: JsonArray(emptyList())
        val audio = files.mapNotNull { it as? JsonObject }
            .filter { file ->
                val format = file.str("format").orEmpty().lowercase()
                val name = file.str("name").orEmpty().lowercase()
                format.contains("mp3") || format.contains("ogg") ||
                    name.endsWith(".mp3") || name.endsWith(".m4b") || name.endsWith(".ogg")
            }
            // У одного трека на Archive бывает несколько форматов — берём один файл на трек.
            .distinctBy { it.str("title") ?: it.str("name")?.substringBeforeLast('.') }
            .sortedBy { it.str("track")?.substringBefore('/')?.trim()?.toIntOrNull() ?: Int.MAX_VALUE }

        if (audio.isEmpty()) throw ProviderException("В этой записи нет аудиофайлов")

        val chapters = audio.mapIndexed { index, file ->
            val name = file.str("name").orEmpty()
            Chapter(
                id = "$bookId#$name",
                bookId = bookId,
                index = index,
                title = file.str("title")?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.').replace('_', ' '),
                audioUrl = "https://$server$dir/${name.encodePath()}",
                durationMs = file.str("length").parseDurationMs(),
                sizeBytes = file.str("size")?.toLongOrNull() ?: 0L,
            )
        }

        val book = Book(
            id = bookId,
            providerId = ID,
            title = meta.firstOf("title") ?: identifier,
            author = meta.firstOf("creator") ?: "Неизвестный автор",
            coverUrl = "$BASE/services/img/$identifier",
            description = meta.firstOf("description")?.stripHtml(),
            durationMs = chapters.sumOf { it.durationMs },
            language = meta.firstOf("language"),
            sourceUrl = "$BASE/details/$identifier",
        )
        return BookDetails(book, chapters)
    }

    /** length бывает и "1234.5" (секунды), и "20:34". */
    private fun String?.parseDurationMs(): Long {
        if (this.isNullOrBlank()) return 0L
        return if (contains(':')) {
            val parts = split(':').mapNotNull { it.trim().toDoubleOrNull() }
            when (parts.size) {
                3 -> ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
                2 -> ((parts[0] * 60 + parts[1]) * 1000).toLong()
                else -> 0L
            }
        } else {
            ((toDoubleOrNull() ?: 0.0) * 1000).toLong()
        }
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    /** Поля Archive.org бывают строкой, а бывают массивом строк. */
    private fun JsonObject.firstOf(key: String): String? {
        val element: JsonElement = this[key] ?: return null
        return when (element) {
            is JsonPrimitive -> element.content.takeIf { it != "null" }
            is JsonArray -> element.firstOrNull()?.jsonPrimitive?.content
            else -> null
        }
    }

    private fun String.stripHtml(): String =
        replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

    private fun String.encodePath(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        const val ID = "archive"
        private const val BASE = "https://archive.org"
        private const val PAGE_SIZE = 30

        /** Коллекции, где лежат именно книги, а не музыка и не лекции. */
        private const val BOOK_COLLECTIONS =
            "librivoxaudio OR audio_bookspoetry OR audiobooksandpoetry OR audio_religion OR oldtimeradio"

        /** Ниже этого числа результатов имеет смысл искать шире. */
        private const val BROADEN_BELOW = 8
    }
}
