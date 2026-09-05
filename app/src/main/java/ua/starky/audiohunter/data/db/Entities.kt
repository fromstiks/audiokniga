package ua.starky.audiohunter.data.db

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
