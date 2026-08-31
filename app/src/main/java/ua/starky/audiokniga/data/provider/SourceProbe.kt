package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.CustomSource

/**
 * Проверка источника: что на самом деле лежит по адресу.
 *
 * Без неё разбираться приходится вслепую — «не нашлось аудиофайлов» одинаково звучит
 * и для мёртвого домена, и для страницы, где аудио просто нет. Проверка отвечает
 * конкретно и подсказывает, что делать дальше.
 */
object SourceProbe {

    /** Запрос-пустышка для проверки шаблонов поиска. */
    private const val SAMPLE_QUERY = "тест"

    /** Распространённые адреса поиска. Ими подсказываем, а не угадываем молча. */
    val SEARCH_PATTERNS: List<Pair<String, String>> = listOf(
        "WordPress" to "?s={q}",
        "Обычный" to "?q={q}",
        "/search" to "search?q={q}",
        "DataLife" to "index.php?do=search&subaction=search&story={q}",
    )

    data class Report(
        val ok: Boolean,
        val text: String,
    )

    suspend fun probe(source: CustomSource): Report {
        val url = source.urlFor(SAMPLE_QUERY)

        val response = try {
            Http.get(url)
        } catch (e: ProviderException) {
            return Report(false, "Адрес ${e.message ?: "не отвечает"}")
        } catch (e: Throwable) {
            return Report(false, "Адрес не отвечает: ${e.message ?: "неизвестная ошибка"}")
        }

        // Свой сервер-агрегатор отвечает списком книг, а не файлами одной страницы —
        // и проверка должна показывать именно книги, иначе выглядит как неудача.
        val aggregated = runCatching { AggregatorFormat.parseSearch(response, "probe") }.getOrNull()
        if (!aggregated.isNullOrEmpty()) {
            val chapters = aggregated.sumOf { it.chaptersHint }
            return Report(
                true,
                "Агрегатор ответил: книг ${aggregated.size}, глав $chapters · " +
                    "«${aggregated.first().book.title.take(40)}»",
            )
        }

        val found = runCatching { MediaScraper.scrape(response, fallbackTitle = source.name) }.getOrNull()
        if (found != null) {
            val what = if (source.isSearchTemplate) "По запросу нашлось" else "Нашлось"
            return Report(true, "$what ${found.tracks.size} файлов · «${found.title.take(40)}»")
        }

        // Ответ есть, аудио нет — дальше важно, что это за страница. Прежде всего
        // стоит проверить, не подменили ли настоящую страницу проверкой на робота
        // или пустым каркасом для JavaScript: тогда разбирать было нечего с самого
        // начала, и дело не в том, что на сайте нет аудио.
        MediaScraper.blockReason(response)?.let { return Report(false, it.replaceFirstChar { c -> c.uppercase() }) }

        val body = response.body.take(2000).lowercase()
        val looksLikeHtml = body.contains("<html") || response.contentType.contains("html")
        return when {
            !source.isSearchTemplate && looksLikeHtml -> Report(
                false,
                "Страница открывается, но прямых ссылок на аудио на ней нет. " +
                    "Обычно это витрина сайта: ссылки ведут в карточки книг, а файлы лежат уже там.",
            )
            looksLikeHtml -> Report(
                false,
                "Поиск отвечает, но выдаёт список книг, а не файлы. " +
                    "Такой источник приложение сыграть не сможет.",
            )
            else -> Report(false, "Ответ получен, но аудио в нём не нашлось")
        }
    }
}
