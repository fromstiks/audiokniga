package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.SearchResult

/**
 * Любая RSS/Atom-лента с аудиовложениями: подкасты, авторские озвучки, собственные фиды.
 * В строку поиска вставляется сам адрес ленты.
 */
class RssProvider : AudiobookProvider {

    override val id: String = ID
    override val displayName: String = "Ссылка на ленту"

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val url = query.trim()
        if (!url.startsWith("http", ignoreCase = true)) return emptyList()
        val details = load(url)
        return listOf(SearchResult(details.book, details.chapters.size))
    }

    override suspend fun details(bookId: String): BookDetails = load(localIdOf(bookId))

    private suspend fun load(feedUrl: String): BookDetails {
        val bookId = composeBookId(ID, feedUrl)
        val found = MediaScraper.scrape(Http.get(feedUrl), fallbackTitle = feedUrl)
        return found.toBookDetails(
            bookId = bookId,
            providerId = ID,
            sourceUrl = feedUrl,
            fallbackAuthor = "Лента",
        )
    }

    companion object {
        const val ID = "rss"
    }
}

/** Общее превращение находки разборщика в книгу с главами. */
internal fun MediaScraper.Found.toBookDetails(
    bookId: String,
    providerId: String,
    sourceUrl: String,
    fallbackAuthor: String,
): BookDetails {
    val ordered = if (newestFirst) tracks.reversed() else tracks
    val chapters = ordered.mapIndexed { index, track ->
        ua.starky.audiokniga.data.model.Chapter(
            id = "$bookId#$index",
            bookId = bookId,
            index = index,
            title = track.title,
            audioUrl = track.url,
            durationMs = track.durationMs,
            sizeBytes = track.sizeBytes,
        )
    }
    return BookDetails(
        book = Book(
            id = bookId,
            providerId = providerId,
            title = title,
            author = author?.takeIf { it.isNotBlank() } ?: fallbackAuthor,
            coverUrl = cover,
            description = description,
            durationMs = chapters.sumOf { it.durationMs },
            sourceUrl = sourceUrl,
        ),
        chapters = chapters,
    )
}
