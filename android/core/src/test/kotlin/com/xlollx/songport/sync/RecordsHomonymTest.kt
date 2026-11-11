package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections

/**
 * Two bands called "Eldritch": the playlist's must get its own records, not the other's. Discogs
 * numbers them ("Eldritch (2)"), MusicBrainz gives each an id; the playlist's titles, and
 * MusicBrainz's link to the artist on Discogs, tell them apart. Against stand-ins, no network.
 */
class RecordsHomonymTest {
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val italianId = "11111111-2222-3333-4444-555555555555"
    private val playlistsId = "66666666-7777-8888-9999-000000000000"

    /** Discogs' search for "Eldritch": the more owned Italian band first, then the playlist's "Eldritch (2)". */
    private val search = """{"results":[
        ${result(1, 101, "Eldritch - Headquake", "1997", "CD", have = 900)},
        ${result(2, 102, "Eldritch - Seeds Of Rage", "1995", "CD", have = 800)},
        ${result(3, 103, "Eldritch - El Niño", "1998", "Cassette", have = 700)},
        ${result(4, 201, "Eldritch (2) - Shadows Of The Deep", "2019", "Vinyl", have = 40)},
        ${result(5, 202, "Eldritch (2) - Night Tide", "2022", "CD", have = 30)}
    ]}"""

    private var mbArtists = """{"artists":[
        {"id":"$italianId","name":"Eldritch","score":100,"disambiguation":"Italian progressive metal"},
        {"id":"$playlistsId","name":"Eldritch","score":100}]}"""
    private var releaseGroups = """{"release-groups":[
        {"title":"Night Tide","artist-credit":[{"name":"Eldritch","artist":{"id":"$playlistsId"}}]},
        {"title":"Headquake","artist-credit":[{"name":"Eldritch","artist":{"id":"$italianId"}}]},
        {"title":"Shadows of the Deep","artist-credit":[{"name":"Eldritch","artist":{"id":"$playlistsId"}}]}]}"""
    private val playlistsRelations = """{"relations":[
        {"type":"official homepage","ended":false,"url":{"resource":"https://eldritch-band.example/"}},
        {"type":"discogs","ended":false,"url":{"resource":"https://www.discogs.com/artist/777"}}]}"""
    private val italianRelations = """{"relations":[
        {"type":"official homepage","ended":false,"url":{"resource":"https://eldritch.example.it/"}},
        {"type":"discogs","ended":false,"url":{"resource":"https://www.discogs.com/artist/555"}}]}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path
            val query = URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += "$path?$query"
            val (code, body) = when (path) {
                "/dg/database/search" -> 200 to (if (query.contains("artist=Eldritch")) search else """{"results":[]}""")
                "/dg/artists/777" -> 200 to """{"id":777,"name":"Eldritch (2)"}"""
                "/dg/artists/555" -> 200 to """{"id":555,"name":"Eldritch"}"""
                "/ws/2/artist" -> 200 to (if (query.contains("Eldritch")) mbArtists else """{"artists":[]}""")
                "/ws/2/release-group" -> 200 to releaseGroups
                "/ws/2/artist/$playlistsId" -> 200 to playlistsRelations
                "/ws/2/artist/$italianId" -> 200 to italianRelations
                else -> 404 to "{}"
            }
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()

    private fun result(id: Long, master: Long, title: String, year: String, vararg formats: String, have: Int) = """
        {"id":$id,"master_id":$master,"title":"$title","year":"$year","format":[${formats.joinToString(",") { "\"$it\"" }}],"community":{"have":$have}}"""

    /** The user's playlist: two songs of the Eldritch it holds, from its albums. */
    private val playlist = listOf(
        Track(id = "1", title = "Undertow", artists = listOf("Eldritch"), album = "Night Tide"),
        Track(id = "2", title = "Black Water", artists = listOf("Eldritch"), album = "Shadows of the Deep (Deluxe Edition)"),
        Track(id = "3", title = "Paranoid Android", artists = listOf("Radiohead"), album = "OK Computer"),
    )

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        Records.discogsBase = "$base/dg"
        Records.anonPace = Pacer(0)
        MusicBrainz.base = "$base/ws/2"
        MusicBrainz.pace = Pacer(0)
        Merch.hostBase = { "$base/$it" }
        Merch.hostGap = 0
        Merch.resetPaces(0)
    }

    @After fun tearDown() {
        server.stop(0)
        Records.discogsBase = "https://api.discogs.com"
        Records.anonPace = Pacer(2_500)
        MusicBrainz.base = "https://musicbrainz.org/ws/2"
        MusicBrainz.pace = Pacer(1100)
        Merch.hostBase = { "https://$it" }
        Merch.hostGap = 1_000
        Merch.resetPaces()
    }

    // ------------------------------------------------------------------ the pieces

    @Test fun thePlaylistsTitlesAreReadPerArtist() {
        val ev = Records.evidenceOf(playlist)
        val eldritch = ev[Records.norm("Eldritch")]!!
        assertEquals(setOf("night tide", "shadows of the deep"), eldritch.albums)
        assertEquals(setOf("undertow", "black water"), eldritch.tracks)
        assertEquals(setOf("ok computer"), ev[Records.norm("radiohead")]!!.albums)
    }

    @Test fun discogsNumbersTheArtistsOfOneName() {
        val credits = Records.credits(search, "Eldritch")
        assertEquals(listOf("Eldritch", "Eldritch (2)"), credits.map { it.name })
        assertEquals(setOf("night tide", "shadows of the deep"), credits[1].titles)
        assertEquals(listOf("Shadows Of The Deep", "Night Tide"), Records.parseSearch(search, "Eldritch", "Eldritch (2)").map { it.title })
        assertEquals(listOf("Headquake", "Seeds Of Rage", "El Niño"), Records.parseSearch(search, "Eldritch", "Eldritch").map { it.title })
    }

    @Test fun theDiscogsArtistIdIsReadFromItsAddress() {
        assertEquals(777L, Records.discogsArtistId("https://www.discogs.com/artist/777"))
        assertEquals(777L, Records.discogsArtistId("https://www.discogs.com/artist/777-Eldritch-2"))
        assertEquals(42L, Records.discogsArtistId("http://discogs.com/it/artist/42"))
        assertNull(Records.discogsArtistId("https://www.discogs.com/master/777"))
        assertNull(Records.discogsArtistId("https://example.com/artist/777"))
    }

    @Test fun musicBrainzsLinkNamesTheArtistUnlessThePlaylistSaysOtherwise() {
        val credits = Records.credits(search, "Eldritch")
        assertEquals(Records.Choice("Eldritch (2)", false), Records.chooseCredit(credits, "Eldritch (2)", null))
        // The exact artist has no records in the search: none shown, rather than another's.
        assertEquals(Records.Choice("Eldritch (5)", false), Records.chooseCredit(credits, "Eldritch (5)", null))
        // MusicBrainz linked the other band, but the playlist's albums are all on this one's records.
        val ev = Records.Evidence(setOf("night tide"), emptySet())
        assertEquals(Records.Choice("Eldritch (2)", false), Records.chooseCredit(credits, "Eldritch", ev))
    }

    @Test fun withoutALinkThePlaylistsTitlesChooseAndNothingToGoByIsAGuess() {
        val credits = Records.credits(search, "Eldritch")
        assertEquals(Records.Choice("Eldritch (2)", false), Records.chooseCredit(credits, null, Records.Evidence(emptySet(), setOf("night tide"))))
        assertEquals(Records.Choice("Eldritch", false), Records.chooseCredit(credits, null, Records.Evidence(setOf("headquake"), emptySet())))
        // Nothing in common: the most owned, said to be a guess.
        assertEquals(Records.Choice("Eldritch", true), Records.chooseCredit(credits, null, Records.Evidence(setOf("unknown album"), emptySet())))
        assertEquals(Records.Choice("Eldritch", true), Records.chooseCredit(credits, null, null))
        // One artist of that name: it, with no doubt.
        assertEquals(Records.Choice("Eldritch", false), Records.chooseCredit(credits.take(1), null, null))
        assertEquals(Records.Choice(null, false), Records.chooseCredit(emptyList(), null, null))
    }

    @Test fun musicBrainzsArtistsOfOneNameAreToldApartByThePlaylistsAlbums() {
        assertEquals(listOf(italianId, playlistsId), Records.pickArtists(mbArtists, "Eldritch"))
        val hits = Records.parseHits(releaseGroups, listOf(italianId, playlistsId), setOf("night tide", "shadows of the deep"))
        assertEquals(mapOf(playlistsId to 2), hits)
        assertEquals(playlistsId to false, Records.chooseArtist(listOf(italianId, playlistsId), hits))
        assertEquals(italianId to true, Records.chooseArtist(listOf(italianId, playlistsId), emptyMap()))
        assertEquals(italianId to true, Records.chooseArtist(listOf(italianId, playlistsId), null))
        assertEquals(italianId to true, Records.chooseArtist(listOf(italianId, playlistsId), mapOf(italianId to 1, playlistsId to 1)))
    }

    // ------------------------------------------------------------------ the whole search

    @Test fun thePlaylistsEldritchGetsItsOwnRecordsLinksAndDiscogsPage() = runBlocking {
        val r = Records.find(env, listOf("Eldritch"), "EUR", evidence = Records.evidenceOf(playlist), shops = false)
        val a = r.artists.single()
        assertEquals(listOf("Shadows Of The Deep", "Night Tide"), a.records!!.map { it.title })
        assertFalse(a.sameName)
        // Its own site and Discogs page, not the Italian band's.
        assertEquals(listOf("https://eldritch-band.example/"), a.links.map { it.url })
        assertEquals("https://www.discogs.com/artist/777", a.discogsPage)
        // MusicBrainz was asked about the playlist's albums, by the two artists only.
        val rg = hits.single { it.startsWith("/ws/2/release-group") }
        assertTrue(rg, rg.contains("arid:$italianId") && rg.contains("releasegroup:\"night tide\""))
    }

    @Test fun nothingToTellThemApartShowsTheMostOwnedWithANote() = runBlocking {
        val r = Records.find(env, listOf("Eldritch"), "EUR", shops = false)
        val a = r.artists.single()
        assertTrue(a.sameName)
        assertEquals(listOf("Headquake", "Seeds Of Rage", "El Niño"), a.records!!.map { it.title })
        // A page MusicBrainz is not sure of is not linked: the Discogs search is.
        assertTrue(a.discogsPage, a.discogsPage.startsWith("https://www.discogs.com/search/"))
        assertTrue(hits.none { it.startsWith("/dg/artists/") })
    }

    @Test fun oneArtistOfTheNameNeedsNoNote() = runBlocking {
        mbArtists = """{"artists":[{"id":"$playlistsId","name":"Eldritch","score":100}]}"""
        val r = Records.find(env, listOf("Eldritch"), "EUR", shops = false)
        val a = r.artists.single()
        // MusicBrainz's one Eldritch links to Discogs' "Eldritch (2)": its records only.
        assertFalse(a.sameName)
        assertEquals(listOf("Shadows Of The Deep", "Night Tide"), a.records!!.map { it.title })
        assertTrue(hits.none { it.startsWith("/ws/2/release-group") })
    }

    @Test fun stoppingASearchKeepsTheArtistsAlreadyFound() = runBlocking {
        var kept: Records.Result? = null
        val artists = listOf("Eldritch", "Radiohead", "Muse", "Blur")
        lateinit var search: Deferred<Records.Result>
        // The user taps Stop once two artists are found.
        search = async {
            Records.find(env, artists, "EUR", shops = false, partial = { r ->
                kept = r
                if (r.artists.size == 2) search.cancel()
            })
        }
        try { search.await(); fail("the search should have stopped") } catch (_: CancellationException) {}
        assertEquals(listOf("Eldritch", "Radiohead"), kept!!.artists.map { it.artist })
        assertEquals(4, kept!!.total)
        // Nothing asked for the artists after.
        assertTrue(hits.none { it.contains("Muse") || it.contains("Blur") })
    }
}
