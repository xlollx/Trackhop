package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.providers.DiscoverSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * The user's Discogs collection and wantlist against a stand-in for the API (answers written by
 * hand from its documented shape): what is read, Discogs' six hours, the songs of the records, and
 * what an alert tells.
 */
class DiscogsTest {
    private fun res(name: String) = javaClass.getResource("/discogs/$name")!!.readText()
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val auth: MutableList<String?> = Collections.synchronizedList(ArrayList())
    private var stats: (String) -> Pair<Int, String> = { 200 to """{"lowest_price":{"value":34.0,"currency":"EUR"},"num_for_sale":12,"blocked_from_sale":false}""" }
    private var release: (String) -> Pair<Int, String> = { 200 to res("release.json") }
    private var wants: () -> String = { res("wants.json") }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            hits += path + (ex.requestURI.rawQuery?.let { "?$it" } ?: "")
            auth += ex.requestHeaders.getFirst("Authorization")
            val (code, body) = when {
                path == "/oauth/identity" -> 200 to """{"id":1,"username":"some one","resource_url":"https://api.discogs.com/users/some%20one","consumer_name":"x"}"""
                path == "/users/some one/collection/folders/0/releases" -> 200 to res("collection.json")
                path == "/users/some one/collection/value" -> 200 to """{"minimum":"€1,240.00","median":"€1,800.00","maximum":"€2,380.00"}"""
                path == "/users/some one/wants" -> 200 to wants()
                path.startsWith("/marketplace/stats/") -> stats(path.substringAfterLast('/'))
                path.startsWith("/releases/") -> release(path.substringAfterLast('/'))
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = 1_800_000_000_000L

    @Before fun setUp() {
        Records.discogsBase = "http://127.0.0.1:${server.address.port}"
        Records.now = { clock }
        Records.tokenPace = Pacer(0)
        Records.saveToken(env, "TOKEN")
    }

    @After fun tearDown() {
        server.stop(0)
        Records.discogsBase = "https://api.discogs.com"
        Records.now = { System.currentTimeMillis() }
        Records.tokenPace = Pacer(1_050)
    }

    @Test fun `the collection is read with the token, the latest added first, with Discogs' estimate`() = runBlocking {
        val c = Discogs.collection(env)
        assertEquals("some one", c.user)
        assertEquals(listOf("Dummy", "Mezzanine", "OK Computer"), c.items.map { it.title })
        // "Portishead (2)" is Portishead; a spacer is no picture; year 0 is no year; formats once each.
        assertEquals("Portishead", c.items[0].artist); assertEquals(listOf("Vinyl"), c.items[0].formats)
        assertEquals("", c.items[1].thumb); assertNull(c.items[1].year)
        assertEquals("https://www.discogs.com/release/1362037", c.items[2].url)
        assertEquals(Discogs.Value("€1,240.00", "€1,800.00", "€2,380.00"), c.value)
        assertTrue(auth.all { it == "Discogs token=TOKEN" })
        // Kept for Discogs' six hours, and whose token it is for good.
        val n = hits.size
        Discogs.collection(env)
        assertEquals(n, hits.size)
        clock += Records.DISCOGS_TTL_MS + 1
        Discogs.collection(env)
        assertEquals(n + 2, hits.size)
        assertEquals(1, hits.count { it == "/oauth/identity" })
    }

    @Test fun `an empty wantlist kept is read again, so a record just added on Discogs shows`() = runBlocking {
        wants = { """{"pagination":{"page":1,"pages":1,"per_page":100,"items":0},"wants":[]}""" }
        assertTrue(Discogs.wantlist(env, "EUR").items.isEmpty())
        // Added on Discogs a minute later: shown on the next look, well within the six hours.
        wants = { res("wants.json") }
        clock += 60_000
        assertEquals(listOf("Kid A", "Heligoland"), Discogs.wantlist(env, "EUR").items.map { it.title })
        // A list with records is kept as before.
        val n = hits.size
        Discogs.wantlist(env, "EUR")
        assertEquals(n, hits.size)
    }

    @Test fun `no token asks nothing`() {
        Records.saveToken(env, "")
        assertTrue(runCatching { runBlocking { Discogs.collection(env) } }.exceptionOrNull() is Discogs.NoToken)
        assertTrue(hits.isEmpty())
    }

    @Test fun `the wantlist comes with what is for sale, the records with an alert first`() = runBlocking {
        stats = { id -> if (id == "2224498") 200 to """{"lowest_price":null,"num_for_sale":0,"blocked_from_sale":false}""" else 200 to """{"lowest_price":{"value":34.0,"currency":"EUR"},"num_for_sale":12}""" }
        val w = Discogs.wantlist(env, "EUR", first = setOf("2224498"))
        assertEquals(listOf("Kid A", "Heligoland"), w.items.map { it.title })
        assertEquals(12, w.markets["1810187"]?.forSale); assertEquals(34.0, w.markets["1810187"]?.lowest)
        assertEquals(0, w.markets["2224498"]?.forSale); assertNull(w.markets["2224498"]?.lowest)
        val asked = hits.filter { it.startsWith("/marketplace/stats/") }
        assertEquals(listOf("/marketplace/stats/2224498?curr_abbr=EUR", "/marketplace/stats/1810187?curr_abbr=EUR"), asked)
        assertEquals("https://www.discogs.com/sell/release/1810187", w.items[0].sellUrl)
    }

    @Test fun `a record's songs are its tracks, no headings`() {
        val tracks = Discogs.parseTracks(res("release.json"))
        assertEquals(listOf("Airbag", "Paranoid Android", "Exit Music (For A Film)"), tracks.map { it.title })
        assertTrue(tracks.all { it.artists == listOf("Radiohead") && it.album == "OK Computer" && it.id.isNotEmpty() })
    }

    @Test fun `my records as a playlist asks each record once a month and fails when none answers`() = runBlocking {
        release = { id -> if (id == "28461") 500 to "" else 200 to res("release.json").replace("OK Computer", "Album $id").replace("Airbag", "Song $id") }
        val tracks = DiscoverSource.tracks(env, DiscoverSource.RECORDS)
        // Two records answered (three songs each, two of them the same songs: once), one did not: asked again at the next run.
        assertEquals(listOf("Song 4950798", "Paranoid Android", "Exit Music (For A Film)", "Song 1362037"), tracks.map { it.title })
        assertEquals("Song 4950798", tracks.first().title)
        val n = hits.count { it.startsWith("/releases/") }
        DiscoverSource.tracks(env, DiscoverSource.RECORDS)
        assertEquals(n + 1, hits.count { it.startsWith("/releases/") })
        assertEquals("discover_myrecords_title", DiscoverSource.playlistInfo(env, DiscoverSource.RECORDS).name)
        // Discogs down for every record: the run fails rather than emptying the playlist.
        clock += Discogs.TRACKS_TTL_MS + 1
        release = { 500 to "" }
        assertTrue(runCatching { DiscoverSource.tracks(env, DiscoverSource.RECORDS) }.isFailure)
    }

    @Test fun `an alert tells a new copy and a price below the amount, once`() {
        val both = Discogs.WantAlert(newCopy = true, below = 30.0)
        fun m(n: Int, p: Double?) = Discogs.Market(n, p, "EUR")
        // The first look takes note of the copies; a price already below is told.
        val (s1, k1) = Discogs.diff(both, null, m(12, 34.0))
        assertTrue(k1.isEmpty())
        val (s2, k2) = Discogs.diff(both, s1, m(13, 34.0))
        assertEquals(listOf(Discogs.Kind.NEW_COPY), k2)
        // Below the amount: said once, with the price; the new copy that brought it is not said twice.
        val (s3, k3) = Discogs.diff(both, s2, m(14, 28.0))
        assertEquals(listOf(Discogs.Kind.BELOW), k3)
        assertEquals(28.0, s3.told)
        assertTrue(Discogs.diff(both, s3, m(14, 28.0)).second.isEmpty())
        assertTrue(Discogs.diff(both, s3, m(14, 29.0)).second.isEmpty())
        // Lower still: said again. Back above and below again: said again.
        assertEquals(listOf(Discogs.Kind.BELOW), Discogs.diff(both, s3, m(14, 25.0)).second)
        val (s4, _) = Discogs.diff(both, s3, m(13, 40.0))
        assertNull(s4.told)
        assertEquals(listOf(Discogs.Kind.BELOW), Discogs.diff(both, s4, m(13, 29.5)).second)
        // Fewer copies, none for sale, or an alert that does not ask: nothing.
        assertTrue(Discogs.diff(both, s4, m(0, null)).second.isEmpty())
        assertTrue(Discogs.diff(Discogs.WantAlert(), s1, m(20, 1.0)).second.isEmpty())
        assertEquals(listOf(Discogs.Kind.BELOW), Discogs.diff(Discogs.WantAlert(below = 30.0), null, m(3, 28.0)).second)
    }

    @Test fun `the daily look asks only the records with an alert, once a day`() = runBlocking {
        Discogs.wantlist(env, "EUR")
        hits.clear()
        val alerts = mapOf("1810187" to Discogs.WantAlert(newCopy = true, below = 30.0), "2224498" to Discogs.WantAlert())
        assertTrue(Discogs.checkWants(env, alerts, "EUR").isEmpty()) // 34: not below; the copies noted
        assertEquals(listOf("/marketplace/stats/1810187?curr_abbr=EUR"), hits.toList())
        // The same day: not asked again.
        assertTrue(Discogs.checkWants(env, alerts, "EUR").isEmpty())
        assertEquals(1, hits.size)
        clock += Discogs.CHECK_EVERY_MS + 1
        stats = { 200 to """{"lowest_price":{"value":28.0,"currency":"EUR"},"num_for_sale":13}""" }
        val hit = Discogs.checkWants(env, alerts, "EUR").single()
        assertEquals("Kid A", hit.item.title); assertEquals(Discogs.Kind.BELOW, hit.kind); assertEquals(28.0, hit.price); assertEquals(30.0, hit.below)
        // Discogs not answering: nothing said, nothing forgotten.
        clock += Discogs.CHECK_EVERY_MS + 1
        stats = { 500 to "" }
        assertTrue(Discogs.checkWants(env, alerts, "EUR").isEmpty())
        clock += Discogs.CHECK_EVERY_MS + 1
        stats = { 200 to """{"lowest_price":{"value":28.0,"currency":"EUR"},"num_for_sale":13}""" }
        assertTrue(Discogs.checkWants(env, alerts, "EUR").isEmpty())
        // No alert, or no token: no call.
        hits.clear(); clock += Discogs.CHECK_EVERY_MS + 1
        assertTrue(Discogs.checkWants(env, mapOf("1810187" to Discogs.WantAlert()), "EUR").isEmpty())
        Records.saveToken(env, "")
        assertTrue(Discogs.checkWants(env, alerts, "EUR").isEmpty())
        assertTrue(hits.isEmpty())
    }

    @Test fun `the daily run is planned for the wantlist's alerts too, only with 1_18's switch`() {
        val s = com.xlollx.songport.data.Settings(discogsWantAlerts = mapOf("1" to Discogs.WantAlert(newCopy = true)))
        assertNull(ArtistAlerts.plan(s))
        assertEquals(ArtistAlerts.Plan(1, false), ArtistAlerts.plan(s.copy(preview2 = true)))
        assertNull(ArtistAlerts.plan(s.copy(preview2 = true, artistAlerts = false)))
        assertNull(ArtistAlerts.plan(s.copy(preview2 = true, discogsWantAlerts = mapOf("1" to Discogs.WantAlert()))))
    }
}
