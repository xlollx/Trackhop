package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.platform.BuildDefaults
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * The artists' own sites, for those MusicBrainz names no site or shop for: Wikidata's choice of the
 * artist (never a namesake), the shops of other platforms than Shopify (WooCommerce, Big Cartel,
 * Squarespace, a page's schema.org products), how a site's platform is told before it is asked,
 * robots.txt and the request cap, and the dates a site gives to Concerts (its schema.org events,
 * the Bandsintown and Seated widgets). Made-up artists, sites and answers throughout.
 */
class OwnSitesTest {
    private fun fixture(name: String) = javaClass.getResource("/merch/$name")!!.readText()

    /** Every request: host/path, with its query. */
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val agents: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** host/path (no query) → status and body; anything else is a 404. */
    private val answers = HashMap<String, Pair<Int, String>>()
    /** Wikidata's answers, by its "action". */
    private val wikidata = HashMap<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += path + (if (query.isEmpty()) "" else "?$query")
            agents += ex.requestHeaders.getFirst("User-Agent")
            val (code, body) = when {
                path == "/wd/api.php" -> query.split('&').firstOrNull { it.startsWith("action=") }?.substringAfter('=')?.let { wikidata[it] }?.let { 200 to it } ?: (404 to "{}")
                else -> answers[path] ?: (404 to "not here")
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2030-03-01T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Merch.hostBase = { "$base/$it" }
        Merch.hostGap = 0
        Merch.resetPaces(0)
        Records.now = { clock }
        Concerts.now = { clock }
        Concerts.zone = { java.time.ZoneOffset.UTC }
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
        ArtistSites.enabled = true
        ArtistSites.wikidataBase = "$base/wd/api.php"
        ArtistSites.wikidataPace = Pacer(0)
        BuildDefaults.values = emptyMap()
    }

    @After fun tearDown() {
        server.stop(0)
        Merch.hostBase = { "https://$it" }
        Merch.hostGap = 1_000
        Merch.resetPaces()
        Records.now = { System.currentTimeMillis() }
        Concerts.now = { System.currentTimeMillis() }
        Concerts.zone = { java.time.ZoneId.systemDefault() }
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
        ArtistSites.enabled = false
        ArtistSites.spotify = null
        ArtistSites.wikidataBase = "https://www.wikidata.org/w/api.php"
        ArtistSites.wikidataPace = Pacer(500)
    }

    // ------------------------------------------------------------------ Wikidata

    private fun item(v: String) = """{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"$v"}}}}"""
    private fun text(v: String, rank: String = "normal", ended: Boolean = false) =
        """{"rank":"$rank","mainsnak":{"datavalue":{"value":"$v"}}${if (ended) ""","qualifiers":{"P582":[{"snaktype":"value"}]}""" else ""}}"""

    /** One entity of a wbgetentities answer. */
    private fun entity(
        id: String, label: String, kinds: List<String>, jobs: List<String> = emptyList(), ids: Map<String, String> = emptyMap(),
        sites: List<String> = emptyList(), aliases: List<String> = emptyList(),
    ): String {
        val claims = buildList {
            add("\"P31\":[" + kinds.joinToString(",") { item(it) } + "]")
            if (jobs.isNotEmpty()) add("\"P106\":[" + jobs.joinToString(",") { item(it) } + "]")
            ids.forEach { (p, v) -> add("\"$p\":[${text(v)}]") }
            if (sites.isNotEmpty()) add("\"P856\":[" + sites.joinToString(",") + "]")
        }
        return """"$id":{"id":"$id","labels":{"en":{"language":"en","value":"$label"}},
            "aliases":{"en":[${aliases.joinToString(",") { """{"language":"en","value":"$it"}""" }}]},"claims":{${claims.joinToString(",")}}}"""
    }

    private fun entities(vararg e: String) = """{"entities":{${e.joinToString(",")}}}"""

    private val footballer = entity("Q101", "Kilo Marrow", listOf("Q5"), jobs = listOf("Q937857"))
    private val rapper = entity("Q102", "Kilo Marrow", listOf("Q5"), jobs = listOf("Q2252262"), sites = listOf(text("https://kilomarrow.example/")))
    private val band = entity("Q103", "Kilo Marrow", listOf("Q215380"), ids = mapOf("P434" to "mb-other", "P1902" to "sp-band"), sites = listOf(text("https://band.example/")))

    @Test fun wikidataTakesTheMusicianNeverANamesake() {
        // A footballer of the same name is not the artist.
        assertEquals("Q102", ArtistSites.chooseEntity(entities(footballer, rapper), "Kilo Marrow", null, null))
        // Two musicians of that name and nothing to tell them apart: none is sure.
        assertNull(ArtistSites.chooseEntity(entities(footballer, rapper, band), "Kilo Marrow", null, null))
        // The MusicBrainz id we know is the band's... or another's: then the one with no MusicBrainz id is left.
        assertEquals("Q103", ArtistSites.chooseEntity(entities(rapper, band), "Kilo Marrow", "mb-other", null))
        assertEquals("Q102", ArtistSites.chooseEntity(entities(rapper, band), "Kilo Marrow", "mb-mine", null))
        // Spotify's id tells them apart too.
        assertEquals("Q103", ArtistSites.chooseEntity(entities(rapper, band), "Kilo Marrow", null, "sp-band"))
        // A name that is only close is not the artist's; an alias is.
        assertNull(ArtistSites.chooseEntity(entities(entity("Q104", "Kilo Marrow Jr", listOf("Q5"), jobs = listOf("Q2252262"))), "Kilo Marrow", null, null))
        assertEquals("Q105", ArtistSites.chooseEntity(entities(entity("Q105", "K. Marrow", listOf("Q5"), jobs = listOf("Q177220"), aliases = listOf("Kilo Marrow"))), "Kilo Marrow", null, null))
        // A person with only a music database's id is a musician.
        assertEquals("Q106", ArtistSites.chooseEntity(entities(footballer, entity("Q106", "Kilo Marrow", listOf("Q5"), ids = mapOf("P1953" to "123"))), "Kilo Marrow", null, null))
    }

    @Test fun theOfficialSiteIsThePreferredOneNotAnEndedOneAndBandcampToo() {
        val e = entities(entity("Q107", "Kilo Marrow", listOf("Q5"), jobs = listOf("Q2252262"), ids = mapOf("P3283" to "kilomarrow"),
            sites = listOf(text("https://old.example/", ended = true), text("https://kilomarrow.example/en"), text("https://kilomarrow.example/", rank = "preferred"))))
        val links = ArtistSites.siteLinks(e, "Q107")
        assertEquals(listOf(Records.Kind.OFFICIAL to "https://kilomarrow.example/", Records.Kind.BANDCAMP to "https://kilomarrow.bandcamp.com/"), links.map { it.kind to it.url })
    }

    @Test fun spotifysLinksGiveTheSiteAndTheShopNotTheSocialPages() {
        val links = ArtistSites.spotifyLinks(ArtistSites.SpotifyArtist("sp1",
            links = listOf("https://www.instagram.com/kilo", "https://en.wikipedia.org/wiki/Kilo", "https://kilomarrow.example/"),
            merch = listOf("https://shop.kilomarrow.example/products/tee?utm_source=spotify", "https://open.spotify.com/merch/x")))
        assertEquals(listOf(Records.Kind.OFFICIAL to "https://kilomarrow.example/", Records.Kind.SHOP to "https://shop.kilomarrow.example/"), links.map { it.kind to it.url })
    }

    @Test fun theScriptOfANameChoosesTheLanguageItIsSearchedIn() {
        assertEquals("en", ArtistSites.language("Kilo Marrow"))
        assertEquals("ja", ArtistSites.language("きのこバンド"))
        assertEquals("ko", ArtistSites.language("별빛"))
        assertEquals("ru", ArtistSites.language("Звезда"))
    }

    // ------------------------------------------------------------------ WooCommerce, found through Wikidata

    private val woo = """[
        {"id":1,"name":"Marrow Logo Tee &#8211; Black","permalink":"https://kilomarrow.example/product/logo-tee/","is_in_stock":true,"is_purchasable":true,
         "prices":{"price":"2500","regular_price":"2500","currency_code":"USD","currency_minor_unit":2},"images":[{"src":"https://kilomarrow.example/wp-content/uploads/tee.jpg","thumbnail":"https://kilomarrow.example/wp-content/uploads/tee-300x300.jpg"}],
         "categories":[{"name":"T-Shirts"}]},
        {"id":2,"name":"Night Shift Vinyl","permalink":"https://kilomarrow.example/product/night-shift-lp/","is_in_stock":true,
         "prices":{"price":"3199","currency_code":"USD","currency_minor_unit":2},"images":[],"categories":[{"name":"Vinyl"}]},
        {"id":3,"name":"Sold Out Hoodie","permalink":"https://kilomarrow.example/product/hoodie/","is_in_stock":false,
         "prices":{"price":"6000","currency_code":"USD","currency_minor_unit":2},"categories":[{"name":"Hoodies"}]},
        {"id":4,"name":"Night Shift (Digital Download)","permalink":"https://kilomarrow.example/product/dl/","is_in_stock":true,
         "prices":{"price":"900","currency_code":"USD","currency_minor_unit":2},"categories":[{"name":"Uncategorized"}]}
    ]"""

    @Test fun wooCommercePricesAreInTheShopsMinorUnits() {
        val items = ShopPlatforms.parseWoo(woo, "Kilo Marrow", whole = true)
        assertEquals(listOf("Marrow Logo Tee – Black", "Night Shift Vinyl"), items.map { it.title })
        assertEquals(25.0, items[0].price!!, 0.0)
        assertEquals("USD", items[0].currency)
        assertEquals("T-Shirts", items[0].type)
        assertEquals("https://kilomarrow.example/wp-content/uploads/tee-300x300.jpg", items[0].image)
        assertEquals(31.99, items[1].price!!, 0.0)
        assertEquals(Records.Format.VINYL, items[1].format)
        assertEquals(Merch.Source.WOOCOMMERCE, items[0].source)
        assertEquals("kilomarrow.example", items[0].shopName)
        // A currency with no minor unit (yen): the number is the price.
        val yen = """[{"name":"Tour Towel","permalink":"https://x.example/p/towel","is_in_stock":true,"prices":{"price":"3500","currency_code":"JPY","currency_minor_unit":0}}]"""
        assertEquals(3500.0, ShopPlatforms.parseWoo(yen, "Kilo Marrow", whole = true).single().price!!, 0.0)
        // Three decimals (dinars).
        val kwd = """[{"name":"Cap","permalink":"https://x.example/p/cap","is_in_stock":true,"prices":{"price":"12500","currency_code":"KWD","currency_minor_unit":3}}]"""
        assertEquals(12.5, ShopPlatforms.parseWoo(kwd, "Kilo Marrow", whole = true).single().price!!, 0.0)
        // Someone else's shop gives only what names the artist in full.
        assertTrue(ShopPlatforms.parseWoo(woo, "Kilo Marrow", whole = false).isEmpty())
        assertEquals(1, ShopPlatforms.parseWoo(woo.replace("Marrow Logo Tee", "Kilo Marrow Logo Tee"), "Kilo Marrow", whole = false).size)
    }

    private val wooHome = """<html><head><link rel='stylesheet' href='https://kilomarrow.example/wp-content/plugins/woocommerce/assets/css/woocommerce.css'></head>
        <body class="home woocommerce-js"><nav><a href="/shop/">Shop</a><a href="/tour/">Tour</a></nav></body></html>"""

    private fun wikidataFor(vararg e: String) {
        wikidata["wbsearchentities"] = """{"search":[${e.joinToString(",") { """{"id":"${it.substringAfter('"').substringBefore('"')}"}""" }}]}"""
        wikidata["wbgetentities"] = entities(*e)
    }

    @Test fun anArtistMusicBrainzHasNoSiteForGetsWikidatasAndItsWooCommerceShop() = runBlocking {
        wikidataFor(footballer, rapper)
        answers["/kilomarrow.example/robots.txt"] = 200 to "User-agent: *\nDisallow: /wp-admin/\n"
        answers["/kilomarrow.example/"] = 200 to wooHome
        answers["/kilomarrow.example/wp-json/wc/store/v1/products"] = 200 to woo
        val found = Merch.find(env, "Kilo Marrow", emptyList())
        assertEquals(listOf("Marrow Logo Tee – Black", "Night Shift Vinyl"), found.items.map { it.title })
        assertFalse(found.unread)
        // Wikidata: the name's search, then its entities, politely; the site: robots first, its home page, the Store API.
        val wd = hits.filter { it.startsWith("/wd/") }
        assertEquals(2, wd.size)
        assertTrue(wd.all { it.contains("maxlag=5") && it.contains("format=json") })
        assertTrue(wd[0].contains("action=wbsearchentities") && wd[0].contains("search=Kilo Marrow"))
        assertTrue(wd[1].contains("action=wbgetentities") && wd[1].contains("ids=Q101|Q102"))
        assertTrue(hits.contains("/kilomarrow.example/wp-json/wc/store/v1/products?per_page=30"))
        assertFalse(hits.any { it.startsWith("/store.kilomarrow.example") || it.startsWith("/shop.kilomarrow.example") })
        // Every request names the app.
        assertTrue(agents.all { it != null && it.startsWith("Trackhop") })
        // Kept: asked again, nothing is requested.
        hits.clear()
        assertEquals(found.items, Merch.find(env, "Kilo Marrow", emptyList()).items)
        assertTrue(hits.isEmpty())
    }

    @Test fun aMailOrderShopThatIsNotShopifyIsReadByItsOwnPage() = runBlocking {
        // MusicBrainz (or Spotify's merch shelf) names a shop and no site: not Shopify, its home page says WooCommerce.
        answers["/kilostore.example/"] = 200 to "<html><head><title>Kilo Marrow Official Store</title></head>" + wooHome.substringAfter("<html>")
        answers["/kilostore.example/wp-json/wc/store/v1/products"] = 200 to woo
        val found = Merch.find(env, "Kilo Marrow", listOf(Records.Link(Records.Kind.SHOP, "", "https://kilostore.example/")))
        // A shop named after the artist: everything in it is theirs.
        assertEquals(listOf("Marrow Logo Tee – Black", "Night Shift Vinyl"), found.items.map { it.title })
        assertEquals(listOf("/kilostore.example/robots.txt", "/kilostore.example/products.json?limit=30", "/kilostore.example/",
            "/kilostore.example/wp-json/wc/store/v1/products?per_page=30"), hits)
    }

    @Test fun aSiteMusicBrainzKnowsIsNotLookedUpOnWikidata() = runBlocking {
        answers["/kilomarrow.example/"] = 200 to wooHome
        answers["/kilomarrow.example/wp-json/wc/store/v1/products"] = 200 to woo
        Merch.find(env, "Kilo Marrow", listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://kilomarrow.example/")))
        assertTrue(hits.none { it.startsWith("/wd/") })
        assertTrue(hits.contains("/kilomarrow.example/wp-json/wc/store/v1/products?per_page=30"))
    }

    @Test fun wikidataNotAnsweringIsNotNothingThere() = runBlocking {
        val found = Merch.find(env, "Kilo Marrow", emptyList())
        assertTrue(found.unread)
        // Not kept: asked again.
        hits.clear()
        Merch.find(env, "Kilo Marrow", emptyList())
        assertTrue(hits.any { it.startsWith("/wd/") })
    }

    @Test fun aClosedStoreApiIsNotAskedAndTheShopPagesSchemaOrgIsRead() = runBlocking {
        answers["/kilomarrow.example/robots.txt"] = 200 to "User-agent: *\nDisallow: /wp-json/\n"
        answers["/kilomarrow.example/"] = 200 to wooHome
        answers["/kilomarrow.example/shop/"] = 200 to """<a href="/product/logo-tee/">Tee</a><a href="/cart/">Cart</a>"""
        answers["/kilomarrow.example/product/logo-tee/"] = 200 to """<script type="application/ld+json">
            {"@context":"https://schema.org/","@type":"Product","name":"Marrow Logo Tee","image":["https://kilomarrow.example/tee.jpg"],
             "offers":[{"@type":"Offer","price":"25.00","priceCurrency":"EUR","availability":"http://schema.org/InStock","url":"https://kilomarrow.example/product/logo-tee/"}]}</script>"""
        val found = Merch.find(env, "Kilo Marrow", listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://kilomarrow.example/")))
        assertTrue(hits.none { it.contains("/wp-json/") })
        assertFalse(hits.contains("/kilomarrow.example/cart/"))
        assertEquals(listOf("Marrow Logo Tee"), found.items.map { it.title })
        assertEquals(Merch.Source.SITE, found.items[0].source)
        assertEquals("EUR", found.items[0].currency)
    }

    // ------------------------------------------------------------------ Big Cartel

    private val bigCartel = """[
        {"name":"Static Orchard Tee","permalink":"orchard-tee","url":"/product/orchard-tee","status":"active","price":22.0,
         "categories":[{"name":"Apparel"}],"images":[{"url":"https://assets.bigcartel.com/product_images/1/tee.jpg"}],
         "options":[{"name":"S","price":22.0,"sold_out":true},{"name":"M","price":20.0,"sold_out":false}]},
        {"name":"Orchard Poster","permalink":"poster","url":"/product/poster","status":"sold-out","price":10.0,"options":[]},
        {"name":"Orchard Hat","permalink":"hat","url":"/product/hat","status":"active","price":15.0,"options":[{"name":"One","price":15.0,"sold_out":true}]}
    ]"""

    @Test fun bigCartelsCatalogueAtShopifysAddress() = runBlocking {
        val items = ShopPlatforms.parseBigCartel(bigCartel, """{"name":"Static Orchard","currency":{"code":"GBP","sign":"£"}}""", "https://orchard.bigcartel.com", "Static Orchard", whole = false)
        assertEquals(listOf("Static Orchard Tee"), items.map { it.title })
        assertEquals(20.0, items[0].price!!, 0.0)
        assertEquals("GBP", items[0].currency)
        assertEquals("https://orchard.bigcartel.com/product/orchard-tee", items[0].url)
        assertEquals("https://assets.bigcartel.com/product_images/1/tee.jpg?auto=format&fit=max&w=300", items[0].image)
        // A shop the site links to: Shopify's address answers with Big Cartel's list.
        answers["/staticorchard.example/"] = 200 to """<a href="https://orchard.bigcartel.com/">Merch</a>"""
        answers["/orchard.bigcartel.com/products.json"] = 200 to bigCartel
        answers["/orchard.bigcartel.com/store.json"] = 200 to """{"name":"Static Orchard","currency":{"code":"GBP"}}"""
        val found = Merch.find(env, "Static Orchard", listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://staticorchard.example/")))
        assertEquals(listOf("Static Orchard Tee"), found.items.map { it.title })
        assertEquals(Merch.Source.BIGCARTEL, found.items[0].source)
        assertEquals("orchard.bigcartel.com", found.items[0].shopName)
        assertFalse(hits.any { it.startsWith("/orchard.bigcartel.com/meta.json") })
    }

    // ------------------------------------------------------------------ Squarespace

    private val squarespaceJson = """{"websiteSettings":{"storeSettings":{"selectedCurrency":"CAD"}},"items":[
        {"title":"Lumen Drift Hoodie","fullUrl":"/shop/p/hoodie","assetUrl":"https://images.squarespace-cdn.com/content/v1/a/hoodie.jpg","productType":1,
         "variants":[{"priceMoney":{"currency":"CAD","value":"65.00"},"qtyInStock":0,"unlimited":false},{"priceMoney":{"currency":"CAD","value":"60.00"},"qtyInStock":4}]},
        {"title":"Lumen Drift Cap","fullUrl":"/shop/p/cap","structuredContent":{"productType":1,"variants":[{"price":2500,"onSale":true,"salePrice":2000,"unlimited":true}]}},
        {"title":"Lumen Drift EP (download)","fullUrl":"/shop/p/ep","productType":2,"variants":[{"price":800,"unlimited":true}]},
        {"title":"Gone Tee","fullUrl":"/shop/p/gone","variants":[{"price":2000,"qtyInStock":0}]}
    ]}"""

    @Test fun squarespacesShopJson() {
        val items = ShopPlatforms.parseSquarespace(squarespaceJson, "https://lumendrift.example", "Lumen Drift", whole = true)
        assertEquals(listOf("Lumen Drift Hoodie", "Lumen Drift Cap"), items.map { it.title })
        assertEquals(60.0, items[0].price!!, 0.0)
        assertEquals("CAD", items[0].currency)
        assertEquals("https://lumendrift.example/shop/p/hoodie", items[0].url)
        assertEquals("https://images.squarespace-cdn.com/content/v1/a/hoodie.jpg?format=300w", items[0].image)
        // In cents, on sale: the sale price, in the store's currency.
        assertEquals(20.0, items[1].price!!, 0.0)
        assertEquals("CAD", items[1].currency)
    }

    private val squarespaceHome = """<html><head><link rel="stylesheet" href="https://static1.squarespace.com/static/versioned-site-css/a.css"></head>
        <body><a href="/shop">Shop</a><a href="/tour-dates">Live</a></body></html>"""
    private val squarespaceShop = """<html><body><script type="application/ld+json">{"@context":"http://schema.org","@type":"ItemList","itemListElement":[
        {"@type":"ListItem","position":1,"url":"https://lumendrift.example/shop/p/hoodie"}]}</script>
        <a href="/shop/p/hoodie">Hoodie</a><a href="/shop/p/cap">Cap</a></body></html>"""
    private fun product(name: String, price: String) = """<script type="application/ld+json">{"@context":"http://schema.org","@type":"Product","name":"$name",
        "image":"https://images.squarespace-cdn.com/x.jpg","offers":{"@type":"Offer","price":"$price","priceCurrency":"CAD","availability":"InStock"}}</script>"""

    @Test fun squarespacesJsonOnlyWhereRobotsAllowsElseItsSchemaOrgProducts() = runBlocking {
        // Squarespace's own robots.txt closes "?format=json": the shop page and its product pages instead.
        answers["/lumendrift.example/robots.txt"] = 200 to fixture("squarespace-robots.txt")
        answers["/lumendrift.example/"] = 200 to squarespaceHome
        answers["/lumendrift.example/shop"] = 200 to squarespaceShop
        answers["/lumendrift.example/shop/p/hoodie"] = 200 to product("Lumen Drift Hoodie", "60.00")
        answers["/lumendrift.example/shop/p/cap"] = 200 to product("Lumen Drift Cap", "20.00")
        val site = listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://lumendrift.example/"))
        val found = Merch.find(env, "Lumen Drift", site)
        assertTrue(hits.none { it.contains("format=json") })
        assertEquals(listOf("Lumen Drift Hoodie", "Lumen Drift Cap"), found.items.map { it.title })
        assertTrue(found.items.all { it.source == Merch.Source.SQUARESPACE && it.currency == "CAD" })
        // A site whose robots.txt allows it: its JSON, one request.
        hits.clear(); clock += Merch.TTL_MS; Merch.resetPaces(0)
        answers["/lumendrift.example/robots.txt"] = 200 to "User-agent: *\nAllow: /\n"
        answers["/lumendrift.example/shop"] = 200 to squarespaceJson
        val json = Merch.find(env, "Lumen Drift", site)
        assertTrue(hits.contains("/lumendrift.example/shop?format=json"))
        assertEquals(listOf("Lumen Drift Hoodie", "Lumen Drift Cap"), json.items.map { it.title })
        assertFalse(hits.contains("/lumendrift.example/shop/p/hoodie"))
    }

    // ------------------------------------------------------------------ schema.org products

    @Test fun schemaOrgProductsOfAShopPage() {
        val html = """<html><script type="application/ld+json">{"@context":"https://schema.org","@graph":[
            {"@type":"WebSite","name":"Velvet Static"},
            {"@type":"Product","name":"Velvet Static Tour Tee","category":"Apparel","image":{"@type":"ImageObject","url":"/img/tee.jpg"},"url":"/merch/tee",
             "offers":{"@type":"AggregateOffer","lowPrice":"1.250,00","priceCurrency":"eur","offers":[{"@type":"Offer","availability":"https://schema.org/InStock"}]}},
            {"@type":"Product","name":"Velvet Static Poster","offers":{"@type":"Offer","price":15,"priceCurrency":"EUR","availability":"https://schema.org/OutOfStock"}},
            {"@type":"ProductGroup","name":"Velvet Static Hoodie","hasVariant":[
              {"@type":"Product","name":"Velvet Static Hoodie M","offers":{"@type":"Offer","price":"55.00","priceCurrency":"EUR","url":"/merch/hoodie?size=m"}}]}
            ]}</script><script type="application/ld+json">{ not json </script></html>"""
        val items = ShopPlatforms.parseJsonLd(html, "https://velvetstatic.example/merch", "Velvet Static", whole = true)
        assertEquals(listOf("Velvet Static Tour Tee", "Velvet Static Hoodie", "Velvet Static Hoodie M"), items.map { it.title })
        val tee = items[0]
        assertEquals(1250.0, tee.price!!, 0.0)
        assertEquals("EUR", tee.currency)
        assertEquals("https://velvetstatic.example/merch/tee", tee.url)
        assertEquals("https://velvetstatic.example/img/tee.jpg", tee.image)
        assertEquals("Apparel", tee.type)
        assertEquals(55.0, items[1].price!!, 0.0)
        assertEquals("https://velvetstatic.example/merch/hoodie?size=m", items[1].url)
        assertEquals(25.0, ShopPlatforms.amount("$25")!!, 0.0)
        assertEquals(1250.5, ShopPlatforms.amount("1,250.50")!!, 0.0)
    }

    @Test fun aSitesShopPageAndItsProductPagesBoundedAndCapped() = runBlocking {
        // A site of its own making: its "Merch" page lists twelve product pages and no data of its own.
        answers["/velvetstatic.example/"] = 200 to """<a href="https://velvetstatic.example/merch">MERCH</a>"""
        answers["/velvetstatic.example/merch"] = 200 to (1..12).joinToString("") { """<a href="/merch/item-$it">Item $it</a>""" }
        for (i in 1..12) answers["/velvetstatic.example/merch/item-$i"] = 200 to product("Velvet Static Item $i", "10.00")
        val found = Merch.find(env, "Velvet Static", listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://velvetstatic.example/")))
        assertEquals((1..Merch.MAX_PRODUCT_PAGES).map { "Velvet Static Item $it" }, found.items.map { it.title })
        assertEquals("velvetstatic.example", found.items[0].shopName)
        assertEquals(Merch.MAX_PRODUCT_PAGES, hits.count { it.startsWith("/velvetstatic.example/merch/item-") })
        // Many shops linked, none answering: the look-up stops at the cap, robots.txt included.
        hits.clear(); clock += Merch.TTL_MS
        answers.clear()
        val many = (1..5).map { Records.Link(Records.Kind.SHOP, "", "https://shop$it.example/") }
        answers["/velvetstatic.example/"] = 200 to """<a href="/merch">Merch</a>"""
        answers["/velvetstatic.example/merch"] = 200 to (1..12).joinToString("") { """<a href="/merch/item-$it">Item $it</a>""" }
        Merch.find(env, "Velvet Static", many + Records.Link(Records.Kind.OFFICIAL, "", "https://velvetstatic.example/"))
        assertEquals(Merch.MAX_REQUESTS, hits.size)
        assertTrue(env.logs.last(), env.logs.last().contains("request cap reached"))
    }

    // ------------------------------------------------------------------ telling the platform

    @Test fun thePlatformIsToldFromThePageBeforeAnythingIsAsked() {
        assertEquals(ShopPlatforms.Platform.WOOCOMMERCE, ShopPlatforms.detect(wooHome))
        assertEquals(ShopPlatforms.Platform.SQUARESPACE, ShopPlatforms.detect(squarespaceHome))
        assertEquals(ShopPlatforms.Platform.SQUARESPACE, ShopPlatforms.detect("<html></html>") { if (it == "Server") "Squarespace" else null })
        assertEquals(ShopPlatforms.Platform.BIGCARTEL, ShopPlatforms.detect("""<link href="https://assets.bigcartel.com/theme_assets/1/a.css">"""))
        assertEquals(ShopPlatforms.Platform.WORDPRESS, ShopPlatforms.detect("""<script src="/wp-includes/js/jquery.js"></script>"""))
        assertEquals(ShopPlatforms.Platform.SHOPIFY, ShopPlatforms.detect("""<script src="//cdn.shopify.com/s/files/1/theme.js"></script>"""))
        assertEquals(ShopPlatforms.Platform.SHOPIFY, ShopPlatforms.detect("<html></html>") { if (it == "X-ShopId") "123" else null })
        assertEquals(ShopPlatforms.Platform.OTHER, ShopPlatforms.detect("<html><a href='/shop'>Shop</a></html>"))
        // The site's own shop page, by its address or its words, in other languages too; never another host's.
        assertEquals("/boutique", ShopPlatforms.ownShopPath("""<a href="/">Home</a><a href="/boutique">Boutique</a>""", "groupe.example"))
        assertEquals("/de/fanartikel", ShopPlatforms.ownShopPath("""<a href="https://www.band.example/de/fanartikel">Shop</a>""", "band.example"))
        assertNull(ShopPlatforms.ownShopPath("""<a href="https://other.example/shop">Shop</a><a href="#shop">Shop</a>""", "band.example"))
    }

    @Test fun aShopClosedByRobotsIsNotAsked() = runBlocking {
        answers["/kilomarrow.example/robots.txt"] = 200 to "User-agent: *\nDisallow: /\n"
        answers["/kilomarrow.example/"] = 200 to wooHome
        val found = Merch.find(env, "Kilo Marrow", listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://kilomarrow.example/")))
        assertTrue(found.items.isEmpty())
        assertEquals(listOf("/kilomarrow.example/robots.txt"), hits.filter { it.startsWith("/kilomarrow.example/") })
    }

    // ------------------------------------------------------------------ concerts on the artist's site

    @Test fun musicEventsOfATourPage() {
        val html = """<script type="application/ld+json">[
            {"@context":"https://schema.org","@type":"MusicEvent","name":"Kilo Marrow live","startDate":"2030-04-02T20:00:00+02:00",
             "location":{"@type":"MusicVenue","name":"Halle Nord","address":{"@type":"PostalAddress","addressLocality":"Hamburg","addressCountry":"DE"}},
             "offers":{"@type":"Offer","url":"https://tickets.example/e/1"}},
            {"@type":"Event","name":"Festival day","startDate":"2030-05-10","location":{"@type":"Place","name":"Parc","address":"Lyon, France"},"url":"/tour/lyon"},
            {"@type":"MusicEvent","name":"Cancelled","startDate":"2030-04-05","eventStatus":"https://schema.org/EventCancelled",
             "location":{"name":"X","address":{"addressLocality":"Wien","addressCountry":"AT"}}},
            {"@type":"MusicEvent","name":"Nowhere said","startDate":"2030-04-06","location":{"name":"Somewhere"}}
            ]</script>"""
        val events = SiteConcerts.parseEvents(html, "https://kilomarrow.example/tour", "Kilo Marrow")
        assertEquals(listOf("2030-04-02", "2030-05-10"), events.map { it.date })
        val hamburg = events[0]
        assertEquals("20:00", hamburg.time)
        assertEquals("Hamburg", hamburg.city); assertEquals("Halle Nord", hamburg.venue); assertEquals("DE", hamburg.country)
        assertEquals("https://tickets.example/e/1", hamburg.url)
        assertEquals(Concerts.Source.SITE, hamburg.source)
        assertEquals("Lyon", events[1].city); assertEquals("FR", events[1].country)
        assertEquals("https://kilomarrow.example/tour/lyon", events[1].url)
        assertNull(events[1].time)
        // Tour pages by their address or their words, on the site or a subdomain of it.
        assertEquals(listOf("https://kilomarrow.example/tour-dates", "https://kilomarrow.example/de/termine", "https://kilomarrow.example/kalender"),
            SiteConcerts.tourPaths("""<a href="/about">About</a><a href="/kalender">Konzerte</a><a href="https://other.example/tour">Tour</a>
                <a href="/tour-dates">x</a><a href="/de/termine/">y</a>""", "kilomarrow.example").map { it.trimEnd('/') })
    }

    @Test fun bandsintownAndSeatedWidgets() {
        val bit = """<script charset="utf-8" src="https://widget.bandsintown.com/main.min.js"></script>
            <a class="bit-widget-initializer" data-artist-name="Kilo Marrow" data-app-id="site-widget-id" data-display-limit="15"></a>"""
        assertEquals("Kilo Marrow" to "site-widget-id", SiteConcerts.bandsintownWidget(bit))
        // No app id of the site's own: not asked.
        assertNull(SiteConcerts.bandsintownWidget("""<a class="bit-widget-initializer" data-artist-name="Kilo Marrow" data-app-id=""></a>"""))
        val events = SiteConcerts.parseBandsintown("""[
            {"datetime":"2030-04-12T19:30:00","title":"","url":"https://www.bandsintown.com/e/1","venue":{"name":"The Hall","city":"Austin","region":"TX","country":"United States"},
             "offers":[{"type":"Tickets","url":"https://www.bandsintown.com/t/1","status":"available"}]},
            {"datetime":"2030-04-20T20:00:00","venue":{"name":"Sala","city":"Madrid","country":"Spain"},"offers":[]},
            {"datetime":"2030-04-22T20:00:00","venue":{"name":"Hidden","city":"Nowhere","country":"Atlantis"}}
            ]""", "Kilo Marrow")
        assertEquals(listOf("US" to "Austin", "ES" to "Madrid"), events.map { it.country to it.city })
        assertEquals("https://www.bandsintown.com/t/1", events[0].url)
        assertEquals("19:30", events[0].time)
        assertEquals("", events[1].url)

        val seated = """<script src="https://widget.seated.com/app.js"></script><div id="seated-55fdf2c0" data-artist-id="0a1b2c3d-1111-2222-3333-444455556666" data-css-version="3"></div>"""
        assertEquals("0a1b2c3d-1111-2222-3333-444455556666", SiteConcerts.seatedWidget(seated))
        assertNull(SiteConcerts.seatedWidget("""<div id="seated-55fdf2c0" data-artist-id="0a1b2c3d"></div>"""))
        val tour = SiteConcerts.parseSeated("""{"data":{"type":"tours","id":"t1"},"included":[
            {"type":"tour-events","id":"ev-1","attributes":{"starts-at-date-local":"2030-06-01","venue-name":"Arena","formatted-address":"Manchester, United Kingdom"}},
            {"type":"tour-events","id":"ev-2","attributes":{"starts-at-date-local":"2030-06-03","venue-name":"Club","formatted-address":"Toronto, ON","venue-country-code":"CA"}},
            {"type":"tour-events","id":"ev-3","attributes":{"starts-at-date-local":"2030-06-05","venue-name":"Bar","formatted-address":"Portland, OR"}},
            {"type":"artists","id":"a1","attributes":{"name":"Kilo Marrow"}}]}""", "Kilo Marrow")
        assertEquals(listOf("GB" to "Manchester", "CA" to "Toronto"), tour.map { it.country to it.city })
        assertEquals("https://link.seated.com/ev-1", tour[0].url)
        assertEquals("FR", SiteConcerts.countryCode("Frankreich"))
        assertEquals("DE", SiteConcerts.countryCode("DEU"))
        assertNull(SiteConcerts.countryCode("Atlantis"))
    }

    private fun mbArea(code: String) = """{"name":"x","iso-3166-1-codes":["$code"]}"""

    @Test fun theArtistsSiteIsAConcertsSourceShownOnceWithTheOthers() = runBlocking {
        wikidataFor(footballer, rapper)
        answers["/kilomarrow.example/"] = 200 to wooHome
        answers["/kilomarrow.example/tour/"] = 200 to """<script type="application/ld+json">[
            {"@type":"MusicEvent","name":"Kilo Marrow","startDate":"2030-04-02T20:00","location":{"name":"Halle Nord","address":{"addressLocality":"Hamburg","addressCountry":"DE"}},"offers":{"url":"https://tickets.example/e/1"}},
            {"@type":"MusicEvent","name":"Kilo Marrow","startDate":"2030-04-09","location":{"name":"Sala","address":{"addressLocality":"Madrid","addressCountry":"ES"}}}]</script>"""
        // MusicBrainz knows the Hamburg date: shown once, the site's.
        answers["/ws/2/event"] = 200 to """{"events":[{"id":"e1","name":"Kilo Marrow","life-span":{"begin":"2030-04-02"},
            "relations":[{"type":"main performer","artist":{"name":"Kilo Marrow"}},{"area":{"id":"a-hh","name":"Hamburg"}}]}]}"""
        answers["/ws/2/area/a-hh"] = 200 to mbArea("DE")
        val r = Concerts.find(env, listOf("Kilo Marrow"), "DE")
        val events = r.artists.single().events
        assertEquals(listOf("2030-04-02" to Concerts.Source.SITE), events.map { it.date to it.source })
        assertEquals("Hamburg", events[0].city)
        assertEquals("tickets.example", Concerts.siteName(events[0].url))
        // Kept a day, for every country: another country asks nothing of the site.
        hits.clear()
        val es = Concerts.find(env, listOf("Kilo Marrow"), "ES")
        assertEquals(listOf("2030-04-09"), es.artists.single().events.map { it.date })
        assertTrue(hits.none { it.startsWith("/kilomarrow.example/") || it.startsWith("/wd/") })
        // Never in the background.
        hits.clear(); clock += Concerts.TTL_MS
        Concerts.find(env, listOf("Kilo Marrow"), "DE", background = true)
        assertTrue(hits.none { it.startsWith("/kilomarrow.example/") || it.startsWith("/wd/") })
    }

    @Test fun aTourPagesBandsintownWidgetIsAskedWithItsOwnAppIdWithinTheCap() = runBlocking {
        answers["/kilomarrow.example/"] = 200 to """<a href="/live">Live</a>"""
        answers["/kilomarrow.example/live"] = 200 to """<a class="bit-widget-initializer" data-artist-name="Kilo Marrow" data-app-id="site-widget-id"></a>"""
        answers["/rest.bandsintown.com/artists/Kilo Marrow/events"] = 200 to """[{"datetime":"2030-04-12T19:30:00","venue":{"name":"The Hall","city":"Austin","country":"United States"},"offers":[]}]"""
        answers["/ws/2/event"] = 200 to """{"events":[]}"""
        Records.remember(env, "mb2|${Records.norm("Kilo Marrow")}", com.xlollx.songport.net.json.encodeToString(Records.MbKept.serializer(),
            Records.MbKept(listOf(Records.Link(Records.Kind.OFFICIAL, "", "https://kilomarrow.example/")))))
        val r = Concerts.find(env, listOf("Kilo Marrow"), "US")
        assertEquals(listOf("2030-04-12" to "Austin"), r.artists.single().events.map { it.date to it.city })
        val bit = hits.single { it.startsWith("/rest.bandsintown.com/artists/") }
        assertTrue(bit, bit.contains("app_id=site-widget-id"))
        // The site MusicBrainz gave (kept by the Records page): no Wikidata.
        assertTrue(hits.none { it.startsWith("/wd/") })
        assertTrue(hits.count { !it.startsWith("/ws/2/") } <= SiteConcerts.MAX_REQUESTS)
    }
}
