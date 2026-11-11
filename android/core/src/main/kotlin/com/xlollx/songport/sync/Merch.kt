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
 * the public storefront data shops give every shopper, and only from the artist's own shops:
 *
 * - a shop on Shopify: the mail-order shops MusicBrainz links to the artist ([Records.parseLinks]),
 *   the official site, and the shops the official site's home page links to (a store subdomain
 *   like shop.band.com, a label's collection of the artist, a link page like lnk.to/store followed
 *   once): its public catalogue, `/products.json` (first page), and its currency from `/meta.json`;
 * - the artist's Bandcamp: the merch page, `/merch`.
 *
 * Each shop's robots.txt is read first and honoured (a shop that closes those pages is skipped),
 * at most one request a second goes to a host, every request names the app, and what is found is
 * kept a day ([TTL_MS]). A shop that did not answer is not taken for one with nothing:
 * [Found.unread], never kept. Each artist's look-up leaves one line in the app's log ("merch").
 * Links only: no login, no cart, no affiliate tag.
 */
object Merch {
    /** Items shown per artist. */
    const val PER_ARTIST = 12
    const val TTL_MS = 24L * 3_600_000
    /** Products read from a Shopify shop: the first page of its catalogue. */
    const val SHOPIFY_PAGE = 30
    /** Shopify shops tried per artist; the first that answers with items is used. */
    const val MAX_SHOPS = 6
    /** Of those, the ones found on the official site's home page. */
    const val MAX_LINKED = 4

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

    /**
     * What the artist's shops gave: [items]; [unread] when nothing was found and a shop could not be
     * read (no answer, a server error): "couldn't read their shop", asked again next time.
     */
    data class Found(val items: List<Item>, val unread: Boolean = false)

    /** A Shopify shop: [host], and [collection] the artist's collection in a shared shop (a label's), "" for the whole shop. */
    internal data class Shop(val host: String, val collection: String = "")

    // Tests send every host to a local server ("http://127.0.0.1:port/<host>") and set the gap to 0.
    @Volatile internal var hostBase: (String) -> String = { "https://$it" }
    @Volatile internal var hostGap = 1_000L
    private val paces = ConcurrentHashMap<String, Pacer>()

    /** Big retailers and sites that are not an artist's own shop: never asked. */
    private val NOT_SHOPS = listOf(
        "amazon.", "ebay.", "discogs.com", "bandcamp.com", "apple.com", "cdjapan.co.jp", "yesasia.com", "target.com",
        "urbanoutfitters.com", "walmart.com", "hmv.", "jpc.de", "tower.jp", "spotify.com", "youtube.com",
        // Shopify's own sites, not a shop.
        "shopify.com", "shopifysvc.com", "shop.app",
    )

    /** Link pages that send to the shop ("falloutboy.lnk.to/store"): followed once. */
    private val REDIRECTORS = listOf("lnk.to", "found.ee", "ffm.to", "smarturl.it", "orcd.co", "fanlink.tv", "bfan.link")

    /** A host or link that names a shop: store.band.com, shop.band.com, merch.band.com, bandstore.com, lnk.to/store. */
    private val STORE = Regex("store|shop|merch")

    private val serializer = ListSerializer(Item.serializer())

    /**
     * The merch of [artist] from their shops among [links] (MusicBrainz's) and those the official site
     * links to, at most [PER_ARTIST] of other goods and as many records. Never throws: a shop that
     * fails is skipped, and only when none gave anything is the result [Found.unread].
     */
    suspend fun find(env: Env, artist: String, links: List<Records.Link>): Found {
        // "merch3": the results of builds that kept a failure as "nothing" and knew no shop beyond MusicBrainz's are not reused.
        val ck = "merch3|${Records.norm(artist)}"
        Records.cached(env, ck, TTL_MS) { json.decodeFromString(serializer, it) }?.let { return Found(it) }
        val t = Trace()
        val shop = guarded(t) { shopify(artist, links, t) }
        val bc = links.firstOrNull { it.kind == Records.Kind.BANDCAMP }?.let { host(it.url) }
        val band = if (bc == null) emptyList() else guarded(t) { bandcamp(bc, t) }
        // Records and other goods counted apart: a shop full of records still shows its t-shirts.
        val items = mix(shop.filter { it.format == null }, band.filter { it.format == null }, PER_ARTIST) +
            mix(shop.filter { it.format != null }, band.filter { it.format != null }, PER_ARTIST)
        val unread = items.isEmpty() && t.unread
        env.log("merch", "$artist: " + t.steps.joinToString("; ").ifEmpty { "no shop known" } + " → " + if (unread) "not read" else "${items.size} items")
        if (!unread) Records.remember(env, ck, json.encodeToString(serializer, items))
        return Found(items, unread)
    }

    /** One artist's look-up: what was asked and how it went (for the log), whether a shop did not answer, the robots.txt read. */
    private class Trace {
        val steps = ArrayList<String>()
        var unread = false
        val robots = HashMap<String, String?>()
    }

    private suspend fun guarded(t: Trace, block: suspend () -> List<Item>): List<Item> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        t.unread = true; t.steps += "failed: ${e.javaClass.simpleName}"; emptyList()
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
            .mapNotNull { host(it.url) }.filter { isShopHost(it) }.distinct()

    private fun isShopHost(h: String) = NOT_SHOPS.none { n -> if (n.endsWith(".")) h.contains(n) else h == n || h.endsWith(".$n") }

    private fun isRedirector(h: String) = REDIRECTORS.any { h == it || h.endsWith(".$it") }

    private fun host(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private val COLLECTION = Regex("""^/collections/([A-Za-z0-9_-]+)/?$""")

    /** The Shopify shop a link names: its host, and the collection when the link is one ("/collections/starset"). */
    internal fun shopOf(url: String): Shop? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val h = u.host?.lowercase()?.takeIf { it.isNotEmpty() && isShopHost(it) } ?: return null
        return Shop(h, COLLECTION.find(u.rawPath.orEmpty())?.groupValues?.get(1)?.lowercase().orEmpty())
    }

    private val ANCHOR = Regex("""<a\b[^>]*?\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val ABSOLUTE = Regex("""https?://[A-Za-z0-9.-]+[^\s"'<>\\]*""")

    /**
     * The shops an official site's home page links to ([site] its host): links to another host that
     * names a shop (store.band.com, a label's /collections/band, a link page's /store), those on the
     * site's own domain first, each once.
     */
    internal fun storeLinks(html: String, site: String): List<String> {
        val domain = site.removePrefix("www.")
        return ANCHOR.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1].trim().replace("&amp;", "&")
            val url = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("http://") || href.startsWith("https://") -> href
                else -> return@mapNotNull null
            }
            val h = host(url) ?: return@mapNotNull null
            if (h.removePrefix("www.") == domain || !isShopHost(h)) return@mapNotNull null
            val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().lowercase()
            if (!STORE.containsMatchIn(h) && !(isRedirector(h) && STORE.containsMatchIn(path))) return@mapNotNull null
            url
        }.distinctBy { u -> if (isRedirector(host(u)!!)) u.substringBefore('?') else shopOf(u) }
            .sortedBy { if (host(it)!!.endsWith(".$domain")) 0 else 1 }.toList()
    }

    /** The shop a link page ([page] of [redirector]) sends to: the first link to another host that names a shop. */
    internal fun landingShop(page: String, redirector: String): String? =
        ABSOLUTE.findAll(page.replace("\\/", "/")).map { it.value }.firstOrNull { u ->
            val h = host(u) ?: return@firstOrNull false
            !isRedirector(h) && h != redirector && isShopHost(h) && STORE.containsMatchIn(h)
        }

    // ---------------------------------------------------------------- Shopify

    private suspend fun shopify(artist: String, links: List<Records.Link>, t: Trace): List<Item> {
        val tried = HashSet<Shop>()
        suspend fun read(s: Shop?): List<Item>? {
            if (s == null || tried.size >= MAX_SHOPS || !tried.add(s)) return null
            return readShopify(artist, s, t).takeIf { it.isNotEmpty() }
        }
        for (h in shopHosts(links)) read(Shop(h))?.let { return it }
        // Many official sites are not the shop but link to it: a store subdomain, a label's shop, a link page.
        val site = links.firstOrNull { it.kind == Records.Kind.OFFICIAL }?.let { host(it.url) }?.takeIf { isShopHost(it) } ?: return emptyList()
        val home = allowedGet(site, "/", "text/html", t) ?: return emptyList()
        val linked = storeLinks(home.body, host(home.url?.let { unbase(it) } ?: "") ?: site).take(MAX_LINKED)
        if (linked.isEmpty()) t.steps += "$site: no shop linked"
        for (url in linked) read(resolve(url, t))?.let { return it }
        return emptyList()
    }

    /** A link of the official site as a shop: itself, or for a link page (lnk.to, found.ee...) the shop it sends to. */
    private suspend fun resolve(url: String, t: Trace): Shop? {
        val h = host(url) ?: return null
        if (!isRedirector(h)) return shopOf(url)
        val u = URI(url)
        val r = allowedGet(h, u.rawPath.orEmpty().ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: ""), "text/html", t) ?: return null
        // An HTTP redirect lands on the shop; a page names it.
        val target = r.url?.let { unbase(it) }?.takeIf { host(it) != h } ?: landingShop(r.body, h)
        t.steps += "$h${u.rawPath.orEmpty()} → ${target?.let { host(it) } ?: "no shop"}"
        return target?.let { shopOf(it) }
    }

    private suspend fun readShopify(artist: String, s: Shop, t: Trace): List<Item> {
        val path = (if (s.collection.isEmpty()) "" else "/collections/${s.collection}") + "/products.json"
        val products = allowedGet(s.host, "$path?limit=$SHOPIFY_PAGE", "application/json", t) ?: return emptyList()
        // Not a Shopify shop: no catalogue there.
        if (runCatching { parseJson(products.body)["products"] as? JsonArray }.getOrNull() == null) {
            t.steps += "${s.host}: not a Shopify shop"; return emptyList()
        }
        val meta = allowedGet(s.host, "/meta.json", "application/json", t)?.body
        // A label's collection named after the artist ("/collections/starset") is all theirs.
        val whole = s.collection.isNotEmpty() && Matcher.normalizeArtist(s.collection.replace('-', ' ')) == Matcher.normalizeArtist(artist)
        val items = parseShopify(products.body, meta, "https://${s.host}", artist, whole)
        t.steps += "${s.host}$path: ${items.size} items"
        return items
    }

    /** The shop's currency and name from its `/meta.json`, when it gives them. */
    internal fun parseMeta(body: String?): Pair<String?, String?> {
        val o = body?.let { runCatching { parseJson(it) }.getOrNull() } ?: return null to null
        return o["currency"].str?.uppercase()?.takeIf { it.length == 3 } to o["name"].str
    }

    /**
     * A Shopify catalogue as items: available, shipped things (digital downloads left out), the
     * lowest price of the variants on sale, the first picture as Shopify's 200-pixel copy. When
     * the shop is not named after [artist] (a label's shop) and is not [whole]ly theirs (the artist's
     * collection there), only the products naming the artist.
     */
    fun parseShopify(body: String, meta: String?, base: String, artist: String, whole: Boolean = false): List<Item> {
        val (currency, shopName) = parseMeta(meta)
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        fun names(s: String?) = s != null && " ${Matcher.normalizeArtist(s)} ".contains(" $want ")
        val wholeShop = whole || names(shopName)
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

    private suspend fun bandcamp(h: String, t: Trace): List<Item> {
        val page = allowedGet(h, "/merch", "text/html", t) ?: return emptyList()
        // No merch page: Bandcamp sends to the latest album.
        return parseBandcamp(page.body, "https://$h").also { t.steps += "$h/merch: ${it.size} items" }
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
    private suspend fun robots(h: String, t: Trace): String? {
        if (h in t.robots) return t.robots[h]
        val r = request(h, "/robots.txt", "text/plain", t)
        val robots = when {
            r == null -> null
            r.ok -> r.body
            r.code in 400..499 -> ""
            else -> null
        }
        t.robots[h] = robots
        return robots
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
        // The group naming this app ("Trackhop", "trackhop/1.0"); an empty or partial name is not it.
        val mine = groups.filter { g -> g.agents.any { it.substringBefore('/').trim() == agent.lowercase() } }
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

    /** A GET to [h] that its robots.txt allows; null when it does not, or did not answer with the page (why, in [t]). */
    private suspend fun allowedGet(h: String, path: String, accept: String, t: Trace): HttpResponse? {
        val robots = robots(h, t) ?: return null
        if (!robotsAllows(robots, path)) { t.steps += "$h${path.substringBefore('?')}: closed by robots.txt"; return null }
        return request(h, path, accept, t)?.takeIf { it.ok }
    }

    /**
     * One GET to [h], no more than one a second per host. Null when it did not answer; a server
     * error or "too many requests" is answered but noted in [t] as not read, like no answer.
     */
    private suspend fun request(h: String, path: String, accept: String, t: Trace): HttpResponse? {
        val r = try {
            paces.getOrPut(h) { Pacer(hostGap) }.paced {
                Http.send("GET", hostBase(h) + path, mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to accept), maxRateLimitRetries = 0, retryServerErrors = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            t.unread = true; t.steps += "$h${path.substringBefore('?')}: no answer (${e.javaClass.simpleName})"; return null
        }
        if (!r.ok) t.steps += "$h${path.substringBefore('?')}: ${r.code}"
        if (r.code == 429 || r.code >= 500) t.unread = true
        return r
    }

    /** A URL as the shop's own, where tests answered it from the local server. */
    private fun unbase(url: String): String = hostBase("").let { b -> if (url.startsWith(b)) "https://" + url.removePrefix(b) else url }

    /** Tests: forgets the per-host pacers (after [hostGap] changes). */
    internal fun resetPaces() = paces.clear()
}
