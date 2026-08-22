package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.SearchResult

/**
 * Точка расширения приложения. Новый сайт-источник = один класс, реализующий этот
 * интерфейс, плюс строчка в [ProviderRegistry]. Ядро (плеер, загрузки, база) о
 * конкретных сайтах ничего не знает.
 *
 * Важно: сюда подключаются только источники, которые разрешают загрузку своих файлов
 * (открытые API, публичное достояние, собственные RSS-ленты, свои файлы). Обход DRM
 * или защиты платных сервисов провайдером быть не может.
 */
interface AudiobookProvider {
    /** Стабильный идентификатор, попадает в id книги: "librivox:12345". */
    val id: String

    /** Как источник называется в интерфейсе. */
    val displayName: String

    /** Подпись на вкладке поиска. Места там мало, поэтому по умолчанию — первое слово. */
    val shortName: String get() = displayName.substringBefore(' ').take(12)

    /** Можно ли скачивать файлы этого источника на устройство. */
    val supportsDownload: Boolean get() = true

    suspend fun search(query: String, page: Int = 1): List<SearchResult>

    /** Полные данные книги вместе со списком глав. */
    suspend fun details(bookId: String): BookDetails
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Собирает глобальный id книги: "provider:localId". */
fun composeBookId(providerId: String, localId: String): String = "$providerId:$localId"

/** Достаёт локальный id из глобального. */
fun localIdOf(bookId: String): String = bookId.substringAfter(':')

fun providerIdOf(bookId: String): String = bookId.substringBefore(':')
