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
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            hits += path
            agents += ex.requestHeaders.getFirst("User-Agent")
            val (code, body) = answers[path] ?: (404 to "not here")
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
        Merch.resetPaces()
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

    @Test fun theOfficialShopAndBandcampAreReadAndKeptADay() = runBlocking {
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
        clock += 23 * 3_600_000L
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
        // The home page is still open to it: read for the shops it links to.
        assertEquals(listOf("/www.arcticmonkeys.com/robots.txt", "/www.arcticmonkeys.com/"), hits)
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
        assertEquals(listOf("/www.arcticmonkeys.com/robots.txt"), hits)
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

    @Test fun looksByName() {
        assertEquals(Records.Look.MERCH, Records.Look.of("merch"))
        assertEquals(Records.Look.ALL, Records.Look.of(" All "))
        assertNull(Records.Look.of("posters"))
        assertTrue(Records.Look.ALL.covers(Records.Look.MERCH))
        assertFalse(Records.Look.RECORDS.covers(Records.Look.MERCH))
    }
}
