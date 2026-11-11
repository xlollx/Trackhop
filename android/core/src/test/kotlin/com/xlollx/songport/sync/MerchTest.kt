package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Merch from the artists' own shops: a Shopify shop's public catalogue and a Bandcamp merch page,
 * read from trimmed copies of real answers (Arctic Monkeys' and Taylor Swift's shops, Khruangbin's
 * Bandcamp; Bring Me The Horizon's, Fall Out Boy's and Bad Omens' home pages and the shops they link
 * to, November 2026); robots.txt honoured, one shop per artist, a day's cache, a shop that did not
 * answer told apart from one with nothing, and nothing asked for records only.
 */
class MerchTest {
    private fun fixture(name: String) = javaClass.getResource("/merch/$name")!!.readText()

    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val agents: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** host/path → status and body; anything else is a 404. */
    private val answers = HashMap<String, Pair<Int, String>>()
    /** host/path → the answer to its first request only (then [answers]'). */
    private val first = HashMap<String, Pair<Int, String>>()
    /** host/path → extra headers of a 429 answer (Retry-After). */
    private val headers = HashMap<String, Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            hits += path
            agents += ex.requestHeaders.getFirst("User-Agent")
            val once = synchronized(first) { first.remove(path) }
            val (code, body) = once ?: answers[path] ?: (404 to "not here")
            if (code == 429) headers[path]?.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-11T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Merch.hostBase = { "$base/$it" }
        Merch.hostGap = 0
        Merch.resetPaces(0)
        Records.discogsBase = "$base/dg"
        Records.now = { clock }
        Records.anonPace = Pacer(0)
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
    }

    @After fun tearDown() {
        server.stop(0)
        Merch.hostBase = { "https://$it" }
        Merch.hostGap = 1_000
        Merch.resetPaces()
        Records.discogsBase = "https://api.discogs.com"
        Records.now = { System.currentTimeMillis() }
        Records.anonPace = Pacer(2_500)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
    }

    private fun link(kind: Records.Kind, url: String) = Records.Link(kind, "", url)

    private val am = listOf(
        link(Records.Kind.OFFICIAL, "https://www.arcticmonkeys.com/"),
        link(Records.Kind.SHOP, "https://www.amazon.co.uk/-/e/B0017PCN3O"),
        link(Records.Kind.SHOP, "https://www.cdjapan.co.jp/person/700239373"),
    )

    private fun arcticShop() {
        answers["/www.arcticmonkeys.com/robots.txt"] = 200 to fixture("shopify-robots.txt")
        answers["/www.arcticmonkeys.com/products.json"] = 200 to fixture("shopify-arcticmonkeys-products.json")
        answers["/www.arcticmonkeys.com/meta.json"] = 200 to fixture("shopify-arcticmonkeys-meta.json")
    }

    // ------------------------------------------------------------------ reading

    @Test fun shopifyProductsOnSaleWithLowestPriceSmallPictureAndPage() {
        val items = Merch.parseShopify(fixture("shopify-arcticmonkeys-products.json"), fixture("shopify-arcticmonkeys-meta.json"), "https://www.arcticmonkeys.com", "Arctic Monkeys")
        // The LP with no variant available is left out.
        assertEquals(listOf("Arctic Monkeys Logo Hood", "Gold Logo Black T-Shirt", "'The Car' LP"), items.map { it.title })
        val hood = items[0]
        assertEquals(Merch.Source.SHOP, hood.source)
        assertEquals("Hoodie", hood.type)
        assertEquals(60.0, hood.price!!, 0.0)
        assertEquals("GBP", hood.currency)
        assertEquals("https://www.arcticmonkeys.com/products/arctic-monkeys-logo-hood", hood.url)
        assertTrue(hood.image, hood.image.startsWith("https://cdn.shopify.com/") && hood.image.contains("_200x.png?v="))
    }

    @Test fun withoutTheShopsCurrencyThePriceHasNone() {
        val items = Merch.parseShopify(fixture("shopify-arcticmonkeys-products.json"), null, "https://www.arcticmonkeys.com", "Arctic Monkeys")
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.currency == null && it.price != null })
    }

    @Test fun aShopNotNamedAfterTheArtistGivesOnlyTheirProductsAndNoDownloads() {
        val body = fixture("shopify-taylorswift-products.json")
        // The official store: its vendor names her; the digital download is not merch.
        val label = """{"name":"A Label Store","currency":"USD"}"""
        assertEquals(listOf("Taylor Swift Eras Crewneck"), Merch.parseShopify(body, label, "https://store.example", "Taylor Swift").map { it.title })
        // Someone else's products in a label's shop are not hers.
        assertTrue(Merch.parseShopify(body, label, "https://store.example", "Arctic Monkeys").isEmpty())
    }

    @Test fun shopifyPicturesGetTheSmallCopy() {
        assertEquals("https://cdn.shopify.com/s/files/1/a/b/x_200x.jpg?v=1", Merch.smallShopifyImage("https://cdn.shopify.com/s/files/1/a/b/x.jpg?v=1"))
        assertEquals("https://cdn.shopify.com/a/x_200x.png", Merch.smallShopifyImage("//cdn.shopify.com/a/x.png"))
        assertEquals("https://cdn.shopify.com/a/noext", Merch.smallShopifyImage("https://cdn.shopify.com/a/noext"))
    }

    @Test fun bandcampMerchItemsWithPriceAndPictureSoldOutLeftOut() {
        val items = Merch.parseBandcamp(fixture("bandcamp-khruangbin-merch.html"), "https://khruangbin.bandcamp.com")
        // Five in the page: the sold-out ones (a vinyl, the shirt) are not shown.
        assertEquals(3, items.size)
        val first = items[0]
        assertEquals(Merch.Source.BANDCAMP, first.source)
        assertEquals("Khruangbin & Men I Trust: Live at RBC Echo Beach – Live at RBC Echo Beach Black Vinyl LP", first.title)
        assertEquals("Vinyl", first.type)
        assertEquals(27.99, first.price!!, 0.0)
        assertEquals("USD", first.currency)
        assertEquals("https://f4.bcbits.com/img/0033213085_37.jpg", first.image)
        assertEquals("https://khruangbin.bandcamp.com/album/khruangbin-men-i-trust-live-at-rbc-echo-beach", first.url)
        // A lazy picture: the real address, not the placeholder.
        assertTrue(items.all { it.image.startsWith("https://f4.bcbits.com/") })
    }

    @Test fun robotsRulesLongestMatchWinsAndAnAgentsOwnGroupFirst() {
        val shopify = fixture("shopify-robots.txt")
        assertTrue(Merch.robotsAllows(shopify, "/products.json"))
        assertTrue(Merch.robotsAllows(shopify, "/meta.json"))
        assertFalse(Merch.robotsAllows(shopify, "/checkout"))
        assertTrue(Merch.robotsAllows(fixture("bandcamp-robots.txt"), "/merch"))
        assertFalse(Merch.robotsAllows(fixture("bandcamp-robots.txt"), "/search"))
        assertFalse(Merch.robotsAllows("User-agent: *\nDisallow: /", "/merch"))
        assertTrue(Merch.robotsAllows("User-agent: *\nDisallow: /\n\nUser-agent: Trackhop\nAllow: /products", "/products.json"))
        assertFalse(Merch.robotsAllows("User-agent: *\nDisallow: /*.json$", "/products.json"))
        assertTrue(Merch.robotsAllows("User-agent: *\nDisallow:", "/products.json"))
        assertTrue(Merch.robotsAllows("", "/merch"))
    }

    @Test fun robotsOfRealShopsAndGroupsForOtherAgents() {
        // Shopify, 2026: the catalogue, a collection's and the home page are open; carts and sorted lists are not.
        val shopify = fixture("shopify-robots-2026.txt")
        assertTrue(Merch.robotsAllows(shopify, "/products.json?limit=30"))
        assertTrue(Merch.robotsAllows(shopify, "/collections/bad-omens/products.json?limit=30"))
        assertTrue(Merch.robotsAllows(shopify, "/"))
        assertFalse(Merch.robotsAllows(shopify, "/cart/add"))
        assertFalse(Merch.robotsAllows(shopify, "/collections/all?sort_by=price"))
        // Squarespace: AI crawlers grouped with "*" share its rules, no "Disallow: /" for everyone.
        val squarespace = fixture("squarespace-robots.txt")
        assertTrue(Merch.robotsAllows(squarespace, "/"))
        assertFalse(Merch.robotsAllows(squarespace, "/search"))
        // A group for other crawlers is not this app's, even when their name is part of it or empty.
        assertTrue(Merch.robotsAllows("User-agent: track\nDisallow: /\n\nUser-agent: *\nAllow: /", "/products.json"))
        assertTrue(Merch.robotsAllows("User-agent:\nDisallow: /", "/products.json"))
        assertTrue(Merch.robotsAllows("User-agent: GPTBot\nUser-agent: ClaudeBot\nDisallow: /\n\nUser-agent: *\nDisallow: /cart", "/products.json"))
        assertFalse(Merch.robotsAllows("User-agent: Trackhop/2.0\nDisallow: /", "/products.json"))
    }

    @Test fun retailersAreNeverAskedAndShopsComeFirst() {
        val hosts = Merch.shopHosts(listOf(
            link(Records.Kind.OFFICIAL, "https://www.taylorswift.com/"),
            link(Records.Kind.SHOP, "https://www.target.com/c/-/N-54u4c"),
            link(Records.Kind.SHOP, "https://store.taylorswift.com/collections/music-products"),
            link(Records.Kind.SHOP, "https://www.amazon.co.uk/-/e/B0017PCN3O"),
            link(Records.Kind.BANDCAMP, "https://taylor.bandcamp.com/"),
        ))
        assertEquals(listOf("store.taylorswift.com", "www.taylorswift.com"), hosts)
    }

    @Test fun bothSourcesShareTheRoom() {
        fun items(s: Merch.Source, n: Int) = List(n) { Merch.Item(s, "$s $it", url = "u$it") }
        assertEquals(6, Merch.mix(items(Merch.Source.SHOP, 20), items(Merch.Source.BANDCAMP, 20), 12).count { it.source == Merch.Source.SHOP })
        assertEquals(10, Merch.mix(items(Merch.Source.SHOP, 20), items(Merch.Source.BANDCAMP, 2), 12).count { it.source == Merch.Source.SHOP })
        assertEquals(10, Merch.mix(items(Merch.Source.SHOP, 2), items(Merch.Source.BANDCAMP, 20), 12).count { it.source == Merch.Source.BANDCAMP })
        assertEquals(3, Merch.mix(items(Merch.Source.SHOP, 3), emptyList(), 12).size)
    }

    // ------------------------------------------------------------------ asking the shops

    @Test fun theOfficialShopAndBandcampAreReadAndKeptTwoDays() = runBlocking {
        arcticShop()
        answers["/khruangbin.bandcamp.com/robots.txt"] = 200 to fixture("bandcamp-robots.txt")
        answers["/khruangbin.bandcamp.com/merch"] = 200 to fixture("bandcamp-khruangbin-merch.html")
        val links = am + link(Records.Kind.BANDCAMP, "https://khruangbin.bandcamp.com/")
        val items = Merch.find(env, "Arctic Monkeys", links).items
        assertEquals(3, items.count { it.source == Merch.Source.SHOP })
        assertEquals(3, items.count { it.source == Merch.Source.BANDCAMP })
        // Robots first; the retailers never.
        assertEquals(listOf(
            "/www.arcticmonkeys.com/robots.txt", "/www.arcticmonkeys.com/products.json", "/www.arcticmonkeys.com/meta.json",
            "/khruangbin.bandcamp.com/robots.txt", "/khruangbin.bandcamp.com/merch",
        ), hits)
        assertTrue(agents.all { it != null && it.startsWith("Trackhop") })
        hits.clear()
        clock += 47 * 3_600_000L
        assertEquals(items, Merch.find(env, "Arctic Monkeys", links).items)
        assertTrue(hits.isEmpty())
        clock += 2 * 3_600_000L
        Merch.find(env, "Arctic Monkeys", links).items
        assertTrue(hits.isNotEmpty())
    }

    @Test fun aShopClosedByRobotsIsNotRead() = runBlocking {
        arcticShop()
        answers["/www.arcticmonkeys.com/robots.txt"] = 200 to "User-agent: *\nDisallow: /products"
        assertTrue(Merch.find(env, "Arctic Monkeys", am).items.isEmpty())
        // The home page is still open to it: read for the shops it links to; then store. and shop. of its domain.
        assertEquals(listOf(
            "/www.arcticmonkeys.com/robots.txt", "/www.arcticmonkeys.com/",
            "/store.arcticmonkeys.com/robots.txt", "/store.arcticmonkeys.com/products.json",
            "/shop.arcticmonkeys.com/robots.txt", "/shop.arcticmonkeys.com/products.json",
        ), hits)
    }

    @Test fun aSiteThatIsNotShopifyGivesNothingAndOneThatDoesNotAnswerIsNotRead() = runBlocking {
        answers["/www.arcticmonkeys.com/products.json"] = 200 to "<html>not a shop</html>"
        val none = Merch.find(env, "Arctic Monkeys", am)
        assertTrue(none.items.isEmpty())
        assertFalse(none.unread)
        assertFalse(hits.contains("/www.arcticmonkeys.com/meta.json"))
        // A robots.txt that fails (5xx): the host is skipped altogether, and that is not "nothing there".
        hits.clear(); clock += Merch.TTL_MS
        answers["/www.arcticmonkeys.com/robots.txt"] = 503 to "busy"
        val down = Merch.find(env, "Arctic Monkeys", am)
        assertTrue(down.items.isEmpty())
        assertTrue(down.unread)
        assertEquals("/www.arcticmonkeys.com/robots.txt", hits.first())
        assertFalse(hits.any { it.startsWith("/www.arcticmonkeys.com/") && !it.endsWith("robots.txt") })
        assertTrue(env.logs.last(), env.logs.last().startsWith("[merch] Arctic Monkeys: www.arcticmonkeys.com/robots.txt: 503") && env.logs.last().endsWith("not read"))
        // Not kept: asked again at once, and found when the shop is back.
        hits.clear()
        arcticShop()
        assertEquals(3, Merch.find(env, "Arctic Monkeys", am).items.size)
        assertTrue(hits.contains("/www.arcticmonkeys.com/products.json"))
    }

    // ------------------------------------------------------------------ shops the official site links to

    @Test fun theShopsAnOfficialHomePageLinksTo() {
        // A store subdomain, after a link that does not name a shop (horizonsupply.co).
        assertEquals(listOf("https://shop.bmthofficial.com/?utm_source=Website&utm_medium=Organic&utm_content=Site%20Navigation"),
            Merch.storeLinks(fixture("home-bmth.html"), "www.bmthofficial.com"))
        // A link page named "store"; the other link pages (music, an album) and the social pages are not shops.
        assertEquals(listOf("https://falloutboy.lnk.to/store"), Merch.storeLinks(fixture("home-falloutboy.html"), "www.falloutboy.com"))
        // The artist's collections in the distributors' shops; the site's own /shop pages are the site itself.
        assertEquals(listOf("https://merchjungle.com/collections/bad-omens", "https://cutloosemerch.ca/collections/bad-omens"),
            Merch.storeLinks(fixture("home-badomens.html"), "badomensofficial.com"))
        // Those on the site's own domain first.
        assertEquals(listOf("https://store.band.com/", "https://label-store.com/x"),
            Merch.storeLinks("""<a href="https://label-store.com/x">x</a><a href='https://store.band.com/'>s</a><a href="https://cdn.shopify.com/s">c</a>""", "www.band.com"))
    }

    @Test fun aLinkPageSendsToTheShopItNames() {
        assertEquals("https://store.falloutboy.com", Merch.landingShop(fixture("lnk-falloutboy-store.html"), "falloutboy.lnk.to"))
        assertNull(Merch.landingShop("""<a href="https://open.spotify.com/x">""", "x.lnk.to"))
    }

    @Test fun aShopsCollectionIsTheArtists() {
        assertEquals(Merch.Shop("merchjungle.com", "bad-omens"), Merch.shopOf("https://merchjungle.com/collections/bad-omens"))
        assertEquals(Merch.Shop("store.falloutboy.com"), Merch.shopOf("https://store.falloutboy.com"))
        assertEquals(Merch.Shop("shop.band.com"), Merch.shopOf("https://shop.band.com/products/tee"))
        assertNull(Merch.shopOf("https://www.amazon.com/shop/band"))
    }

    @Test fun theStoreSubdomainOfTheOfficialSiteIsRead() = runBlocking {
        answers["/www.bmthofficial.com/"] = 200 to fixture("home-bmth.html")
        answers["/shop.bmthofficial.com/robots.txt"] = 200 to fixture("shopify-robots-2026.txt")
        answers["/shop.bmthofficial.com/products.json"] = 200 to fixture("shopify-bmth-products.json")
        answers["/shop.bmthofficial.com/meta.json"] = 200 to fixture("shopify-bmth-meta.json")
        val found = Merch.find(env, "Bring Me The Horizon", listOf(link(Records.Kind.OFFICIAL, "https://www.bmthofficial.com/")))
        // "Cleanse This Earth" has no size left.
        assertEquals(listOf("Dance on Your Grave | T-Shirt", "Recount Your Blessings | T-Shirt", "Medusa Long | LS T-Shirt"), found.items.map { it.title })
        assertEquals("https://shop.bmthofficial.com/products/dance-on-your-grave-t-shirt", found.items[0].url)
        assertEquals("GBP", found.items[0].currency)
        assertEquals(listOf(
            "/www.bmthofficial.com/robots.txt", "/www.bmthofficial.com/products.json", "/www.bmthofficial.com/",
            "/shop.bmthofficial.com/robots.txt", "/shop.bmthofficial.com/products.json", "/shop.bmthofficial.com/meta.json",
        ), hits)
    }

    @Test fun aLinkPageIsFollowedOnceToTheShop() = runBlocking {
        answers["/www.falloutboy.com/"] = 200 to fixture("home-falloutboy.html")
        answers["/falloutboy.lnk.to/store"] = 200 to fixture("lnk-falloutboy-store.html")
        answers["/store.falloutboy.com/products.json"] = 200 to fixture("shopify-falloutboy-products.json")
        answers["/store.falloutboy.com/meta.json"] = 200 to fixture("shopify-falloutboy-meta.json")
        val found = Merch.find(env, "Fall Out Boy", listOf(link(Records.Kind.OFFICIAL, "https://www.falloutboy.com/")))
        assertEquals(4, found.items.size)
        assertTrue(found.items.all { it.url.startsWith("https://store.falloutboy.com/products/") && it.currency == "USD" })
        assertTrue(hits.containsAll(listOf("/falloutboy.lnk.to/robots.txt", "/falloutboy.lnk.to/store", "/store.falloutboy.com/robots.txt")))
        assertTrue(env.logs.last(), env.logs.last().contains("falloutboy.lnk.to/store → store.falloutboy.com"))
    }

    /** A small Shopify catalogue: [titles] by [vendor], one $25 variant each. */
    private fun catalogue(vendor: String, vararg titles: String) = titles.joinToString(",", """{"products":[""", "]}") { t ->
        """{"title":"$t","handle":"${t.lowercase().replace(' ', '-')}","vendor":"$vendor","product_type":"T-Shirt","variants":[{"price":"25.00","available":true,"requires_shipping":true}],"images":[]}"""
    }

    @Test fun aLinkPageWhoseRobotsDoesNotAnswerHasOnlyTheGivenLinkOpened() = runBlocking {
        // A Day To Remember, November 2026: the site's "Store" is a found.ee link; found.ee drops the robots.txt request.
        answers["/www.adtr.com/"] = 200 to """<nav><a href="https://found.ee/adtrstore" target="_blank">Store</a><a href="https://found.ee/adtr-tour">Tour</a></nav>"""
        answers["/found.ee/robots.txt"] = 503 to "busy"
        answers["/found.ee/adtrstore"] = 200 to """<script>var redirectUrl = "https://adtrstore.com"; window.location = redirectUrl;</script>"""
        answers["/adtrstore.com/products.json"] = 200 to catalogue("ADTR", "Gargoyle Tee", "Paranoia Logo Tee")
        answers["/adtrstore.com/meta.json"] = 200 to """{"name":"ADTR","currency":"USD","description":"A Day To Remember's official online store."}"""
        val found = Merch.find(env, "A Day To Remember", listOf(link(Records.Kind.OFFICIAL, "https://www.adtr.com/")))
        // The shop is named by the band's initials: all of it is theirs.
        assertEquals(listOf("Gargoyle Tee", "Paranoia Logo Tee"), found.items.map { it.title })
        assertFalse(found.unread)
        // Only that one address on found.ee: not its other link, nothing else there.
        assertEquals(listOf("/found.ee/robots.txt", "/found.ee/adtrstore"), hits.filter { it.startsWith("/found.ee/") })
        // A link page whose robots.txt answers and closes the link: not opened.
        hits.clear(); clock += Merch.TTL_MS
        answers["/found.ee/robots.txt"] = 200 to "User-agent: *\nDisallow: /"
        assertTrue(Merch.find(env, "A Day To Remember", listOf(link(Records.Kind.OFFICIAL, "https://www.adtr.com/"))).items.isEmpty())
        assertFalse(hits.contains("/found.ee/adtrstore"))
    }

    @Test fun aSplashPagesHomeAndALinkNamedStore() = runBlocking {
        // Avenged Sevenfold: the home page is a splash leading to /home, whose "Stores" menu opens a shop not named so.
        answers["/www.avengedsevenfold.com/"] = 200 to """<a href="/home"><img src="/splash.jpg"></a><a href="https://www.avengedsevenfold.io/">Club</a>"""
        answers["/www.avengedsevenfold.com/home"] = 200 to """<a class="nav-link dropdown-toggle" href="https://a7xworld.com/" data-label="Store"><span>Stores</span></a><a href="https://velvethammer.net">Velvet Hammer</a>"""
        answers["/a7xworld.com/products.json"] = 200 to catalogue("The Stage", "Nightmare Tee", "Deathbat Hoodie")
        answers["/a7xworld.com/meta.json"] = 200 to """{"name":"A7X World","currency":"USD","description":"Welcome to the Black Market - the OFFICIAL web store for all things Avenged Sevenfold."}"""
        val found = Merch.find(env, "Avenged Sevenfold", listOf(link(Records.Kind.OFFICIAL, "https://www.avengedsevenfold.com/")))
        assertEquals(listOf("Nightmare Tee", "Deathbat Hoodie"), found.items.map { it.title })
        assertFalse(hits.any { it.startsWith("/velvethammer.net") || it.startsWith("/www.avengedsevenfold.io") })
        // Unquoted addresses (minified pages) and the links' own words.
        assertEquals(listOf("https://sleeptoken.lnk.to/UKstore", "https://merch.example.org/"), Merch.storeLinks(
            """<a href=https://sleeptoken.lnk.to/UKstore class=store-link><span>UK STORE</span></a><a href=https://merch.example.org/>x</a><a href=https://tickets.example.org/>Tickets</a>""",
            "sleep-token.com"))
        assertEquals("/home", Merch.entryPath("""<a href="https://other.com/home">x</a><a href="/home?page=1">more</a>""", "www.avengedsevenfold.com"))
        assertNull(Merch.entryPath("""<a href="/tour">Tour</a>""", "band.com"))
    }

    @Test fun aSiteThatNamesNoShopHasItsStoreSubdomainTried() = runBlocking {
        // Green Day: the home page's menu is drawn by a script; the shop is store.greenday.com.
        answers["/greenday.com/"] = 200 to """<html><body><div id="app"></div></body></html>"""
        answers["/store.greenday.com/products.json"] = 200 to catalogue("Green Day", "Iconic Tee")
        answers["/store.greenday.com/meta.json"] = 200 to """{"name":"Green Day","currency":"USD"}"""
        val found = Merch.find(env, "Green Day", listOf(link(Records.Kind.OFFICIAL, "https://greenday.com/")))
        assertEquals(listOf("Iconic Tee"), found.items.map { it.title })
        assertFalse(hits.any { it.startsWith("/shop.greenday.com") })
        // A guessed host that does not answer is not a shop that did not answer.
        hits.clear(); clock += Merch.TTL_MS
        answers.remove("/store.greenday.com/products.json")
        answers["/store.greenday.com/robots.txt"] = 503 to "no such shop"
        val none = Merch.find(env, "Green Day", listOf(link(Records.Kind.OFFICIAL, "https://greenday.com/")))
        assertTrue(none.items.isEmpty())
        assertFalse(none.unread)
        // An official page on someone else's site (a label's page of the artist): no guess there.
        hits.clear(); clock += Merch.TTL_MS
        Merch.find(env, "Green Day", listOf(link(Records.Kind.OFFICIAL, "https://label.example.com/artist/greenday/")))
        assertFalse(hits.any { it.startsWith("/store.") || it.startsWith("/shop.") })
    }

    @Test fun aShopNamedAfterTheArtistsInitialsOrDescribedAsTheirsIsAllTheirs() {
        val body = catalogue("PTV", "Logo Tee")
        assertEquals(1, Merch.parseShopify(body, """{"name":"PTV"}""", "https://x.com", "Pierce The Veil").size)
        assertEquals(1, Merch.parseShopify(body, """{"name":"Store","description":"The official store of Pierce The Veil"}""", "https://x.com", "Pierce The Veil").size)
        // Two words make no initials worth trusting; a shop of someone else gives only what names the artist.
        assertTrue(Merch.parseShopify(catalogue("GD", "Logo Tee"), """{"name":"GD"}""", "https://x.com", "Green Day").isEmpty())
        assertTrue(Merch.parseShopify(catalogue("Label", "Logo Tee"), """{"name":"Label Shop"}""", "https://x.com", "Pierce The Veil").isEmpty())
    }

    @Test fun theOfficialPagesAreShownOnceEach() {
        fun l(kind: Records.Kind, url: String) = Records.Link(kind, Records.siteName(java.net.URI(url).host.removePrefix("www.")), url)
        val shown = Records.shownLinks(listOf(
            l(Records.Kind.OFFICIAL, "https://www.avengedsevenfold.com/"),
            l(Records.Kind.OFFICIAL, "https://wmg.jp/artist/avengedsevenfold/"),
            l(Records.Kind.SHOP, "https://www.amazon.co.uk/-/e/B00197GPBG"),
            l(Records.Kind.SHOP, "https://www.amazon.com/-/e/B00197GPBG"),
            l(Records.Kind.SHOP, "https://www.ptvmerch.com/"),
            l(Records.Kind.DOWNLOAD, "https://itunes.apple.com/us/artist/id6766228"),
            l(Records.Kind.DOWNLOAD, "https://music.apple.com/us/artist/6766228"),
            l(Records.Kind.DOWNLOAD, "https://open.qobuz.com/artist/1"),
            l(Records.Kind.DOWNLOAD, "https://www.qobuz.com/us-en/interpreter/band/download-streaming-albums"),
            l(Records.Kind.SOCIAL, "https://twitter.com/band"),
            l(Records.Kind.SOCIAL, "https://x.com/band/"),
            l(Records.Kind.SOCIAL, "https://twitter.com/band/"),
        ))
        assertEquals(listOf(
            "" to "https://www.avengedsevenfold.com/", "Amazon" to "https://www.amazon.com/-/e/B00197GPBG", "ptvmerch.com" to "https://www.ptvmerch.com/",
            "Apple" to "https://music.apple.com/us/artist/6766228", "Qobuz" to "https://open.qobuz.com/artist/1", "X" to "https://x.com/band/",
        ), shown.map { it.label to it.url })
        // The only shop is "the official shop".
        assertEquals(listOf(""), Records.shownLinks(listOf(l(Records.Kind.SHOP, "https://www.ptvmerch.com/"))).map { it.label })
    }

    @Test fun theArtistsCollectionInADistributorsShop() = runBlocking {
        answers["/badomensofficial.com/"] = 200 to fixture("home-badomens.html")
        answers["/merchjungle.com/robots.txt"] = 200 to fixture("shopify-robots-2026.txt")
        answers["/merchjungle.com/collections/bad-omens/products.json"] = 200 to fixture("shopify-merchjungle-badomens-products.json")
        answers["/merchjungle.com/meta.json"] = 200 to fixture("shopify-merchjungle-meta.json")
        val found = Merch.find(env, "Bad Omens", listOf(link(Records.Kind.OFFICIAL, "https://badomensofficial.com/")))
        // Sold out (the vinyl, the hood, a tee) left out.
        assertEquals(listOf("Bad Omens / Drip Reaper Tee (Black)", "Bad Omens / Cherub Tote (Black)", "Bad Omens / Take Me Tee"), found.items.map { it.title })
        assertEquals("AUD", found.items[0].currency)
        assertFalse(hits.contains("/cutloosemerch.ca/robots.txt"))
        // A collection named after the artist is theirs, even where the products do not name them.
        val unnamed = fixture("shopify-merchjungle-badomens-products.json").replace("Bad Omens", "BO")
        assertTrue(Merch.parseShopify(unnamed, fixture("shopify-merchjungle-meta.json"), "https://merchjungle.com", "Bad Omens").isEmpty())
        assertEquals(3, Merch.parseShopify(unnamed, fixture("shopify-merchjungle-meta.json"), "https://merchjungle.com", "Bad Omens", whole = true).size)
    }

    @Test fun withoutMusicBrainzTheShopsAreNotReadRatherThanEmpty() = runBlocking {
        answers["/ws/2/artist"] = 503 to "busy"
        val r = Records.find(env, listOf("Arctic Monkeys"), "EUR", look = Records.Look.MERCH)
        assertEquals(emptyList<Merch.Item>(), r.artists[0].merch)
        assertTrue(r.artists[0].shopsUnread)
        assertTrue(hits.none { it.startsWith("/www.") })
    }

    @Test fun recordsInTheShopsGoWithTheRecordsAndMerchOnlyAsksNotDiscogs() = runBlocking {
        arcticShop()
        val id = "ada7a83c-e3e1-40f1-93f9-3e73dbc9298a"
        answers["/ws/2/artist"] = 200 to """{"artists":[{"id":"$id","name":"Arctic Monkeys","score":100}]}"""
        answers["/ws/2/artist/$id"] = 200 to """{"relations":[{"type":"official homepage","ended":false,"url":{"resource":"https://www.arcticmonkeys.com/"}}]}"""
        answers["/dg/database/search"] = 200 to """{"results":[]}"""
        val records = Records.find(env, listOf("Arctic Monkeys"), "EUR", look = Records.Look.RECORDS)
        assertNull(records.artists[0].merch)
        // The shop's LP is a record: shown with the records, from the shop.
        assertEquals(listOf("'The Car' LP"), records.artists[0].shopRecords!!.map { it.title })
        assertEquals(Records.Format.VINYL, records.artists[0].shopRecords!![0].format)
        assertTrue(hits.any { it.startsWith("/dg/") })
        hits.clear()
        val merch = Records.find(env, listOf("Arctic Monkeys"), "EUR", look = Records.Look.MERCH)
        assertEquals(Records.Look.MERCH, merch.look)
        assertNull(merch.artists[0].records)
        assertNull(merch.artists[0].shopRecords)
        assertTrue(hits.none { it.startsWith("/dg/") })
        // Merch keeps only the goods that are not records.
        assertEquals(listOf("Arctic Monkeys Logo Hood", "Gold Logo Black T-Shirt"), merch.artists[0].merch!!.map { it.title })
    }

    @Test fun bandcampRecordsAreRecordsAndItsShirtsMerch() {
        val items = Merch.parseBandcamp(fixture("bandcamp-khruangbin-merch.html"), "https://khruangbin.bandcamp.com")
        assertEquals(items.map { if (it.type == "Shirt") null else Records.Format.VINYL }, items.map { it.format })
    }

    @Test fun whatIsARecordFromTheTypeOrElseTheTitle() {
        fun f(type: String, title: String = "Album") = Merch.recordFormat(type, title)
        // Bandcamp's types.
        assertEquals(Records.Format.VINYL, f("Record/Vinyl"))
        assertEquals(Records.Format.VINYL, f("2 x Vinyl LP"))
        assertEquals(Records.Format.CD, f("Compact Disc (CD)"))
        assertEquals(Records.Format.CASSETTE, f("Cassette"))
        assertNull(f("T-Shirt/Shirt", "Album LP Cover Tee"))
        assertNull(f("Poster/Print"))
        assertNull(f("Bag", "Vinyl Tote"))
        // Shopify: the product type, else the title.
        assertEquals(Records.Format.VINYL, f("LP"))
        assertEquals(Records.Format.VINYL, f("", "The Car – Black Vinyl"))
        assertEquals(Records.Format.VINYL, f("Music", "Single 7\" Picture Disc"))
        assertEquals(Records.Format.VINYL, f("Bundle", "LP + T-Shirt Bundle"))
        assertEquals(Records.Format.CD, f("", "Live in Paris (2CD)"))
        assertEquals(Records.Format.CASSETTE, f("Other", "Demo Tape"))
        // Things for records, and goods without one.
        assertNull(f("", "Logo Vinyl Slipmat"))
        assertNull(f("Accessories", "Vinyl Storage Crate"))
        assertNull(f("Bundle", "Hoodie + Poster Bundle"))
        assertNull(f("", "Enamel Pin"))
        assertNull(f("Hoodie", "Logo Hood"))
    }

    // ------------------------------------------------------------------ Shopify's "too many requests"

    @Test fun aShopAsking429IsBusyNotKeptAndNotAskedAgainWhileItCoolsDown() = runBlocking {
        // November 2026, on a phone: the shared Shopify edge answers 429 to every shop after a burst.
        answers["/badomensofficial.com/"] = 200 to fixture("home-badomens.html")
        answers["/merchjungle.com/collections/bad-omens/products.json"] = 429 to "Too Many Requests"
        headers["/merchjungle.com/collections/bad-omens/products.json"] = mapOf("Retry-After" to "600")
        val links = listOf(link(Records.Kind.OFFICIAL, "https://badomensofficial.com/"))
        val found = Merch.find(env, "Bad Omens", links)
        assertTrue(found.items.isEmpty())
        assertTrue(found.unread)
        assertTrue(found.busy)
        assertTrue(found.retryAt > System.currentTimeMillis() + 500_000)
        // Asked once (no quick retry for a long wait); the other shops and the guesses are not asked into a 429.
        assertEquals(1, hits.count { it == "/merchjungle.com/collections/bad-omens/products.json" })
        assertFalse(hits.any { it.startsWith("/cutloosemerch.ca") || it.startsWith("/store.") || it.startsWith("/shop.") })
        assertTrue(env.logs.last(), env.logs.last().contains("429 (retry after 600 s)") && env.logs.last().endsWith("shop busy"))
        // Not kept, and the shop is not hammered: asked again, it is not called while it cools down.
        hits.clear()
        val again = Merch.find(env, "Bad Omens", links)
        assertTrue(again.busy)
        assertFalse(hits.any { it.startsWith("/merchjungle.com/") && it.endsWith("/products.json") })
    }

    @Test fun aShortRetryAfterIsWaitedOutOnce() = runBlocking {
        arcticShop()
        first["/www.arcticmonkeys.com/products.json"] = 429 to "slow down"
        headers["/www.arcticmonkeys.com/products.json"] = mapOf("Retry-After" to "1")
        val found = Merch.find(env, "Arctic Monkeys", am)
        assertEquals(3, found.items.size)
        assertFalse(found.busy)
        assertEquals(2, hits.count { it == "/www.arcticmonkeys.com/products.json" })
    }

    @Test fun nothingFoundIsKeptLessThanWhatWasFound() = runBlocking {
        answers["/www.arcticmonkeys.com/products.json"] = 200 to "<html>not a shop</html>"
        assertTrue(Merch.find(env, "Arctic Monkeys", am).items.isEmpty())
        hits.clear()
        clock += Merch.EMPTY_TTL_MS - 1
        Merch.find(env, "Arctic Monkeys", am)
        assertTrue(hits.isEmpty())
        clock += 2
        Merch.find(env, "Arctic Monkeys", am)
        assertTrue(hits.isNotEmpty())
    }

    @Test fun theShopsLeftToTheAppAreNotReadBySearch() = runBlocking {
        arcticShop()
        val id = "ada7a83c-e3e1-40f1-93f9-3e73dbc9298a"
        answers["/ws/2/artist"] = 200 to """{"artists":[{"id":"$id","name":"Arctic Monkeys","score":100}]}"""
        answers["/ws/2/artist/$id"] = 200 to """{"relations":[{"type":"official homepage","ended":false,"url":{"resource":"https://www.arcticmonkeys.com/"}}]}"""
        val r = Records.find(env, listOf("Arctic Monkeys"), "EUR", look = Records.Look.MERCH, shops = false)
        assertNull(r.artists[0].merch)
        assertFalse(r.artists[0].shopsUnread)
        assertEquals("https://www.arcticmonkeys.com/", r.artists[0].links.single().url)
        assertTrue(hits.none { it.startsWith("/www.") })
    }

    @Test fun looksByName() {
        assertEquals(Records.Look.MERCH, Records.Look.of("merch"))
        assertEquals(Records.Look.ALL, Records.Look.of(" All "))
        assertNull(Records.Look.of("posters"))
        assertTrue(Records.Look.ALL.covers(Records.Look.MERCH))
        assertFalse(Records.Look.RECORDS.covers(Records.Look.MERCH))
    }
}
