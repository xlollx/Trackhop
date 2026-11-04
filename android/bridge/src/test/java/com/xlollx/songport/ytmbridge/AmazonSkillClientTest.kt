package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skill API of the Amazon Music web player (issue #35), on answers trimmed from a real capture of
 * a Dutch account's player (resources/amazon-skill: playlist and song names kept, customer and device
 * ids, tokens and metrics links left out). The request bodies below are the capture's, byte for byte.
 */
class AmazonSkillClientTest {
    private fun resource(name: String) = parseJson(javaClass.getResource("/amazon-skill/$name")!!.readText())

    private val hd = """{"level":"HD_MEMBER"}"""
    private val test = "087f7086-7db8-462b-9b1d-7ed7822e5a8a"
    private val eu = "eu.web.skill.music.a2z.com"

    @Test fun libraryPlaylistsWithTheNameInTheStorageDefault() {
        assertEquals(listOf(PlaylistDto(test, "Test", -1)), AmazonSkillClient.parsePlaylists(resource("library-playlists.json")))
    }

    @Test fun playlistRowsCarryTheirTrackEntryId() {
        val ts = AmazonSkillClient.parsePlaylistTracks(resource("library-playlist.json"))
        assertEquals(1, ts.size)
        val t = ts[0]
        assertEquals("B0FWKPNTJ7", t.id)
        assertEquals("People Watching", t.title)
        assertEquals(listOf("Sam Fender"), t.artists)
        assertEquals("People Watching (Deluxe Edition) [Clean]", t.album)
        assertEquals(305_000L, t.durationMs)
        // What removeTrackFromPlaylist takes as trackEntryId (the capture's removal of this very row).
        assertEquals("a64b54d9-c63c-46c1-a921-33e1eed66364", t.setVideoId)
        assertTrue(AmazonSkillClient.nextPages(resource("library-playlist.json")).isEmpty())
    }

    @Test fun searchKeepsSongsAndLeavesTheArtistOut() {
        val ts = AmazonSkillClient.parseTracks(resource("search.json"))
        assertEquals(listOf("B0HNNQZGGG"), ts.map { it.id })
        assertEquals("You Can't Always Get What You Want", ts[0].title)
        assertEquals(listOf("Sam Fender"), ts[0].artists)
        assertNull(ts[0].setVideoId)
    }

    @Test fun bodiesAreTheCapturesOwn() {
        assertEquals("""{"userHash":"{\"level\":\"HD_MEMBER\"}"}""", AmazonSkillClient.body(emptyMap(), hd, null))
        assertEquals("""{"id":"$test","userHash":"{\"level\":\"HD_MEMBER\"}"}""", AmazonSkillClient.body(mapOf("id" to test), hd, null))
        assertEquals(
            """{"playlistId":"$test","trackEntryId":"a64b54d9-c63c-46c1-a921-33e1eed66364","trackId":"B0FWKPNTJ7","userHash":"{\"level\":\"HD_MEMBER\"}"}""",
            AmazonSkillClient.body(mapOf("trackId" to "B0FWKPNTJ7", "playlistId" to test, "trackEntryId" to "a64b54d9-c63c-46c1-a921-33e1eed66364"), hd, null),
        )
        assertEquals(
            """{"keyword":"{\"interface\":\"Web.TemplatesInterface.v1_0.Touch.SearchTemplateInterface.SearchKeywordClientInformation\",\"keyword\":\"\"}","suggestedKeyword":"sam fender","userHash":"{\"level\":\"HD_MEMBER\"}"}""",
            AmazonSkillClient.body(AmazonSkillClient.searchParams("sam fender"), hd, null),
        )
        assertEquals(
            """{"trackId":"B07QGX8GNT","trackTitle":"Hypersonic Missiles","userHash":"{\"level\":\"HD_MEMBER\"}"}""",
            AmazonSkillClient.body(mapOf("trackId" to "B07QGX8GNT", "trackTitle" to "Hypersonic Missiles"), hd, null),
        )
    }

    @Test fun theHeadersTravelAsAJsonStringInTheBody() {
        val b = parseJson(AmazonSkillClient.body(mapOf("id" to "X"), hd, mapOf("x-amzn-device-id" to "1", "x-amzn-csrf" to """{"token":"t"}""")))
        val h = parseJson(b["headers"].str!!)
        assertEquals("1", h["x-amzn-device-id"].str)
        assertEquals("""{"token":"t"}""", h["x-amzn-csrf"].str)
    }

    @Test fun theAddDialogsLinkIsSentToThePlayersHostAsTheCapturedCall() {
        val link = AmazonSkillClient.addLink(resource("add-dialog.json"), test)!!
        assertTrue(link, link.startsWith("https://zaz.mesk.skill.music.a2z.com/api/addTrackToPlaylist?"))
        val (url, params) = AmazonSkillClient.request(link, eu)!!
        assertEquals("https://$eu/api/addTrackToPlaylist", url)
        // The playlist's version is not a constant: 3 for "Test", 16298 for the first "Approved".
        assertEquals("3", params["version"])
        val captured = """{"isTrackInLibrary":"false","playlistId":"$test","playlistTitle":"Test","rejectDuplicate":"true","shouldReplaceAddedTrack":"false","trackId":"B07QGX8GNT","trackTitle":"Hypersonic Missiles","userHash":"{\"level\":\"HD_MEMBER\"}","version":"3"}"""
        assertEquals(captured, AmazonSkillClient.body(params, params["userHash"]!!, null))
        assertEquals(captured, AmazonSkillClient.body(AmazonSkillClient.addParams(test, "Test", "B07QGX8GNT", "Hypersonic Missiles", "3"), hd, null))
        val other = AmazonSkillClient.request(AmazonSkillClient.addLink(resource("add-dialog.json"), "79531412-a6a9-4eda-8be8-e8ec238c7f97")!!, eu)!!.second
        assertEquals("16298", other["version"])
        assertEquals("Approved", other["playlistTitle"])
        assertNull(AmazonSkillClient.addLink(resource("add-dialog.json"), "not-a-playlist"))
    }

    @Test fun answersOfAddAndRemove() {
        assertEquals("added", AmazonSkillClient.addResult(resource("add-answer.json")))
        assertNull(AmazonSkillClient.topLevelError(resource("remove-answer.json")))
        // Error templates inside onError are what a later failure would show, not an error now.
        assertNull(AmazonSkillClient.topLevelError(resource("library-playlists.json")))
        val sorry = parseJson("""{"methods":[{"interface":"TemplateListInterface.v1_0.CreateAndBindTemplateMethod","template":{"interface":"Web.TemplatesInterface.v1_0.Touch.MessageTemplateInterface.MessageTemplate","message":"We are unable to complete your action.","header":"We're Sorry"}}]}""")
        assertEquals("We're Sorry: We are unable to complete your action.", AmazonSkillClient.topLevelError(sorry))
        assertEquals("We're Sorry: We are unable to complete your action.", AmazonSkillClient.addResult(sorry))
        val again = parseJson("""{"methods":[{"url":"https://zaz.mesk.skill.music.a2z.com/api/addTrackToPlaylist?playlistId=$test&rejectDuplicate=false"}]}""")
        assertEquals("duplicate", AmazonSkillClient.addResult(again))
    }

    @Test fun theSkillHosts() {
        assertTrue(AmazonSkillClient.isSkillHost(eu))
        assertTrue(AmazonSkillClient.isSkillHost("na.web.skill.music.a2z.com"))
        assertTrue(AmazonSkillClient.isSkillHost("fe.web.skill.music.a2z.com"))
        assertTrue(AmazonSkillClient.isSkillHost("EU.web.skill.music.a2z.com"))
        assertFalse(AmazonSkillClient.isSkillHost("zaz.mesk.skill.music.a2z.com"))
        assertFalse(AmazonSkillClient.isSkillHost("eu.web.skill.music.a2z.com.evil.net"))
        assertFalse(AmazonSkillClient.isSkillHost("gql.music.amazon.com"))
        assertFalse(AmazonSkillClient.isSkillHost(null))
        // The hook in the page uses the same expression.
        assertTrue(AmazonBridge.HOOK.contains("""/^(na|eu|fe)\.web\.skill\.music\.a2z\.com$/"""))
    }

    @Test fun linksOfAnswersGoToThePlayersOwnHostWithTheQueryAsArguments() {
        val (url, params) = AmazonSkillClient.request("https://zaz.mesk.skill.music.a2z.com/api/createPlaylist?userHash=%7B%22level%22%3A%22HD_MEMBER%22%7D", eu)!!
        assertEquals("https://$eu/api/createPlaylist", url)
        assertEquals(mapOf("userHash" to hd), params)
        val (_, p2) = AmazonSkillClient.request("https://zaz.mesk.skill.music.a2z.com/api/showLibraryPlaylist?id=$test&userHash=%7B%7D", eu)!!
        assertEquals(test, p2["id"])
        assertNull(AmazonSkillClient.request("https://music.amazon.com/api/weblab", eu))
        assertNull(AmazonSkillClient.request("https://zaz.mesk.skill.music.a2z.com.evil.net/api/x", eu))
    }

    @Test fun onlyTheAnswersOwnMoreLinksArePages() {
        val j = parseJson("""{"methods":[{"template":{"widgets":[{"items":[],"onEndOfWidget":[{"url":"https://zaz.mesk.skill.music.a2z.com/api/showLibraryPlaylist?id=X&next=2"}]}],
            "x":{"onItemSelected":[{"template":{"widgets":[{"onEndOfWidget":[{"url":"https://zaz.mesk.skill.music.a2z.com/api/showCatalogAlbum?id=Y"}]}]}}]}}}]}""")
        assertEquals(listOf("https://zaz.mesk.skill.music.a2z.com/api/showLibraryPlaylist?id=X&next=2"), AmazonSkillClient.nextPages(j))
    }

    @Test fun createdPlaylistId() {
        assertEquals(test, AmazonSkillClient.createdId(parseJson("""{"methods":[{"url":"https://zaz.mesk.skill.music.a2z.com/api/addTrackToPlaylist?playlistId=$test&version=1"}]}""")))
        assertEquals(test, AmazonSkillClient.createdId(parseJson("""{"methods":[{"deeplink":"/my/playlists/$test"}]}""")))
        assertNull(AmazonSkillClient.createdId(parseJson("""{"methods":[]}""")))
    }

    @Test fun mirroredHeadersAreKeptOnlyWithCredentials() {
        val token = """{"interface":"ClientAuthenticationInterface.v1_0.ClientTokenElement","accessToken":"Atna|x"}"""
        val withToken = jsonObj("X-Amzn-Authentication" to token, "x-amzn-device-id" to "1").toString()
        assertEquals("1", AmazonSkillClient.headersOf(withToken)!!["x-amzn-device-id"])
        assertEquals(null, AmazonSkillClient.headersOf(jsonObj("x-amzn-device-id" to "1").toString()))
        assertEquals(null, AmazonSkillClient.headersOf("not json"))
    }

    @Test fun routing() {
        val skill = AmazonRoute.SKILL
        val gql = AmazonRoute.GQL
        // The Dutch player: showHome to na., then its library on eu., never GraphQL.
        assertEquals(null, AmazonRoute.afterPlayerCall(null, skill = true, method = "showHome", pageSawGql = false))
        assertEquals(null, AmazonRoute.afterPlayerCall(null, skill = true, method = "pageViewed", pageSawGql = false))
        assertEquals(skill, AmazonRoute.afterPlayerCall(null, skill = true, method = "showLibraryPlaylists", pageSawGql = false))
        assertEquals(skill, AmazonRoute.afterPlayerCall(null, skill = true, method = "showHomeBrowse", pageSawGql = false))
        // A page that also calls GraphQL is a GraphQL player, whatever else it calls.
        assertEquals(null, AmazonRoute.afterPlayerCall(null, skill = true, method = "showLibraryPlaylists", pageSawGql = true))
        assertEquals(gql, AmazonRoute.afterPlayerCall(gql, skill = true, method = "showSearch", pageSawGql = true))
        // A GraphQL call: an account on the skill API goes back; any other stays as it is (no write).
        assertEquals(gql, AmazonRoute.afterPlayerCall(skill, skill = false, method = "", pageSawGql = true))
        assertEquals(null, AmazonRoute.afterPlayerCall(null, skill = false, method = "", pageSawGql = true))
        assertEquals(gql, AmazonRoute.afterPlayerCall(gql, skill = false, method = "", pageSawGql = true))
        // GraphQL's AUTH_INVALID_CUSTOMER_ID moves to the skill API only with the player's skill credentials known.
        assertTrue(AmazonRoute.fallbackToSkill(invalidCustomer = true, skillKnown = true))
        assertFalse(AmazonRoute.fallbackToSkill(invalidCustomer = true, skillKnown = false))
        assertFalse(AmazonRoute.fallbackToSkill(invalidCustomer = false, skillKnown = true))
    }
}
