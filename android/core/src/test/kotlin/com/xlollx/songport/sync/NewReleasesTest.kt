package com.xlollx.songport.sync

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.providers.NewReleasesProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * New releases from a playlist's artists: Deezer's and MusicBrainz's answers read, the same album
 * on both shown once, the window, the day's cache, and the weekly "New releases" sync, against a
 * stand-in for both catalogues.
 */
class NewReleasesTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val hits: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val userAgents: MutableList<String?> = Collections.synchronizedList(ArrayList())
    /** Path (with query, decoded) to status and body; 404 when nothing fits. */
    private var answer: (String) -> Pair<Int, String> = { 404 to "{}" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val asked = ex.requestURI.path + "?" + URLDecoder.decode(ex.requestURI.rawQuery.orEmpty(), "UTF-8")
            hits += asked
            if (asked.startsWith("/ws/2")) userAgents += ex.requestHeaders.getFirst("User-Agent")
            val (code, body) = answer(asked)
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val env = ContractEnv()
    private var clock = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() {
        val base = "http://127.0.0.1:${server.address.port}"
        NewReleases.deezer = "$base/dz"
        NewReleases.musicBrainz = "$base/ws/2"
        NewReleases.now = { clock }
        NewReleases.deezerPace = Pacer(0)
        MusicBrainz.pace = Pacer(0)
        NewReleases.reset(env)
    }

    @After fun tearDown() {
        server.stop(0)
        NewReleases.deezer = "https://api.deezer.com"
        NewReleases.musicBrainz = "https://musicbrainz.org/ws/2"
        NewReleases.now = { System.currentTimeMillis() }
        NewReleases.deezerPace = Pacer(150)
        MusicBrainz.pace = Pacer(1100)
        Providers.testDoubles = emptyList()
    }

    // ------------------------------------------------------------------ the answers

    private val deezerAlbums = """{"data":[
        {"id":11,"title":"Nightfall","release_date":"2026-11-07","record_type":"album","cover_medium":"https://cdn/11.jpg"},
        {"id":12,"title":"Glow - Single","release_date":"2026-10-20","record_type":"single"},
        {"id":13,"title":"Greatest Hits","release_date":"2026-11-01","record_type":"compile"},
        {"id":14,"title":"Old Times","release_date":"2019-03-01","record_type":"album"},
        {"id":15,"title":"Tomorrow EP","release_date":"2026-12-05","record_type":"ep"},
        {"id":16,"title":"Undated","release_date":"0000-00-00","record_type":"album"}
    ]}"""

    private fun mbGroup(id: String, title: String, date: String, type: String, artist: String = "The Lumens", secondary: String = "") =
        """{"id":"$id","title":"$title","first-release-date":"$date","primary-type":"$type","secondary-types":[$secondary],
            "artist-credit":[{"name":"$artist","artist":{"id":"aaaaaaaa-0000-0000-0000-000000000001","name":"$artist"}}]}"""

    private val mbGroups = """{"count":5,"release-groups":[
        ${mbGroup("11111111-1111-1111-1111-111111111111", "Nightfall", "2026-11-06", "Album")},
        ${mbGroup("22222222-2222-2222-2222-222222222222", "Live at Home", "2026-11-02", "Album", secondary = "\"Live\"")},
        ${mbGroup("33333333-3333-3333-3333-333333333333", "Ember", "2026-09-01", "EP")},
        ${mbGroup("44444444-4444-4444-4444-444444444444", "Faraway", "2027-02", "Album")},
        ${mbGroup("55555555-5555-5555-5555-555555555555", "Not Ours", "2026-11-05", "Single", artist = "The Lumens Tribute Band")}
    ]}"""

    private fun catalogues(path: String): Pair<Int, String> = when {
        path.startsWith("/dz/search/artist") -> 200 to """{"data":[{"id":77,"name":"The Lumens","nb_fan":900},{"id":78,"name":"Lumens Tribute","nb_fan":3}]}"""
        path.startsWith("/dz/artist/77/albums") -> 200 to deezerAlbums
        path.startsWith("/dz/album/11/tracks") -> 200 to """{"data":[{"id":101,"title":"Nightfall","duration":201,"artist":{"name":"The Lumens"}},{"id":102,"title":"Glow","duration":180,"artist":{"name":"The Lumens"}}]}"""
        path.startsWith("/dz/album/12/tracks") -> 200 to """{"data":[{"id":103,"title":"Glow","duration":180,"artist":{"name":"The Lumens"}}]}"""
        path.startsWith("/ws/2/release-group") -> 200 to mbGroups
        path.startsWith("/ws/2/release?") -> 200 to """{"releases":[{"media":[{"tracks":[{"title":"Ember","length":190000,"recording":{"id":"66666666-6666-6666-6666-666666666666"}}]}]}]}"""
        else -> 404 to "{}"
    }

    @Test fun deezerAlbumsAreReadWithoutCompilationsOrUndatedOnes() {
        val list = NewReleases.parseDeezerAlbums(deezerAlbums, "The Lumens")
        assertEquals(listOf("Nightfall", "Glow - Single", "Old Times", "Tomorrow EP"), list.map { it.title })
        assertEquals(listOf(NewReleases.Type.ALBUM, NewReleases.Type.SINGLE, NewReleases.Type.ALBUM, NewReleases.Type.EP), list.map { it.type })
        assertEquals("https://www.deezer.com/album/11", list.first().link)
        assertEquals("https://cdn/11.jpg", list.first().cover)
    }

    @Test fun musicBrainzKeepsTheArtistsOwnStudioReleases() {
        val list = NewReleases.parseMusicBrainz(mbGroups, "the lumens")
        // The live album and the tribute band's single are left out; a month alone is a date.
        assertEquals(listOf("Nightfall", "Ember", "Faraway"), list.map { it.title })
        assertEquals("2027-02", list.last().date)
        assertEquals(LocalDate.of(2027, 2, 1), list.last().day)
        assertEquals("https://musicbrainz.org/release-group/33333333-3333-3333-3333-333333333333", list[1].link)
    }

    @Test fun theSameAlbumOnBothSourcesIsShownOnce() {
        val dz = NewReleases.parseDeezerAlbums(deezerAlbums, "The Lumens")
        val mb = NewReleases.parseMusicBrainz(mbGroups, "The Lumens")
        val merged = NewReleases.merge(dz, mb)
        val nightfall = merged.filter { it.title == "Nightfall" }
        assertEquals(1, nightfall.size)
        // Deezer's page and cover, MusicBrainz's id, the earlier date.
        assertEquals(11L, nightfall.single().deezerId)
        assertEquals("11111111-1111-1111-1111-111111111111", nightfall.single().mbid)
        assertEquals("2026-11-06", nightfall.single().date)
        assertEquals(listOf("Nightfall", "Glow - Single", "Old Times", "Tomorrow EP", "Ember", "Faraway"), merged.map { it.title })
    }

    @Test fun aSingleAndTheAlbumOfTheSameNameStayTwo() {
        val a = NewReleases.Release("X", "Glow", NewReleases.Type.ALBUM, "2026-11-01", deezerId = 1)
        val s = NewReleases.Release("X", "Glow - Single", NewReleases.Type.SINGLE, "2026-10-01", deezerId = 2)
        assertEquals(2, NewReleases.merge(listOf(a, s), emptyList()).size)
        // The " - Single" Deezer writes does not keep MusicBrainz's single apart.
        val mb = NewReleases.Release("X", "Glow", NewReleases.Type.SINGLE, "2026-10-01", mbid = "m")
        assertEquals(1, NewReleases.merge(listOf(s), listOf(mb)).size)
    }

    @Test fun theWindowKeepsTheLastDaysAndTheAnnouncedOnesOnlyWhenAsked() {
        val all = NewReleases.merge(NewReleases.parseDeezerAlbums(deezerAlbums, "The Lumens"), NewReleases.parseMusicBrainz(mbGroups, "The Lumens"))
        assertEquals(listOf("Nightfall"), NewReleases.window(all, 7, false, today).map { it.title })
        assertEquals(listOf("Nightfall", "Glow - Single"), NewReleases.window(all, 30, false, today).map { it.title })
        assertEquals(listOf("Nightfall", "Glow - Single", "Ember"), NewReleases.window(all, 90, false, today).map { it.title })
        // Announced: on top, soonest first.
        assertEquals(listOf("Tomorrow EP", "Faraway", "Nightfall"), NewReleases.window(all, 7, true, today).map { it.title })
        // The first day of the window counts, the day before does not.
        val edge = listOf(
            NewReleases.Release("X", "In", NewReleases.Type.SINGLE, "2026-11-04"),
            NewReleases.Release("X", "Out", NewReleases.Type.SINGLE, "2026-11-03"),
        )
        assertEquals(listOf("In"), NewReleases.window(edge, 7, false, today).map { it.title })
    }

    // ------------------------------------------------------------------ asking

    @Test fun findAsksBothCataloguesOnceADayPerArtist() = runBlocking {
        answer = ::catalogues
        val r = NewReleases.find(env, listOf("The Lumens"), 30, upcoming = true)
        assertEquals(0, r.unanswered)
        assertEquals(listOf("Tomorrow EP", "Faraway", "Nightfall", "Glow - Single"), r.releases.map { it.title })
        val mbQuery = hits.single { it.startsWith("/ws/2/release-group") }
        assertTrue(mbQuery, mbQuery.contains("artistname:\"The Lumens\" AND firstreleasedate:[2026-08-02 TO *]"))
        assertTrue(userAgents.all { it == com.xlollx.songport.ai.ArtistMixSource.userAgent })
        val asked = hits.size
        // Another window the same day: from the cache, nothing asked.
        assertEquals(listOf("Nightfall", "Glow - Single", "Ember"), NewReleases.find(env, listOf("the lumens"), 90, upcoming = false).releases.map { it.title })
        assertEquals(asked, hits.size)
        // A day later: asked again.
        clock += NewReleases.TTL_MS + 1
        NewReleases.find(env, listOf("The Lumens"), 30, upcoming = false)
        assertEquals(asked * 2, hits.size)
    }

    @Test fun oneSourceDownStillAnswersButIsNotRemembered() = runBlocking {
        answer = { p -> if (p.startsWith("/ws/2")) 503 to "{}" else catalogues(p) }
        val r = NewReleases.find(env, listOf("The Lumens"), 30, upcoming = false)
        assertEquals(0, r.unanswered)
        assertEquals(listOf("Nightfall", "Glow - Single"), r.releases.map { it.title })
        val asked = hits.size
        NewReleases.find(env, listOf("The Lumens"), 30, upcoming = false)
        assertEquals(asked * 2, hits.size)
        // Both down: the artist is counted as not answered.
        answer = { 503 to "{}" }
        clock += NewReleases.TTL_MS + 1
        assertEquals(1, NewReleases.find(env, listOf("The Lumens"), 30, upcoming = false).unanswered)
    }

    @Test fun tracksComeFromDeezerOrMusicBrainzEachSongOnce() = runBlocking {
        answer = ::catalogues
        val all = NewReleases.find(env, listOf("The Lumens"), 90, upcoming = true).releases
        val tracks = NewReleases.tracksOf(env, all)
        // The announced ones have no tracks; "Glow" (single and album track) once; Ember from MusicBrainz.
        assertEquals(listOf("Nightfall", "Glow", "Ember"), tracks.map { it.title })
        assertEquals(listOf("66666666-6666-6666-6666-666666666666"), tracks.last().mbids)
        assertEquals("Ember", tracks.last().album)
        assertEquals(201_000L, tracks.first().durationMs)
        assertTrue(hits.none { it.startsWith("/dz/album/15") })
    }

    // ------------------------------------------------------------------ the weekly sync

    @Test fun theSourceIdNamesTheWindowAndThePlaylist() {
        val id = NewReleasesProvider.id(7, "spotify@2", "pl:with:colons")
        assertEquals(NewReleasesProvider.Ref(7, "spotify@2", "pl:with:colons"), NewReleasesProvider.parse(id))
        assertNull(NewReleasesProvider.parse("x:spotify:1"))
        assertNull(NewReleasesProvider.parse("7:spotify"))
        assertSame(NewReleasesProvider, Providers.byId(NewReleasesProvider.SERVICE))
        assertTrue(Providers.services().none { it.id == NewReleasesProvider.SERVICE })
    }

    @Test fun aWeeklyReleasesSyncFillsItsPlaylistAndKeepsWhatItHad() = runBlocking {
        answer = ::catalogues
        val mine = MemoryService("msrc")
        val dst = MemoryService("mdst")
        Providers.testDoubles = listOf(mine, dst)
        mine.lists["fav"] = mutableListOf(Track("a", "Old song", listOf("The Lumens")), Track("b", "Other", listOf("The Lumens")))
        dst.catalogue += listOf(Track("d1", "Nightfall", listOf("The Lumens"), durationMs = 201_000), Track("d2", "Glow", listOf("The Lumens"), durationMs = 180_000))
        dst.lists["new"] = mutableListOf(Track("d0", "Last week's", listOf("Someone")))
        val job = SyncJob(
            id = "rel", name = "New releases from fav",
            source = PlaylistRef(NewReleasesProvider.SERVICE, NewReleasesProvider.id(30, "msrc", "fav"), "New releases from fav"),
            target = PlaylistRef("mdst", "new", "New releases from fav"),
            schedule = Schedule.WEEKLY, archive = true, mirrorRemovals = true,
        ).also { env.store.upsertJob(it) }
        val r = SyncEngine(env).run(job)
        assertTrue(r.error, r.ok)
        assertEquals(2, r.added)
        assertEquals(listOf("d0", "d1", "d2"), dst.lists["new"]!!.map { it.id })
        assertTrue(ChangingPlaylists.isSelfChanging(NewReleasesProvider.SERVICE, "anything"))
    }

    @Test fun aRunThatLearnedNothingFailsInsteadOfLookingEmpty() = runBlocking {
        answer = { 503 to "{}" }
        val mine = MemoryService("msrc")
        Providers.testDoubles = listOf(mine)
        mine.lists["fav"] = mutableListOf(Track("a", "Old song", listOf("The Lumens")))
        val e = runCatching { NewReleasesProvider.tracks(env, NewReleasesProvider.id(7, "msrc", "fav")) }.exceptionOrNull()
        assertEquals("releases_unavailable", e?.message)
    }
}
