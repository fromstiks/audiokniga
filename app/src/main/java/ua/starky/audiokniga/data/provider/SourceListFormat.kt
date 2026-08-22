package ua.starky.audiokniga.data.provider

import android.util.Xml
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Разбор списка источников: имя плюс адрес, много штук за раз.
 *
 * Список приходит либо вставкой в поле, либо файлом, и в каком он виде — заранее
 * неизвестно, поэтому поддержаны три формы:
 *
 * - **OPML** — стандарт выгрузки подписок, его отдают почти все читалки лент;
 * - **JSON** — массив или объект, дерево обходится целиком;
 * - **обычный текст** — строка на источник, «Название | адрес» или просто адрес.
 */
object SourceListFormat {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val URL_KEYS = listOf("xmlurl", "url", "feedurl", "address", "link", "href", "site")
    private val NAME_KEYS = listOf("text", "title", "name", "label", "displayname")

    fun parse(text: String): List<Pair<String, String>> {
        val head = text.take(400).trimStart()
        val parsed = when {
            head.startsWith("<") -> fromOpml(text)
            head.startsWith("{") || head.startsWith("[") -> fromJson(text)
            else -> fromLines(text)
        }
        return parsed
            .mapNotNull { (name, url) ->
                val address = url.trim()
                if (!address.startsWith("http", ignoreCase = true)) return@mapNotNull null
                val title = name.trim().ifBlank { address.substringAfter("//").substringBefore('/') }
                title to address
            }
            .distinctBy { it.second }
    }

    /** Сколько источников распознано — нужно, чтобы подписать кнопку до нажатия. */
    fun count(text: String): Int = parse(text).size

    // ——— OPML ———

    private fun fromOpml(xml: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                val url = URL_KEYS.firstNotNullOfOrNull { key ->
                    parser.attributeIgnoringCase(key)?.takeIf { it.startsWith("http", true) }
                }
                if (url != null) {
                    val name = NAME_KEYS.firstNotNullOfOrNull { parser.attributeIgnoringCase(it) }.orEmpty()
                    out += name to url
                }
            }
            event = parser.next()
        }
        return out
    }

    /** Имена полей в OPML пишут кто во что горазд: xmlUrl, xmlurl, XMLURL. */
    private fun XmlPullParser.attributeIgnoringCase(name: String): String? {
        for (index in 0 until attributeCount) {
            if (getAttributeName(index).equals(name, ignoreCase = true)) {
                return getAttributeValue(index)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ——— JSON ———

    private fun fromJson(body: String): List<Pair<String, String>> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        collect(root, out)
        return out
    }

    private fun collect(element: JsonElement, into: MutableList<Pair<String, String>>) {
        when (element) {
            is JsonArray -> element.forEach { collect(it, into) }

            is JsonObject -> {
                val url = URL_KEYS.firstNotNullOfOrNull { key ->
                    element.stringIgnoringCase(key)?.takeIf { it.startsWith("http", true) }
                }
                if (url != null) {
                    val name = NAME_KEYS.firstNotNullOfOrNull { element.stringIgnoringCase(it) }.orEmpty()
                    into += name to url
                }
                element.values.forEach { collect(it, into) }
            }

            else -> Unit
        }
    }

    private fun JsonObject.stringIgnoringCase(key: String): String? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value?.let { it as? JsonPrimitive }
            ?.content?.takeIf { it.isNotBlank() && it != "null" }

    // ——— Обычный текст ———

    private fun fromLines(text: String): List<Pair<String, String>> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val separator = line.indexOfFirst { it == '|' || it == '\t' }
                if (separator >= 0) {
                    line.substring(0, separator) to line.substring(separator + 1)
                } else {
                    "" to line
                }
            }
            .toList()

    /** Обратная операция: список в текст, который потом можно вставить или сохранить. */
    fun format(sources: List<Pair<String, String>>): String =
        sources.joinToString("\n") { (name, url) -> "$name | $url" }
}
