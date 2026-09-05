package ua.starky.audiohunter.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.starky.audiohunter.data.db.AppDatabase
import ua.starky.audiohunter.data.db.BookEntity
import ua.starky.audiohunter.data.db.ChapterEntity
import ua.starky.audiohunter.data.model.Book
import ua.starky.audiohunter.data.model.BookDetails
import ua.starky.audiohunter.data.model.Chapter
import ua.starky.audiohunter.data.model.SearchResult
import ua.starky.audiohunter.data.model.SourceMode
import ua.starky.audiohunter.data.provider.ProviderRegistry

class LibraryRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val books = db.bookDao()
    private val chapters = db.chapterDao()

    fun observeLibrary(): Flow<List<Book>> = books.observeLibrary().map { list -> list.map { it.toBook() } }

    fun observeBook(bookId: String): Flow<Book?> = books.observeBook(bookId).map { it?.toBook() }

    fun observeSourceMode(bookId: String): Flow<SourceMode> =
        books.observeBook(bookId).map { SourceMode.fromOrdinalOrAuto(it?.sourceMode ?: 0) }

    fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapters.observeChapters(bookId).map { list -> list.map { it.toChapter() } }

    fun observeBookWithChapters(bookId: String): Flow<Pair<Book?, List<Chapter>>> =
        combine(observeBook(bookId), observeChapters(bookId)) { book, list -> book to list }

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        // Ссылку считаем RSS-лентой, слово — поисковым запросом к остальным источникам.
        val providers = if (trimmed.startsWith("http")) {
            ProviderRegistry.all.filter { it.id == "rss" }
        } else {
            ProviderRegistry.searchable
        }

        providers.flatMap { provider ->
            runCatching { provider.search(trimmed) }.getOrElse { emptyList() }
        }
    }

    /** Кладёт книгу в библиотеку вместе с оглавлением. */
    suspend fun addToLibrary(bookId: String): BookDetails = withContext(Dispatchers.IO) {
        val details = ProviderRegistry.forBook(bookId).details(bookId)
        val existing = books.getBook(bookId)
        val now = System.currentTimeMillis()
        books.upsert(
            BookEntity(
                id = details.book.id,
                providerId = details.book.providerId,
                title = details.book.title,
                author = details.book.author,
                coverUrl = details.book.coverUrl,
                description = details.book.description,
                language = details.book.language,
                sourceUrl = details.book.sourceUrl,
                durationMs = details.book.durationMs,
                sourceMode = existing?.sourceMode ?: SourceMode.AUTO.ordinal,
                addedAt = existing?.addedAt ?: now,
                lastOpenedAt = now,
                lastChapterIndex = existing?.lastChapterIndex ?: 0,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
            )
        )
        chapters.replaceForBook(
            bookId,
            details.chapters.map { chapter ->
                ChapterEntity(
                    id = chapter.id,
                    bookId = bookId,
                    chapterIndex = chapter.index,
                    title = chapter.title,
                    audioUrl = chapter.audioUrl,
                    durationMs = chapter.durationMs,
                    sizeBytes = chapter.sizeBytes,
                )
            }
        )
        details
    }

    suspend fun ensureLoaded(bookId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val stored = chapters.getChapters(bookId)
        if (stored.isNotEmpty()) stored.map { it.toChapter() }
        else addToLibrary(bookId).chapters
    }

    suspend fun setSourceMode(bookId: String, mode: SourceMode) = books.setSourceMode(bookId, mode.ordinal)

    suspend fun saveProgress(bookId: String, chapterIndex: Int, positionMs: Long) =
        books.saveProgress(bookId, chapterIndex, positionMs, System.currentTimeMillis())

    suspend fun lastPosition(bookId: String): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val book = books.getBook(bookId)
        (book?.lastChapterIndex ?: 0) to (book?.lastPositionMs ?: 0L)
    }

    suspend fun remove(bookId: String) = withContext(Dispatchers.IO) {
        chapters.deleteForBook(bookId)
        books.delete(bookId)
    }

    private fun BookEntity.toBook() = Book(
        id = id,
        providerId = providerId,
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = description,
        durationMs = durationMs,
        language = language,
        sourceUrl = sourceUrl,
    )

    private fun ChapterEntity.toChapter() = Chapter(
        id = id,
        bookId = bookId,
        index = chapterIndex,
        title = title,
        audioUrl = audioUrl,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )
}
