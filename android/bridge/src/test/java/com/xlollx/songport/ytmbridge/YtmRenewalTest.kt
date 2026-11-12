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
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The YouTube Music session renewed in the player's page (issue #62): the keep-alive's outcomes,
 * the jar kept with the account's name and channel, and the renewal after a 401 at most once per
 * pause and once for concurrent calls. No WebView in a JVM test: the hidden page is played here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class YtmRenewalTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private val realPage = YtmRenewal.hiddenPage
    private val realAnswers = YtmRenewal.sessionAnswers
    private val pages = Collections.synchronizedList(ArrayList<String>())
    private var page: () -> YtmRenewal.Page = { YtmRenewal.Page(null, null, "no page in a test") }
    private var answers: Boolean? = null
    private var clock = 1_000_000_000L
    private val logs = Collections.synchronizedList(ArrayList<String>())

    private val old = "SAPISID=sapi-old; SID=sid-old; __Secure-3PSIDTS=ts-old"
    private val fresh = "SAPISID=sapi-old; SID=sid-old; __Secure-3PSIDTS=ts-new"

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Session.clear(app); Session.forSlot("2").clear(app)
        YtmRenewal.forget()
        YtmRenewal.hiddenPage = { _, slot -> pages += slot; page() }
        YtmRenewal.sessionAnswers = { _, _ -> answers }
        YtmRenewal.now = { clock }
        BridgeNet.logSink = { tag, m -> logs += "[$tag] $m" }
    }

    @After fun tearDown() {
        YtmRenewal.hiddenPage = realPage
        YtmRenewal.sessionAnswers = realAnswers
        YtmRenewal.now = { System.currentTimeMillis() }
        BridgeNet.logSink = null
    }

    private fun signedIn(slot: String = "") = Session.forSlot(slot).apply {
        save(app, old, "Mario Rossi")
        setChannel(app, "112233", "Rossi Records")
    }

    private fun noSecrets() = assertTrue(logs.toString(), logs.none { it.contains("sapi-") || it.contains("sid-") || it.contains("ts-") })

    @Test fun theKeepAliveKeepsTheRotatedJarWithTheAccountAndItsChannel() {
        signedIn()
        page = { YtmRenewal.Page(fresh, true, null) }
        assertEquals(YtmRenewal.Outcome.RENEWED, YtmRenewal.keepAlive(app))
        assertEquals(listOf(""), pages)
        assertEquals(fresh, Session.cookies(app))
        assertEquals("Mario Rossi", Session.account(app))
        assertEquals("112233", Session.channel(app))
        assertEquals("Rossi Records", Session.channelName(app))
        assertEquals(listOf("[ytm] session renewed in the background"), logs)
        noSecrets()
    }

    @Test fun aSessionRenewedLessThanAnHourAgoIsLeftAlone() {
        signedIn()
        page = { YtmRenewal.Page(fresh, true, null) }
        YtmRenewal.keepAlive(app)
        clock += 30 * 60_000L
        assertEquals(YtmRenewal.Outcome.SKIPPED, YtmRenewal.keepAlive(app))
        clock += 31 * 60_000L
        assertEquals(YtmRenewal.Outcome.RENEWED, YtmRenewal.keepAlive(app))
        assertEquals(2, pages.size)
    }

    @Test fun nothingIsOpenedWithoutASession() {
        assertEquals(YtmRenewal.Outcome.NOT_CONNECTED, YtmRenewal.keepAlive(app))
        assertNull(YtmRenewal.afterRefusal(app, "", old))
        assertTrue(pages.isEmpty())
    }

    @Test fun aSignedOutPageWhoseSessionIsRefusedTooIsTheSessionGone() {
        signedIn()
        page = { YtmRenewal.Page("SID=other", false, null) }
        answers = false
        val b = BridgeCore.call(app, "keepAlive", null, null)
        assertNull(b.getString("error"))
        assertTrue(b.getBoolean("expired"))
        assertFalse(b.getBoolean("renewed"))
        assertTrue(b.getBoolean("connected"))
        // The jar of a signed-out page is not kept over the session.
        assertEquals(old, Session.cookies(app))
        assertEquals(1, logs.size)
        assertTrue(logs[0], logs[0].startsWith("[ytm] the background renewal found the session signed out: "))
        noSecrets()
    }

    @Test fun aPageThatOnlyLooksSignedOutEndsNothing() {
        signedIn()
        page = { YtmRenewal.Page(old, false, null) }
        answers = true
        assertEquals(YtmRenewal.Outcome.FAILED, YtmRenewal.keepAlive(app))
        answers = null
        assertEquals(YtmRenewal.Outcome.FAILED, YtmRenewal.keepAlive(app))
        assertTrue(logs.all { it.startsWith("[ytm] the background renewal failed: ") })
        assertEquals(old, Session.cookies(app))
    }

    @Test fun aPageThatDidNotLoadKeepsTheSessionAndSaysWhy() {
        signedIn()
        page = { YtmRenewal.Page(null, null, "the page did not load (error -2)") }
        val b = BridgeCore.call(app, "keepAlive", null, null)
        assertFalse(b.getBoolean("expired"))
        assertFalse(b.getBoolean("renewed"))
        assertEquals(old, Session.cookies(app))
        assertEquals(listOf("[ytm] the background renewal failed: the page did not load (error -2)"), logs)
    }

    @Test fun aRefusalRenewsOnceThenNotAgainForAWhile() {
        signedIn()
        page = { YtmRenewal.Page(fresh, true, null) }
        assertEquals(fresh, YtmRenewal.afterRefusal(app, "", old))
        assertEquals(1, pages.size)
        // The renewed session refused too, within the pause: no second page, the call fails.
        clock += 5 * 60_000L
        assertNull(YtmRenewal.afterRefusal(app, "", fresh))
        assertEquals(1, pages.size)
        assertTrue(logs.toString(), logs.last().startsWith("[ytm] session refused (401); renewed 5 minutes ago already"))
        // A later run renews again.
        clock += 6 * 60_000L
        page = { YtmRenewal.Page("$fresh; x=1", true, null) }
        assertEquals("$fresh; x=1", YtmRenewal.afterRefusal(app, "", fresh))
        assertEquals(2, pages.size)
        noSecrets()
    }

    @Test fun aFailedRenewalAfterARefusalSaysWhyOnce() {
        signedIn()
        page = { YtmRenewal.Page(null, false, null) }
        assertNull(YtmRenewal.afterRefusal(app, "", old))
        assertEquals(listOf("[ytm] session refused (401); the player's page is signed out: the player's page says it is not signed in"), logs)
        assertEquals(old, Session.cookies(app))
    }

    @Test fun concurrentRefusalsOpenOnePage() {
        signedIn()
        val inPage = CountDownLatch(1)
        val release = CountDownLatch(1)
        page = { inPage.countDown(); release.await(5, TimeUnit.SECONDS); YtmRenewal.Page(fresh, true, null) }
        val got = Collections.synchronizedList(ArrayList<String?>())
        val first = thread { got += YtmRenewal.afterRefusal(app, "", old) }
        assertTrue(inPage.await(5, TimeUnit.SECONDS))
        val second = thread { got += YtmRenewal.afterRefusal(app, "", old) }
        Thread.sleep(200)
        release.countDown()
        first.join(5_000); second.join(5_000)
        // One page; the call that waited retries with what it renewed.
        assertEquals(1, pages.size)
        assertEquals(listOf<String?>(fresh, fresh), got)
    }

    @Test fun eachAccountIsRenewedInItsOwnSession() {
        signedIn("2")
        page = { YtmRenewal.Page(fresh, true, null) }
        val b = BridgeCore.call(app, "keepAlive", null, Bundle().apply { putString("slot", "2") })
        assertNull(b.getString("error"))
        assertTrue(b.getBoolean("renewed"))
        assertEquals(listOf("2"), pages)
        assertEquals(fresh, Session.forSlot("2").cookies(app))
        assertEquals("112233", Session.forSlot("2").channel(app))
        assertNull(Session.cookies(app))
        assertEquals(listOf("[ytm] session renewed in the background (account 2)"), logs)
    }

    @Test fun aSignInOrASignOutStartsTheRenewalsAfresh() {
        signedIn()
        page = { YtmRenewal.Page(fresh, true, null) }
        YtmRenewal.afterRefusal(app, "", old)
        Session.clear(app)
        signedIn()
        assertEquals(fresh, YtmRenewal.afterRefusal(app, "", old))
        assertEquals(2, pages.size)
    }

    @Test fun whatThePageSaysOfItsVisitor() {
        assertEquals(true, YtmRenewal.loggedIn("\"true\""))
        assertEquals(false, YtmRenewal.loggedIn("\"false\""))
        assertNull(YtmRenewal.loggedIn("\"\""))
        assertNull(YtmRenewal.loggedIn("null"))
        assertNull(YtmRenewal.loggedIn(null))
    }
}
