package com.xlollx.songport.ytmbridge

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The review's removal on Qobuz: the entries holding a few tracks, read from the end of a long
 * playlist (where a sync adds) and no further than needed. A made-up playlist of 2130 tracks, served
 * by offset and limit as playlist/get does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class QobuzFindEntriesTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private val token = "Ab3_" + "y".repeat(80) + "-Z"
    private val size = 2130
    /** The (offset, limit) of each playlist/get read. */
    private val reads = ArrayList<Pair<Int, Int>>()
    /** Reads the site refuses before answering (a 403 page, not the API's JSON). */
    private var refusals = 0

    private fun entry(i: Int) = """{"id":${5000 + i},"title":"Track $i","duration":200,"performer":{"name":"Artist"},"album":{"title":"Album"},"playlist_track_id":${900000 + i}}"""

    @Before fun setUp() {
        BridgeMock.start()
        app = RuntimeEnvironment.getApplication()
        assertNull(QobuzBridge.keep(app, token, "798273057"))
        QobuzWebClient.gapMs = 0
        QobuzWebClient.retryWaitsMs = longArrayOf(1, 1)
        BridgeMock.fixed = { r ->
            if (r.requestUrl!!.encodedPath.endsWith("playlist/get")) {
                if (refusals > 0) {
                    refusals--
                    MockResponse().setResponseCode(403).setBody("<html>Access denied</html>")
                } else {
                    val offset = r.requestUrl!!.queryParameter("offset")!!.toInt()
                    val limit = r.requestUrl!!.queryParameter("limit")!!.toInt()
                    synchronized(reads) { reads += offset to limit }
                    val items = (offset until minOf(size, offset + limit)).joinToString(",") { entry(it) }
                    MockResponse().setBody("""{"id":7001,"tracks":{"offset":$offset,"limit":$limit,"total":$size,"items":[$items]}}""")
                }
            } else null
        }
    }

    @After fun tearDown() {
        BridgeMock.fixed = { null }
        BridgeMock.stop()
        QobuzWebClient.gapMs = 400
        QobuzWebClient.retryWaitsMs = longArrayOf(15_000, 45_000)
    }

    private fun id(i: Int) = "${5000 + i}"

    @Test fun aTrackAtTheEndTakesTwoShortReads() {
        val found = QobuzWebClient(app).findEntries("7001", setOf(id(2129)))
        assertEquals(listOf("902129"), found.map { it.setVideoId })
        assertEquals(listOf(0 to 50, 2080 to 50), reads)
    }

    @Test fun aTrackAtTheStartTakesOne() {
        QobuzWebClient(app).findEntries("7001", setOf(id(3)))
        assertEquals(listOf(0 to 50), reads)
    }

    @Test fun theReadGoesBackUntilEveryTrackTurnedUp() {
        val found = QobuzWebClient(app).findEntries("7001", setOf(id(2100), id(1700)))
        assertEquals(setOf(id(2100), id(1700)), found.map { it.id }.toSet())
        assertEquals(listOf(0 to 50, 2080 to 50, 1580 to 500), reads)
    }

    @Test fun aTrackNotThereReadsEveryEntryOnce() {
        assertTrue(QobuzWebClient(app).findEntries("7001", setOf("nope")).isEmpty())
        assertEquals(listOf(0 to 50, 2080 to 50, 1580 to 500, 1080 to 500, 580 to 500, 80 to 500, 50 to 30), reads)
        assertEquals(size, reads.sumOf { it.second })
    }

    @Test fun aRefusedReadIsAskedAgain() {
        refusals = 1
        assertEquals(1, QobuzWebClient(app).findEntries("7001", setOf(id(2129))).size)
    }

    @Test fun theBridgeAnswersTheFindCall() {
        val b = BridgeCore.call(app, "qobuz.find", "7001", android.os.Bundle().apply { putStringArray("ids", arrayOf(id(2129), id(2128))) })
        assertNull(b.getString("error"))
        val list = parseJson(b.getString("json")!!).arr
        assertEquals(listOf("902128", "902129"), list.map { it["setVideoId"].str })
        assertEquals(2, reads.size)
    }
}
