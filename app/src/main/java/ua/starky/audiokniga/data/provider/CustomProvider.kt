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
    override val shortName: String = source.name.take(12)

    /**
     * Искать источник умеет, только если в адресе есть подстановка `{q}` — иначе непонятно,
     * куда девать запрос. Без неё говорим об этом прямо: раньше мы всё равно лезли по
     * адресу, не находили там аудио и жаловались на «нет аудиофайлов», хотя дело не в этом.
     */
    override suspend fun search(query: String, page: Int): List<SearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        if (!source.isSearchTemplate) throw ProviderException(NEEDS_PLACEHOLDER)

        val url = source.urlFor(q)
        val response = Http.get(url)

        // Свой сервер-агрегатор отвечает списком книг — каждая становится отдельной
        // находкой. Обычный сайт по-прежнему даёт одну книгу из всего, что на странице.
        AggregatorFormat.parseSearch(response, id)?.let { return it }

        val details = fromPage(response, url)
        return listOf(SearchResult(details.book, details.chapters.size))
    }

    /**
     * У книги агрегатора в идентификаторе спрятан адрес, по которому её брать, и — если
     * сервер не дал отдельного адреса — её номер в ответе поиска после решётки.
     */
    override suspend fun details(bookId: String): BookDetails {
        val target = localIdOf(bookId)
        val url = target.substringBefore('#')
        val wanted = target.substringAfter('#', "")

        val response = Http.get(url)
        AggregatorFormat.parseBook(response, id, wanted)?.let { return it }
        return fromPage(response, url)
    }

    /** Открыть источник целиком, не набирая запрос — с экрана «Источники». */
    suspend fun open(): BookDetails {
        val url = source.urlFor("")
        return fromPage(Http.get(url), url)
    }

    private fun fromPage(response: Http.Response, url: String): BookDetails {
        val found = MediaScraper.scrape(response, fallbackTitle = source.name)
        return found.toBookDetails(
            bookId = composeBookId(id, url),
            providerId = id,
            sourceUrl = url,
            fallbackAuthor = source.name,
        )
    }

    companion object {
        /** Текст должен объяснять, что чинить, а не только что сломалось. */
        const val NEEDS_PLACEHOLDER =
            "не участвует в поиске: добавьте {q} в адрес там, где сайт ждёт запрос"

        private const val PREFIX = "custom-"

        /** Идентификатор провайдера попадает в id книги, поэтому двоеточий в нём быть не должно. */
        fun providerIdFor(sourceId: String): String = PREFIX + sourceId.replace(':', '-')

        fun isCustom(providerId: String): Boolean = providerId.startsWith(PREFIX)
    }
}
