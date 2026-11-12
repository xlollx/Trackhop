package com.xlollx.songport.sync

import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * schema.org data a web page carries for search engines (`<script type="application/ld+json">`):
 * the products of a shop page ([Merch]) and the events of a tour page ([SiteConcerts]). Read as the
 * page gives it; a block that is not JSON is skipped.
 */
internal object JsonLd {
    private val BLOCK = Regex("""<script\b[^>]*type\s*=\s*["']?application/ld\+json["']?[^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Every object of the page's JSON-LD blocks: the top ones, those of a `@graph` and of a list's items, in page order. */
    fun nodes(html: String): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun walk(e: JsonElement?, depth: Int) {
            if (depth > 6 || out.size > 500) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    out += e
                    walk(e["@graph"], depth + 1)
                    // A list of products or events: each one's item, or the element itself.
                    e["itemListElement"].arr.forEach { li -> walk(li["item"] ?: li, depth + 1) }
                    // A series' or a product group's own events and variants.
                    walk(e["subEvent"], depth + 1)
                    walk(e["hasVariant"], depth + 1)
                }
                else -> {}
            }
        }
        for (m in BLOCK.findAll(html)) {
            val raw = m.groupValues[1].trim().removePrefix("<!--").removeSuffix("-->").removePrefix("//<![CDATA[").removeSuffix("//]]>").trim()
            walk(runCatching { parseJson(raw) }.getOrNull(), 0)
        }
        return out
    }

    /** The node's types without the schema.org prefix ("MusicEvent", "Product"). */
    fun types(o: JsonObject): Set<String> {
        val t = o["@type"]
        val all = if (t is JsonArray) t.mapNotNull { it.str } else listOfNotNull(t.str)
        return all.map { it.substringAfterLast('/').substringAfterLast(':') }.toSet()
    }

    /** A text value: a string, or the first of a list, or an object's name. */
    fun text(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.str
        is JsonArray -> e.firstNotNullOfOrNull { text(it) }
        is JsonObject -> e["name"].str ?: e["@value"].str
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** An address or picture value: a string, an object's url (or contentUrl, @id), or the first of a list. */
    fun url(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.str
        is JsonArray -> e.firstNotNullOfOrNull { url(it) }
        is JsonObject -> e["url"].str ?: e["contentUrl"].str ?: e["@id"].str
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** [href] made absolute against [base] (a page's address); null when it is not a web address. */
    fun absolute(href: String?, base: String): String? {
        val h = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val u = runCatching { URI(base).resolve(h.replace(" ", "%20")).toString() }.getOrNull() ?: return null
        return webUrl(u).takeIf { it.isNotEmpty() }
    }

    /** A list of offers or a single one (an AggregateOffer's own offers too). */
    fun offers(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap { offers(it) }
        is JsonObject -> listOf(e) + e["offers"].let { if (it != null && it !== e) offers(it) else emptyList() }
        else -> emptyList()
    }
}
