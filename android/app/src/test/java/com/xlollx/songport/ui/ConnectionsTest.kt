package com.xlollx.songport.ui

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.FakeProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Who is signed in, read once and shared by the sync editors (the lag when swapping "One target" ↔ "Several targets"). */
class ConnectionsTest {
    /** A connector that counts how often it is asked whether it is signed in. */
    private class Counting(private val inner: MusicProvider, var signedIn: Boolean) : MusicProvider by inner {
        var asked = 0
        override fun isConnected(ctx: Env): Boolean { asked++; return signedIn }
    }

    private val env = java.lang.reflect.Proxy.newProxyInstance(Env::class.java.classLoader, arrayOf(Env::class.java)) { _, _, _ -> null } as Env
    private var clock = 0L
    private val a = Counting(FakeProvider("svc-a"), true)
    private val b = Counting(FakeProvider("svc-b"), false)
    private val all = listOf(a, b)

    @Before fun setUp() { Connections.now = { clock }; Connections.invalidate() }
    @After fun tearDown() { Connections.now = { System.nanoTime() / 1_000_000 }; Connections.invalidate() }

    @Test fun withoutAReadingEachConnectorIsAskedAsBefore() {
        assertEquals(listOf(a), Connections.connected(env, all))
        assertEquals(1, a.asked); assertEquals(1, b.asked)
    }

    @Test fun swappingEditorsBackAndForthAsksNoConnectorAgain() {
        Connections.refresh(env, all)
        val after = a.asked + b.asked
        // Each swap builds the other editor: its lists, its count of services, the multi sync's targets.
        repeat(50) {
            assertEquals(listOf(a), Connections.connected(env, all))
            assertTrue(Connections.isConnected(env, a))
            assertFalse(Connections.isConnected(env, b))
        }
        assertEquals(after, a.asked + b.asked)
    }

    @Test fun aSignInWrittenOrRemovedIsAskedAgain() {
        Connections.refresh(env, all)
        b.signedIn = true
        Connections.invalidate(b.id)
        assertEquals(listOf(a, b), Connections.connected(env, all))
        // The other answer is still the one kept.
        assertEquals(1, a.asked)
    }

    @Test fun leavingTheAppForgetsEverything() {
        Connections.refresh(env, all)
        a.signedIn = false
        Connections.invalidate()
        assertEquals(emptyList<MusicProvider>(), Connections.connected(env, all))
    }

    @Test fun anOldAnswerIsNotTrusted() {
        Connections.refresh(env, all)
        clock += Connections.MAX_AGE_MS + 1
        a.signedIn = false
        assertFalse(Connections.isConnected(env, a))
    }

    @Test fun aConnectorAddedAfterTheReadingIsAskedDirectly() {
        Connections.refresh(env, listOf(a))
        assertEquals(listOf(a), Connections.connected(env, all))
        assertEquals(1, b.asked)
    }

    @Test fun aReadingOvertakenByAnInvalidationIsNotKept() {
        val slow = object : MusicProvider by a {
            override fun isConnected(ctx: Env): Boolean { Connections.invalidate(); return true }
        }
        Connections.refresh(env, listOf(slow))
        assertTrue(Connections.known().isEmpty())
    }
}
