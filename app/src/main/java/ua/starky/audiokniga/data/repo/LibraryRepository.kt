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
import ua.starky.audiokniga.data.db.BookmarkEntity
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.Bookmark
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter
import ua.starky.audiokniga.data.model.ChapterOrder
import ua.starky.audiokniga.data.model.SearchResult
import ua.starky.audiokniga.data.model.SourceMode
import ua.starky.audiokniga.data.db.CustomSourceEntity
import ua.starky.audiokniga.data.db.PlaylistBookEntity
import ua.starky.audiokniga.data.db.PlaylistEntity
import ua.starky.audiokniga.data.model.CustomSource
import ua.starky.audiokniga.data.model.Playlist
import ua.starky.audiokniga.data.provider.CustomProvider
import ua.starky.audiokniga.data.provider.ProviderRegistry
import ua.starky.audiokniga.data.provider.RssProvider
import java.util.UUID

/** Место, на котором книгу оставили в прошлый раз. */
data class LastPosition(
    val chapterId: String,
    val queueIndex: Int,
    val positionMs: Long,
)

/** Что ответил один источник. Вкладки поиска строятся прямо по этому списку. */
data class SourceOutcome(
    val providerId: String,
    val name: String,
    val shortName: String,
    val results: List<SearchResult> = emptyList(),
    val problem: String? = null,
    val done: Boolean = false,
)

/**
 * Ход поиска. Источники отвечают вразнобой, поэтому состояние отдаётся порциями:
 * список растёт по мере ответов, а счётчик показывает, сколько ещё ждать.
 */
data class SearchProgress(
    val sources: List<SourceOutcome> = emptyList(),
) {
    val answered: Int get() = sources.count { it.done }
    val askedSources: Int get() = sources.size
    val finished: Boolean get() = answered >= askedSources

    /** Всё найденное вместе, без повторов между источниками. */
    val results: List<SearchResult> get() =
        sources.flatMap { it.results }.distinctBy { it.book.id }

    val problems: List<String> get() =
        sources.mapNotNull { source -> source.problem?.let { "${source.name} $it" } }

    /** Сообщение на весь экран показываем только когда искать было негде или никто не ответил. */
    fun summaryError(): String? = when {
        !finished || results.isNotEmpty() -> null
        askedSources == 0 -> "Нет ни одного включённого источника"
        sources.all { it.problem != null } -> "Ни один источник не ответил"
        else -> null
    }
}

class LibraryRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val books = db.bookDao()
    private val chapters = db.chapterDao()
    private val customSources = db.customSourceDao()
    private val playlists = db.playlistDao()
    private val bookmarks = db.bookmarkDao()

    fun observeLibrary(): Flow<List<Book>> = books.observeLibrary().map { list -> list.map { it.toBook() } }

    fun observeBook(bookId: String): Flow<Book?> = books.observeBook(bookId).map { it?.toBook() }

    fun observeChapterOrder(bookId: String): Flow<ChapterOrder> =
        books.observeBook(bookId).map { ChapterOrder.fromOrdinal(it?.chapterOrder ?: 0) }

    suspend fun chapterOrderOf(bookId: String): ChapterOrder = withContext(Dispatchers.IO) {
        ChapterOrder.fromOrdinal(books.getBook(bookId)?.chapterOrder ?: 0)
    }

    suspend fun setChapterOrder(bookId: String, order: ChapterOrder) =
        books.setChapterOrder(bookId, order.ordinal)

    fun observeSourceMode(bookId: String): Flow<SourceMode> =
        books.observeBook(bookId).map { SourceMode.fromOrdinalOrAuto(it?.sourceMode ?: 0) }

    fun observeChapters(bookId: String): Flow<List<Chapter>> =
        chapters.observeChapters(bookId).map { list -> list.map { it.toChapter() } }

    fun observeBookWithChapters(bookId: String): Flow<Pair<Book?, List<Chapter>>> =
        combine(observeBook(bookId), observeChapters(bookId)) { book, list -> book to list }

    /**
     * Опрашивает все подходящие источники параллельно и отдаёт результат порциями.
     *
     * Результат хранится по источникам, а не общей кучей: так видно, кто ответил,
     * кто молчит и у кого что нашлось — и по этому же строятся вкладки на экране.
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

        val state = providers.map { provider ->
            SourceOutcome(
                providerId = provider.id,
                name = provider.displayName,
                shortName = provider.shortName,
            )
        }.toMutableList()
        val guard = Mutex()

        send(SearchProgress(state.toList()))

        providers.mapIndexed { index, provider ->
            launch(Dispatchers.IO) {
                val outcome = runCatching { provider.search(trimmed) }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                guard.withLock {
                    state[index] = state[index].copy(
                        results = outcome.getOrDefault(emptyList()),
                        problem = outcome.exceptionOrNull()?.readableMessage(),
                        done = true,
                    )
                    send(SearchProgress(state.toList()))
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
                lastChapterId = existing?.lastChapterId ?: "",
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                // Повторное открытие книги не должно снимать звёздочку и сбивать порядок.
                favorite = existing?.favorite ?: false,
                chapterOrder = existing?.chapterOrder ?: 0,
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

    /** Кладёт на полку книгу, собранную из файлов на устройстве. */
    suspend fun importLocal(details: BookDetails): String = withContext(Dispatchers.IO) {
        store(details)
        details.book.id
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

    /**
     * Где книгу оставили. [chapterId] здесь главный, а [queueIndex] — только запасной
     * ориентир: номер главы в очереди зависит от выбранного порядка глав и от режима
     * источника, а идентификатор не зависит ни от чего.
     */
    suspend fun saveProgress(bookId: String, chapterId: String, queueIndex: Int, positionMs: Long) =
        books.saveProgress(bookId, chapterId, queueIndex, positionMs, System.currentTimeMillis())

    suspend fun lastPosition(bookId: String): LastPosition = withContext(Dispatchers.IO) {
        val book = books.getBook(bookId)
        LastPosition(
            chapterId = book?.lastChapterId.orEmpty(),
            queueIndex = book?.lastChapterIndex ?: 0,
            positionMs = book?.lastPositionMs ?: 0L,
        )
    }

    /** Книга, открытая последней. Нужна виджету: он умеет запускать её без экранов. */
    suspend fun lastOpenedBookId(): String? = withContext(Dispatchers.IO) { books.lastOpenedId() }

    suspend fun remove(bookId: String) = withContext(Dispatchers.IO) {
        chapters.deleteForBook(bookId)
        playlists.removeBookEverywhere(bookId)
        bookmarks.deleteForBook(bookId)
        books.delete(bookId)
    }

    // ——— Отмеченные моменты ———

    fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarks.observeForBook(bookId).map { list -> list.map { it.toBookmark() } }

    /**
     * [replaceSameLabel] — для метки таймера сна: интересен последний момент засыпания,
     * а не тридцать за месяц. Отметки, поставленные руками, при этом не трогаются.
     */
    suspend fun addBookmark(
        bookId: String,
        chapterId: String,
        chapterTitle: String,
        positionMs: Long,
        label: String,
        replaceSameLabel: Boolean = false,
    ): Bookmark = withContext(Dispatchers.IO) {
        if (replaceSameLabel) bookmarks.deleteLabelled(bookId, label)
        val bookmark = Bookmark(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterId = chapterId,
            chapterTitle = chapterTitle,
            positionMs = positionMs,
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        bookmarks.upsert(
            BookmarkEntity(
                id = bookmark.id,
                bookId = bookmark.bookId,
                chapterId = bookmark.chapterId,
                chapterTitle = bookmark.chapterTitle,
                positionMs = bookmark.positionMs,
                label = bookmark.label,
                createdAt = bookmark.createdAt,
            )
        )
        bookmark
    }

    suspend fun deleteBookmark(id: String) = withContext(Dispatchers.IO) { bookmarks.delete(id) }

    private fun BookmarkEntity.toBookmark() = Bookmark(
        id = id,
        bookId = bookId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        positionMs = positionMs,
        label = label,
        createdAt = createdAt,
    )

    // ——— Избранное и списки ———

    suspend fun setFavorite(bookId: String, favorite: Boolean) = books.setFavorite(bookId, favorite)

    fun observePlaylists(): Flow<List<Playlist>> = playlists.observeAll().map { list ->
        list.map { Playlist(id = it.id, name = it.name, bookCount = it.bookCount) }
    }

    /** Все связки разом: библиотека невелика, а так экран получает всё одним потоком. */
    fun observePlaylistMembership(): Flow<Map<String, Set<String>>> =
        playlists.observeMemberships().map { rows ->
            rows.groupBy { it.playlistId }
                .mapValues { (_, links) -> links.map { it.bookId }.toSet() }
        }

    suspend fun createPlaylist(name: String): Playlist = withContext(Dispatchers.IO) {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Новый список" },
            bookCount = 0,
        )
        playlists.upsert(
            PlaylistEntity(id = playlist.id, name = playlist.name, createdAt = System.currentTimeMillis())
        )
        playlist
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) playlists.rename(playlistId, trimmed)
    }

    suspend fun deletePlaylist(playlistId: String) = playlists.deletePlaylist(playlistId)

    suspend fun setInPlaylist(playlistId: String, bookId: String, inList: Boolean) {
        if (inList) {
            playlists.addBook(
                PlaylistBookEntity(playlistId = playlistId, bookId = bookId, addedAt = System.currentTimeMillis())
            )
        } else {
            playlists.removeBook(playlistId, bookId)
        }
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

    /** Правка уже добавленного источника: имя и адрес. */
    suspend fun updateCustomSource(id: String, name: String, url: String) = withContext(Dispatchers.IO) {
        val address = url.trim()
        if (address.isEmpty()) return@withContext
        val title = name.trim().ifBlank { address.substringAfter("//").substringBefore('/') }
        customSources.update(id, title, address)
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
        favorite = favorite,
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
