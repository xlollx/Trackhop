package com.xlollx.songport.contract

import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.ChartsProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Apple's chart playlists in the Charts source: the Daily Top 100 (world and countries) and the
 * cities' Top 25, listed by the Apple Music API's charts call with `with=dailyGlobalTopCharts,cityCharts`
 * (developer.apple.com/documentation/applemusicapi/charts) and read as catalogue playlists, with the
 * developer token alone. The answers are written from Apple's reference, not saved from an account.
 */
class AppleChartPlaylistsContractTest : ContractTest() {
    private val api = "api.music.apple.com"
    private fun res(name: String) = javaClass.getResource("/charts/$name")!!.readText()
    private val country = ChartsProvider.country

    @Before fun token() {
        BuildDefaults.values = mapOf("APPLE_DEVELOPER_TOKEN" to "DEVJWT")
        ChartsProvider.country = { "it" }
        ChartsProvider.forgetLists()
    }

    @After fun restore() {
        ChartsProvider.country = country
        ChartsProvider.forgetLists()
    }

    private fun deezerNone() = json("""{"data":[]}""")

    private val page2 = """{"results":{"dailyGlobalTopCharts":[{"chart":"daily-global-top","name":"Top 100 giornaliere","orderId":"daily-global-top:playlists",
        "href":"/v1/catalog/it/charts?chart=daily-global-top&limit=50&offset=3&types=playlists&with=dailyGlobalTopCharts",
        "data":[{"id":"pl.0b593f1142b84a50a2c1e7088b3fb683","type":"playlists","attributes":{"name":"Top 100: Giappone","curatorName":"Apple Music","isChart":true}}]}]}}"""

    @Test fun dailyAndCityChartsAreListedNextToTheTop100() = runBlocking {
        deezerNone(); json(res("apple-chart-playlists-it.json")); json(page2)
        val lists = ChartsProvider.playlists(env)
        val apple = lists.filter { it.id.startsWith(ChartsProvider.APPLE_LIST) }
        // The user's country's daily chart first, then Apple's order: the other daily charts (both pages), then the cities.
        assertEquals(
            listOf("Top 100: Italia", "Top 100: Mondiale", "Top 100: Stati Uniti", "Top 100: Giappone", "Top 25: Milano", "Top 25: Roma").map { "charts_apple_list_name($it)" },
            apple.map { it.name },
        )
        assertEquals("applelist:pl.737e067787df485a8062e2c4927d94db", apple[0].id)
        // The country's Top 100 from the public feed stays first of all, and all the others are still there.
        assertEquals("apple:it", lists[0].id)
        assertTrue(lists.count { it.id.startsWith("apple:") } > 30)

        assertEquals("api.deezer.com", next().host())
        val r = next()
        assertCall(r, "GET", api, "/v1/catalog/it/charts")
        assertEquals("playlists", r.query("types"))
        assertEquals("dailyGlobalTopCharts,cityCharts", r.query("with"))
        assertEquals("Bearer DEVJWT", r.getHeader("Authorization"))
        // A catalogue call: no user is signed in and no user token travels.
        assertNull(r.getHeader("Music-User-Token"))
        val more = next()
        assertCall(more, "GET", api, "/v1/catalog/it/charts")
        assertEquals("3", more.query("offset"))
        assertEquals("dailyGlobalTopCharts", more.query("with"))
        noMoreRequests()

        // Kept for the day, as Deezer's list is: listing again asks nothing.
        assertEquals(apple.map { it.id }, ChartsProvider.playlists(env).filter { it.id.startsWith(ChartsProvider.APPLE_LIST) }.map { it.id })
        noMoreRequests()
    }

    @Test fun aChartPlaylistIsReadAsACataloguePlaylist() = runBlocking {
        json("""{"data":[
            {"id":"1772364192","type":"songs","attributes":{"name":"Espresso","artistName":"Sabrina Carpenter","albumName":"Short n' Sweet","durationInMillis":175459,"isrc":"USUM72403305","releaseDate":"2024-08-23"}},
            {"id":"1781270323","type":"songs","attributes":{"name":"Die With A Smile","artistName":"Lady Gaga & Bruno Mars","albumName":"Die With A Smile - Single","durationInMillis":251667,"isrc":"USUM72409273","contentRating":"clean","releaseDate":"2024-08-16"}}]}""")
        val id = "applelist:pl.d25f5d1181894928af76c85c967f8f31"
        assertTrue(ChartsProvider.canRead(env, id))
        val ts = ChartsProvider.tracks(env, id)
        assertEquals(listOf("am:1772364192", "am:1781270323"), ts.map { it.id })
        assertEquals(listOf("Lady Gaga", "Bruno Mars"), ts[1].artists)
        assertEquals("USUM72403305", ts[0].isrc)
        assertEquals(175459L, ts[0].durationMs)
        val r = next()
        assertEquals(api, r.host())
        assertTrue(r.pathOnly(), r.pathOnly().matches(Regex("/v1/catalog/[a-z]{2}/playlists/pl\\.d25f5d1181894928af76c85c967f8f31/tracks")))
        assertEquals("Bearer DEVJWT", r.getHeader("Authorization"))
        assertNull(r.getHeader("Music-User-Token"))
    }

    @Test fun aSavedChartKeepsItsNameWhenTheListWasNotReadYet() = runBlocking {
        json("""{"data":[{"id":"pl.c2a2f1d4b65b4c5a9e1b4e3b9b2a1d22","type":"playlists","attributes":{"name":"Top 25: Roma","curatorName":"Apple Music"}}]}""")
        assertEquals("charts_apple_list_name(Top 25: Roma)", ChartsProvider.playlistInfo(env, "applelist:pl.c2a2f1d4b65b4c5a9e1b4e3b9b2a1d22").name)
        assertTrue(next().pathOnly().endsWith("/playlists/pl.c2a2f1d4b65b4c5a9e1b4e3b9b2a1d22"))
    }

    @Test fun withoutATokenTheyAreNotListedAndNothingIsAsked() = runBlocking {
        BuildDefaults.values = emptyMap()
        deezerNone()
        val lists = ChartsProvider.playlists(env)
        assertTrue(lists.none { it.id.startsWith(ChartsProvider.APPLE_LIST) })
        assertFalse(ChartsProvider.canRead(env, "applelist:pl.d25f5d1181894928af76c85c967f8f31"))
        assertEquals("apple:it", lists[0].id)
        assertEquals("api.deezer.com", next().host())
        noMoreRequests()
    }

    @Test fun anExpiredTokenLeavesTheOtherChartsListed() = runBlocking {
        deezerNone()
        json("""{"errors":[{"status":"401","code":"40100","title":"Unauthorized","detail":"The developer token has expired"}]}""", 401)
        val lists = ChartsProvider.playlists(env)
        assertTrue(lists.none { it.id.startsWith(ChartsProvider.APPLE_LIST) })
        assertEquals("apple:it", lists[0].id)
        assertTrue(env.logs.any { "chart playlists not listed" in it })
    }

    @Test fun onlyACatalogueIdIsEverAsked() = runBlocking {
        for (bad in listOf("applelist:../me/library", "applelist:pl.x/../../y", "applelist:p.library", "applelist:"))
            assertTrue(bad, runCatching { ChartsProvider.tracks(env, bad) }.exceptionOrNull() is IllegalArgumentException)
        noMoreRequests()
    }
}
