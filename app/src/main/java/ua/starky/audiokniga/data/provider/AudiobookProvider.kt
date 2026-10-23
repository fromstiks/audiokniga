package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.BookDetails
import ua.starky.audiokniga.data.model.BookSeries
import ua.starky.audiokniga.data.model.Chapter
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

    /** Строка под названием на экране «Источники». */
    val description: String get() = ""

    /** Можно ли скачивать файлы этого источника на устройство. */
    val supportsDownload: Boolean get() = true

    /**
     * Ссылки на файлы подписаны и со временем перестают работать. У таких источников
     * приложение обновляет адреса глав перед открытием книги, иначе книга, добавленная
     * неделю назад, «внезапно» перестала бы играть.
     */
    val linksExpire: Boolean get() = false

    /**
     * Узнаёт адрес своего сайта. Нужно, чтобы вставленную в поиск ссылку можно было
     * открыть без поиска: [search] получит её как запрос.
     */
    fun acceptsLink(url: String): Boolean = false

    suspend fun search(query: String, page: Int = 1): List<SearchResult>

    /** Полные данные книги вместе со списком глав. */
    suspend fun details(bookId: String): BookDetails

    /**
     * Свежие адреса глав: те же идентификаторы, новые ссылки. [chapters] — то, что
     * уже лежит в базе. `null` — обновлять нечего или источник этого не умеет.
     */
    suspend fun refreshTracks(bookId: String, chapters: List<Chapter>): List<Chapter>? = null

    /**
     * Цикл, в который входит книга, и ссылки на другие его части. `null` — источник
     * такое не различает (большинство) или у этой книги цикла нет.
     */
    suspend fun series(bookId: String): BookSeries? = null
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Собирает глобальный id книги: "provider:localId". */
fun composeBookId(providerId: String, localId: String): String = "$providerId:$localId"

/** Достаёт локальный id из глобального. */
fun localIdOf(bookId: String): String = bookId.substringAfter(':')

fun providerIdOf(bookId: String): String = bookId.substringBefore(':')
