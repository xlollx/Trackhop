package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * The merch an artist sells in their own shops (Records and merch, "Merch"): t-shirts, hoodies,
 * posters, records... with a picture, the price and a link to the product's page. Read only from
 * the public storefront data shops give every shopper, and only from the shops MusicBrainz links
 * to the artist ([Records.parseLinks]):
 *
 * - an official shop on Shopify (a mail-order shop or the official site): its public catalogue,
 *   `/products.json` (first page), and the shop's currency from `/meta.json`;
 * - the artist's Bandcamp: the merch page, `/merch`.
 *
 * Each shop's robots.txt is read first and honoured (a shop that closes those pages is skipped),
 * at most one request a second goes to a host, every request names the app, and what is found is
 * kept a day ([TTL_MS]). Links only: no login, no cart, no affiliate tag.
 */
object Merch {
    /** Items shown per artist. */
    const val PER_ARTIST = 12
    const val TTL_MS = 24L * 3_600_000
    /** Products read from a Shopify shop: the first page of its catalogue. */
    const val SHOPIFY_PAGE = 30
    /** Shops tried per artist; the first that answers with items is used. */
    const val MAX_SHOPS = 3

    enum class Source { SHOP, BANDCAMP }

    /**
     * One thing for sale: [type] as the shop names it ("T-Shirt", "Vinyl"; "" when none),
     * [image] a small picture ("" when none), [price] the lowest of its variants, in [currency]
     * (null when the shop does not say), [url] the product's page.
     */
    @Serializable
    data class Item(
        val source: Source,
        val title: String,
        val type: String = "",
        val image: String = "",
        val price: Double? = null,
        val currency: String? = null,
        val url: String,
    ) {
        /** The record this is (vinyl, CD, cassette: shown with the records, not the merch); null for other goods. */
        val format: Records.Format? get() = recordFormat(type, title)
    }

    // Tests send every host to a local server ("http://127.0.0.1:port/<host>") and set the gap to 0.
    @Volatile internal var hostBase: (String) -> String = { "https://$it" }
    @Volatile internal var hostGap = 1_000L
    private val paces = ConcurrentHashMap<String, Pacer>()

    /** Big retailers and sites that are not an artist's own shop: never asked. */
    private val NOT_SHOPS = listOf(
        "amazon.", "ebay.", "discogs.com", "bandcamp.com", "apple.com", "cdjapan.co.jp", "yesasia.com", "target.com",
        "urbanoutfitters.com", "walmart.com", "hmv.", "jpc.de", "tower.jp", "spotify.com", "youtube.com",
    )

    private val serializer = ListSerializer(Item.serializer())

    /**
     * The merch of [artist] from the shops among [links] (MusicBrainz's), at most [PER_ARTIST];
     * empty when none answers. Never throws: a shop that fails is skipped.
     */
    suspend fun find(env: Env, artist: String, links: List<Records.Link>): List<Item> {
        // "merch2": kept before records and goods were counted apart.
        val ck = "merch2|${Records.norm(artist)}"
        Records.cached(env, ck, TTL_MS) { json.decodeFromString(serializer, it) }?.let { return it }
        val shop = runCatching { shopify(artist, shopHosts(links)) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        val bc = links.firstOrNull { it.kind == Records.Kind.BANDCAMP }?.let { host(it.url) }
        val band = if (bc == null) emptyList() else runCatching { bandcamp(bc) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        // Records and other goods counted apart: a shop full of records still shows its t-shirts.
        val items = mix(shop.filter { it.format == null }, band.filter { it.format == null }, PER_ARTIST) +
            mix(shop.filter { it.format != null }, band.filter { it.format != null }, PER_ARTIST)
        Records.remember(env, ck, json.encodeToString(serializer, items))
        return items
    }

    /** A type naming other goods: it decides even when the title names a record ("LP Cover T-Shirt"). */
    private val GOODS = Regex("""\b(shirts?|t-shirts?|tees?|hoodies?|sweat\w*|apparel|clothing|posters?|prints?|bags?|totes?|stickers?|pins?|patch(es)?|hats?|beanies?|caps?|mugs?|socks?|scarf|jackets?|jewelry|jewellery|accessor(y|ies)|books?)\b""")
    /** Things for records, not records: the title names vinyl but it is not one. */
    private val ACCESSORY = Regex("""\b(slipmats?|slip mats?|crates?|cleaner|cleaning|brush|sleeves?|turntables?|wallets?|frames?|stand|holder|storage|tote)\b""")
    private val VINYL = Regex("""\b(vinyl|lp|2lp|3lp|2xlp|lps)\b|\b(7|10|12) ?("|”|''|-inch|inch)""")
    private val CD = Regex("""\b(cd|cds|2cd|compact disc)\b""")
    private val CASSETTE = Regex("""\b(cassettes?|tapes?)\b""")

    /**
     * The record an item is, from the shop's [type] ("Vinyl LP", "Record/Vinyl", "Compact Disc (CD)",
     * "Cassette") or, when the type is empty or vague ("Other", "Bundle"), its [title] ("Album – Black
     * Vinyl", "LP + T-Shirt Bundle"); null for other goods (a t-shirt, a poster, a slipmat).
     */
    fun recordFormat(type: String, title: String): Records.Format? {
        fun of(s: String) = when {
            CASSETTE.containsMatchIn(s) -> Records.Format.CASSETTE
            VINYL.containsMatchIn(s) -> Records.Format.VINYL
            CD.containsMatchIn(s) -> Records.Format.CD
            else -> null
        }
        val t = type.lowercase()
        if (!ACCESSORY.containsMatchIn(t)) of(t)?.let { return it }
        if (GOODS.containsMatchIn(t) || ACCESSORY.containsMatchIn(t)) return null
        val name = title.lowercase()
        return if (ACCESSORY.containsMatchIn(name)) null else of(name)
    }

    /** Both sources together, [n] at most: each keeps at least half when the other has enough. */
    internal fun mix(a: List<Item>, b: List<Item>, n: Int): List<Item> {
        val fromB = minOf(b.size, maxOf(n / 2, n - a.size))
        return a.take(n - fromB) + b.take(fromB)
    }

    /** The hosts that may be an official shop: the mail-order shops, then the official site, retailers left out. */
    internal fun shopHosts(links: List<Records.Link>): List<String> =
        (links.filter { it.kind == Records.Kind.SHOP } + links.filter { it.kind == Records.Kind.OFFICIAL })
            .mapNotNull { host(it.url) }
            .filter { h -> NOT_SHOPS.none { n -> if (n.endsWith(".")) h.contains(n) else h == n || h.endsWith(".$n") } }
            .distinct().take(MAX_SHOPS)

    private fun host(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- Shopify

    private suspend fun shopify(artist: String, hosts: List<String>): List<Item> {
        for (h in hosts) {
            val robots = robots(h) ?: continue
            if (!robotsAllows(robots, "/products.json")) continue
            val products = get(h, "/products.json?limit=$SHOPIFY_PAGE", "application/json") ?: continue
            // Not a Shopify shop: no catalogue there.
            if (runCatching { parseJson(products.body)["products"] as? JsonArray }.getOrNull() == null) continue
            val meta = if (robotsAllows(robots, "/meta.json")) get(h, "/meta.json", "application/json")?.body else null
            val items = parseShopify(products.body, meta, "https://$h", artist)
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    /** The shop's currency and name from its `/meta.json`, when it gives them. */
    internal fun parseMeta(body: String?): Pair<String?, String?> {
        val o = body?.let { runCatching { parseJson(it) }.getOrNull() } ?: return null to null
        return o["currency"].str?.uppercase()?.takeIf { it.length == 3 } to o["name"].str
    }

    /**
     * A Shopify catalogue as items: available, shipped things (digital downloads left out), the
     * lowest price of the variants on sale, the first picture as Shopify's 200-pixel copy. When
     * the shop is not named after [artist] (a label's shop), only the products naming the artist.
     */
    fun parseShopify(body: String, meta: String?, base: String, artist: String): List<Item> {
        val (currency, shopName) = parseMeta(meta)
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        fun names(s: String?) = s != null && " ${Matcher.normalizeArtist(s)} ".contains(" $want ")
        val wholeShop = names(shopName)
        return parseJson(body)["products"].arr.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val title = p["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val handle = p["handle"].str?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!wholeShop && !names(p["vendor"].str) && !names(title)) return@mapNotNull null
            val variants = p["variants"].arr.filterIsInstance<JsonObject>()
            val onSale = variants.filter { it["available"].bool != false }
            if (onSale.isEmpty()) return@mapNotNull null
            // Downloads are not merch: some shops list them as products that ship.
            if (variants.all { it["requires_shipping"].bool == false } || "$title ${p["product_type"].str}".contains("digital download", ignoreCase = true)) return@mapNotNull null
            val price = onSale.mapNotNull { it["price"].str?.toDoubleOrNull() }.minOrNull()
            val image = p["images"].arr.firstOrNull()?.get("src")?.str?.let { smallShopifyImage(it) }.orEmpty()
            Item(Source.SHOP, title, p["product_type"].str?.trim().orEmpty(), image, price, currency?.takeIf { price != null }, "$base/products/$handle")
        }
    }

    /** Shopify's own smaller copy of a product picture: "_200x" before the extension. */
    fun smallShopifyImage(src: String): String {
        val url = if (src.startsWith("//")) "https:$src" else src
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, q)
        val dot = path.lastIndexOf('.')
        if (dot <= path.lastIndexOf('/')) return url
        return path.substring(0, dot) + "_200x" + path.substring(dot) + url.substring(q)
    }

    // ---------------------------------------------------------------- Bandcamp

    private suspend fun bandcamp(h: String): List<Item> {
        val robots = robots(h) ?: return emptyList()
        if (!robotsAllows(robots, "/merch")) return emptyList()
        val page = get(h, "/merch", "text/html") ?: return emptyList()
        return parseBandcamp(page.body, "https://$h")
    }

    private val GRID_ITEM = Regex("""<li\b[^>]*class="merch-grid-item.*?</li>""", RegexOption.DOT_MATCHES_ALL)
    private val HREF = Regex("""<a\s+href="([^"]+)"""")
    private val IMG = Regex("""<img\b[^>]*?(?:data-original|src)="(https?://[^"]+)"""")
    private val TITLE = Regex("""<p class="title">(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val TYPE = Regex("""class="merchtype[^"]*">\s*([^<]*?)\s*<""")
    private val PRICE = Regex("""<span class="price">([^<]*)</span>\s*<span class="currency">([A-Z]{3})</span>""")
    private val TAG = Regex("<[^>]+>")
    private val SPACE = Regex("""\s+""")

    /** The items of a Bandcamp merch page (its `merch-grid-item` list), those sold out left out. */
    fun parseBandcamp(html: String, base: String): List<Item> = GRID_ITEM.findAll(html).mapNotNull { m ->
        val li = m.value
        if (li.contains("sold-out")) return@mapNotNull null
        val href = HREF.find(li)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = TITLE.find(li)?.groupValues?.get(1)?.let { text(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val price = PRICE.find(li)
        val value = price?.groupValues?.get(1)?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
        Item(
            Source.BANDCAMP, title, TYPE.find(li)?.groupValues?.get(1)?.let { text(it) }.orEmpty(),
            IMG.find(li)?.groupValues?.get(1).orEmpty(), value, price?.groupValues?.get(2)?.takeIf { value != null },
            if (href.startsWith("http")) href else base + (if (href.startsWith("/")) href else "/$href"),
        )
    }.toList()

    private fun text(html: String): String = SPACE.replace(TAG.replace(html, " "), " ").trim()
        .replace("&ndash;", "–").replace("&mdash;", "—").replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&nbsp;", " ")
        .removeSuffix("–").trim()

    // ---------------------------------------------------------------- robots.txt

    /** The host's robots.txt ("" when it has none); null when it could not be read (then the host is skipped). */
    private suspend fun robots(h: String): String? {
        val r = request(h, "/robots.txt", "text/plain") ?: return null
        return when {
            r.ok -> r.body
            r.code in 400..499 -> ""
            else -> null
        }
    }

    /**
     * Whether robots.txt lets this app read [path]: the rules for "trackhop", else for every
     * agent ("*"); the longest matching rule wins, Allow on a tie; `*` and `$` as in RFC 9309.
     */
    fun robotsAllows(robots: String, path: String, agent: String = "trackhop"): Boolean {
        class Group(val agents: MutableList<String> = ArrayList(), val rules: MutableList<Pair<Boolean, String>> = ArrayList())
        val groups = ArrayList<Group>()
        var current: Group? = null
        var inAgents = false
        for (raw in robots.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            val key = line.substringBefore(':', "").trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "user-agent" -> {
                    if (!inAgents || current == null) { current = Group(); groups += current }
                    current.agents += value.lowercase(); inAgents = true
                }
                "allow", "disallow" -> { current?.rules?.add((key == "allow") to value); inAgents = false }
                else -> if (key.isNotEmpty()) inAgents = false
            }
        }
        val mine = groups.filter { g -> g.agents.any { it != "*" && agent.lowercase().contains(it) } }
        val rules = mine.ifEmpty { groups.filter { "*" in it.agents } }.flatMap { it.rules }.filter { it.second.isNotEmpty() }
        val best = rules.filter { matches(it.second, path) }.maxWithOrNull(compareBy<Pair<Boolean, String>> { it.second.length }.thenBy { it.first })
        return best?.first ?: true
    }

    private fun matches(rule: String, path: String): Boolean {
        val end = rule.endsWith("$")
        val body = rule.removeSuffix("$")
        val re = body.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex("^$re" + if (end) "$" else "").containsMatchIn(path)
    }

    // ---------------------------------------------------------------- requests

    private suspend fun get(h: String, path: String, accept: String): HttpResponse? = request(h, path, accept)?.takeIf { it.ok }

    /** One GET to [h], no more than one a second per host; null when it did not answer. */
    private suspend fun request(h: String, path: String, accept: String): HttpResponse? = try {
        paces.getOrPut(h) { Pacer(hostGap) }.paced {
            Http.send("GET", hostBase(h) + path, mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to accept), maxRateLimitRetries = 0, retryServerErrors = false)
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    /** Tests: forgets the per-host pacers (after [hostGap] changes). */
    internal fun resetPaces() = paces.clear()
}
