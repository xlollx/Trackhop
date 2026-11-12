package com.xlollx.songport.contract

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.YouTubeProvider
import com.xlollx.songport.sync.QuotaMeter
import com.xlollx.songport.sync.Reorder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * YouTube Data API playlistItems.update with `snippet.position` (developers.google.com/youtube/v3/docs/playlistItems/update):
 * a playlist put in a sync's order, each move a write of 50 units, never more than the day's quota left.
 */
class YouTubeReorderContractTest : ContractTest() {
    private val yt = YouTubeProvider()
    private val api = "www.googleapis.com"

    @Before fun signIn() {
        BuildDefaults.values = mapOf("GOOGLE_CLIENT_ID" to "gcid.apps.googleusercontent.com")
        env.tokens.set("youtube", Tokens("YA1", "YR1", userId = "UCme", userName = "Me"))
    }

    private fun item(id: String, video: String, title: String = "Song $video") =
        """{"id":"$id","snippet":{"title":${q(title)},"playlistId":"PL1","resourceId":{"kind":"youtube#video","videoId":"$video"}},"contentDetails":{"videoId":"$video"}}"""

    /** a, b, c listed; a deleted video between a and b, which the listing leaves out but positions count. */
    private val cur = listOf(Track("va", "a", itemId = "Ia"), Track("vb", "b", itemId = "Ib"), Track("vc", "c", itemId = "Ic"))
    private val playlist = """{"items":[${item("Ia", "va")},${item("Ix", "vx", "Deleted video")},${item("Ib", "vb")},${item("Ic", "vc")}]}"""

    /** Applies the updates made, as YouTube would: each entry taken out and put at its new position. */
    private fun replay(start: List<String>, moves: Int): List<String> {
        val list = start.toMutableList()
        repeat(moves) {
            val r = next()
            assertCall(r, "PUT", api, "/youtube/v3/playlistItems")
            assertEquals("snippet", r.query("part"))
            val b = r.jsonBody()
            val id = b["id"].str!!
            assertEquals("PL1", b["snippet"]["playlistId"].str)
            assertEquals("youtube#video", b["snippet"]["resourceId"]["kind"].str)
            assertEquals("v" + id.drop(1).lowercase(), b["snippet"]["resourceId"]["videoId"].str)
            list.remove(id)
            list.add(b["snippet"]["position"].int!!, id)
        }
        return list
    }

    @Test fun movesByPositionCountingTheEntriesTheListingLeavesOut() = runBlocking {
        json(playlist)
        repeat(2) { json("""{"id":"x"}""") }
        withContext(MusicProvider.OrderedAdds) { yt.reorderTracks(env, "PL1", cur, listOf(cur[2], cur[1], cur[0])) }
        val read = next()
        assertCall(read, "GET", api, "/youtube/v3/playlistItems")
        assertEquals(listOf("Ic", "Ix", "Ib", "Ia"), replay(listOf("Ia", "Ix", "Ib", "Ic"), 2))
        noMoreRequests()
        // One unit for the page read, fifty for each move.
        assertEquals(101L, QuotaMeter.used(env.store.data, "youtube"))
    }

    @Test fun notEnoughQuotaLeftNoMoveIsMade() = runBlocking {
        QuotaMeter.add(env, "youtube", QuotaMeter.YOUTUBE_DAILY - 60)
        json(playlist)
        try {
            withContext(MusicProvider.OrderedAdds) { yt.reorderTracks(env, "PL1", cur, listOf(cur[2], cur[1], cur[0])) }
            fail()
        } catch (e: Reorder.Skipped) {
            assertTrue(e.message, e.message!!.contains("quota"))
        }
        next()
        noMoreRequests()
    }

    @Test fun noMoveOutsideASyncThatKeepsTheOrder() = runBlocking {
        try { yt.reorderTracks(env, "PL1", cur, cur.reversed()); fail() } catch (_: Reorder.Skipped) {}
        noMoreRequests()
        assertEquals(0L, QuotaMeter.used(env.store.data, "youtube"))
    }

    @Test fun alreadyInOrderNothingIsAsked() = runBlocking {
        withContext(MusicProvider.OrderedAdds) { yt.reorderTracks(env, "PL1", cur, cur) }
        noMoreRequests()
    }

    @Test fun aPlaylistChangedMeanwhileIsLeftAsItIs() = runBlocking {
        json("""{"items":[${item("Ia", "va")},${item("Iz", "vz")},${item("Ic", "vc")}]}""")
        try {
            withContext(MusicProvider.OrderedAdds) { yt.reorderTracks(env, "PL1", cur, cur.reversed()) }
            fail()
        } catch (_: Reorder.Skipped) {}
        next()
        noMoreRequests()
    }
}
