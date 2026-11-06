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
 * The `slot` extra of the connectors' calls ([BridgeCore]): absent, the primary account is served
 * exactly as every Trackhop before slots asked for it; "2", "3"… name an extra account with a
 * session of its own; the connectors that serve one account refuse a slot; the status calls say
 * whether a service takes slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeCoreSlotTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application
    private var profiles = true

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Slots.multiProfile = { profiles }
        for (s in listOf(SpotifyBridge.session, AppleBridge.session, DeezerBridge.session, QobuzBridge.session, AnghamiBridge.session, LiveOneBridge.session)) {
            s.clear(app); s.forSlot("2").clear(app); s.forSlot("3").clear(app)
        }
        Session.clear(app); Session.forSlot("2").clear(app)
    }

    @After fun tearDown() {
        Slots.multiProfile = { runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) }.getOrDefault(false) }
    }

    private fun slot(s: String) = Bundle().apply { putString("slot", s) }
    private fun call(method: String, extras: Bundle? = null) = BridgeCore.call(app, method, null, extras)

    @Test fun noSlotIsThePrimaryAccountAndASlotIsItsOwn() {
        SpotifyBridge.session.save(app, "sp_dc=one", "Alice")
        SpotifyBridge.session.forSlot("2").save(app, "sp_dc=two", "Bob")

        val primary = call("spotify.status")
        assertTrue(primary.getBoolean("connected")); assertEquals("Alice", primary.getString("account"))
        val two = call("spotify.status", slot("2"))
        assertTrue(two.getBoolean("connected")); assertEquals("Bob", two.getString("account"))
        val three = call("spotify.status", slot("3"))
        assertFalse(three.getBoolean("connected")); assertNull(three.getString("account"))
        // An empty or malformed slot is the primary, as before slots existed.
        assertEquals("Alice", call("spotify.status", slot("")).getString("account"))
        assertEquals("Alice", call("spotify.status", slot("x")).getString("account"))
        // Other extras travel as they did; a slot among them changes only the account.
        assertEquals("Alice", call("spotify.status", Bundle().apply { putInt("offset", 0) }).getString("account"))
    }

    @Test fun disconnectingASlotLeavesThePrimaryAndTheOtherWayRound() {
        AppleBridge.session.save(app, "media-user-token=one", "Apple Music (IT)")
        AppleBridge.session.forSlot("2").save(app, "media-user-token=two", "Apple Music (DE)")
        assertNull(call("apple.disconnect", slot("2")).getString("error"))
        assertFalse(call("apple.status", slot("2")).getBoolean("connected"))
        assertEquals("Apple Music (IT)", call("apple.status").getString("account"))
        AppleBridge.session.forSlot("2").save(app, "media-user-token=two", "Apple Music (DE)")
        assertNull(call("apple.disconnect").getString("error"))
        assertFalse(call("apple.status").getBoolean("connected"))
        assertEquals("Apple Music (DE)", call("apple.status", slot("2")).getString("account"))
    }

    @Test fun everySlotServiceKeepsItsAccountsApart() {
        val services = mapOf(
            "deezer" to DeezerBridge.session, "qobuz" to QobuzBridge.session,
            "anghami" to AnghamiBridge.session, "liveone" to LiveOneBridge.session,
        )
        for ((name, session) in services) {
            session.save(app, "$name=1", "$name one")
            session.forSlot("2").save(app, "$name=1", "$name two")
            assertEquals(name, "$name one", call("$name.status").getString("account"))
            assertEquals(name, "$name two", call("$name.status", slot("2")).getString("account"))
            assertFalse(name, call("$name.status", slot("3")).getBoolean("connected"))
        }
        Session.save(app, "SAPISID=aaa", "yt one")
        Session.forSlot("2").save(app, "SAPISID=bbb", "yt two")
        assertEquals("yt one", call("status").getString("account"))
        assertEquals("yt two", call("status", slot("2")).getString("account"))
    }

    @Test fun theOneAccountConnectorsRefuseASlotAndServeThePrimaryAsBefore() {
        for (m in listOf("amazon.status", "amazon.playlists", "soundcloud.status", "soundcloud.playlists", "net.fetch", "audio.recognize")) {
            assertEquals(m, BridgeCore.ONE_ACCOUNT, call(m, slot("2")).getString("error"))
        }
        // Without a slot they answer as always (not connected here, but answered).
        assertNull(call("amazon.status").getString("error"))
        assertFalse(call("amazon.status").getBoolean("connected"))
        assertNull(call("soundcloud.status").getString("error"))
    }

    @Test fun theStatusCallsSayWhichServicesTakeSlots() {
        for (m in listOf("status", "spotify.status", "apple.status", "deezer.status", "qobuz.status", "anghami.status", "liveone.status")) {
            assertTrue(m, call(m).getBoolean("slots"))
        }
        for (m in listOf("amazon.status", "soundcloud.status")) {
            assertTrue(m, call(m).containsKey("slots"))
            assertFalse(m, call(m).getBoolean("slots"))
        }
        // A WebView that cannot keep profiles apart: no slots anywhere, the primary account as before.
        profiles = false
        assertFalse(call("spotify.status").getBoolean("slots"))
        assertFalse(call("status").getBoolean("slots"))
        assertFalse(call("net.status").getBoolean("slots"))
        assertTrue(call("net.status").getBoolean("ok"))
    }
}
