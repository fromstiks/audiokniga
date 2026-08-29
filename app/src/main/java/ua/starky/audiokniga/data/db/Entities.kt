package ua.starky.audiokniga.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String?,
    val language: String?,
    val sourceUrl: String?,
    val durationMs: Long,
    /** Выбранный пользователем источник для этой книги. Хранится как ordinal SourceMode. */
    val sourceMode: Int,
    val addedAt: Long,
    val lastOpenedAt: Long,
    /** Индекс главы, на которой остановились. */
    val lastChapterIndex: Int,
    val lastPositionMs: Long,
    val favorite: Boolean = false,
    /** Порядок глав, выбранный для этой книги. Хранится как ordinal ChapterOrder. */
    val chapterOrder: Int = 0,
)

@Entity(
    tableName = "chapters",
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"], unique = true)],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val audioUrl: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val positionMs: Long = 0L,
    val completed: Boolean = false,
)

@Entity(tableName = "custom_sources")
data class CustomSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

/** Связка «книга в списке». Порядок задаётся временем добавления. */
@Entity(
    tableName = "playlist_books",
    primaryKeys = ["playlistId", "bookId"],
    indices = [Index("bookId")],
)
data class PlaylistBookEntity(
    val playlistId: String,
    val bookId: String,
    val addedAt: Long,
)

/** Отмеченный момент в книге. Глава хранится идентификатором: её номер зависит
 *  от выбранного порядка и от режима источника, а идентификатор — нет. */
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val positionMs: Long,
    val label: String,
    val createdAt: Long,
)
