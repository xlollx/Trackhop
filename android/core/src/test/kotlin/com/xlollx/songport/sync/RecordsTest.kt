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
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale

/**
 * Records and merch of a playlist's artists, against stand-ins for Discogs and MusicBrainz: what
 * is read from each answer (formats, one record per master, unofficial and digital left out, the
 * marketplace's copies), the url relations as buttons, the token, Discogs' limit and the caches
 * (six hours for Discogs, as its terms ask; a week for MusicBrainz's links).
 */
class RecordsTest {
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val auths: MutableList<String?> = Collections.synchronizedList(ArrayList())
    private val agents: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** The Discogs search answer: status and body. */
    private var search: (String) -> Pair<Int, String> = { 200 to searchPage() }
    private var stats: (String) -> Pair<Int, String> = { 200 to """{"num_for_sale":12,"lowest_price":{"currency":"EUR","value":18.5},"blocked_from_sale":false}""" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += "$path?$query"
            if (path.startsWith("/dg/")) { auths += ex.requestHeaders.getFirst("Authorization"); agents += ex.requestHeaders.getFirst("User-Agent") }
            val (code, body) = when {
                path == "/dg/database/search" -> search(query)
                path.startsWith("/dg/marketplace/stats/") -> stats(path)
                path == "/ws/2/artist" -> 200 to """{"artists":[
                    {"id":"11111111-1111-1111-1111-111111111111","name":"Radiohead Tribute","score":100},
                    {"id":"a74b1b7f-71a5-4011-9441-d0b5e4122711","name":"Radiohead","score":100}]}"""
                path == "/ws/2/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711" -> 200 to RELATIONS
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = java.time.Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Records.discogsBase = "$base/dg"
        Records.now = { clock }
        Records.anonPace = Pacer(0)
        Records.tokenPace = Pacer(0)
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
    }

    @After fun tearDown() {
        server.stop(0)
        Records.discogsBase = "https://api.discogs.com"
        Records.now = { System.currentTimeMillis() }
        Records.anonPace = Pacer(2_500)
        Records.tokenPace = Pacer(1_050)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
    }

    private fun result(id: Long, master: Long, title: String, year: String?, vararg formats: String, have: Int = 100) = """
        {"id":$id,"master_id":$master,"title":"$title","year":${year?.let { "\"$it\"" } ?: "null"},
         "format":[${formats.joinToString(",") { "\"$it\"" }}],"uri":"/release/$id-x","community":{"have":$have,"want":1}}"""

    private fun searchPage() = """{"pagination":{"items":7},"results":[
        ${result(1174296, 21520, "Radiohead - In Rainbows", "2016", "Vinyl", "LP", "Album", "Reissue")},
        ${result(105704, 21491, "Radiohead - OK Computer", "1997", "CD", "Album")},
        ${result(1184135, 21520, "Radiohead - In Rainbows", "2007", "CD", "Album")},
        ${result(9, 21491, "Radiohead = レディオヘッド* - OK Computer = ＯＫコンピューター", "1998", "Cassette", "Album")},
        ${result(10, 555, "Radiohead - Live Bootleg", "1999", "CD", "Unofficial Release")},
        ${result(11, 556, "Radiohead - Hail To The Thief (Live)", "2025", "File", "FLAC")},
        ${result(12, 557, "Radiohead Tribute - Covers", "2010", "CD")},
        ${result(13, 0, "Radiohead (2) - Demo Tape", null, "Cassette")}
    ]}"""

    private val RELATIONS = """{"name":"Radiohead","relations":[
        {"type":"official homepage","ended":false,"url":{"resource":"https://radiohead.com/"}},
        {"type":"official homepage","ended":false,"url":{"resource":"http://www.radiohead.com/"}},
        {"type":"bandcamp","ended":false,"url":{"resource":"https://radiohead.bandcamp.com/"}},
        {"type":"purchase for mail-order","ended":false,"url":{"resource":"https://shop.radiohead.com/"}},
        {"type":"purchase for download","ended":false,"url":{"resource":"https://itunes.apple.com/us/artist/id657515"}},
        {"type":"social network","ended":false,"url":{"resource":"https://www.instagram.com/radiohead/"}},
        {"type":"social network","ended":true,"url":{"resource":"https://plus.google.com/1181"}},
        {"type":"free streaming","ended":false,"url":{"resource":"https://open.spotify.com/artist/4Z8W"}},
        {"type":"discogs","ended":false,"url":{"resource":"https://www.discogs.com/artist/3840"}},
        {"type":"wikidata","ended":false,"url":{"resource":"https://www.wikidata.org/wiki/Q44190"}}
    ]}"""

    // ------------------------------------------------------------------ reading the answers

    @Test fun formatsAreReadAndOneRecordPerMasterWithItsFirstYear() {
        assertEquals(Records.Format.VINYL, Records.format("Vinyl"))
        assertEquals(Records.Format.CD, Records.format("CDr"))
        assertEquals(Records.Format.CASSETTE, Records.format("Cassette"))
        assertNull(Records.format("File")); assertNull(Records.format("DVD")); assertNull(Records.format("LP"))
        val got = Records.parseSearch(searchPage(), "Radiohead")
        // In Rainbows (vinyl and CD), OK Computer (CD and the Japanese cassette), the numbered "Radiohead (2)"
        // tape: the bootleg, the download and the tribute band are left out.
        assertEquals(listOf("In Rainbows", "OK Computer", "Demo Tape"), got.map { it.title })
        val rainbows = got[0]
        assertEquals(listOf(Records.Format.VINYL, Records.Format.CD), rainbows.formats)
        assertEquals(2007, rainbows.year)
        assertEquals("https://www.discogs.com/master/21520", rainbows.url)
        assertEquals(1174296L, rainbows.releaseId)
        assertEquals("https://www.discogs.com/sell/release/1174296", rainbows.sellUrl)
        assertEquals(listOf(Records.Format.CD, Records.Format.CASSETTE), got[1].formats)
        // No master: the release's own page.
        assertEquals("https://www.discogs.com/release/13-x", got[2].url)
        assertNull(got[2].year)
        assertTrue(Records.parseSearch("""{"results":[]}""", "Radiohead").isEmpty())
    }

    @Test fun theMarketplaceStatsAreReadOnlyWhenCopiesAreThere() {
        assertEquals(Triple(12, 18.5, "EUR"), Records.parseStats("""{"num_for_sale":12,"lowest_price":{"currency":"EUR","value":18.5}}"""))
        assertEquals(Triple(0, null, null), Records.parseStats("""{"num_for_sale":0,"lowest_price":null,"blocked_from_sale":false}"""))
        assertNull(Records.parseStats("""{"num_for_sale":3,"lowest_price":{"currency":"EUR","value":2.0},"blocked_from_sale":true}"""))
    }

    @Test fun urlRelationsBecomeButtonsInOrderEachAddressOnce() {
        val links = Records.parseLinks(RELATIONS)
        assertEquals(
            listOf(
                Records.Kind.OFFICIAL to "radiohead.com", Records.Kind.SHOP to "shop.radiohead.com", Records.Kind.BANDCAMP to "Bandcamp",
                Records.Kind.DOWNLOAD to "Apple", Records.Kind.SOCIAL to "Instagram", Records.Kind.DISCOGS to "Discogs",
            ),
            links.map { it.kind to it.label },
        )
        // Streaming, Wikidata and an ended relation are not buttons; http becomes https.
        assertTrue(links.none { "spotify" in it.url || "wikidata" in it.url || "plus.google" in it.url })
        assertTrue(links.all { it.url.startsWith("https://") })
        assertEquals("X", Records.siteName("twitter.com")); assertEquals("Amazon", Records.siteName("amazon.co.uk"))
    }

    @Test fun theArtistIsTheExactNameNotTheTributeBand() {
        val body = """{"artists":[{"id":"11111111-1111-1111-1111-111111111111","name":"Mina Tribute","score":100},
            {"id":"a56f5af0-3e87-4877-b68f-390f62903381","name":"Mina","score":100},{"id":"f0a5e020-0c8e-484a-b183-4f90c3826cae","name":"Mina","score":73}]}"""
        assertEquals("a56f5af0-3e87-4877-b68f-390f62903381", Records.pickArtist(body, "Mina"))
        assertNull(Records.pickArtist("""{"artists":[{"id":"f0a5e020-0c8e-484a-b183-4f90c3826cae","name":"Mina","score":73}]}""", "Mina"))
    }

    @Test fun searchesAreShopLinksWithoutAffiliateTags() {
        val s = Records.searches("Sigur Rós")
        assertEquals(listOf("Bandcamp", "Discogs", "eBay", null), s.map { it.site })
        assertTrue(s[0].url, s[0].url.startsWith("https://bandcamp.com/search?q=Sigur%20R%C3%B3s"))
        assertTrue(s.none { Regex("[?&](tag|affid|campid|ref|aff)=").containsMatchIn(it.url) })
    }

    @Test fun currencyFollowsTheLanguageWhenDiscogsHasIt() {
        assertEquals("EUR", Records.currencyFor(Locale.ITALY))
        assertEquals("USD", Records.currencyFor(Locale.US))
        assertEquals("JPY", Records.currencyFor(Locale.JAPAN))
        // The Polish zloty is not a marketplace currency.
        assertEquals("EUR", Records.currencyFor(Locale("pl", "PL")))
    }

    // ------------------------------------------------------------------ the search

    @Test fun withoutATokenRecordsAndLinksButNoPrices() = runBlocking {
        val r = Records.find(env, listOf("Radiohead", "radiohead", "Nobody Known"), "EUR")
        assertEquals(2, r.total)
        assertFalse(r.token)
        val rh = r.artists.first()
        assertEquals(3, rh.records!!.size)
        assertTrue(rh.records!!.all { it.forSale == null })
        assertEquals("https://www.discogs.com/artist/3840", rh.discogsPage)
        assertTrue(rh.links.none { it.kind == Records.Kind.DISCOGS })
        assertEquals(4, rh.searches.size)
        // An artist MusicBrainz does not know: no links, a Discogs search for the attribution.
        val nobody = r.artists.last()
        assertTrue(nobody.links.isEmpty())
        assertTrue(nobody.discogsPage, nobody.discogsPage.startsWith("https://www.discogs.com/search/?q=Nobody%20Known"))
        assertTrue(hits.none { it.contains("marketplace") })
        assertTrue(auths.all { it == null })
        // Discogs asks every app to name itself.
        assertTrue(agents.all { it != null && it.startsWith("Trackhop") })
        val call = hits.first { it.startsWith("/dg/database/search") }
        assertTrue(call, call.contains("artist=Radiohead") && call.contains("type=release") && call.contains("sort=have"))
    }

    @Test fun withATokenTheFirstRecordsGetTheMarketplace() = runBlocking {
        Records.saveToken(env, "  " + "t".repeat(40) + " ")
        val r = Records.find(env, listOf("Radiohead"), "EUR")
        assertTrue(r.token)
        val rec = r.artists.single().records!!
        assertEquals(12, rec[0].forSale); assertEquals(18.5, rec[0].lowestPrice!!, 0.001); assertEquals("EUR", rec[0].currency)
        assertEquals(3, hits.count { it.startsWith("/dg/marketplace/stats/") && it.contains("curr_abbr=EUR") })
        assertTrue(auths.all { it == "Discogs token=" + "t".repeat(40) })
        // Kept in the vault, not in the settings (which a set-up export carries).
        assertTrue(env.store.data.settings.clientIds.values.none { it.contains("tttt") })
        Records.saveToken(env, "")
        assertNull(Records.token(env))
    }

    @Test fun aRefusedTokenFallsBackToAnonymousCalls() = runBlocking {
        Records.saveToken(env, "bad")
        var first = true
        search = { if (first) { first = false; 401 to """{"message":"Invalid consumer token."}""" } else 200 to searchPage() }
        val r = Records.find(env, listOf("Radiohead"), "EUR")
        assertTrue(r.tokenRefused)
        assertEquals(3, r.artists.single().records!!.size)
        assertNull(auths.last())
    }

    @Test fun discogsLimitStopsItsCallsButTheLinksAndSearchesStay() = runBlocking {
        search = { 429 to """{"message":"You are making requests too quickly."}""" }
        val r = Records.find(env, listOf("Radiohead", "Muse", "Blur"), "EUR")
        assertTrue(r.slowedDown)
        assertEquals(1, hits.count { it.startsWith("/dg/") })
        assertTrue(r.artists.all { it.records == null && it.searches.isNotEmpty() })
        assertTrue(r.artists.first().links.isNotEmpty())
    }

    @Test fun discogsIsKeptSixHoursAndTheLinksAWeek() = runBlocking {
        Records.find(env, listOf("Radiohead"), "EUR")
        val dg = hits.count { it.startsWith("/dg/") }
        val mb = hits.count { it.startsWith("/ws/2/") }
        Records.find(env, listOf("Radiohead"), "EUR")
        assertEquals(dg, hits.count { it.startsWith("/dg/") })
        assertEquals(mb, hits.count { it.startsWith("/ws/2/") })
        clock += Records.DISCOGS_TTL_MS + 1
        Records.find(env, listOf("Radiohead"), "EUR")
        assertEquals("Discogs asked again after six hours", dg * 2, hits.count { it.startsWith("/dg/") })
        assertEquals("the links are still fresh", mb, hits.count { it.startsWith("/ws/2/") })
        clock += Records.LINKS_TTL_MS
        Records.find(env, listOf("Radiohead"), "EUR")
        assertTrue(hits.count { it.startsWith("/ws/2/") } > mb)
    }

    @Test fun lookupsAreLimitedWithoutAToken() = runBlocking {
        search = { 200 to """{"results":[]}""" }
        val many = (1..(Records.MAX_ANON + 5)).map { "Artist $it" }
        val r = Records.find(env, many, "EUR")
        assertEquals(Records.MAX_ANON + 5, r.total)
        assertEquals(Records.MAX_ANON, r.artists.size)
    }
}
