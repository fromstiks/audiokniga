package ua.starky.audiokniga.data.provider

/**
 * Готовые источники: открытые ленты, которые проверены и отдают аудио вложениями.
 *
 * Смысл не в том, чтобы заменить поиск, а в том, чтобы экран «Источники» не начинался
 * с пустого поля и не заканчивался десятком мёртвых адресов. Каждый адрес здесь —
 * обычная RSS-лента, как у любого подкаста: её можно открыть в браузере и увидеть
 * те же главы, что и в приложении.
 *
 * Чего здесь намеренно нет: каталогов сайтов. Страница каталога — это список книг,
 * а не файлы, и приложение сыграть её не сможет: ему нужна страница одной книги
 * или лента с вложениями. Почему адреса вида «/feed/rss/all» не работают —
 * в docs/sources.md.
 */
data class SourcePreset(
    val name: String,
    val url: String,
    /** Короткая подпись под названием: сколько глав и что за книга. */
    val note: String,
)

object SourcePresets {

    val all: List<SourcePreset> = listOf(
        SourcePreset(
            name = "Аудиокниги 3000",
            url = "https://anchor.fm/s/4a6188d0/podcast/rss",
            note = "9 рассказов · классика",
        ),
        SourcePreset(
            name = "Русские сказки",
            url = "http://rss.castbox.fm/everest/c423f7b3f28340c7b3ba05d8680aedc4.xml",
            note = "24 сказки",
        ),
        SourcePreset(
            name = "Рассказы Чехова",
            url = "http://rss.castbox.fm/everest/739bace27b7b4647a74978b8f12eb79b.xml",
            note = "8 рассказов",
        ),
        SourcePreset(
            name = "Аудиокниги slovo365",
            url = "https://slovo365.org/feed/podcast/audiobooks/",
            note = "105 книг · классика",
        ),
        // ЛитРПГ на русском в открытых лентах не нашлось: жанр продаётся на
        // Author.Today и в подписках, а бесплатные озвучки — почти всегда чужие
        // книги без разрешения. Ниже — авторские сериалы на английском, они
        // выложены самими авторами и отдают главы вложениями.
        SourcePreset(
            name = "Seeker (EN)",
            url = "https://anchor.fm/s/f7a60b38/podcast/rss",
            note = "20 глав · LitRPG",
        ),
        SourcePreset(
            name = "Soul Corruption (EN)",
            url = "https://rss.pdrl.fm/63857e/feeds.libsyn.com/551982/rss/?redirect=false",
            note = "264 главы · LitRPG",
        ),
        SourcePreset(
            name = "Worlds Beneath (EN)",
            url = "https://anchor.fm/s/fb71d490/podcast/rss",
            note = "22 главы · LitRPG",
        ),
    )

    /**
     * Ленты, которых ещё нет в списке. Сравниваем без учёта регистра и хвостового
     * слеша: один и тот же адрес человек и лента могут записать по-разному.
     */
    fun missing(existing: Collection<String>): List<SourcePreset> {
        val taken = existing.map { it.canonical() }.toSet()
        return all.filter { it.url.canonical() !in taken }
    }

    private fun String.canonical(): String = trim().trimEnd('/').lowercase()
}
