package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.sync.Listening.Period
import com.xlollx.songport.sync.Listening.Source
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wrapped of listening: each service's answer read, the period and source chosen, the figures made of the plays. */
class ListeningTest {

    // ------------------------------------------------------------ Last.fm

    private val lastFmTop = """
        {"toptracks":{"track":[
          {"mbid":"","name":"Nightcall","duration":"258","playcount":"42","url":"https://www.last.fm/music/Kavinsky/_/Nightcall",
           "artist":{"name":"Kavinsky","mbid":""},
           "image":[{"size":"small","#text":"https://lastfm.freetls.fastly.net/i/u/34s/2a96cbd8b46e442fc41c2b86b821562f.png"},
                    {"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"}],
           "@attr":{"rank":"1"}},
          {"mbid":"b1a9c0e9-d987-4042-ae91-78d6a3267d69","name":"Midnight City","duration":"0","playcount":"17","url":"u2",
           "artist":{"name":"M83"},"image":[{"size":"extralarge","#text":"https://example.org/cover.jpg"}],"@attr":{"rank":"2"}}
        ],"@attr":{"user":"someone","page":"1","perPage":"50","totalPages":"3","total":"131"}}}
    """.trimIndent()

    @Test fun readsLastFmTopTracks() {
        val (plays, total) = Listening.lastFmTracks(parseJson(lastFmTop))
        assertEquals(131, total)
        assertEquals(listOf("Nightcall", "Midnight City"), plays.map { it.track.title })
        assertEquals(listOf(42, 17), plays.map { it.plays })
        assertEquals(listOf("Kavinsky"), plays[0].track.artists)
        assertEquals(258_000L, plays[0].track.durationMs)
        // Last.fm's grey star is no cover; a real picture is kept.
        assertNull(plays[0].cover)
        assertEquals("https://example.org/cover.jpg", plays[1].cover)
        assertEquals("b1a9c0e9-d987-4042-ae91-78d6a3267d69", plays[1].track.id)
    }

    @Test fun readsLastFmSingleItemAndArtistsAndTotals() {
        // A list of one comes as the object itself (Last.fm's JSON is made from XML).
        val one = """{"toptracks":{"track":{"name":"Solo","playcount":"3","artist":{"name":"A"}},"@attr":{"total":"1"}}}"""
        assertEquals(listOf("Solo"), Listening.lastFmTracks(parseJson(one)).first.map { it.track.title })

        val artists = """{"topartists":{"artist":[{"name":"Kavinsky","playcount":"60"},{"name":"M83","playcount":"25"},{"name":"","playcount":"9"}],"@attr":{"total":"48"}}}"""
        val (a, total) = Listening.lastFmArtists(parseJson(artists))
        assertEquals(listOf(Listening.ArtistPlays("Kavinsky", 60), Listening.ArtistPlays("M83", 25)), a)
        assertEquals(48, total)

        val chart = """{"weeklyartistchart":{"artist":[{"name":"M83","playcount":"4"},{"name":"The Beatles","playcount":"2"}],"@attr":{"from":"1","to":"2"}}}"""
        assertEquals(setOf(Matcher.normalizeArtist("M83"), Matcher.normalizeArtist("The Beatles")), Listening.lastFmBefore(parseJson(chart)))

        assertEquals(1234, Listening.lastFmTotal(parseJson("""{"recenttracks":{"track":[],"@attr":{"total":"1234"}}}""")))
        assertEquals(0, Listening.lastFmTotal(parseJson("""{"error":6}""")))
    }

    // ------------------------------------------------------------ ListenBrainz

    @Test fun readsListenBrainzStats() {
        val recordings = """
            {"payload":{"count":2,"range":"month","total_recording_count":210,"user_id":"someone","recordings":[
              {"artist_name":"Daft Punk","listen_count":30,"recording_mbid":"rec-1","release_name":"Discovery","track_name":"One More Time",
               "caa_id":123,"caa_release_mbid":"rel-1"},
              {"artist_name":"Air","listen_count":12,"recording_mbid":null,"release_name":"Moon Safari","track_name":"La femme d'argent"}
            ]}}
        """.trimIndent()
        val (plays, total) = Listening.listenBrainzTracks(parseJson(recordings))
        assertEquals(210, total)
        assertEquals(listOf(30, 12), plays.map { it.plays })
        assertEquals("rec-1", plays[0].track.id)
        assertEquals("Discovery", plays[0].track.album)
        assertEquals("https://coverartarchive.org/release/rel-1/front-250", plays[0].cover)
        assertNull(plays[1].cover)
        assertEquals("La femme d'argent|Air", plays[1].track.id)

        val artists = """{"payload":{"artists":[{"artist_name":"Daft Punk","listen_count":80},{"artist_name":"Air","listen_count":20}],"total_artist_count":64}}"""
        val (a, n) = Listening.listenBrainzArtists(parseJson(artists))
        assertEquals(listOf("Daft Punk", "Air"), a.map { it.name })
        assertEquals(80, a[0].plays)
        assertEquals(64, n)

        // Statistics not computed yet (204, no body): nothing, no failure.
        assertTrue(Listening.listenBrainzTracks(kotlinx.serialization.json.JsonNull).first.isEmpty())
    }

    // ------------------------------------------------------------ Spotify

    @Test fun readsSpotifyTopItems() {
        val tracks = """
            {"items":[
              {"id":"t1","name":"Blinding Lights","uri":"spotify:track:t1","duration_ms":200040,"external_ids":{"isrc":"USUG11904206"},
               "artists":[{"name":"The Weeknd"}],
               "album":{"name":"After Hours","release_date":"2020-03-20","images":[{"url":"https://i.scdn.co/640"},{"url":"https://i.scdn.co/300"},{"url":"https://i.scdn.co/64"}]}},
              {"id":"t2","name":"Old One","artists":[{"name":"X"}],"album":{"name":"A","release_date":"1977","images":[]}},
              {"id":null,"name":"Local file"}
            ],"total":2,"limit":50}
        """.trimIndent()
        val plays = Listening.spotifyTracks(parseJson(tracks))
        assertEquals(listOf("Blinding Lights", "Old One"), plays.map { it.track.title })
        assertEquals(listOf(2020, 1977), plays.map { it.track.year })
        assertEquals("https://i.scdn.co/300", plays[0].cover)
        assertNull(plays[1].cover)
        assertEquals(0, plays[0].plays)
        assertEquals("USUG11904206", plays[0].track.isrc)

        val artists = """{"items":[{"name":"The Weeknd","id":"a1"},{"name":"Dua Lipa","id":"a2"}]}"""
        assertEquals(listOf(Listening.ArtistPlays("The Weeknd"), Listening.ArtistPlays("Dua Lipa")), Listening.spotifyArtists(parseJson(artists)))
    }

    // ------------------------------------------------------------ YouTube Music history

    @Test fun recentPlaysRankArtistsBySongs() {
        val r = Listening.fromHistory(listOf(
            Track("v1", "A1", listOf("Alpha")), Track("v2", "B1", listOf("Beta")), Track("v3", "A2", listOf("Alpha")),
            Track("v1", "A1", listOf("Alpha")), Track("al", "Album", listOf("Alpha"), kind = Track.KIND_ALBUM),
        ))
        assertEquals(listOf("A1", "B1", "A2"), r.tracks.map { it.track.title })
        assertEquals(listOf("Alpha", "Beta"), r.artists.map { it.name })
        assertEquals(3, r.totalTracks)
        assertNull(r.before)
    }

    // ------------------------------------------------------------ periods and sources

    @Test fun mapsPeriodsPerSource() {
        assertEquals(listOf("1month", "6month", "12month"), listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR).map { Listening.lastFmPeriod(it) })
        assertEquals(listOf("month", "half_yearly", "year"), listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR).map { Listening.listenBrainzRange(it) })
        assertEquals(listOf("short_term", "medium_term", "long_term"), listOf(Period.MONTH, Period.HALF_YEAR, Period.YEAR).map { Listening.spotifyRange(it) })
        assertNull(Listening.lastFmPeriod(Period.RECENT))
        // A period the source cannot tell gives way to the one it can.
        assertEquals(Period.RECENT, Listening.period(Source.YTM, Period.YEAR))
        assertEquals(Period.YEAR, Listening.period(Source.SPOTIFY, Period.YEAR))
        assertEquals(Period.MONTH, Listening.period(Source.LASTFM, Period.RECENT))
        assertEquals(Period.MONTH, Listening.period(Source.LASTFM, null))
        assertEquals(Period.HALF_YEAR, Period.of("half_year"))
        assertNull(Period.of(""))
    }

    @Test fun choosesTheBestSourceUnlessOneIsAsked() {
        assertEquals(Source.LASTFM, Listening.choose(listOf(Source.YTM, Source.SPOTIFY, Source.LASTFM), null))
        assertEquals(Source.SPOTIFY, Listening.choose(listOf(Source.YTM, Source.SPOTIFY), null))
        assertEquals(Source.YTM, Listening.choose(listOf(Source.YTM, Source.LASTFM), Source.YTM))
        // Asked for one no longer connected: the best one there.
        assertEquals(Source.LISTENBRAINZ, Listening.choose(listOf(Source.LISTENBRAINZ), Source.SPOTIFY))
        // None: the playlists.
        assertNull(Listening.choose(emptyList(), Source.LASTFM))
    }

    // ------------------------------------------------------------ the figures

    private fun play(title: String, artist: String, year: Int, plays: Int, cover: String? = null) =
        Listening.Play(Track(title, title, listOf(artist), year = year), plays, cover)

    @Test fun musicalAgeWeighsThePlays() {
        // Five songs from the 2010s played once each, one from 1975 played twenty times: the 1975 one decides.
        val tracks = listOf(play("Old", "Queen", 1975, 20)) + (1..5).map { play("New $it", "Artist $it", 2015 + it, 1, "https://c/$it") }
        val r = Listening.Report(tracks, listOf(Listening.ArtistPlays("Queen", 20), Listening.ArtistPlays("Artist 1", 1)), totalTracks = 90, totalArtists = 40, totalPlays = 700,
            before = setOf(Matcher.normalizeArtist("Queen")))
        val source = Wrapped.Service("lastfm", "Last.fm", 0xFFD51007)
        val w = Wrapped.fromListening(r, source, Period.MONTH, 2026, madeAt = 1_000)
        assertEquals(1975 - Wrapped.FORMATIVE_AGE, w.bornIn)
        assertEquals(2026 - (1975 - Wrapped.FORMATIVE_AGE), w.age)
        assertEquals(1970, w.decades.first().decade)
        assertEquals(80, w.decades.first().percent)
        assertEquals(1, w.decades.first().tracks)
        assertEquals(90, w.trackCount)
        assertEquals(40, w.artistCount)
        assertEquals(700, w.plays)
        assertEquals("month", w.period)
        assertTrue(w.listening)
        assertEquals(listOf(source), w.services)
        assertEquals(Wrapped.TOP_TRACKS, w.topTracks.size)
        assertEquals(Wrapped.Song("Old", "Queen", 1975, plays = 20), w.topTracks.first())
        assertEquals(20, w.topArtists.first().plays)
        assertEquals("Old", w.oldest?.title)
        // Queen was heard the month before: the discovery is the next artist.
        assertEquals("Artist 1", w.discovery)
        assertEquals(listOf("https://c/1", "https://c/2", "https://c/3", "https://c/4", "https://c/5"), w.covers)
    }

    @Test fun fewDatedSongsGiveNoAgeAndUnknownBeforeNoDiscovery() {
        val r = Listening.Report(listOf(play("A", "X", 1990, 0), play("B", "Y", 0, 0)), listOf(Listening.ArtistPlays("X")))
        val w = Wrapped.fromListening(r, Wrapped.Service("spotify", "Spotify"), Period.YEAR, 2026, madeAt = 1)
        assertNull(w.age)
        assertNull(w.discovery)
        assertEquals(2, w.trackCount)
        assertEquals(100, w.decades.single().percent)
        // Nothing heard before (a new account): no discovery either.
        assertNull(Listening.discovery(r.copy(before = emptySet())))
    }

    @Test fun weightedMedian() {
        assertEquals(1990, Wrapped.weightedMedian(listOf(2010 to 1, 1990 to 1)))
        assertEquals(2010, Wrapped.weightedMedian(listOf(2010 to 3, 1990 to 1)))
        assertEquals(2000, Wrapped.weightedMedian(listOf(2010 to 1, 1990 to 1, 2000 to 1)))
        assertNull(Wrapped.weightedMedian(listOf(1990 to 0)))
    }

    @Test fun fillLooksUpOneAtATimeAndOnlyWhatIsMissing() = runBlocking {
        val r = Listening.Report(listOf(
            play("Dated", "A", 2001, 9, "https://c/a"), play("No year", "B", 0, 8), play("Fails", "C", 0, 7), play("Beyond", "D", 0, 1),
        ))
        var running = 0
        val asked = ArrayList<String>()
        val progress = ArrayList<Pair<Int, Int>>()
        val filled = Listening.fill(r, max = 3, onProgress = { a, b -> progress += a to b }) { t ->
            running++
            assertEquals("never two lookups at once", 1, running)
            asked += t.title
            kotlinx.coroutines.yield()
            running--
            if (t.title == "Fails") throw RuntimeException("offline") else Listening.Details(1984, "https://c/b")
        }
        // The dated song with a cover is not asked; the fourth is beyond the limit.
        assertEquals(listOf("No year", "Fails"), asked)
        assertEquals(listOf(2001, 1984, 0, 0), filled.tracks.map { it.track.year })
        assertEquals("https://c/b", filled.tracks[1].cover)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
    }

    @Test fun readsDeezerYearAndCover() {
        val page = """{"id":3135556,"title":"Harder, Better, Faster, Stronger","release_date":"2001-03-07",
            "album":{"id":302127,"cover_medium":"https://e-cdns-images.dzcdn.net/m.jpg","cover_big":"https://e-cdns-images.dzcdn.net/b.jpg"}}"""
        assertEquals(Listening.Details(2001, "https://e-cdns-images.dzcdn.net/b.jpg"), DeezerCatalog.trackDetails(parseJson(page)))
        assertEquals(Listening.Details(0, null), DeezerCatalog.trackDetails(parseJson("""{"error":{"code":800}}""")))
    }

    @Test fun oldSavedWrappedStillReads() {
        // A Wrapped kept before listening was read: no period, no top songs, the playlists' one.
        val old = """{"id":"x","madeAt":5,"year":2026,"trackCount":120,"age":40,"bornIn":1969,
            "topArtists":[{"name":"Mina","tracks":12}],"services":[{"serviceId":"spotify","name":"Spotify"}]}"""
        val w = Json { ignoreUnknownKeys = true }.decodeFromString(Wrapped.Saved.serializer(), old)
        assertFalse(w.listening)
        assertEquals(0, w.topArtists.single().plays)
        assertTrue(w.topTracks.isEmpty())
        assertNull(w.discovery)
    }

    // ------------------------------------------------------------ Deezer, the web player, personal servers

    @Test fun readsDeezerTopTracksAndArtists() {
        val j = parseJson("""{"data":[
            {"id":3135556,"title":"Harder, Better, Faster, Stronger","duration":224,"artist":{"name":"Daft Punk"},"album":{"title":"Discovery","cover_medium":"https://e-cdns-images.dzcdn.net/images/cover/x/250x250.jpg"}},
            {"title":"No id"},{"id":2,"title":"","artist":{"name":"Nobody"}}],"total":3}""")
        val plays = Listening.deezerTracks(j)
        assertEquals(listOf("3135556"), plays.map { it.track.id })
        assertEquals(listOf("Daft Punk"), plays[0].track.artists)
        assertEquals(224_000L, plays[0].track.durationMs)
        assertEquals(0, plays[0].plays)
        assertTrue(plays[0].cover!!.endsWith("250x250.jpg"))
        assertEquals(listOf("Daft Punk"), Listening.deezerArtists(parseJson("""{"data":[{"name":"Daft Punk"},{"name":" "}]}""")).map { it.name })
    }

    private fun counted(id: String, title: String, artist: String, plays: Int) = Listening.Play(Track(id = id, title = title, artists = listOf(artist)), plays)

    @Test fun aServersCountsMakeAReportOfTheSongsPlayed() {
        val r = Listening.fromCounts(listOf(
            counted("1", "Battery", "Metallica", 5), counted("2", "Home", "Beyoncé", 12), counted("3", "One", "Metallica", 9),
            counted("4", "Never", "Nobody", 0), counted("1", "Battery", "Metallica", 5),
        ))
        assertEquals(listOf("2", "3", "1"), r.tracks.map { it.track.id })
        assertEquals(listOf(Listening.ArtistPlays("Metallica", 14), Listening.ArtistPlays("Beyoncé", 12)), r.artists)
        assertEquals(26, r.totalPlays)
        assertEquals(3, r.totalTracks)
        assertEquals(2, r.totalArtists)
    }

    @Test fun readsEachServersOwnCountField() {
        val t = Track(id = "x", title = "Battery", artists = listOf("Metallica"))
        assertEquals(7, Listening.subsonicPlay(parseJson("""{"playCount":7}"""), t).plays)
        assertEquals(3, Listening.jellyfinPlay(parseJson("""{"UserData":{"PlayCount":3}}"""), t).plays)
        assertEquals(0, Listening.jellyfinPlay(parseJson("""{"UserData":{}}"""), t).plays)
        assertEquals(11, Listening.plexPlay(parseJson("""{"viewCount":11}"""), t).plays)
        assertEquals(0, Listening.plexPlay(parseJson("""{}"""), t).plays)
        assertEquals(4, Listening.lyrionPlay(parseJson("""{"playcount":"4"}"""), t).plays)
    }

    @Test fun personalServersRankAfterTheScrobblersAndBeforeTheRankings() {
        assertEquals(Source.SUBSONIC, Listening.choose(listOf(Source.SPOTIFY, Source.SUBSONIC, Source.YTM), null))
        assertEquals(Source.LASTFM, Listening.choose(listOf(Source.JELLYFIN, Source.LASTFM), null))
        assertEquals(Source.SPOTIFY_WEB, Listening.choose(listOf(Source.DEEZER, Source.SPOTIFY_WEB, Source.YTM), null))
        assertTrue(Source.PLEX.server && Source.LYRION.server && Source.EMBY.server)
        assertFalse(Source.LASTFM.server || Source.DEEZER.server || Source.SPOTIFY.server)
        // A server and Deezer tell one span only, their own.
        assertEquals(Period.ALL, Listening.period(Source.JELLYFIN, Period.YEAR))
        assertEquals(Period.ALL, Listening.period(Source.DEEZER, null))
        assertEquals(Source.SPOTIFY_WEB, Source.of("spotify_bridge"))
    }

    @Test fun theWebPlayerSaysItsSpansInItsOwnWords() {
        assertEquals("SHORT_TERM", Listening.spotifyWebRange(Period.MONTH))
        assertEquals("MID_TERM", Listening.spotifyWebRange(Period.HALF_YEAR))
        assertEquals("LONG_TERM", Listening.spotifyWebRange(Period.YEAR))
        assertNull(Listening.spotifyWebRange(Period.ALL))
        assertNull(Listening.spotifyRange(Period.ALL))
        assertNull(Listening.lastFmPeriod(Period.ALL))
        assertEquals(Period.ALL, Period.of("all"))
    }
}
