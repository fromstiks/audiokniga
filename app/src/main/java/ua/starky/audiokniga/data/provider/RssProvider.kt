package ua.starky.audiokniga.data.provider

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult
import java.io.StringReader

/**
 * Универсальный источник: любая RSS/Atom-лента с вложениями-аудио.
 * Сюда же попадают подкасты и собственные фиды. В поиск передаётся сам URL ленты.
 */
class RssProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "RSS-лента"

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val url = query.trim()
        if (!url.startsWith("http")) return emptyList()
        val feed = parse(Http.getString(url), url)
        return listOf(SearchResult(feed.book, feed.chapters.size))
    }

    override suspend fun details(bookId: String): BookDetails {
        val url = localIdOf(bookId)
        return parse(Http.getString(url), url)
    }

    private suspend fun parse(xml: String, feedUrl: String): BookDetails = withContext(Dispatchers.IO) {
        val bookId = composeBookId(ID, feedUrl)
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var feedTitle = "RSS-лента"
        var feedAuthor = "Неизвестный автор"
        var cover: String? = null
        var description: String? = null

        val chapters = mutableListOf<Chapter>()
        var inItem = false
        var itemTitle: String? = null
        var itemUrl: String? = null
        var itemLength = 0L
        var itemDuration = 0L
        var seenChannelTitle = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item", "entry" -> {
                        inItem = true
                        itemTitle = null; itemUrl = null; itemLength = 0L; itemDuration = 0L
                    }
                    "title" -> {
                        val text = parser.nextText().trim()
                        if (inItem) itemTitle = text
                        else if (!seenChannelTitle) { feedTitle = text; seenChannelTitle = true }
                    }
                    "author", "itunes:author" -> {
                        val text = parser.nextText().trim()
                        if (!inItem && text.isNotBlank()) feedAuthor = text
                    }
                    "description", "itunes:summary" -> {
                        val text = parser.nextText().trim()
                        if (!inItem && description == null) description = text
                    }
                    "itunes:image" -> if (!inItem) cover = parser.getAttributeValue(null, "href")
                    "itunes:duration" -> {
                        val text = parser.nextText().trim()
                        if (inItem) itemDuration = text.toDurationMs()
                    }
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && (type.startsWith("audio") || url.looksLikeAudio())) {
                            itemUrl = url
                            itemLength = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                    val url = itemUrl
                    if (url != null) {
                        chapters += Chapter(
                            id = "$bookId#${chapters.size}",
                            bookId = bookId,
                            index = chapters.size,
                            title = itemTitle ?: "Часть ${chapters.size + 1}",
                            audioUrl = url,
                            durationMs = itemDuration,
                            sizeBytes = itemLength,
                        )
                    }
                    inItem = false
                }
            }
            event = parser.next()
        }

        if (chapters.isEmpty()) throw ProviderException("В ленте нет аудиовложений")

        BookDetails(
            book = Book(
                id = bookId,
                providerId = ID,
                title = feedTitle,
                author = feedAuthor,
                coverUrl = cover,
                description = description?.replace(Regex("<[^>]*>"), " ")?.trim(),
                durationMs = chapters.sumOf { it.durationMs },
                sourceUrl = feedUrl,
            ),
            // В лентах свежее идёт первым, слушать книгу удобнее с начала.
            chapters = chapters.reversed().mapIndexed { index, chapter -> chapter.copy(index = index) },
        )
    }

    private fun String.looksLikeAudio(): Boolean =
        substringBefore('?').lowercase().let {
            it.endsWith(".mp3") || it.endsWith(".m4a") || it.endsWith(".m4b") || it.endsWith(".ogg")
        }

    private fun String.toDurationMs(): Long {
        if (isBlank()) return 0L
        return if (contains(':')) {
            val parts = split(':').mapNotNull { it.trim().toLongOrNull() }
            when (parts.size) {
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                2 -> (parts[0] * 60 + parts[1]) * 1000
                else -> 0L
            }
        } else {
            (toLongOrNull() ?: 0L) * 1000
        }
    }

    companion object {
        const val ID = "rss"
    }
}
