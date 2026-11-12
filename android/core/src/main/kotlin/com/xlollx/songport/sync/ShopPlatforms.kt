package com.xlollx.songport.sync

import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.net.URI

/**
 * The shops [Merch] reads beside Shopify and Bandcamp, on the artist's own site or one it links to:
 * how a page says what it runs on ([detect]: its HTML and headers, no request of its own), and what
 * each platform's public catalogue says ([parseWoo], [parseBigCartel], [parseSquarespace], else the
 * schema.org products a page carries, [parseJsonLd]). The requests are [Merch]'s.
 */
internal object ShopPlatforms {
    enum class Platform { WOOCOMMERCE, BIGCARTEL, SQUARESPACE, WORDPRESS, SHOPIFY, OTHER }

    /** WooCommerce's public Store API: the first page of products, as the shop's own blocks ask it. */
    const val WOO_PATH = "/wp-json/wc/store/v1/products"

    /**
     * What a site runs on, from its page and headers: the first sure sign, the shop platforms
     * first. WordPress without WooCommerce's marks may still have it (its Store API is tried once).
     */
    fun detect(html: String, header: (String) -> String? = { null }): Platform {
        val h = html.lowercase()
        val server = (header("Server").orEmpty() + " " + header("X-Powered-By").orEmpty() + " " + header("Powered-By").orEmpty()).lowercase()
        return when {
            "woocommerce" in h || "wc-block-" in h || "/wp-json/wc/" in h -> Platform.WOOCOMMERCE
            "assets.bigcartel.com" in h || "bigcartel.com/theme" in h || "content=\"big cartel\"" in h -> Platform.BIGCARTEL
            "static1.squarespace.com" in h || "squarespace-cdn.com" in h || "squarespace_context" in h || "this is squarespace" in h || "squarespace" in server ->
                Platform.SQUARESPACE
            "/wp-content/" in h || "/wp-includes/" in h || header("Link").orEmpty().contains("api.w.org") -> Platform.WORDPRESS
            "cdn.shopify.com" in h || "shopify.theme" in h || header("X-ShopId") != null || "shopify" in server -> Platform.SHOPIFY
            else -> Platform.OTHER
        }
    }

    /**
     * Whether an artist's name is in a text: the name itself, its plural ("Arctic Monkeys's"), or its
     * initials when it has three words or more ("ADTR"). Shops of others (a label's) give only what names the artist.
     */
    class Names(artist: String) {
        private val want = Matcher.normalizeArtist(artist)
        private val initials = want.split(' ').let { w -> if (w.size >= 3) w.joinToString("") { it.take(1) } else "" }
        val empty get() = want.isEmpty()

        fun named(s: String?): Boolean {
            if (want.isEmpty()) return false
            val n = " ${Matcher.normalizeArtist(s ?: return false)} "
            return n.contains(" $want ") || n.contains(" ${want}s ") || (initials.isNotEmpty() && n.contains(" $initials "))
        }
    }

    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    /** Text from HTML: tags and spaces folded, the entities shops write in names (`&#8211;`) decoded. */
    fun plain(s: String): String = Merch.text(NUMERIC.replace(s) { m ->
        val v = m.groupValues[1]
        val code = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
        code?.takeIf { it in 32..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
    })

    private fun notGoods(title: String, type: String) =
        "$title $type".let { it.contains("digital download", ignoreCase = true) || it.contains("gift card", ignoreCase = true) }

    // ---------------------------------------------------------------- WooCommerce

    /**
     * WooCommerce's Store API (`/wp-json/wc/store/v1/products`): products in stock, the price in the
     * shop's minor units (`currency_minor_unit`: 2500 with 2 is 25.00) and its currency, the first
     * picture's small copy, the product's page. [whole]: the artist's own site, every product is theirs.
     */
    fun parseWoo(body: String, artist: String, whole: Boolean): List<Merch.Item> {
        val names = Names(artist)
        return parseJson(body).arr.mapNotNull { p ->
            val title = p["name"].str?.let { plain(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = webUrl(p["permalink"].str).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (p["is_in_stock"].bool == false || p["is_purchasable"].bool == false) return@mapNotNull null
            val type = p["categories"].arr.firstNotNullOfOrNull { c -> c["name"].str?.let { plain(it) }?.takeIf { !it.equals("Uncategorized", true) } }.orEmpty()
            if (!whole && !names.named(title)) return@mapNotNull null
            if (notGoods(title, type)) return@mapNotNull null
            val prices = p["prices"]
            val minor = prices["currency_minor_unit"].long?.toInt()?.takeIf { it in 0..4 } ?: 2
            val price = prices["price"].str?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.let { BigDecimal(it).movePointLeft(minor).toDouble() }
            val currency = prices["currency_code"].str?.uppercase()?.takeIf { it.length == 3 && price != null }
            val image = p["images"].arr.firstOrNull()?.let { webUrl(it["thumbnail"].str).ifEmpty { webUrl(it["src"].str) } }.orEmpty()
            Merch.Item(Merch.Source.WOOCOMMERCE, title, type, image, price, currency, url)
        }
    }

    // ---------------------------------------------------------------- Big Cartel

    /** A Big Cartel answer to `/products.json`: a list of products with a permalink (Shopify's is an object). */
    fun isBigCartel(e: JsonElement?): Boolean = e is JsonArray && e.isNotEmpty() && e.all { it["permalink"].str != null && it["name"].str != null }

    /**
     * A Big Cartel catalogue (`/products.json`): active products with an option not sold out, the lowest
     * price of those, the currency from the shop's `/store.json` ([store]), a small picture. A shop
     * named after the artist ([store]'s name), or [whole]ly theirs, gives everything; else what names them.
     */
    fun parseBigCartel(body: String, store: String?, base: String, artist: String, whole: Boolean): List<Merch.Item> {
        val s = store?.let { runCatching { parseJson(it) }.getOrNull() }
        val currency = s["currency"]["code"].str?.uppercase()?.takeIf { it.length == 3 }
        val names = Names(artist)
        val all = whole || names.named(s["name"].str)
        return parseJson(body).arr.mapNotNull { p ->
            val title = p["name"].str?.let { plain(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (p["status"].str?.let { it != "active" } == true) return@mapNotNull null
            val options = p["options"].arr
            val open = options.filter { it["sold_out"].bool != true }
            if (options.isNotEmpty() && open.isEmpty()) return@mapNotNull null
            val type = p["categories"].arr.firstNotNullOfOrNull { it["name"].str }?.let { plain(it) }.orEmpty()
            if (!all && !names.named(title)) return@mapNotNull null
            if (notGoods(title, type)) return@mapNotNull null
            val price = open.mapNotNull { it["price"].str?.toDoubleOrNull() }.minOrNull() ?: p["price"].str?.toDoubleOrNull()
            val path = p["url"].str ?: "/product/${p["permalink"].str}"
            val url = JsonLd.absolute(path, "$base/") ?: return@mapNotNull null
            val pic = p["images"].arr.firstOrNull()?.let { webUrl(it["secure_url"].str).ifEmpty { webUrl(it["url"].str) } }.orEmpty()
            // Big Cartel's pictures are resized on request: a small copy.
            val image = if (pic.isNotEmpty() && '?' !in pic) "$pic?auto=format&fit=max&w=300" else pic
            Merch.Item(Merch.Source.BIGCARTEL, title, type, image, price, currency?.takeIf { price != null }, url)
        }
    }

    // ---------------------------------------------------------------- Squarespace

    /**
     * A Squarespace shop page as JSON (`?format=json`, read only where the site's robots.txt allows it:
     * Squarespace's own closes it): physical products with a variant in stock, the lowest price
     * (`priceMoney`, else cents), a small picture, the product's page. Digital products and gift cards left out.
     */
    fun parseSquarespace(body: String, base: String, artist: String, whole: Boolean): List<Merch.Item> {
        val o = parseJson(body)
        val storeCurrency = o["websiteSettings"]["storeSettings"]["selectedCurrency"].str ?: o["website"]["baseCurrency"].str
        val names = Names(artist)
        return o["items"].arr.mapNotNull { p ->
            val sc = p["structuredContent"]
            val kind = p["productType"].long ?: sc["productType"].long
            if (kind == 2L || kind == 4L) return@mapNotNull null
            val title = p["title"].str?.let { plain(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = JsonLd.absolute(p["fullUrl"].str, "$base/") ?: return@mapNotNull null
            if (!whole && !names.named(title)) return@mapNotNull null
            val variants = (p["variants"] as? JsonArray ?: sc["variants"] as? JsonArray).orEmpty()
            fun inStock(v: JsonElement): Boolean {
                if (v["unlimited"].bool == true || v["stock"]["unlimited"].bool == true) return true
                val q = v["qtyInStock"].long ?: v["stock"]["quantity"].long ?: return true
                return q > 0
            }
            val open = variants.filter { inStock(it) }
            if (variants.isNotEmpty() && open.isEmpty()) return@mapNotNull null
            var currency: String? = null
            val price = open.mapNotNull { v ->
                val sale = v["onSale"].bool == true
                val money = (if (sale) v["salePriceMoney"] else null)?.takeIf { it["value"].str != null } ?: v["priceMoney"]
                money["value"].str?.toDoubleOrNull()?.also { currency = currency ?: money["currency"].str }
                    ?: ((if (sale) v["salePrice"].long?.takeIf { it > 0 } else null) ?: v["price"].long)?.let { it / 100.0 }
            }.minOrNull()
            val cur = (currency ?: storeCurrency)?.uppercase()?.takeIf { it.length == 3 && price != null }
            val type = p["categories"].arr.firstNotNullOfOrNull { it.str }.orEmpty()
            if (notGoods(title, type)) return@mapNotNull null
            val pic = webUrl(p["assetUrl"].str)
            val image = if (pic.isNotEmpty() && '?' !in pic) "$pic?format=300w" else pic
            Merch.Item(Merch.Source.SQUARESPACE, title, type, image, price, cur, url)
        }
    }

    // ---------------------------------------------------------------- schema.org products

    private val OUT = Regex("OutOfStock|SoldOut|Discontinued", RegexOption.IGNORE_CASE)

    /**
     * The schema.org `Product`s a page carries (a shop page's list, a product page's own): those with an
     * offer still in stock (or no availability said), the lowest price and its currency, a picture, the
     * product's page ([page] when it says none). [source]: the platform the page is on.
     */
    fun parseJsonLd(html: String, page: String, artist: String, whole: Boolean, source: Merch.Source = Merch.Source.SITE): List<Merch.Item> {
        val names = Names(artist)
        return JsonLd.nodes(html).mapNotNull { p ->
            val types = JsonLd.types(p)
            if ("Product" !in types && "ProductGroup" !in types) return@mapNotNull null
            val title = JsonLd.text(p["name"])?.let { plain(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val offers = JsonLd.offers(p["offers"]) + p["hasVariant"].arr.flatMap { JsonLd.offers(it["offers"]) }
            val open = offers.filter { o -> o["availability"].str?.let { OUT.containsMatchIn(it) } != true }
            if (offers.isNotEmpty() && open.isEmpty()) return@mapNotNull null
            val type = JsonLd.text(p["category"])?.let { plain(it) }.orEmpty()
            if (!whole && !names.named(title) && !names.named(JsonLd.text(p["brand"]))) return@mapNotNull null
            if (notGoods(title, type)) return@mapNotNull null
            var currency: String? = null
            val price = open.mapNotNull { o ->
                val v = (o["price"] ?: o["lowPrice"] ?: o["priceSpecification"]["price"]).str?.let { amount(it) }
                v?.also { currency = currency ?: (o["priceCurrency"] ?: o["priceSpecification"]["priceCurrency"]).str }
            }.minOrNull()
            val url = JsonLd.absolute(JsonLd.url(p["url"]) ?: open.firstNotNullOfOrNull { JsonLd.url(it["url"]) }, page) ?: page
            val image = JsonLd.absolute(JsonLd.url(p["image"]), page).orEmpty()
            Merch.Item(source, title, type, image, price, currency?.uppercase()?.takeIf { it.length == 3 && price != null }, url)
        }.distinctBy { it.url.substringBefore('#') + "|" + it.title }
    }

    /** "25.00", "25,00", "1.250,00", "$25": the amount; null when none. */
    internal fun amount(s: String): Double? {
        val digits = s.filter { it.isDigit() || it == '.' || it == ',' }.ifEmpty { return null }
        val lastDot = digits.lastIndexOf('.')
        val lastComma = digits.lastIndexOf(',')
        val normal = when {
            lastComma > lastDot && digits.length - lastComma - 1 in 1..2 -> digits.replace(".", "").replace(',', '.')
            else -> digits.replace(",", "")
        }
        return normal.toDoubleOrNull()?.takeIf { it >= 0 }
    }

    // ---------------------------------------------------------------- pages on the site

    /** Words a site's shop page goes by, in its address or its link (several languages). */
    private val SHOP_WORDS = setOf(
        "shop", "store", "merch", "merchandise", "boutique", "tienda", "loja", "negozio", "winkel", "sklep", "butik",
        "kauppa", "obchod", "magazin", "webshop", "webstore", "onlineshop",
    )
    private val SHOP_TEXT = Regex(
        """\b(shop|store|merch|merchandise|boutique|tienda|loja|negozio|winkel|sklep|butik|kauppa|webshop)\b|магазин|ストア|ショップ|商店|商城|상점|스토어|متجر""",
        RegexOption.IGNORE_CASE,
    )

    private fun sameSite(h: String?, site: String) = h == null || h.lowercase().removePrefix("www.") == site.lowercase().removePrefix("www.")

    /** The site's own shop page ("/shop", "/store", a link named "Merch") on [site]'s host, or null. */
    fun ownShopPath(html: String, site: String): String? = Merch.links(html).firstNotNullOfOrNull { (href, label) ->
        if (href.startsWith("#") || href.startsWith("mailto:", true) || href.startsWith("javascript:", true)) return@firstNotNullOfOrNull null
        val u = runCatching { URI(href.replace(" ", "%20")) }.getOrNull() ?: return@firstNotNullOfOrNull null
        if (u.scheme != null && u.scheme !in setOf("http", "https")) return@firstNotNullOfOrNull null
        if (!sameSite(u.host, site)) return@firstNotNullOfOrNull null
        val path = u.rawPath.orEmpty()
        val first = path.trim('/').substringBefore('/').lowercase()
        if (first.isEmpty()) return@firstNotNullOfOrNull null
        path.takeIf { first in SHOP_WORDS || SHOP_TEXT.containsMatchIn(Merch.text(label)) }
    }

    private val PRODUCT_PATH = Regex("""/(products?|p|items?|shop|store|merch|boutique|tienda|loja|negozio|winkel|sklep)/[^/?#]+/?$""", RegexOption.IGNORE_CASE)
    private val NOT_PRODUCT = Regex("""/(cart|checkout|account|login|basket|categor(y|ies)|collections?|tags?|page)(/|$)""", RegexOption.IGNORE_CASE)

    /** The product pages a shop page ([path] on [site]) links to, on the same host, each once, at most [max]. */
    fun productPaths(html: String, site: String, path: String, max: Int): List<String> {
        val listed = JsonLd.nodes(html).filter { "ListItem" in JsonLd.types(it) || "Product" in JsonLd.types(it) }.mapNotNull { JsonLd.url(it["url"]) ?: it["item"].str }
        return (listed.asSequence() + Merch.links(html).map { it.first }).mapNotNull { href ->
            val u = runCatching { URI(href.replace(" ", "%20")) }.getOrNull() ?: return@mapNotNull null
            if (!sameSite(u.host, site)) return@mapNotNull null
            u.rawPath?.takeIf { p -> p.isNotEmpty() && p.trimEnd('/') != path.trimEnd('/') && PRODUCT_PATH.containsMatchIn(p) && !NOT_PRODUCT.containsMatchIn(p) }
        }.distinct().take(max).toList()
    }

    private val TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SITE_NAME = Regex("""<meta\b[^>]*property\s*=\s*["']og:site_name["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val CONTENT = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /** What a page calls its site: its og:site_name and its title. */
    fun siteName(html: String): String =
        listOfNotNull(SITE_NAME.find(html)?.value?.let { CONTENT.find(it)?.groupValues?.get(1) }, TITLE.find(html)?.groupValues?.get(1))
            .joinToString(" ") { plain(it) }
}
