package com.xlollx.songport.ytmbridge

import android.app.Application
import android.webkit.CookieManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The silent renewal of Anghami's, Gaana's and Apple Music's web sessions ([SessionRenewal]): a
 * refused session is renewed once from the site's own (a hidden page, replaced here: a JVM test has
 * no WebView), the call is made again, and nothing is wiped; refused again, or not renewed, it is
 * "sign in again", and a page that renewed nothing is not opened again for every call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SessionRenewalTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private var pages = 0
    private val renewals = listOf(AnghamiBridge.renewal, GaanaBridge.renewal, AppleBridge.renewal)
    private val realPages = renewals.map { it.hiddenPage }

    @Before fun setUp() {
        BridgeMock.start()
        app = RuntimeEnvironment.getApplication()
        GaanaWebClient.gapMs = 0
        GaanaWebClient.retryWaitMs = 1
        renewals.forEach { r -> r.forget(); r.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(null, "no page in tests") } }
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
        CookieManager.getInstance().setCookie("https://play.anghami.com", "site_session=kept")
        CookieManager.getInstance().setCookie("https://gaana.com", "site_session=kept")
    }

    @After fun tearDown() {
        BridgeMock.stop()
        renewals.forEachIndexed { i, r -> r.hiddenPage = realPages[i]; r.forget() }
        BridgeNet.logSink = null
        listOf(AnghamiBridge.session, GaanaBridge.session, AppleBridge.session).forEach { it.forgetStored(app) }
    }

    private fun cookie(site: String) = CookieManager.getInstance().getCookie(site).orEmpty()
    private fun noTokenIn(vararg tokens: String) = assertTrue(logs.toString(), logs.none { l -> tokens.any { l.contains(it) } })

    // ---- Anghami

    private val sid = "i7:abcdefgh:" + "Q".repeat(126)
    private val newSid = "i7:abcdefgh:" + "R".repeat(126)
    private val anghamiPlaylists = """{"status":"ok","sections":[{"type":"playlist","data":[{"id":"298799901","name":"Road trip","count":3}]}]}"""
    private val anghamiLogoff = """{"_attributes":{"status":"failed"},"error":{"_attributes":{"code":"111","message":"Please login again","logoff":"true"}}}"""

    @Test fun anghamiRenewsARefusedSidFromThePlayerAndMakesTheCallAgain() {
        assertNull(AnghamiBridge.keep(app, sid, "fp-1", fromPage = true))
        AnghamiBridge.renewal.hiddenPage = { _, _, isNew ->
            pages++
            assertFalse(isNew(mapOf(AnghamiWebClient.SID to sid, AnghamiWebClient.FP to "fp-other")))
            HiddenSessionPage.Result(mapOf(AnghamiWebClient.SID to newSid, AnghamiWebClient.FP to "fp-2"), null)
        }
        BridgeMock.answer(anghamiLogoff)
        BridgeMock.answer(anghamiPlaylists)
        assertEquals(listOf("Road trip"), AnghamiWebClient(app).playlists().map { it.name })
        assertEquals(1, pages)
        assertEquals(sid, BridgeMock.calls[0].requestUrl!!.queryParameter("sid"))
        assertEquals(newSid, BridgeMock.calls[1].requestUrl!!.queryParameter("sid"))
        assertEquals("fp-2", BridgeMock.calls[1].requestUrl!!.queryParameter("fingerprint"))
        assertTrue(AnghamiBridge.session.isConnected(app))
        assertTrue(cookie("https://play.anghami.com").contains("site_session=kept"))
        assertTrue(logs.toString(), logs.any { it.startsWith("[anghami] access renewed in a hidden page (new sid") })
        assertTrue(logs.contains("[anghami] session refused; access renewed and the call made again: ok"))
        noTokenIn(sid, newSid)
    }

    @Test fun anghamiRefusedAgainOrNotRenewedNeedsASignInWithoutAWipe() {
        assertNull(AnghamiBridge.keep(app, sid, "fp-1", fromPage = true))
        AnghamiBridge.renewal.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(mapOf(AnghamiWebClient.SID to newSid), null) }
        BridgeMock.answer(anghamiLogoff)
        BridgeMock.answer("", code = 401)
        try { AnghamiWebClient(app).create("x"); fail() } catch (e: BridgeException) { assertEquals(AnghamiWebClient.EXPIRED, e.message) }
        assertEquals(2, BridgeMock.calls.size)
        assertEquals(1, pages)
        assertTrue(logs.contains("[anghami] session refused; access renewed and the call made again: refused again"))
        // A page that gives nothing: "sign in again", and no page again for the next call.
        AnghamiBridge.renewal.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(null, "no page in tests") }
        repeat(2) {
            BridgeMock.answer(anghamiLogoff)
            try { AnghamiWebClient(app).playlists(); fail() } catch (e: BridgeException) { assertEquals(AnghamiWebClient.EXPIRED, e.message) }
        }
        assertEquals(2, pages)
        assertEquals(newSid, AnghamiBridge.session.get(app, AnghamiWebClient.SID))
        assertTrue(AnghamiBridge.session.isConnected(app))
        assertTrue(cookie("https://play.anghami.com").contains("site_session=kept"))
    }

    // ---- Gaana

    private val gToken = "tk_" + "a1B2".repeat(12)
    private val gNew = "tk_" + "z9Y8".repeat(12)
    private val gaanaPlaylists = """{"playlist":[{"playlist_id":"5550001","seokey":"road-trip-u1","title":"Road trip","trackids":"","trackcount":0,"is_public":0}],"user_token_status":"1","status":1}"""
    private val loginNeeded = """{"msg":"Login Needed!","user_token_status":0,"user-token-status":0}"""

    @Test fun gaanaRenewsARefusedTokenFromTheSiteAndMakesTheCallAgain() {
        assertNull(GaanaBridge.keep(app, gToken, deviceId = "dev-123", country = "IN", fromPage = true))
        GaanaBridge.renewal.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(mapOf(GaanaWebClient.TOKEN to gNew), null) }
        BridgeMock.answer(loginNeeded)
        BridgeMock.answer(gaanaPlaylists)
        assertEquals(listOf("Road trip"), GaanaWebClient(app).playlists().map { it.name })
        assertEquals(gToken, BridgeMock.calls[0].requestUrl!!.queryParameter("token"))
        assertEquals(gNew, BridgeMock.calls[1].requestUrl!!.queryParameter("token"))
        // What the page did not show stays as it was.
        assertEquals("dev-123", BridgeMock.calls[1].getHeader("deviceId"))
        assertTrue(GaanaBridge.session.isConnected(app))
        assertTrue(cookie("https://gaana.com").contains("site_session=kept"))
        assertTrue(logs.toString(), logs.any { it.startsWith("[gaana] access renewed in a hidden page (new token") })
        noTokenIn(gToken, gNew)
    }

    @Test fun gaanaPlaylistDetailRefusedForItsTokenIsRenewedToo() {
        assertNull(GaanaBridge.keep(app, gToken))
        GaanaBridge.renewal.hiddenPage = { _, _, _ -> pages++; HiddenSessionPage.Result(mapOf(GaanaWebClient.TOKEN to gNew), null) }
        BridgeMock.answer("""{"status":1,"user_token_status":0,"tracks":[]}""")
        BridgeMock.answer("""{"status":1,"user_token_status":"1","tracks":[],"playlist":{"playlist_id":"5550001","title":"Road trip","trackids":""}}""")
        assertEquals(0, GaanaWebClient(app).tracks("5550001").size)
        assertEquals(1, pages)
        assertEquals(2, BridgeMock.calls.size)
    }

    @Test fun gaanaNotRenewedNeedsASignInAndIsNotTriedForEveryCall() {
        assertNull(GaanaBridge.keep(app, gToken, fromPage = true))
        repeat(2) {
            BridgeMock.answer(loginNeeded)
            try { GaanaWebClient(app).playlists(); fail() } catch (e: BridgeException) { assertEquals(GaanaWebClient.EXPIRED, e.message) }
        }
        assertEquals(1, pages)
        assertEquals(gToken, GaanaBridge.session.get(app, GaanaWebClient.TOKEN))
        assertTrue(cookie("https://gaana.com").contains("site_session=kept"))
        assertTrue(logs.toString(), logs.contains("[gaana] access not renewed: no page in tests"))
    }

    @Test fun theSignInsOwnTryRenewsNothing() {
        assertNull(GaanaBridge.keep(app, gToken, fromPage = true))
        BridgeMock.answer(loginNeeded)
        try { GaanaWebClient(app, renews = false).playlists(); fail() } catch (e: BridgeException) { assertEquals(GaanaWebClient.EXPIRED, e.message) }
        assertEquals(0, pages)
        // Kept from the page, the site's cookies stay; a pasted session clears them.
        assertTrue(cookie("https://gaana.com").contains("site_session=kept"))
        assertNull(GaanaBridge.keep(app, gToken))
        assertFalse(cookie("https://gaana.com").contains("site_session=kept"))
    }

    // ---- Apple Music

    private val mut = "Am" + "u".repeat(60)
    private val mutNew = "An" + "v".repeat(60)

    @Test fun appleRenewsTheUserTokenFromThePlayersCookieAndKeepsTheJar() {
        AppleBridge.session.save(app, "media-user-token=$mut; itspod=1", "me")
        AppleBridge.renewal.hiddenPage = { _, _, isNew ->
            pages++
            assertFalse(isNew(mapOf("userToken" to mut)))
            HiddenSessionPage.Result(mapOf("userToken" to mutNew, "jar" to "media-user-token=$mutNew; itspod=1"), null)
        }
        assertTrue(AppleBridge.renewal.renew(app, "", mut))
        assertEquals(mutNew, AppleBridge.userToken(AppleBridge.session.cookies(app)))
        assertEquals("me", AppleBridge.session.account(app))
        // Another call refused with the old token meanwhile: the new one is used, no page.
        assertTrue(AppleBridge.renewal.renew(app, "", mut))
        assertEquals(1, pages)
        assertTrue(logs.toString(), logs.any { it.startsWith("[apple] access renewed in a hidden page (new user token") })
        noTokenIn(mut, mutNew)
    }

    @Test fun appleNotRenewedKeepsTheSessionAsItIs() {
        AppleBridge.session.save(app, "media-user-token=$mut", "me")
        assertFalse(AppleBridge.renewal.renew(app, "", mut))
        assertFalse(AppleBridge.renewal.renew(app, "", mut))
        assertEquals(1, pages)
        assertEquals(mut, AppleBridge.userToken(AppleBridge.session.cookies(app)))
        // Not signed in: nothing to renew, no page.
        AppleBridge.session.forgetStored(app)
        AppleBridge.renewal.forget()
        assertFalse(AppleBridge.renewal.renew(app, "", mut))
        assertEquals(1, pages)
    }
}
