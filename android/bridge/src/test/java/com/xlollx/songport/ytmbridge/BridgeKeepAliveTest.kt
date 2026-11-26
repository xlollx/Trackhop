package com.xlollx.songport.ytmbridge

import android.app.Application
import android.os.Bundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The web sessions' keep-alive methods (`<service>.keepAlive`), Trackhop's twice-daily renewal and
 * its "Reconnect": nothing done for an account not signed in, one signed-in call otherwise, which
 * renews a refused session as the connector's calls do; `renewed`, `expired` or neither (not renewed
 * this time) as `soundcloud.keepAlive` answers. Never a token in the log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeKeepAliveTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private val token = "Ab3_" + "k".repeat(80) + "-Z"
    private val realPage = QobuzRenewal.hiddenPage
    private var pages = 0
    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())

    private val methods = listOf(
        "spotify.keepAlive", "deezer.keepAlive", "qobuz.keepAlive", "anghami.keepAlive",
        "gaana.keepAlive", "liveone.keepAlive", "apple.keepAlive", "amazon.keepAlive",
    )

    @Before fun setUp() {
        QobuzRenewal.forget()
        QobuzRenewal.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(null, "no page in tests") }
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
        BridgeMock.start()
        QobuzWebClient.gapMs = 0
        app = RuntimeEnvironment.getApplication()
        for (s in listOf(SpotifyBridge.session, AppleBridge.session, DeezerBridge.session, QobuzBridge.session, GaanaBridge.session, AnghamiBridge.session, LiveOneBridge.session)) {
            s.clear(app); s.forSlot("2").clear(app)
        }
        AmazonSession.clear(app)
    }

    @After fun tearDown() { QobuzWebClient.gapMs = 400; BridgeMock.stop(); QobuzRenewal.hiddenPage = realPage; QobuzRenewal.forget(); BridgeNet.logSink = null }

    private fun call(method: String, slot: String? = null) =
        BridgeCore.call(app, method, null, slot?.let { s -> Bundle().apply { putString("slot", s) } })

    private val playlists = """{"user":{"id":1001,"login":"listener"},"playlists":{"offset":0,"limit":50,"total":0,"items":[]}}"""
    private val refused = """{"status":"error","code":401,"message":"User authentication is required."}"""

    @Test fun nothingIsDoneForAnAccountNotSignedIn() {
        for (m in methods) {
            val b = call(m)
            assertNull(m, b.getString("error"))
            assertFalse(m, b.getBoolean("connected"))
            assertFalse(m, b.getBoolean("renewed"))
            assertFalse(m, b.getBoolean("expired"))
        }
        assertTrue("no request at all", BridgeMock.all.isEmpty())
    }

    @Test fun aSessionThatWorksIsRenewed() {
        assertNull(QobuzBridge.keep(app, token, "798273057"))
        BridgeMock.answer(playlists)
        val b = call("qobuz.keepAlive")
        assertTrue(b.getBoolean("renewed"))
        assertTrue(b.getBoolean("connected"))
        assertFalse(b.getBoolean("expired"))
        assertEquals(1, BridgeMock.calls.size)
        assertTrue(logs.toString(), logs.contains("[qobuz] keep-alive: the session works"))
        assertTrue(logs.none { it.contains(token) })
    }

    @Test fun aSessionRefusedAndNotRenewedIsExpired() {
        assertNull(QobuzBridge.keep(app, token, "798273057"))
        BridgeMock.answer(refused, code = 401)
        val b = call("qobuz.keepAlive")
        assertFalse(b.getBoolean("renewed"))
        assertTrue(b.getBoolean("expired"))
        // The hidden page was tried first, as any refused call does.
        assertEquals(1, pages)
        assertTrue(logs.toString(), logs.contains("[qobuz] keep-alive: the session is gone, a new sign-in is needed"))
        assertTrue(logs.none { it.contains(token) })
    }

    @Test fun anOutageIsNeitherRenewedNorExpired() {
        assertNull(QobuzBridge.keep(app, token, "798273057"))
        repeat(4) { BridgeMock.answer("<html>busy</html>", code = 503) }
        QobuzWebClient.retryWaitsMs = longArrayOf(1, 1)
        try {
            val b = call("qobuz.keepAlive")
            assertFalse(b.getBoolean("renewed"))
            assertFalse(b.getBoolean("expired"))
            assertTrue(b.getBoolean("connected"))
            assertTrue(b.getString("problem").orEmpty().isNotEmpty())
        } finally {
            QobuzWebClient.retryWaitsMs = longArrayOf(15_000, 45_000)
        }
    }

    @Test fun eachAccountIsKeptAliveOnItsOwn() {
        assertNull(QobuzBridge.keep(app, token, "798273057", slot = "2"))
        // The primary is not signed in: nothing; the extra account is.
        assertFalse(call("qobuz.keepAlive").getBoolean("connected"))
        assertTrue(BridgeMock.all.isEmpty())
        BridgeMock.answer(playlists)
        assertTrue(call("qobuz.keepAlive", "2").getBoolean("renewed"))
        assertTrue(logs.toString(), logs.contains("[qobuz] keep-alive (account 2): the session works"))
    }

    @Test fun amazonServesOneAccount() {
        assertEquals(BridgeCore.ONE_ACCOUNT, call("amazon.keepAlive", "2").getString("error"))
    }

    @Test fun theWordsOfASessionGone() {
        listOf(
            QobuzWebClient.EXPIRED, AnghamiWebClient.EXPIRED, GaanaWebClient.EXPIRED, LiveOneWebClient.EXPIRED,
            DeezerWebClient.EXPIRED, AppleBridge.EXPIRED, "the Spotify web session has expired: sign in again",
            "Amazon Music served the player to a signed-out visitor: the Amazon session has expired",
        ).forEach { assertTrue(it, BridgeCore.sessionGone(it)) }
        listOf(null, "HTTP 503", "Timeout", "Apple Music: HTTP 403").forEach { assertFalse(it.toString(), BridgeCore.sessionGone(it)) }
    }
}
