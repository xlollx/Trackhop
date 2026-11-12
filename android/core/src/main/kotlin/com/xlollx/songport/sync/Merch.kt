package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.ShopPacer
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
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * The merch an artist sells in their own shops (Records and merch, "Merch"): t-shirts, hoodies,
 * posters, records... with a picture, the price and a link to the product's page. Read only from
 * the public storefront data shops give every shopper, and only from the artist's own shops:
 *
 * - a shop on Shopify: the mail-order shops MusicBrainz links to the artist ([Records.parseLinks]),
 *   the official site, the shops the official site's home page links to (a store subdomain like
 *   shop.band.com, a link named "Store", a label's collection of the artist, a link page like
 *   lnk.to/store followed once; a splash page's "/home" read first), else store. and shop. of the
 *   site's domain: its public catalogue, `/products.json` (first page), and its currency from `/meta.json`;
 * - the artist's Bandcamp: the merch page, `/merch`;
 * - a shop on another platform, told from the page's own marks ([ShopPlatforms.detect]) before anything is
 *   asked of it: WooCommerce's public Store API, Big Cartel's `/products.json` (the same address as Shopify's,
 *   told apart by its answer), a Squarespace shop page as JSON where its robots.txt allows it, else the
 *   schema.org products of the site's shop page and of at most [MAX_PRODUCT_PAGES] product pages it links to.
 *   On the official site's own shop page, and on the first shop the site links to that is not Shopify.
 *
 * When MusicBrainz names neither an official site nor a shop, Wikidata's and Spotify's ([ArtistSites]) are used.
 *
 * Each shop's robots.txt is read first and honoured (a shop that closes those pages is skipped; a
 * link page whose robots.txt does not answer only has the one link the site gives opened, as a click would),
 * at most one request a second goes to a host, and the Shopify catalogues go through one [ShopPacer]
 * for all shops ([shopify]): Shopify limits a phone's requests to all its shops together, and
 * answers a burst with 429 everywhere. Every request names the app, at most [MAX_REQUESTS] per
 * artist, and what is found is kept two days ([TTL_MS]; nothing found, [EMPTY_TTL_MS]). A shop that
 * did not answer is not taken for one with nothing: [Found.unread], never kept; one that answered
 * "too many requests" is [Found.busy]. Each artist's look-up leaves one line in the app's log ("merch").
 * Links only: no login, no cart, no affiliate tag.
 */
object Merch {
    /** Items shown per artist. */
    const val PER_ARTIST = 12
    const val TTL_MS = 48L * 3_600_000
    /** How long "nothing found" is kept: a shop may open or come back sooner. */
    const val EMPTY_TTL_MS = 12L * 3_600_000
    /** Requests per artist, robots.txt included: past them the look-up ends with what it has. */
    const val MAX_REQUESTS = 16
    /** Between two calls to Shopify shops, any of them: its limit is per phone, not per shop. */
    const val SHOPIFY_GAP_MS = 2_500L
    /** How long a robots.txt read is reused for other artists on the same host (a label's shop). */
    const val ROBOTS_TTL_MS = 3_600_000L
    /** Products read from a Shopify shop: the first page of its catalogue. */
    const val SHOPIFY_PAGE = 30
    /** Shopify shops tried per artist; the first that answers with items is used. */
    const val MAX_SHOPS = 6
    /** Of those, the ones found on the official site's home page. */
    const val MAX_LINKED = 4
    /** Product pages read for their schema.org data when a shop page lists too few products itself. */
    const val MAX_PRODUCT_PAGES = 4
    /** Products asked of WooCommerce's Store API: its first page. */
    const val WOO_PAGE = 30

    /**
     * Where an item is from. The names are kept with the items ([TTL_MS]): never renamed. [SHOP] a
     * Shopify shop; the others than [BANDCAMP] are read from the artist's own site or one it links to,
     * and shown with that shop's domain ([Item.shopName]).
     */
    enum class Source {
        SHOP, BANDCAMP, WOOCOMMERCE, BIGCARTEL, SQUARESPACE, SITE;

        val ownSite: Boolean get() = this != SHOP && this != BANDCAMP
    }

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

        /** The shop's domain ("band.com"), shown as where it is sold, for a shop on the artist's own site; null for Shopify's and Bandcamp's. */
        val shopName: String? get() = if (source.ownSite) host(url)?.removePrefix("www.") else null
    }

    /**
     * What the artist's shops gave: [items]; [unread] when nothing was found and a shop could not be
     * read (no answer, a server error): "couldn't read their shop", asked again next time. [busy]
     * when that was a shop saying "too many requests" (or one cooling down after it): asking again
     * before [retryAt] would only be refused.
     */
    data class Found(val items: List<Item>, val unread: Boolean = false, val busy: Boolean = false, val retryAt: Long = 0L)

    /** A Shopify shop: [host], and [collection] the artist's collection in a shared shop (a label's), "" for the whole shop. */
    internal data class Shop(val host: String, val collection: String = "")

    // Tests send every host to a local server ("http://127.0.0.1:port/<host>") and set the gap to 0.
    @Volatile internal var hostBase: (String) -> String = { "https://$it" }
    @Volatile internal var hostGap = 1_000L
    private val paces = ConcurrentHashMap<String, Pacer>()
    /** Every Shopify catalogue call, all shops together (tests set a gap of 0). */
    @Volatile internal var shopify = ShopPacer(SHOPIFY_GAP_MS)
    /** The robots.txt read lately, per host: (when, body). */
    private val robotsRead = ConcurrentHashMap<String, Pair<Long, String>>()

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
    /** A link's text or label naming a shop: "Store", "Shop", "Merch" (a shop on a host that does not say so: a7xworld.com). */
    private val STORE_WORD = Regex("""\b(stores?|shop|merch|merchandise)\b""", RegexOption.IGNORE_CASE)
    /** A splash page's link to the real home page: followed once when the splash names no shop. */
    private val ENTRY = setOf("home", "index", "main", "enter", "welcome")
    /** Subdomains many official shops live on, tried when the official site names none (its menu built by a script). */
    private val STORE_SUBDOMAINS = listOf("store", "shop")

    private val serializer = ListSerializer(Item.serializer())

    /**
     * The merch of [artist] from their shops among [links] (MusicBrainz's) and those the official site
     * links to, at most [PER_ARTIST] of other goods and as many records. Never throws: a shop that
     * fails is skipped, and only when none gave anything is the result [Found.unread].
     */
    suspend fun find(env: Env, artist: String, links: List<Records.Link>, ids: ArtistSites.Ids = ArtistSites.Ids(), background: Boolean = false): Found {
        // "merch4": the results of builds that knew no shop beyond MusicBrainz's and Shopify are not reused.
        val ck = "merch4|${Records.norm(artist)}"
        cachedItems(env, ck)?.let { return Found(it) }
        val t = Trace()
        // No site nor shop on MusicBrainz: Wikidata's and Spotify's.
        val beyond = ArtistSites.extend(env, artist, links, ids, background)
        val links = beyond.links
        if (beyond.unread) t.unread = true
        val shop = guarded(t) { shopify(artist, links, t) }
        val bc = links.firstOrNull { it.kind == Records.Kind.BANDCAMP }?.let { host(it.url) }
        val band = if (bc == null) emptyList() else guarded(t) { bandcamp(bc, t) }
        // Records and other goods counted apart: a shop full of records still shows its t-shirts.
        val items = mix(shop.filter { it.format == null }, band.filter { it.format == null }, PER_ARTIST) +
            mix(shop.filter { it.format != null }, band.filter { it.format != null }, PER_ARTIST)
        val busy = items.isEmpty() && t.busy
        val unread = items.isEmpty() && (t.unread || busy)
        env.log("merch", "$artist: " + t.steps.joinToString("; ").ifEmpty { "no shop known" } + " → " + when {
            busy -> "shop busy"; unread -> "not read"; else -> "${items.size} items"
        })
        // A shop that did not answer, or asked to wait, is never kept: asked again next time.
        if (!unread) Records.remember(env, ck, json.encodeToString(serializer, items))
        return Found(items, unread, busy, if (busy) t.retryAt else 0L)
    }

    /** The items kept for [key]: two days, or [EMPTY_TTL_MS] when there were none. */
    internal fun cachedItems(env: Env, key: String): List<Item>? {
        val items = Records.cached(env, key, TTL_MS) { json.decodeFromString(serializer, it) } ?: return null
        return items.takeIf { it.isNotEmpty() || Records.cached(env, key, EMPTY_TTL_MS) { true } != null }
    }

    /** One artist's look-up: what was asked and how it went (for the log), whether a shop did not answer, the robots.txt read; at most [max] requests. */
    internal class Trace(val max: Int = MAX_REQUESTS) {
        val steps = ArrayList<String>()
        var unread = false
        val robots = HashMap<String, String?>()
        /** A Shopify shop answered "too many requests", or was not asked while it cools down. */
        var busy = false
        var retryAt = 0L
        /** A Shopify shop answered (or was busy): the store./shop. guesses are not needed. */
        var shopSeen = false
        var requests = 0
        /** The status of the last answer (0 when none). */
        var lastCode = 0
        /** Shops linked to (MusicBrainz's, the official site's) whose catalogue is not Shopify's: their own page says what they are. */
        val notShopify = ArrayList<String>()
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

    /** A shop platform's own host for its shops: band.bigcartel.com, band.myshopify.com. */
    private fun isPlatformHost(h: String) = h.endsWith(".bigcartel.com") || h.endsWith(".myshopify.com")

    private fun isRedirector(h: String) = REDIRECTORS.any { h == it || h.endsWith(".$it") }

    internal fun host(url: String) = runCatching { URI(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private val COLLECTION = Regex("""^/collections/([A-Za-z0-9_-]+)/?$""")

    /** The Shopify shop a link names: its host, and the collection when the link is one ("/collections/starset"). */
    internal fun shopOf(url: String): Shop? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val h = u.host?.lowercase()?.takeIf { it.isNotEmpty() && isShopHost(it) } ?: return null
        return Shop(h, COLLECTION.find(u.rawPath.orEmpty())?.groupValues?.get(1)?.lowercase().orEmpty())
    }

    private val ANCHOR = Regex("""<a\b([^>]*)>""", RegexOption.IGNORE_CASE)
    /** An attribute's value, quoted or not (`href=https://...`, as minified pages write it). */
    private val HREF_ATTR = Regex("""\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE)

    /** A page's links: the address and what names it (its text, title and other attributes), in page order. */
    internal fun links(html: String): Sequence<Pair<String, String>> = anchors(html)

    private fun anchors(html: String): Sequence<Pair<String, String>> = ANCHOR.findAll(html).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val h = HREF_ATTR.find(attrs) ?: return@mapNotNull null
        val href = (h.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null).trim().replace("&amp;", "&")
        val after = m.range.last + 1
        val end = listOf(html.indexOf("</a", after, true), html.indexOf("<a", after, true), after + 300).filter { it >= 0 }.min().coerceAtMost(html.length)
        href to (attrs.removeRange(h.range) + " " + text(html.substring(after, end)))
    }
    private val ABSOLUTE = Regex("""https?://[A-Za-z0-9.-]+[^\s"'<>\\]*""")

    /**
     * The shops an official site's home page links to ([site] its host): links to another host that
     * names a shop (store.band.com, a label's /collections/band, a link page's /store), those on the
     * site's own domain first, each once.
     */
    internal fun storeLinks(html: String, site: String): List<String> {
        val domain = site.removePrefix("www.")
        return anchors(html).mapNotNull { (href, label) ->
            val url = when {
                href.startsWith("//") -> "https:$href"
                href.startsWith("http://") || href.startsWith("https://") -> href
                else -> return@mapNotNull null
            }
            val h = host(url) ?: return@mapNotNull null
            if (h.removePrefix("www.") == domain || !isShopHost(h)) return@mapNotNull null
            val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty().lowercase()
            if (!STORE.containsMatchIn(h) && !(isRedirector(h) && STORE.containsMatchIn(path)) && !STORE_WORD.containsMatchIn(label) && !isPlatformHost(h)) return@mapNotNull null
            url
        }.distinctBy { u -> if (isRedirector(host(u)!!)) u.substringBefore('?') else shopOf(u) }
            .sortedBy { if (host(it)!!.endsWith(".$domain")) 0 else 1 }.toList()
    }

    /** A splash page's link to the site's real home page ("/home" on [site]), or null. */
    internal fun entryPath(html: String, site: String): String? = anchors(html).mapNotNull { (href, _) ->
        val u = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
        if (u.host != null && u.host.lowercase().removePrefix("www.") != site.removePrefix("www.")) return@mapNotNull null
        u.rawPath?.takeIf { it.trim('/').lowercase() in ENTRY }
    }.firstOrNull()

    /** The shop a link page ([page] of [redirector]) sends to: the first link to another host that names a shop. */
    internal fun landingShop(page: String, redirector: String): String? =
        ABSOLUTE.findAll(page.replace("\\/", "/")).map { it.value }.firstOrNull { u ->
            val h = host(u) ?: return@firstOrNull false
            !isRedirector(h) && h != redirector && isShopHost(h) && STORE.containsMatchIn(h)
        }

    // ---------------------------------------------------------------- Shopify

    private suspend fun shopify(artist: String, links: List<Records.Link>, t: Trace): List<Item> {
        val tried = HashSet<Shop>()
        suspend fun read(s: Shop?, follow: Boolean = false): List<Item>? {
            // Shopify busy: its other shops would be refused too, and asking them makes it last longer.
            if (s == null || t.busy || tried.size >= MAX_SHOPS || !tried.add(s)) return null
            return readShopify(artist, s, t, follow).takeIf { it.isNotEmpty() }
        }
        // The mail-order shops that are not Shopify's are looked at again by their own page; the official sites are read below.
        val shopKind = links.filter { it.kind == Records.Kind.SHOP }.mapNotNull { host(it.url) }.toSet()
        for (h in shopHosts(links)) read(Shop(h), follow = h in shopKind)?.let { return it }
        // A shop linked to that is not Shopify (Big Cartel was read with Shopify's address): its home page says what it is. The first only.
        suspend fun other(site: String): List<Item> =
            if (t.busy) emptyList() else t.notShopify.firstOrNull()?.let { h -> otherShop(artist, h, site, t) }.orEmpty()
        // Many official sites are not the shop but link to it: a store subdomain, a label's shop, a link page.
        val official = links.firstOrNull { it.kind == Records.Kind.OFFICIAL }?.url ?: return other("")
        val site = host(official)?.takeIf { isShopHost(it) } ?: return other("")
        val home = allowedGet(site, "/", "text/html", t)
        val at = home?.url?.let { host(unbase(it)) } ?: site
        var linked = home?.let { storeLinks(it.body, at) }.orEmpty()
        // A splash page that only leads to the real home page ("/home"): that page is read once.
        val entry = if (home != null && linked.isEmpty()) entryPath(home.body, at) else null
        var linkedFrom: String? = null
        if (entry != null) linked = allowedGet(at, entry, "text/html", t)?.also { linkedFrom = it.body }?.let { storeLinks(it.body, at) }.orEmpty()
        if (home != null && linked.isEmpty()) t.steps += "$site: no shop linked"
        for (url in linked.take(MAX_LINKED)) read(resolve(url, t), follow = true)?.let { return it }
        val bare = runCatching { URI(official).rawPath.orEmpty().trim('/') }.getOrDefault("x").isEmpty()
        // The site's own shop on another platform (WooCommerce, Squarespace, a page of schema.org products): the site itself, not a page on a host.
        if (bare && home != null && !t.busy) {
            val page = if (entry != null) linkedFrom ?: home.body else home.body
            ownSite(artist, at, page, home, t).takeIf { it.isNotEmpty() }?.let { return it }
        }
        other(at).takeIf { it.isNotEmpty() }?.let { return it }
        // A home page drawn by a script names no shop: store. and shop. of the site's own domain (the site itself, not a page on a host).
        if (!bare) return emptyList()
        // A shop was found (with nothing of theirs) or is busy: no guessing beside it.
        if (t.shopSeen || t.busy) return emptyList()
        // A guessed host that does not exist is not a shop that did not answer.
        val unread = t.unread
        val domain = at.removePrefix("www.")
        try {
            for (sub in STORE_SUBDOMAINS) read(Shop("$sub.$domain"))?.let { return it }
        } finally { t.unread = unread }
        return emptyList()
    }

    /** A link of the official site as a shop: itself, or for a link page (lnk.to, found.ee...) the shop it sends to. */
    private suspend fun resolve(url: String, t: Trace): Shop? {
        val h = host(url) ?: return null
        if (!isRedirector(h)) return shopOf(url)
        val u = URI(url)
        val r = allowedGet(h, u.rawPath.orEmpty().ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: ""), "text/html", t, link = true) ?: return null
        // An HTTP redirect lands on the shop; a page names it.
        val target = r.url?.let { unbase(it) }?.takeIf { host(it) != h } ?: landingShop(r.body, h)
        t.steps += "$h${u.rawPath.orEmpty()} → ${target?.let { host(it) } ?: "no shop"}"
        return target?.let { shopOf(it) }
    }

    /**
     * A Shopify shop's catalogue (or Big Cartel's, at the same address). [follow]: a shop linked to,
     * which when it is neither is looked at again by its home page ([Trace.notShopify]).
     */
    private suspend fun readShopify(artist: String, s: Shop, t: Trace, follow: Boolean = false): List<Item> {
        val path = (if (s.collection.isEmpty()) "" else "/collections/${s.collection}") + "/products.json"
        t.lastCode = 0
        val products = allowedGet(s.host, "$path?limit=$SHOPIFY_PAGE", "application/json", t)
        if (products == null) {
            if (follow && t.lastCode == 404 && s.collection.isEmpty()) t.notShopify += s.host
            return emptyList()
        }
        val parsed = runCatching { parseJson(products.body) }.getOrNull()
        // Not a Shopify shop: no catalogue there.
        if (parsed["products"] as? JsonArray == null) {
            if (s.collection.isEmpty() && ShopPlatforms.isBigCartel(parsed)) return readBigCartel(artist, s.host, products.body, t)
            t.steps += "${s.host}: not a Shopify shop"
            if (follow && s.collection.isEmpty()) t.notShopify += s.host
            return emptyList()
        }
        t.shopSeen = true
        val meta = allowedGet(s.host, "/meta.json", "application/json", t)?.body
        // A label's collection named after the artist ("/collections/starset") is all theirs.
        val whole = s.collection.isNotEmpty() && Matcher.normalizeArtist(s.collection.replace('-', ' ')) == Matcher.normalizeArtist(artist)
        val items = parseShopify(products.body, meta, "https://${s.host}", artist, whole)
        t.steps += "${s.host}$path: ${items.size} items"
        return items
    }

    /** The shop's currency, name and description from its `/meta.json`, when it gives them. */
    internal fun parseMeta(body: String?): Triple<String?, String?, String?> {
        val o = body?.let { runCatching { parseJson(it) }.getOrNull() } ?: return Triple(null, null, null)
        return Triple(o["currency"].str?.uppercase()?.takeIf { it.length == 3 }, o["name"].str, o["description"].str)
    }

    /**
     * A Shopify catalogue as items: available, shipped things (digital downloads left out), the
     * lowest price of the variants on sale, the first picture as Shopify's 200-pixel copy. When
     * the shop is not named after [artist] (its name, its initials "ADTR", or its description "A Day
     * To Remember's official online store"; not a label's shop) and is not [whole]ly theirs (the
     * artist's collection there), only the products naming the artist.
     */
    fun parseShopify(body: String, meta: String?, base: String, artist: String, whole: Boolean = false): List<Item> {
        val (currency, shopName, about) = parseMeta(meta)
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        val words = want.split(' ')
        val initials = if (words.size >= 3) words.joinToString("") { it.take(1) } else ""
        fun names(s: String?): Boolean {
            val n = " ${Matcher.normalizeArtist(s ?: return false)} "
            return n.contains(" $want ") || n.contains(" ${want}s ") || (initials.isNotEmpty() && n.contains(" $initials "))
        }
        val wholeShop = whole || names(shopName) || names(about)
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

    // ---------------------------------------------------------------- other platforms

    /** A Big Cartel shop ([body] its `/products.json`): its currency and name from `/store.json`. */
    private suspend fun readBigCartel(artist: String, h: String, body: String, t: Trace): List<Item> {
        t.shopSeen = true
        val store = allowedGet(h, "/store.json", "application/json", t)?.body
        val items = ShopPlatforms.parseBigCartel(body, store, "https://$h", artist, whole = false)
        t.steps += "$h/products.json (Big Cartel): ${items.size} items"
        return items
    }

    /**
     * The official site's own shop ([site], [page] the home page that was read): WooCommerce's Store
     * API, a Squarespace shop page, else the schema.org products of the shop page it links to. The
     * artist's own site: everything on sale there is theirs. Empty when it has none.
     */
    private suspend fun ownSite(artist: String, site: String, page: String, home: HttpResponse, t: Trace): List<Item> {
        val platform = ShopPlatforms.detect(page) { home.header(it) }
        val shopPath = ShopPlatforms.ownShopPath(page, site)
        t.steps += "$site: ${platform.name.lowercase()}" + (shopPath?.let { ", shop page $it" } ?: "")
        return when (platform) {
            // Their catalogues were asked at the site's own address already.
            ShopPlatforms.Platform.SHOPIFY, ShopPlatforms.Platform.BIGCARTEL -> emptyList()
            ShopPlatforms.Platform.WOOCOMMERCE, ShopPlatforms.Platform.WORDPRESS ->
                readWoo(artist, site, t, whole = true) ?: shopPath?.let { readPages(artist, site, it, null, t, whole = true) }.orEmpty()
            ShopPlatforms.Platform.SQUARESPACE -> shopPath?.let { readSquarespace(artist, site, it, t, whole = true) }.orEmpty()
            ShopPlatforms.Platform.OTHER -> shopPath?.let { readPages(artist, site, it, null, t, whole = true) }.orEmpty()
        }
    }

    /**
     * A shop the artist's site or MusicBrainz links to that is not Shopify ([h]): its home page says what
     * it runs on. Theirs entirely when it is on the official site's domain ([site]) or named after the artist.
     */
    private suspend fun otherShop(artist: String, h: String, site: String, t: Trace): List<Item> {
        val home = allowedGet(h, "/", "text/html", t) ?: return emptyList()
        val domain = site.removePrefix("www.")
        val whole = h.removePrefix("www.") == domain || h.endsWith(".$domain") || ShopPlatforms.Names(artist).named(ShopPlatforms.siteName(home.body))
        val platform = ShopPlatforms.detect(home.body) { home.header(it) }
        t.steps += "$h: ${platform.name.lowercase()}"
        val shopPath = ShopPlatforms.ownShopPath(home.body, h)
        return when (platform) {
            ShopPlatforms.Platform.SHOPIFY, ShopPlatforms.Platform.BIGCARTEL -> emptyList()
            ShopPlatforms.Platform.WOOCOMMERCE, ShopPlatforms.Platform.WORDPRESS ->
                readWoo(artist, h, t, whole) ?: readPages(artist, h, shopPath ?: "/", home.body.takeIf { shopPath == null }, t, whole)
            ShopPlatforms.Platform.SQUARESPACE -> readSquarespace(artist, h, shopPath ?: "/", t, whole)
            // A shop's home page is its shop page: its own products, then its product pages.
            ShopPlatforms.Platform.OTHER -> readPages(artist, h, "/", home.body, t, whole)
        }
    }

    /** WooCommerce's public Store API on [h]: null when it has none (or it is closed), so the shop page is read instead. */
    private suspend fun readWoo(artist: String, h: String, t: Trace, whole: Boolean): List<Item>? {
        val r = allowedGet(h, "${ShopPlatforms.WOO_PATH}?per_page=$WOO_PAGE", "application/json", t) ?: return null
        val items = runCatching { parseJson(r.body) as? JsonArray }.getOrNull()?.let { runCatching { ShopPlatforms.parseWoo(r.body, artist, whole) }.getOrNull() } ?: run {
            t.steps += "$h: no WooCommerce catalogue"; return null
        }
        t.shopSeen = true
        t.steps += "$h${ShopPlatforms.WOO_PATH}: ${items.size} items"
        return items
    }

    /** A Squarespace shop page as JSON where robots.txt allows it (Squarespace's own does not), else its schema.org products. */
    private suspend fun readSquarespace(artist: String, h: String, path: String, t: Trace, whole: Boolean): List<Item> {
        val r = allowedGet(h, "$path?format=json", "application/json", t)
        val items = r?.let { runCatching { ShopPlatforms.parseSquarespace(it.body, "https://$h", artist, whole) }.getOrNull() }
        if (items != null) {
            t.shopSeen = true
            t.steps += "$h$path?format=json: ${items.size} items"
            return items
        }
        return readPages(artist, h, path, null, t, whole, Source.SQUARESPACE)
    }

    /**
     * The schema.org products of a shop page ([path] on [h]; [body] when it was read already) and, when it
     * lists fewer than three itself, of at most [MAX_PRODUCT_PAGES] product pages it links to.
     */
    private suspend fun readPages(artist: String, h: String, path: String, body: String?, t: Trace, whole: Boolean, source: Source = Source.SITE): List<Item> {
        val page = body ?: allowedGet(h, path, "text/html", t)?.body ?: return emptyList()
        val base = "https://$h$path"
        val items = ArrayList(ShopPlatforms.parseJsonLd(page, base, artist, whole, source))
        if (items.size < 3) {
            for (p in ShopPlatforms.productPaths(page, h, path, MAX_PRODUCT_PAGES)) {
                if (t.requests >= t.max) break
                val r = allowedGet(h, p, "text/html", t) ?: continue
                items += ShopPlatforms.parseJsonLd(r.body, "https://$h$p", artist, whole, source).filter { n -> items.none { it.url == n.url } }
            }
        }
        if (items.isNotEmpty()) t.shopSeen = true
        t.steps += "$h$path (schema.org): ${items.size} items"
        return items.take(PER_ARTIST * 2)
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

    internal fun text(html: String): String = SPACE.replace(TAG.replace(html, " "), " ").trim()
        .replace("&ndash;", "–").replace("&mdash;", "—").replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&nbsp;", " ")
        .removeSuffix("–").trim()

    // ---------------------------------------------------------------- robots.txt

    /** The host's robots.txt ("" when it has none); null when it could not be read (then the host is skipped). */
    private suspend fun robots(h: String, t: Trace): String? {
        if (h in t.robots) return t.robots[h]
        robotsRead[h]?.takeIf { Records.now() - it.first < ROBOTS_TTL_MS }?.let { t.robots[h] = it.second; return it.second }
        val r = request(h, "/robots.txt", "text/plain", t)
        val robots = when {
            r == null -> null
            r.ok -> r.body
            r.code in 400..499 -> ""
            else -> null
        }
        t.robots[h] = robots
        if (robots != null) robotsRead[h] = Records.now() to robots
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

    /**
     * A GET to [h] that its robots.txt allows; null when it does not, or did not answer with the page (why, in [t]).
     * A [link] the artist's site gives to a link page (found.ee/band-store) is opened even when that host's
     * robots.txt cannot be read: that one address, as a click would, nothing else on the host.
     */
    private suspend fun allowedGet(h: String, path: String, accept: String, t: Trace, link: Boolean = false): HttpResponse? {
        val unread = t.unread
        val robots = robots(h, t)
        if (robots == null && link) {
            val r = request(h, path, accept, t)?.takeIf { it.ok } ?: return null
            t.unread = unread; t.steps += "$h: robots.txt unreadable, the link opened once"
            return r
        }
        robots ?: return null
        if (!robotsAllows(robots, path)) { t.steps += "$h${path.substringBefore('?')}: closed by robots.txt"; return null }
        return request(h, path, accept, t)?.takeIf { it.ok }
    }

    /** A page of a site ([SiteConcerts]): robots.txt honoured, paced per host, counted in [t]. */
    internal suspend fun fetch(h: String, path: String, accept: String, t: Trace): HttpResponse? = allowedGet(h, path, accept, t)

    /** A Shopify catalogue call: paced with every other Shopify shop's ([shopify]). */
    private fun isShopify(path: String) = path.substringBefore('?').let { it.endsWith("/products.json") || it == "/meta.json" }

    /**
     * One GET to [h], no more than one a second per host, and the Shopify catalogues no faster than
     * [shopify] lets them. Null when it did not answer (or was not asked: busy shop, [MAX_REQUESTS]
     * reached); a server error or "too many requests" is answered but noted in [t] as not read, like
     * no answer. A host that does not exist is not a shop that did not answer.
     */
    private suspend fun request(h: String, path: String, accept: String, t: Trace): HttpResponse? {
        val p = h + path.substringBefore('?')
        val shop = isShopify(path)
        var retried = false
        while (true) {
            if (t.requests >= t.max) { if (t.requests++ == t.max) t.steps += "request cap reached"; return null }
            t.requests++
            val send: suspend () -> HttpResponse = {
                Http.send("GET", hostBase(h) + path, mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to accept), maxRateLimitRetries = 0, retryServerErrors = false)
            }
            val r = try {
                if (shop) shopify.paced(h, send) else paces.getOrPut(h) { Pacer(hostGap) }.paced { send() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                t.steps += "$h: no such host"; return null
            } catch (e: Exception) {
                t.unread = true; t.steps += "$p: no answer (${e.javaClass.simpleName})"; return null
            }
            if (r == null) {
                t.busy = true; t.shopSeen = true; t.retryAt = maxOf(t.retryAt, shopify.freeAt(h))
                t.steps += "$p: not asked, Shopify busy"; return null
            }
            if (shop && r.code == 429) {
                val wait = ShopPacer.retryAfterMs(r.header("Retry-After"), System.currentTimeMillis())
                t.steps += "$p: 429" + (wait?.let { " (retry after ${it / 1000} s)" } ?: "")
                if (shopify.tooMany(h, wait, mayRetry = !retried)) { retried = true; continue }
                t.busy = true; t.shopSeen = true; t.unread = true; t.retryAt = maxOf(t.retryAt, shopify.freeAt(h))
                return r
            }
            if (shop) shopify.answered()
            t.lastCode = r.code
            if (!r.ok) t.steps += "$p: ${r.code}"
            if (r.code == 429 || r.code >= 500) t.unread = true
            return r
        }
    }

    /** A URL as the shop's own, where tests answered it from the local server. */
    private fun unbase(url: String): String = hostBase("").let { b -> if (url.startsWith(b)) "https://" + url.removePrefix(b) else url }

    /** Tests: forgets the per-host pacers (after [hostGap] changes), the Shopify pacer's state and the robots.txt read. */
    internal fun resetPaces(shopifyGap: Long = SHOPIFY_GAP_MS) {
        paces.clear(); robotsRead.clear(); shopify = ShopPacer(shopifyGap)
    }
}
