package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.Book
import ua.starky.audiokniga.data.model.SearchResult

/**
 * Отбор и сортировка находок по близости к запросу.
 *
 * Открытые каталоги ищут по всем словам сразу и на многословный запрос отдают либо
 * пустоту, либо всё подряд. Поэтому запрос к ним намеренно расширяется, а лишнее
 * отсеивается уже здесь: иначе на «мир карика» приходят «Духовный мир» и «War and Peace».
 */
object Relevance {

    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

    fun words(query: String): List<String> =
        query.lowercase().split(SEPARATORS).filter { it.length >= 2 }

    /**
     * Основа слова. Русский язык склоняет всё подряд: «карика» в запросе и «Карик»
     * в названии — одно и то же слово, поэтому сравниваем по началу.
     */
    private fun stem(word: String): String = word.take(maxOf(4, word.length - 2))

    /**
     * Ключевое слово запроса — самое длинное. В «мир карика» это «карика»:
     * по нему книга и опознаётся, а «мир» встречается в каждом втором названии.
     */
    private fun keyStem(words: List<String>): String? =
        words.maxByOrNull { it.length }?.let { stem(it) }

    /** Стоит ли вообще показывать находку. */
    fun keep(book: Book, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val key = keyStem(words) ?: return true
        return book.haystack().contains(key)
    }

    fun score(book: Book, query: String, words: List<String>): Int {
        val haystack = book.haystack()
        var score = 0
        if (query.length >= 3 && haystack.contains(query.lowercase())) score += 10
        for (word in words) {
            val s = stem(word)
            if (book.title.lowercase().contains(s)) score += 3
            if (book.author.lowercase().contains(s)) score += 2
        }
        // Совпадение целым словом ценнее совпадения началом.
        for (word in words) if (haystack.contains(word)) score += 1
        return score
    }

    /** Отбрасывает мусор и ставит вперёд то, что ближе к запросу. */
    fun rank(results: List<SearchResult>, query: String): List<SearchResult> {
        val words = words(query)
        if (words.isEmpty()) return results
        return results
            .filter { keep(it.book, words) }
            .sortedByDescending { score(it.book, query, words) }
    }

    private fun Book.haystack(): String = (title + " " + author).lowercase()
}
