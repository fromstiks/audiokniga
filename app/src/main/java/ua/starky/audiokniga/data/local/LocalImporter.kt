package ua.starky.audiokniga.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.Chapter

/**
 * Чтение аудиокниг с самого устройства.
 *
 * Ни сети, ни источников: пользователь показывает папку или файлы, приложение читает
 * их теги и складывает книгу с главами. Доступ берётся через системный выбор файлов,
 * поэтому разрешение на «все файлы» не нужно.
 */
object LocalImporter {

    const val PROVIDER_ID = "local"

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "ogg", "oga", "opus", "aac", "wav", "flac", "mp4",
    )

    /** Папка целиком — одна книга, файлы внутри становятся главами по порядку имён. */
    suspend fun fromFolder(context: Context, treeUri: Uri): BookDetails? = withContext(Dispatchers.IO) {
        persist(context, treeUri)
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext null

        val files = tree.listFiles()
            .filter { it.isFile && it.name.isAudio() }
            .sortedWith(compareBy(NaturalOrder) { it.name.orEmpty() })
        if (files.isEmpty()) return@withContext null

        build(
            context = context,
            bookId = "$PROVIDER_ID:${treeUri}",
            fallbackTitle = tree.name?.cleanUp() ?: "Книга с устройства",
            sourceUrl = treeUri.toString(),
            items = files.map { it.uri to it.name.orEmpty() },
        )
    }

    /** Выбранные вручную файлы — тоже одна книга, в порядке выбора. */
    suspend fun fromFiles(context: Context, uris: List<Uri>): BookDetails? = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext null
        uris.forEach { persist(context, it) }

        val items = uris.map { uri -> uri to (displayName(context, uri) ?: uri.lastPathSegment.orEmpty()) }
            .filter { it.second.isAudio() }
        if (items.isEmpty()) return@withContext null

        // Имя книги берём у первого файла: общего родителя у произвольного выбора нет.
        val title = items.first().second.substringBeforeLast('.').cleanUp()
        build(
            context = context,
            // Список файлов может быть любым, поэтому опираемся на первый адрес.
            bookId = "$PROVIDER_ID:${items.first().first}",
            fallbackTitle = title.ifBlank { "Книга с устройства" },
            sourceUrl = items.first().first.toString(),
            items = items,
        )
    }

    private fun build(
        context: Context,
        bookId: String,
        fallbackTitle: String,
        sourceUrl: String,
        items: List<Pair<Uri, String>>,
    ): BookDetails {
        var album: String? = null
        var artist: String? = null

        val chapters = items.mapIndexed { index, (uri, name) ->
            val tags = readTags(context, uri)
            if (album == null) album = tags.album
            if (artist == null) artist = tags.artist
            Chapter(
                id = "$bookId#$index",
                bookId = bookId,
                index = index,
                title = tags.title?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.').cleanUp().ifBlank { "Часть ${index + 1}" },
                audioUrl = uri.toString(),
                durationMs = tags.durationMs,
            )
        }

        return BookDetails(
            book = Book(
                id = bookId,
                providerId = PROVIDER_ID,
                title = album?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                author = artist?.takeIf { it.isNotBlank() } ?: "С устройства",
                durationMs = chapters.sumOf { it.durationMs },
                sourceUrl = sourceUrl,
            ),
            chapters = chapters,
        )
    }

    private data class Tags(
        val title: String?,
        val album: String?,
        val artist: String?,
        val durationMs: Long,
    )

    /** Теги читаются самим Android; отсутствие тегов — обычное дело, не ошибка. */
    private fun readTags(context: Context, uri: Uri): Tags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Tags(null, null, null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Без этого доступ к файлам пропадёт после перезапуска приложения,
     * и книга на полке перестанет играть.
     */
    private fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()

    private fun String?.isAudio(): Boolean {
        val name = this?.substringAfterLast('.', "")?.lowercase() ?: return false
        return name in AUDIO_EXTENSIONS
    }

    /** «01_glava-pervaya» читается плохо, а показывать это пользователю. */
    private fun String.cleanUp(): String =
        replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * Естественный порядок: «Глава 2» должна идти перед «Глава 10».
     * Обычное сравнение строк ставит их наоборот и ломает порядок глав.
     */
    private object NaturalOrder : Comparator<String> {
        private val CHUNK = Regex("\\d+|\\D+")

        override fun compare(a: String, b: String): Int {
            val left = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
            val right = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]
                val y = right[i]
                val result = if (x.first().isDigit() && y.first().isDigit()) {
                    (x.toLongOrNull() ?: 0L).compareTo(y.toLongOrNull() ?: 0L)
                } else {
                    x.compareTo(y)
                }
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }
    }
}
