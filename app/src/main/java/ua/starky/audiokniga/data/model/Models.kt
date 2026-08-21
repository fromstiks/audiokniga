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
