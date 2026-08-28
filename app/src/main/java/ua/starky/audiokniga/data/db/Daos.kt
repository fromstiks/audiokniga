package ua.starky.audiokniga.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, addedAt DESC")
    fun observeLibrary(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeBook(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getBook(bookId: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET sourceMode = :mode WHERE id = :bookId")
    suspend fun setSourceMode(bookId: String, mode: Int)

    @Query("UPDATE books SET lastChapterIndex = :chapterIndex, lastPositionMs = :positionMs, lastOpenedAt = :now WHERE id = :bookId")
    suspend fun saveProgress(bookId: String, chapterIndex: Int, positionMs: Long, now: Long)

    @Query("UPDATE books SET favorite = :favorite WHERE id = :bookId")
    suspend fun setFavorite(bookId: String, favorite: Boolean)

    @Query("UPDATE books SET chapterOrder = :order WHERE id = :bookId")
    suspend fun setChapterOrder(bookId: String, order: Int)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("UPDATE chapters SET positionMs = :positionMs, completed = :completed WHERE id = :chapterId")
    suspend fun saveChapterProgress(chapterId: String, positionMs: Long, completed: Boolean)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Transaction
    suspend fun replaceForBook(bookId: String, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        upsertAll(chapters)
    }
}

@Dao
interface CustomSourceDao {
    @Query("SELECT * FROM custom_sources ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<CustomSourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: CustomSourceEntity)

    @Query("UPDATE custom_sources SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE custom_sources SET name = :name, url = :url WHERE id = :id")
    suspend fun update(id: String, name: String, url: String)

    @Query("DELETE FROM custom_sources WHERE id = :id")
    suspend fun delete(id: String)
}

/** Список с числом книг в нём — считать в запросе дешевле, чем тянуть все связки. */
data class PlaylistWithCount(
    val id: String,
    val name: String,
    val createdAt: Long,
    val bookCount: Int,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, p.createdAt AS createdAt, " +
            "(SELECT COUNT(*) FROM playlist_books pb WHERE pb.playlistId = p.id) AS bookCount " +
            "FROM playlists p ORDER BY p.createdAt ASC"
    )
    fun observeAll(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlist_books")
    fun observeMemberships(): Flow<List<PlaylistBookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun rename(playlistId: String, name: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBook(link: PlaylistBookEntity)

    @Query("DELETE FROM playlist_books WHERE playlistId = :playlistId AND bookId = :bookId")
    suspend fun removeBook(playlistId: String, bookId: String)

    @Query("DELETE FROM playlist_books WHERE bookId = :bookId")
    suspend fun removeBookEverywhere(bookId: String)

    @Query("DELETE FROM playlist_books WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistRow(playlistId: String)

    @Transaction
    suspend fun deletePlaylist(playlistId: String) {
        clearPlaylist(playlistId)
        deletePlaylistRow(playlistId)
    }
}
