package ua.starky.audiokniga.data.repo

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ua.starky.audiokniga.data.db.AppDatabase
import ua.starky.audiokniga.data.db.BookEntity
import ua.starky.audiokniga.data.db.ChapterEntity
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.SearchResult
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.db.CustomSourceEntity
import ua.starky.audiokniga.data.model.CustomSource
import ua.starky.audiokniga.data.provider.CustomProvider
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.data.provider.RssProvider
import java.util.UUID

/**
 * Ход поиска. Источники отвечают вразнобой, поэтому состояние отдаётся порциями:
 * список растёт по мере ответов, а счётчик показывает, сколько ещё ждать.
 */
data class SearchProgress(
    val results: List<SearchResult> = emptyList(),
    val problems: List<String> = emptyList(),
    val answered: Int = 0,
    val askedSources: Int = 0,
) {
    val finished: Boolean get() = answered >= askedSources

    /** Сообщение на весь экран показываем только когда искать было негде или никто не ответил. */
    fun summaryError(): String? = when {
        !finished || results.isNotEmpty() -> null
        askedSources == 0 -> "Нет ни одного включённого источника"
        problems.size == askedSources -> "Ни один источник не ответил"
        else -> null
    }
}

class LibraryRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val books = db.bookDao()
    private val chapters = db.chapterDao()
    private val customSources = db.customSourceDao()

    fun observeLibrary(): Flow<List<Book>> = books.observeLibrary().map { list -> list.map { it.toBook() } }

    fun observeBook(bookId: String): Flow<Book?> = books.observeBook(bookId).map { it?.toBook() }

    fun observeSourceMode(bookId: String): Flow<SourceMode> =
        books.observeBook(bookId).map { SourceMode.fromOrdinalOrAuto(it?.sourceMode ?: 0) }

    fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapters.observeChapters(bookId).map { list -> list.map { it.toChapter() } }

    fun observeBookWithChapters(bookId: String): Flow<Pair<Book?, List<Chapter>>> =
        combine(observeBook(bookId), observeChapters(bookId)) { book, list -> book to list }

    /**
     * Опрашивает все подходящие источники параллельно и отдаёт результат порциями.
     *
     * Ошибки не проглатываются: если источник не ответил, это видно на экране —
     * иначе «нет сети» и «ничего не нашлось» выглядят одинаково.
     */
    fun search(query: String): Flow<SearchProgress> = channelFlow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            send(SearchProgress())
            return@channelFlow
        }

        // Ссылку разбираем как ленту, слово отправляем во все поисковые источники.
        val providers = if (trimmed.startsWith("http", ignoreCase = true)) {
            ProviderRegistry.all.filter { it.id == RssProvider.ID }
        } else {
            ProviderRegistry.searchable
        }

        val seen = LinkedHashMap<String, SearchResult>()
        val problems = mutableListOf<String>()
        var answered = 0
        val guard = Mutex()

        send(SearchProgress(askedSources = providers.size))

        providers.map { provider ->
            launch(Dispatchers.IO) {
                val outcome = runCatching { provider.search(trimmed) }
                guard.withLock {
                    outcome
                        .onSuccess { found -> found.forEach { seen.putIfAbsent(it.book.id, it) } }
                        .onFailure { error ->
                            // Отмену показывать нельзя: это наш собственный новый запрос.
                            if (error is CancellationException) throw error
                            problems += "${provider.displayName} ${error.readableMessage()}"
                        }
                    answered++
                    send(
                        SearchProgress(
                            results = seen.values.toList(),
                            problems = problems.toList(),
                            answered = answered,
                            askedSources = providers.size,
                        )
                    )
                }
            }
        }.joinAll()
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "не ответил"

    /** Кладёт книгу в библиотеку вместе с оглавлением. */
    suspend fun addToLibrary(bookId: String): BookDetails = withContext(Dispatchers.IO) {
        val details = ProviderRegistry.forBook(bookId).details(bookId)
        store(details)
        details
    }

    /** Запись книги и её глав в базу. Вынесено отдельно: главы приходят разными путями. */
    private suspend fun store(details: BookDetails) {
        val bookId = details.book.id
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
    }

    suspend fun ensureLoaded(bookId: String): List<Chapter> = withContext(Dispatchers.IO) {
        val stored = chapters.getChapters(bookId)
        if (stored.isNotEmpty()) stored.map { it.toChapter() }
        else addToLibrary(bookId).chapters
    }

    suspend fun setSourceMode(bookId: String, mode: SourceMode) = books.setSourceMode(bookId, mode.ordinal)

    suspend fun sourceModeOf(bookId: String): SourceMode = withContext(Dispatchers.IO) {
        SourceMode.fromOrdinalOrAuto(books.getBook(bookId)?.sourceMode ?: 0)
    }

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

    // ——— Свои источники ———

    fun observeCustomSources(): Flow<List<CustomSource>> =
        customSources.observeAll().map { list -> list.map { it.toCustomSource() } }

    suspend fun addCustomSource(name: String, url: String): CustomSource = withContext(Dispatchers.IO) {
        val source = CustomSource(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { url.trim().substringAfter("//").substringBefore('/') },
            url = url.trim(),
            enabled = true,
            addedAt = System.currentTimeMillis(),
        )
        customSources.upsert(source.toEntity())
        source
    }

    /** Добавляет сразу несколько источников. Возвращает, сколько записано. */
    suspend fun addCustomSources(sources: List<Pair<String, String>>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for ((name, url) in sources) {
            runCatching { addCustomSource(name, url) }.onSuccess { added++ }
        }
        added
    }

    suspend fun setCustomSourceEnabled(id: String, enabled: Boolean) =
        customSources.setEnabled(id, enabled)

    suspend fun removeCustomSource(id: String) = customSources.delete(id)

    /** Открывает свой источник целиком и кладёт его в библиотеку как книгу. */
    suspend fun openCustomSource(source: CustomSource): String = withContext(Dispatchers.IO) {
        val provider = ProviderRegistry.customProviders.firstOrNull { it.source.id == source.id }
            ?: CustomProvider(source)
        val details = provider.open()
        store(details)
        details.book.id
    }

    private fun CustomSourceEntity.toCustomSource() =
        CustomSource(id = id, name = name, url = url, enabled = enabled, addedAt = addedAt)

    private fun CustomSource.toEntity() =
        CustomSourceEntity(id = id, name = name, url = url, enabled = enabled, addedAt = addedAt)

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
