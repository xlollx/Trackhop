package com.xlollx.songport.ytmbridge

import android.app.Application
import android.os.Bundle
import android.os.Process
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * The web sessions handed to Trackhop's encrypted backup (`sessions.export`, `sessions.import`):
 * only this app itself or Trackhop, signed with its own certificate, gets them; they come back as
 * they were kept, and a session already here is replaced only when asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SessionTransferTest {
    private lateinit var app: Application
    private val known = "AB".repeat(32)
    private val other = "CD".repeat(32)
    private val trackhop = SessionTransfer.TRACKHOP

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // Other tests of this run leave sessions behind: each test starts from none.
        Session.clear(app)
        AmazonSession.clear(app)
        SessionTransfer.SERVICES.forEach { BridgeCore.resettable(it, "")?.clear(app) }
    }

    @After fun tearDown() = ShadowBinder.reset()

    // ------------------------------------------------------------------ the caller check

    @Test fun onlyThisAppOrTrackhopWithItsCertificate() {
        fun ok(uid: Int, packages: List<String>?, certs: Set<String>?) =
            SessionTransfer.callerOk(uid, 1000, packages, setOf(known)) { if (it == trackhop) certs else null }
        // In-process (Trackhop's own build, or the Bridge app itself).
        assertTrue(ok(1000, null, null))
        // Trackhop, signed with a known certificate (any letter case).
        assertTrue(ok(2000, listOf(trackhop), setOf(known)))
        assertTrue(ok(2000, listOf(trackhop), setOf(known.lowercase(), other)))
        // Trackhop's name with another key, without a readable certificate, or none at all.
        assertFalse(ok(2000, listOf(trackhop), setOf(other)))
        assertFalse(ok(2000, listOf(trackhop), emptySet()))
        assertFalse(ok(2000, listOf(trackhop), null))
        // Any other app, an unknown uid, a uid shared with another package.
        assertFalse(ok(2000, listOf("org.example.other"), setOf(known)))
        assertFalse(ok(2000, null, setOf(known)))
        assertFalse(ok(2000, emptyList(), setOf(known)))
        assertFalse(ok(2000, listOf(trackhop, "org.example.other"), setOf(known)))
        assertFalse(ok(2000, listOf("org.example.other", trackhop), setOf(known)))
        // A failure reading the certificates is a refusal.
        assertFalse(SessionTransfer.callerOk(2000, 1000, listOf(trackhop), setOf(known)) { throw SecurityException("no") })
    }

    @Test fun anotherAppGetsNoSessions() {
        SpotifyBridge.session.save(app, "sp_dc=cookie-a", "Account A")
        val uid = Process.myUid() + 1
        ShadowBinder.setCallingUid(uid)
        val out = BridgeCore.call(app, SessionTransfer.EXPORT, null, null)
        assertEquals(SessionTransfer.NOT_ALLOWED, out.getString("error"))
        assertNull(out.getString("json"))
        // Even under Trackhop's name: without Trackhop's certificate, nothing.
        shadowOf(app.packageManager).setPackagesForUid(uid, trackhop)
        assertEquals(SessionTransfer.NOT_ALLOWED, BridgeCore.call(app, SessionTransfer.EXPORT, null, null).getString("error"))
        val write = BridgeCore.call(app, SessionTransfer.IMPORT, null, Bundle().apply { putString(SessionTransfer.EXTRA_JSON, "{}"); putBoolean(SessionTransfer.EXTRA_REPLACE, true) })
        assertEquals(SessionTransfer.NOT_ALLOWED, write.getString("error"))
        assertEquals("cookie-a".let { "sp_dc=$it" }, SpotifyBridge.session.cookies(app))
    }

    // ------------------------------------------------------------------ the sessions

    @Test fun sessionsGoAndComeBackAsTheyWereKept() {
        val spotify = SpotifyBridge.session
        spotify.save(app, "sp_dc=cookie-a", "Account A")
        spotify.put(app, "token", "token-a")
        val out = BridgeCore.call(app, SessionTransfer.EXPORT, null, null)
        assertNull(out.getString("error"))
        val text = out.getString(SessionTransfer.EXTRA_JSON)!!
        assertEquals(1, out.getInt("count"))
        assertTrue(text.contains("\"spotify\"") && text.contains("Account A"))

        // On a device without it: put back.
        spotify.clear(app)
        assertFalse(spotify.isConnected(app))
        val back = BridgeCore.call(app, SessionTransfer.IMPORT, null, Bundle().apply { putString(SessionTransfer.EXTRA_JSON, text) })
        assertEquals(1, back.getInt("restored"))
        assertEquals("sp_dc=cookie-a", spotify.cookies(app))
        assertEquals("Account A", spotify.account(app))
        assertEquals("token-a", spotify.get(app, "token"))
    }

    @Test fun aSessionHereIsReplacedOnlyWhenAsked() {
        val spotify = SpotifyBridge.session
        spotify.save(app, "sp_dc=cookie-a", "Account A")
        val text = BridgeCore.call(app, SessionTransfer.EXPORT, null, null).getString(SessionTransfer.EXTRA_JSON)!!
        spotify.save(app, "sp_dc=cookie-b", "Account B")
        fun import(replace: Boolean, dryRun: Boolean = false) = BridgeCore.call(app, SessionTransfer.IMPORT, null, Bundle().apply {
            putString(SessionTransfer.EXTRA_JSON, text); putBoolean(SessionTransfer.EXTRA_REPLACE, replace); putBoolean(SessionTransfer.EXTRA_DRY_RUN, dryRun)
        })
        assertArrayEquals(arrayOf("spotify"), import(replace = true, dryRun = true).getStringArray("present"))
        assertEquals("sp_dc=cookie-b", spotify.cookies(app))
        val kept = import(replace = false)
        assertArrayEquals(arrayOf("spotify"), kept.getStringArray("kept"))
        assertEquals(0, kept.getInt("restored"))
        assertEquals("sp_dc=cookie-b", spotify.cookies(app))
        assertEquals(1, import(replace = true).getInt("restored"))
        assertEquals("sp_dc=cookie-a", spotify.cookies(app))
        assertEquals("Account A", spotify.account(app))
    }

    @Test fun unknownServicesAndOtherFilesAreSkipped() {
        val text = """{"format":"trackhop-bridge-sessions","version":1,"sessions":[
            {"service":"nowhere","slot":"","values":{"cookies":{"t":"s","v":"cookie-a"}}},
            {"service":"deezer","slot":"../x","values":{"cookies":{"t":"s","v":"cookie-a"}}},
            {"service":"deezer","slot":"","values":{"cookies":{"t":"s","v":"arl=cookie-a"},"x_n":{"t":"l","v":5}}}]}"""
        val r = BridgeCore.call(app, SessionTransfer.IMPORT, null, Bundle().apply { putString(SessionTransfer.EXTRA_JSON, text) })
        assertEquals(2, r.getInt("skipped"))
        assertEquals(1, r.getInt("restored"))
        assertEquals("arl=cookie-a", DeezerBridge.session.cookies(app))
        assertTrue(BridgeCore.call(app, SessionTransfer.IMPORT, null, Bundle().apply { putString(SessionTransfer.EXTRA_JSON, "{\"format\":\"other\"}") }).containsKey("error"))
    }

    @Test fun valuesKeepTheirTypes() {
        val values = mapOf("s" to "text-a", "b" to true, "i" to 3, "l" to 4L, "f" to 1.5f, "ss" to setOf("x", "y"), "__androidx_security_crypto_x" to "skip")
        val back = SessionTransfer.decode(SessionTransfer.encode(values))
        assertEquals(values - "__androidx_security_crypto_x", back)
    }
}
