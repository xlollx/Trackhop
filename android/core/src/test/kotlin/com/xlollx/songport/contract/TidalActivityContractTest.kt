package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.sync.CoverPrompt
import com.xlollx.songport.sync.Listening
import com.xlollx.songport.sync.Listening.ArtistPlays
import com.xlollx.songport.sync.Listening.Month
import com.xlollx.songport.sync.Listening.Period
import com.xlollx.songport.sync.Listening.Source
import com.xlollx.songport.sync.Wrapped
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * TIDAL's listening activity (the apps' "My activity": top artists of a month with their streams),
 * read from api.tidal.com/v2/my-activity as the open-source tidlers crate (0.1.1) calls it. The
 * answers here are made up in the crate's shape (serde camelCase: `nrOfStreams`); nothing of it
 * could be checked against a live account.
 */
class TidalActivityContractTest : ContractTest() {
    private val td = TidalProvider()
    private val host = "api.tidal.com"
    private val now = Month(2026, 11)

    @Before fun signIn() {
        TidalProvider.clearCaches()
        BuildDefaults.values = mapOf("TIDAL_CLIENT_ID" to "tcid")
        env.tokens.set("tidal", Tokens("TT1", "TR1", userId = "u1", userName = "me", extra = mapOf("country" to "IT")))
    }

    private fun timeline(vararg months: Pair<Int, Int>) =
        """{"timeline":[${months.joinToString(",") { (y, m) -> """{"year":$y,"month":$m,"title":"Month $m $y"}""" }}]}"""

    /** A top-artists answer in the crate's full shape (TopArtistsResponse), the artists in no particular order. */
    private fun topArtists(year: Int, month: Int, vararg artists: Triple<Long, String, Int>) = """{
        "activity":{"@class":"com.example.ActivityTopArtists","activityType":"TOP_ARTISTS",
          "artists":[${artists.joinToString(",") { (id, name, n) -> """{"id":$id,"name":${q(name)},"nrOfStreams":$n}""" }}],
          "images":[{"id":"img-one","url":"https://example.invalid/one.jpg"}],
          "month":$month,"totalNrOfDaysLeft":null,"subtitle":"Month $month","title":"Your top artists","year":$year},
        "artists":[${artists.joinToString(",") { (id, name, n) -> """{"artist":{"artistRoles":[{"category":"Artist","categoryId":-1}],
          "artistTypes":["ARTIST"],"doublePopularity":0.42,"id":$id,"mixes":{"ARTIST_MIX":"mix-$id"},"name":${q(name)},
          "picture":"pic-$id","popularity":42,"selectedAlbumCoverFallback":null,"vibrantColor":"#a0b0c0"},"nrOfStreams":$n}""" }}],
        "disclaimers":[{"text":"Counts may lag.","url":"https://example.invalid/help"}],
        "metadata":{"timeline":[{"year":$year,"month":$month,"title":"Month $month $year"}]}
    }"""

    @Test fun monthReadsTheLatestMonthWithDataAndRanksByStreams() = runBlocking {
        // The timeline in no order: the latest month with data is October, not the current one.
        json(timeline(2026 to 8, 2026 to 10, 2026 to 9))
        json(topArtists(2026, 10, Triple(3, "Gamma Ensemble", 7), Triple(1, "Artist Alpha", 41), Triple(2, "Band Beta", 19)))
        val r = td.listening(env, Period.MONTH, now)
        assertEquals(listOf(ArtistPlays("Artist Alpha", 41), ArtistPlays("Band Beta", 19), ArtistPlays("Gamma Ensemble", 7)), r.artists)
        assertTrue("TIDAL tells no songs", r.tracks.isEmpty())

        val t = next()
        assertCall(t, "GET", host, "/v2/my-activity/timeline")
        assertEquals("IT", t.query("countryCode"))
        assertTrue(t.query("locale").orEmpty().isNotEmpty())
        assertEquals("Bearer TT1", t.getHeader("Authorization"))
        val a = next()
        assertCall(a, "GET", host, "/v2/my-activity/top-artists")
        assertEquals("2026", a.query("year"))
        assertEquals("10", a.query("month"))
        assertEquals("IT", a.query("countryCode"))
        assertEquals("Bearer TT1", a.getHeader("Authorization"))
        noMoreRequests()
        assertTrue(env.logs.any { it.startsWith("[poster] TIDAL activity: 3 artists over 1 month(s)") })
    }

    @Test fun yearAddsUpTheMonthsOfTheLastTwelve() = runBlocking {
        // November 2025 is more than twelve months back from November 2026: not read.
        json(timeline(2026 to 11, 2026 to 3, 2025 to 12, 2025 to 11))
        json(topArtists(2026, 11, Triple(1, "Artist Alpha", 10), Triple(2, "Band Beta", 30)))
        json(topArtists(2026, 3, Triple(1, "Artist Alpha", 25), Triple(4, "Delta Duo", 5)))
        json(topArtists(2025, 12, Triple(4, "Delta Duo", 6)))
        val r = td.listening(env, Period.YEAR, now)
        assertEquals(listOf(ArtistPlays("Artist Alpha", 35), ArtistPlays("Band Beta", 30), ArtistPlays("Delta Duo", 11)), r.artists)
        next()
        assertEquals(listOf("2026-11", "2026-3", "2025-12"), (0 until 3).map { next().let { "${it.query("year")}-${it.query("month")}" } })
        noMoreRequests()
    }

    @Test fun noMonthWithDataIsAnEmptyReport() = runBlocking {
        json(timeline())
        val r = td.listening(env, Period.MONTH, now)
        assertTrue(r.artists.isEmpty() && r.tracks.isEmpty())
        next()
        noMoreRequests()
    }

    @Test fun aRefusalIsOneLogLineWithoutTheTokenAndNoRefresh() = runBlocking {
        json("""{"status":401,"subStatus":11002,"userMessage":"Token is not valid for this client"}""", code = 401)
        try { td.listening(env, Period.MONTH, now); fail("expected Unavailable") } catch (_: Listening.Unavailable) { }
        assertCall(next(), "GET", host, "/v2/my-activity/timeline")
        // No refresh, no second try: the token is the Open API's, and TIDAL may simply not take it here.
        noMoreRequests()
        assertEquals(listOf("[poster] TIDAL activity: HTTP 401 GET /my-activity/timeline: subStatus 11002, Token is not valid for this client"), env.logs)
        assertTrue(env.logs.none { "TT1" in it || "TR1" in it })
    }

    @Test fun notFoundIsUnavailable() = runBlocking {
        json(timeline(2026 to 10))
        text("Not Found", code = 404, contentType = "text/plain")
        try { td.listening(env, Period.MONTH, now); fail("expected Unavailable") } catch (_: Listening.Unavailable) { }
        assertEquals(listOf("[poster] TIDAL activity: HTTP 404 GET /my-activity/top-artists"), env.logs)
    }

    @Test fun anAnswerOfAnotherShapeIsUnavailable() = runBlocking {
        json(timeline(2026 to 10))
        json("""{"items":[{"title":"something else"}]}""")
        try { td.listening(env, Period.MONTH, now); fail("expected Unavailable") } catch (_: Listening.Unavailable) { }
        assertEquals(1, env.logs.size)
        assertTrue(env.logs.single().startsWith("[poster] TIDAL activity: GET /my-activity/top-artists 2026-10: an answer without artists"))
    }

    @Test fun parsingTakesEitherListAndTheMetadataTimeline() {
        // Only the activity's own list (no top-level artists): still read, by streams.
        val onlyActivity = parseJson("""{"activity":{"artists":[{"id":1,"name":"Band Beta","nrOfStreams":"4"},{"id":2,"name":"Artist Alpha","nrOfStreams":9},{"id":3,"name":" ","nrOfStreams":99}]}}""")
        assertEquals(listOf(ArtistPlays("Artist Alpha", 9), ArtistPlays("Band Beta", 4)), Listening.tidalArtists(onlyActivity))
        assertNull(Listening.tidalArtists(parseJson("""{"data":[]}""")))
        val full = parseJson(topArtists(2026, 7, Triple(1, "Artist Alpha", 3)))
        assertEquals(listOf(Month(2026, 7)), Listening.tidalTimeline(full))
        assertNull(Listening.tidalTimeline(parseJson("""{"data":[]}""")))
        assertEquals(listOf(Month(2026, 2)), Listening.tidalMonths(listOf(Month(2026, 2), Month(2025, 1)), Period.MONTH, now))
    }

    @Test fun thePosterListsThePlaylistsSongsByTheMostStreamedArtists() = runBlocking {
        json(timeline(2026 to 10))
        json(topArtists(2026, 10, Triple(9, "Nobody In The Playlist", 80), Triple(2, "Band Beta", 50), Triple(1, "Artist Alpha", 20)))
        val artists = td.listening(env, Period.MONTH, now).artists
        fun t(id: Int, title: String, vararg by: String) = Track(id = "t$id", title = title, artists = by.toList())
        val playlist = listOf(
            t(1, "First Light", "Artist Alpha"),
            t(2, "Paper Moon", "Echo Foxtrot"),
            t(3, "Second Wind", "Artist Alpha"),
            t(4, "Third Rail", "Artist Alpha"),
            t(5, "Low Tide", "Guest Singer", "Band Beta"),
            t(6, "High Noon", "BAND BETA"),
            t(7, "Last Call", "Band Beta"),
            t(8, "Quiet Room", "Echo Foxtrot"),
        )
        val picks = CoverPrompt.byArtists(playlist, artists, n = 6)
        // Band Beta (50 streams) first, two of theirs in playlist order (a featured credit counts), then Artist Alpha (20).
        assertEquals(4, picks.fromPlays)
        assertEquals(listOf("Low Tide", "High Noon", "First Light", "Second Wind"), picks.tracks.take(4).map { it.title })
        assertEquals(6, picks.tracks.size)
        assertEquals(6, picks.tracks.distinct().size)
        // No artist of the playlist streamed: the playlist's own list, nothing said to come from the plays.
        val none = CoverPrompt.byArtists(playlist, listOf(ArtistPlays("Nobody In The Playlist", 80)), n = 6)
        assertEquals(0, none.fromPlays)
        assertEquals(CoverPrompt.posterTracks(playlist, 6), none.tracks)
    }

    @Test fun tidalComesAfterDeezerAndBeforeYouTubeMusic() {
        assertEquals(Source.DEEZER.ordinal + 1, Source.TIDAL.ordinal)
        assertEquals(Source.TIDAL.ordinal + 1, Source.YTM.ordinal)
        assertEquals(Source.TIDAL, Listening.choose(listOf(Source.YTM, Source.TIDAL), null))
        assertEquals(Source.DEEZER, Listening.choose(listOf(Source.TIDAL, Source.DEEZER), null))
        assertEquals(Period.MONTH, Listening.period(Source.TIDAL, null))
        assertEquals(Period.YEAR, Listening.period(Source.TIDAL, Period.YEAR))
        assertTrue(Source.TIDAL.artistsOnly && Source.TIDAL.counts && !Source.TIDAL.server)
        assertEquals(Source.TIDAL, Source.of("tidal"))
    }

    @Test fun theWrappedTakesTidalsArtistsOverTheSongsSource() {
        val songs = Wrapped.Service("lastfm", "Last.fm")
        val tidal = Wrapped.Service("tidal", "TIDAL")
        val w = Wrapped.Saved(
            id = "w", madeAt = 1, year = 2026, trackCount = 3, topArtists = listOf(Wrapped.Artist("Echo Foxtrot", 0, 3)),
            services = listOf(songs), period = "month", discovery = "Echo Foxtrot",
        )
        val out = Wrapped.withArtists(w, listOf(ArtistPlays("Band Beta", 5), ArtistPlays("Artist Alpha", 12)), tidal)
        assertEquals(listOf(Wrapped.Artist("Artist Alpha", 0, 12), Wrapped.Artist("Band Beta", 0, 5)), out.topArtists)
        assertEquals(listOf(tidal, songs), out.services)
        assertNull(out.discovery)
        assertEquals(w, Wrapped.withArtists(w, emptyList(), tidal))
    }
}
