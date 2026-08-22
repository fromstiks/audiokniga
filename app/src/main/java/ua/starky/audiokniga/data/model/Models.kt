package ua.starky.audiokniga.data.model

/** Откуда плеер берёт звук. Выбор пользователя, а не догадка приложения. */
enum class SourceMode {
    /** Скачано — играем с устройства, нет — стримим. Поведение по умолчанию. */
    AUTO,

    /** Всегда сеть, даже если файл лежит рядом. */
    ONLINE,

    /** Только то, что уже на устройстве. Сеть не трогаем вообще. */
    OFFLINE;

    companion object {
        fun fromOrdinalOrAuto(value: Int): SourceMode = entries.getOrElse(value) { AUTO }
    }
}

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/** Книга в том виде, в каком её отдаёт провайдер источника. */
data class Book(
    val id: String,
    val providerId: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val durationMs: Long = 0L,
    val language: String? = null,
    val sourceUrl: String? = null,
)

/** Глава = один аудиофайл. */
data class Chapter(
    val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val audioUrl: String,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
)

data class BookDetails(
    val book: Book,
    val chapters: List<Chapter>,
)

/** Глава + её состояние на устройстве. Именно это показывает список в плеере. */
data class ChapterUi(
    val chapter: Chapter,
    val downloadState: DownloadState,
    val downloadProgress: Float,
    val isPlaying: Boolean,
    val isCurrent: Boolean,
)

data class SearchResult(
    val book: Book,
    val chaptersHint: Int = 0,
)

/**
 * Источник, добавленный пользователем прямо в приложении.
 *
 * [url] — либо адрес ленты или страницы с аудио, либо шаблон поиска с подстановкой
 * `{q}` вместо запроса. В первом случае источник открывается целиком с экрана
 * «Источники», во втором участвует в обычном поиске по книгам.
 */
data class CustomSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val addedAt: Long = 0L,
) {
    /** Шаблон поиска отличается от простой ленты наличием подстановки. */
    val isSearchTemplate: Boolean get() = url.contains(QUERY_PLACEHOLDER)

    fun urlFor(query: String): String =
        if (isSearchTemplate) url.replace(QUERY_PLACEHOLDER, java.net.URLEncoder.encode(query, "UTF-8"))
        else url

    companion object {
        const val QUERY_PLACEHOLDER = "{q}"

        /**
         * Разбирает список источников, вставленный одним куском.
         *
         * Строка — это «Название | адрес» либо просто адрес, тогда именем становится
         * домен. Пустые строки и начинающиеся с # пропускаются, чтобы можно было
         * держать список с комментариями.
         */
        fun parseList(text: String): List<Pair<String, String>> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val name: String
                    val url: String
                    val separator = line.indexOf('|')
                    if (separator >= 0) {
                        name = line.substring(0, separator).trim()
                        url = line.substring(separator + 1).trim()
                    } else {
                        // Разделителя нет — значит вся строка адрес, имя берём из домена.
                        url = line
                        name = ""
                    }
                    if (!url.startsWith("http", ignoreCase = true)) return@mapNotNull null
                    val title = name.ifBlank { url.substringAfter("//").substringBefore('/') }
                    title to url
                }
                .distinctBy { it.second }
                .toList()
    }
}
