package com.xlollx.songport.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionKeepAliveTest {
    @Test fun scheduledOnlyWhileAKeptAliveConnectorIsThere() {
        assertTrue(SessionKeepAlive.wanted(listOf("spotify", "soundcloud")))
        assertTrue(SessionKeepAlive.wanted(listOf("ytm")))
        assertTrue(SessionKeepAlive.wanted(listOf("deezer_web", "ytm@2")))
        assertFalse(SessionKeepAlive.wanted(listOf("spotify", "deezer_web", "spotify_bridge", "qobuz_web", "amazon", "youtube")))
        assertFalse(SessionKeepAlive.wanted(emptyList()))
    }

    @Test fun thePlanNamesEachConnectorItsMethodAndAccount() {
        val plan = SessionKeepAlive.plan(listOf("spotify", "soundcloud", "ytm", "ytm@2", "ytm", "deezer_bridge@2"))
        assertEquals(listOf("soundcloud", "ytm", "ytm@2"), plan.map { it.connectorId })
        assertEquals(listOf("soundcloud.keepAlive", "keepAlive", "keepAlive"), plan.map { it.target.method })
        assertEquals(listOf("", "", "2"), plan.map { it.slot })
        assertEquals(listOf("SoundCloud", "YouTube Music", "YouTube Music"), plan.map { it.target.name })
        // SoundCloud serves one account: an extra one is not asked for.
        assertTrue(SessionKeepAlive.plan(listOf("soundcloud@2")).isEmpty())
    }

    @Test fun theRenewalWaitsForTheNetworkAndABatteryNotLow() {
        val c = Scheduler.keepAliveConstraints()
        assertEquals(NetworkType.CONNECTED, c.requiredNetworkType)
        assertTrue(c.requiresBatteryNotLow())
        assertFalse(c.requiresCharging())
        assertTrue(SessionKeepAlive.EVERY_HOURS in 12..24)
    }

    @Test fun anExpiryIsToldOnce() {
        assertFalse("a live session", SessionKeepAlive.shouldNotify(null, null))
        assertTrue("a new expiry", SessionKeepAlive.shouldNotify("1000", null))
        assertFalse("already told", SessionKeepAlive.shouldNotify("1000", "1000"))
        // Signed in again, then expired again: a new expiry, told again.
        assertTrue(SessionKeepAlive.shouldNotify("2000", "1000"))
    }

    @Test fun whatAKeepAliveAnswerSaysOfTheSession() {
        assertNull(SessionKeepAlive.expiryOf(null, false))
        assertEquals("1000", SessionKeepAlive.expiryOf("1000", false))
        assertEquals(SessionKeepAlive.GONE, SessionKeepAlive.expiryOf(null, true))
    }

    @Test fun theWordsOfASessionGoneForGood() {
        listOf(
            "YouTube Music rejected the session (401): sign out of YouTube Music and sign in again",
            "Spotify did not hand out a token: the Spotify web session has expired: sign in again",
            "the Deezer web session has expired: sign out of Deezer and sign in again",
            "the SoundCloud web session has expired: sign out of SoundCloud and sign in again",
            "the Qobuz session has ended: sign in again",
            "the Anghami session has ended: sign in again",
            "Amazon Music no longer accepts the session (401; again after renewing it; player ran). Accounts, Amazon Music: disconnect and connect again.",
            "the Amazon session no longer opens the player (it asked to sign in again). Sign out of Amazon Music and sign in again.",
            "Amazon Music served the player to a signed-out visitor: the Amazon session has expired.",
        ).forEach { assertTrue(it, SessionKeepAlive.sessionGone(it)) }
        listOf(
            null,
            "YouTube Music refused the signed-in request (403): too many requests. Trackhop will wait and retry; if it persists, sign out of YouTube Music and sign in again",
            "Spotify did not hand out a token in time (timed out): the web player was slow to load, trying again",
            "YouTube Music did not answer within 40s",
            "SoundCloud blocked these requests as unusual activity: wait a while",
        ).forEach { assertFalse(it.toString(), SessionKeepAlive.sessionGone(it)) }
    }

    private class Memory : SessionKeepAlive.Memory {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test fun eachConnectorIsToldOncePerExpiry() {
        val m = Memory()
        val told = ArrayList<String>()
        fun call(id: String, method: String, error: String? = null, expiredAt: String? = null) =
            SessionKeepAlive.afterCall(m, id, method, error, expiredAt) { told += id; true }
        val gone = "YouTube Music rejected the session (401): sign out of YouTube Music and sign in again"

        // A sync finds YouTube Music's session gone: told; the next calls of the run, and the next runs, are not.
        call("ytm", "tracks", gone)
        call("ytm", "add", gone)
        call("ytm", "playlists", gone)
        assertEquals(listOf("ytm"), told)
        // Its other account and another connector are told on their own.
        call("ytm@2", "tracks", gone)
        call("qobuz_web", "qobuz.tracks", "the Qobuz session has ended: sign in again")
        assertEquals(listOf("ytm", "ytm@2", "qobuz_web"), told)
        // A throttle, or an anonymous search that works, tells and ends nothing.
        call("ytm", "search")
        call("ytm", "tracks", "YouTube Music refused the signed-in request (403): too many requests")
        assertEquals(3, told.size)
        // Signed in again: the library answers, and the next expiry is told again.
        assertTrue(call("ytm", "playlists"))
        assertFalse("nothing was told any more", call("ytm", "playlists"))
        call("ytm", "tracks", gone)
        assertEquals(listOf("ytm", "ytm@2", "qobuz_web", "ytm"), told)
        // A sign-out (or a start over) ends it too.
        assertTrue(call("qobuz_web", "qobuz.disconnect"))
        call("qobuz_web", "qobuz.tracks", "the Qobuz session has ended: sign in again")
        assertEquals(5, told.size)
        assertTrue(call("ytm@2", "disconnect"))
    }

    @Test fun soundCloudIsToldOncePerExpiryItDates() {
        val m = Memory()
        val told = ArrayList<String>()
        val expired = "the SoundCloud web session has expired: sign out of SoundCloud and sign in again"
        fun call(expiredAt: String?) = SessionKeepAlive.afterCall(m, "soundcloud", "soundcloud.tracks", expired, expiredAt) { told += expiredAt.orEmpty(); true }
        call("1000")
        call("1000")
        assertEquals(listOf("1000"), told)
        // Signed in again, then expired again: a new expiry, told again.
        call("2000")
        assertEquals(listOf("1000", "2000"), told)
    }

    @Test fun aNotificationThatCouldNotBePostedIsTriedAgain() {
        val m = Memory()
        var posted = 0
        var allowed = false
        repeat(2) { SessionKeepAlive.tell(m, "deezer_bridge", SessionKeepAlive.GONE) { if (allowed) posted++; allowed } }
        assertEquals(0, posted)
        allowed = true
        repeat(2) { SessionKeepAlive.tell(m, "deezer_bridge", SessionKeepAlive.GONE) { if (allowed) posted++; allowed } }
        assertEquals(1, posted)
    }
}
