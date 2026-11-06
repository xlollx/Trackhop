package com.xlollx.songport.ytmbridge

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The sessions of a web connector's accounts ([Slots]): the primary account keeps what it always
 * had, an extra slot has storage of its own, and neither can overwrite the other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SlotSessionTest {
    private lateinit var app: Application
    private val primary = WebSession("slottest", listOf("example.com"))

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        primary.clear(app); primary.forSlot("2").clear(app); primary.forSlot("3").clear(app)
    }

    @Test fun thePrimaryIsItselfAndAnExtraSlotIsAnotherSession() {
        assertSame(primary, primary.forSlot(""))
        assertSame(primary, primary.forSlot(Slots.PRIMARY))
        // A malformed slot is the primary, never a session of its own.
        assertSame(primary, primary.forSlot("x"))
        assertSame(primary, primary.forSlot("2x"))
        val two = primary.forSlot("2")
        assertEquals("2", two.slot)
        assertSame(two, primary.forSlot("2"))
        assertSame(two, two.forSlot("2"))
        assertSame(primary.forSlot("3"), two.forSlot("3"))
        assertEquals("", two.forSlot("").slot)
    }

    @Test fun twoSlotsDoNotOverwriteEachOtherAndThePrimaryIsUntouched() {
        primary.save(app, "sid=one", "Alice")
        primary.put(app, "token", "t1")
        val two = primary.forSlot("2")
        two.save(app, "sid=two", "Bob")
        two.put(app, "token", "t2")

        assertEquals("sid=one", primary.cookies(app)); assertEquals("Alice", primary.account(app)); assertEquals("t1", primary.get(app, "token"))
        assertEquals("sid=two", two.cookies(app)); assertEquals("Bob", two.account(app)); assertEquals("t2", two.get(app, "token"))
        assertFalse(primary.forSlot("3").isConnected(app))
        assertNull(primary.forSlot("3").get(app, "token"))

        // Signing out of the extra account leaves the primary as it was, and the other way round.
        two.clear(app)
        assertFalse(two.isConnected(app))
        assertEquals("sid=one", primary.cookies(app)); assertEquals("t1", primary.get(app, "token"))
        two.save(app, "sid=two-again", null)
        primary.clear(app)
        assertFalse(primary.isConnected(app))
        assertEquals("sid=two-again", two.cookies(app))
    }

    @Test fun thePrimaryKeepsItsFileAndAnExtraSlotHasItsOwn() {
        // Robolectric has no Keystore, so the sessions fall back to the plain files: the primary's
        // name is the one every version so far wrote, an extra slot's sits next to it.
        primary.save(app, "sid=one", "Alice")
        primary.forSlot("2").save(app, "sid=two", "Bob")
        assertEquals("sid=one", app.getSharedPreferences("slottest_session_plain", 0).getString("cookies", null))
        assertEquals("sid=two", app.getSharedPreferences("slottest_s2_session_plain", 0).getString("cookies", null))
        assertNull(app.getSharedPreferences("slottest_session_plain", 0).getString("x_slot", null))
    }

    @Test fun theYouTubeMusicSessionKeepsAnAccountPerSlot() {
        val two = Session.forSlot("2")
        Session.clear(app); two.clear(app)
        assertSame(Session, Session.forSlot(""))
        assertSame(Session, Session.forSlot("nope"))
        assertSame(two, Session.forSlot("2"))
        Session.save(app, "SAPISID=aaa; HSID=1", "first@example.com")
        two.save(app, "SAPISID=bbb; HSID=2", "second@example.com")
        assertEquals("first@example.com", Session.account(app))
        assertEquals("second@example.com", two.account(app))
        assertTrue(Session.cookies(app)!!.contains("SAPISID=aaa"))
        assertTrue(two.cookies(app)!!.contains("SAPISID=bbb"))
        assertFalse(Session.forSlot("3").isConnected(app))
        two.clear(app)
        assertEquals("first@example.com", Session.account(app))
        assertFalse(two.isConnected(app))
        Session.clear(app)
    }

    @Test fun aSlotIsReadFromTheExtrasAndMalformedOnesAreThePrimary() {
        assertEquals("", Slots.of(null))
        assertEquals("", Slots.of(android.os.Bundle()))
        assertEquals("2", Slots.of(android.os.Bundle().apply { putString("slot", "2") }))
        assertEquals("12", Slots.of(android.os.Bundle().apply { putString("slot", "12") }))
        assertEquals("", Slots.of(android.os.Bundle().apply { putString("slot", "") }))
        assertEquals("", Slots.of(android.os.Bundle().apply { putString("slot", "a") }))
        assertEquals("", Slots.of(android.os.Bundle().apply { putString("slot", "2; DROP") }))
        assertEquals("trackhop-spotify-2", Slots.profileName("spotify", "2"))
    }
}
