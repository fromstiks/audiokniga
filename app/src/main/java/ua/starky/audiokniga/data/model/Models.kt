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
    val favorite: Boolean = false,
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

    }
}

/** Пользовательский список книг. */
data class Playlist(
    val id: String,
    val name: String,
    val bookCount: Int,
)

/** Что показывать на полке. */
sealed interface ShelfFilter {
    data object All : ShelfFilter
    data object Favorites : ShelfFilter
    data class InPlaylist(val playlistId: String) : ShelfFilter
}

/**
 * Чем упорядочены главы книги. У файлов с устройства имена и теги часто расходятся,
 * поэтому единственно верного порядка не существует — выбор остаётся за человеком.
 */
enum class ChapterOrder {
    /** Как отдал источник или как лежат файлы в папке. */
    AS_IS,

    /** По числу в названии: «Глава_2» перед «Глава_10». */
    BY_NUMBER,

    /** По названию целиком. */
    BY_TITLE,

    /** По длительности — помогает выловить дубликаты и обрезки. */
    BY_DURATION;

    val label: String
        get() = when (this) {
            AS_IS -> "Как в папке"
            BY_NUMBER -> "По номеру"
            BY_TITLE -> "По названию"
            BY_DURATION -> "По длительности"
        }

    fun next(): ChapterOrder = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromOrdinal(value: Int): ChapterOrder = entries.getOrElse(value) { AS_IS }

        /** Число внутри названия — то, по чему главы обычно и нумеруют. */
        private val NUMBER = Regex("\\d+")

        fun sort(chapters: List<Chapter>, order: ChapterOrder): List<Chapter> = when (order) {
            AS_IS -> chapters
            BY_NUMBER -> chapters.sortedWith(
                compareBy({ numberIn(it.title) ?: Long.MAX_VALUE }, { it.title })
            )
            BY_TITLE -> chapters.sortedBy { it.title.lowercase() }
            BY_DURATION -> chapters.sortedBy { it.durationMs }
        }

        private fun numberIn(title: String): Long? =
            NUMBER.findAll(title).lastOrNull()?.value?.toLongOrNull()
    }
}
