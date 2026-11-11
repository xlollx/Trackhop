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
 * Bandcamp, November 2026); robots.txt honoured, one shop per artist, a day's cache, and nothing
 * asked for records only.
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
        val items = Merch.find(env, "Arctic Monkeys", links)
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
        assertEquals(items, Merch.find(env, "Arctic Monkeys", links))
        assertTrue(hits.isEmpty())
        clock += 2 * 3_600_000L
        Merch.find(env, "Arctic Monkeys", links)
        assertTrue(hits.isNotEmpty())
    }

    @Test fun aShopClosedByRobotsIsNotRead() = runBlocking {
        arcticShop()
        answers["/www.arcticmonkeys.com/robots.txt"] = 200 to "User-agent: *\nDisallow: /products"
        assertTrue(Merch.find(env, "Arctic Monkeys", am).isEmpty())
        assertEquals(listOf("/www.arcticmonkeys.com/robots.txt"), hits)
    }

    @Test fun aSiteThatIsNotShopifyOrDoesNotAnswerGivesNothing() = runBlocking {
        answers["/www.arcticmonkeys.com/products.json"] = 200 to "<html>not a shop</html>"
        assertTrue(Merch.find(env, "Arctic Monkeys", am).isEmpty())
        assertFalse(hits.contains("/www.arcticmonkeys.com/meta.json"))
        // A robots.txt that fails (5xx): the host is skipped altogether.
        hits.clear(); clock += Merch.TTL_MS
        answers["/www.arcticmonkeys.com/robots.txt"] = 503 to "busy"
        assertTrue(Merch.find(env, "Arctic Monkeys", am).isEmpty())
        assertEquals(listOf("/www.arcticmonkeys.com/robots.txt"), hits)
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
