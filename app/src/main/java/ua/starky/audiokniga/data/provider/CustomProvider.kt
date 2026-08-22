package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.CustomSource
import ua.starky.audiokniga.data.model.SearchResult

/**
 * Источник, который пользователь добавил сам: имя плюс адрес.
 *
 * Ничего не знает о конкретном сайте — что вернул сервер, то и разбирает
 * [MediaScraper]: ленту, JSON или обычную страницу со ссылками на файлы.
 */
class CustomProvider(val source: CustomSource) : AudiobookProvider {

    override val id: String = providerIdFor(source.id)
    override val displayName: String = source.name

    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val target = source.urlFor(q)
        val details = load(target)

        // Лента без подстановки {q} — это не поиск, а одна конкретная книга.
        // Показываем её, только если запрос действительно про неё.
        if (!source.isSearchTemplate && !details.book.matches(q)) return emptyList()

        return listOf(SearchResult(details.book, details.chapters.size))
    }

    override suspend fun details(bookId: String): BookDetails = load(localIdOf(bookId))

    /** Открыть источник целиком, не набирая запрос — с экрана «Источники». */
    suspend fun open(): BookDetails = load(source.urlFor(""))

    private suspend fun load(url: String): BookDetails {
        val found = MediaScraper.scrape(Http.get(url), fallbackTitle = source.name)
        return found.toBookDetails(
            bookId = composeBookId(id, url),
            providerId = id,
            sourceUrl = url,
            fallbackAuthor = source.name,
        )
    }

    private fun ua.starky.audiokniga.data.model.Book.matches(query: String): Boolean =
        title.contains(query, ignoreCase = true) || author.contains(query, ignoreCase = true)

    companion object {
        private const val PREFIX = "custom-"

        /** Идентификатор провайдера попадает в id книги, поэтому двоеточий в нём быть не должно. */
        fun providerIdFor(sourceId: String): String = PREFIX + sourceId.replace(':', '-')

        fun isCustom(providerId: String): Boolean = providerId.startsWith(PREFIX)
    }
}
