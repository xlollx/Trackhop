package com.xlollx.songport.ytmbridge

import android.app.Application
import android.os.Bundle
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
 * The YouTube Music channel choice in the Bridge (issue #41): kept per account slot next to the
 * session, said in the status answer (with `channels`, which an older Bridge lacks), set and
 * cleared through the `channel` method, and gone with a sign-out. Nothing reaches Google here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class YtmChannelBridgeTest {
    companion object { init { BridgeMock.install() } }

    private lateinit var app: Application

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        Session.clear(app); Session.forSlot("2").clear(app)
    }

    private fun slot(s: String) = Bundle().apply { putString("slot", s) }

    @Test fun aSessionFromBeforeHasNoChannelAndTheStatusSaysTheChoiceIsOffered() {
        Session.save(app, "SAPISID=aaa", "Me")
        assertNull(Session.channel(app))
        assertNull(Session.channelName(app))
        val status = BridgeCore.call(app, "status", null, null)
        assertTrue(status.getBoolean("channels"))
        assertFalse(status.containsKey("channel"))
        assertEquals("Me", status.getString("account"))
    }

    @Test fun theChoiceIsKeptPerSlotAndClearedWithABlankOrASignOut() {
        Session.save(app, "SAPISID=aaa", "Me")
        Session.forSlot("2").save(app, "SAPISID=bbb", "Other")
        // Set on the second account: the first is untouched. The name read from Google fails here (no network) and is kept as it was.
        val set = BridgeCore.call(app, "channel", "112233", slot("2").apply { putString("name", "Rossi Records") })
        assertNull(set.getString("error"))
        assertEquals("112233", set.getString("channel"))
        assertEquals("Rossi Records", set.getString("channelName"))
        assertEquals("112233", Session.forSlot("2").channel(app))
        assertEquals("Rossi Records", Session.forSlot("2").channelName(app))
        assertNull(Session.channel(app))
        val status2 = BridgeCore.call(app, "status", null, slot("2"))
        assertEquals("112233", status2.getString("channel")); assertEquals("Rossi Records", status2.getString("channelName"))
        assertFalse(BridgeCore.call(app, "status", null, null).containsKey("channel"))
        // The client of that slot makes its calls as the brand account; the primary's does not.
        assertEquals("112233", AndroidYtmHost(app, "2").channel)
        assertNull(AndroidYtmHost(app).channel)
        // A blank choice is the account's own channel again.
        BridgeCore.call(app, "channel", "", slot("2"))
        assertNull(Session.forSlot("2").channel(app))
        assertNull(Session.forSlot("2").channelName(app))
        // And a sign-out forgets the choice with the session.
        BridgeCore.call(app, "channel", "445566", slot("2"))
        BridgeCore.call(app, "disconnect", null, slot("2"))
        assertNull(Session.forSlot("2").channel(app))
        assertEquals("Me", Session.account(app))
    }

    @Test fun aMalformedOrMissingChoiceIsNoChoice() {
        Session.save(app, "SAPISID=aaa", "Me")
        Session.setChannel(app, "  ", "x")
        assertNull(Session.channel(app))
        Session.setChannel(app, null, null)
        assertNull(Session.channel(app))
        assertNull(AndroidYtmHost(app).channel)
    }
}
