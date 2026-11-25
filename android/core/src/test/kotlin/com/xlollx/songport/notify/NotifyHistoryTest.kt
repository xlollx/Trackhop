package com.xlollx.songport.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The bell's history (and the server's Notifications page): kept, read, cleared, capped, on disk. */
class NotifyHistoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var clock = 1_000_000_000_000L
    private fun history() = NotifyHistory(java.io.File(tmp.root, NotifyHistory.FILE)) { clock }

    @Test fun keptNewestFirstReadAndCleared() {
        val h = history()
        val first = h.add("unplayable", "Tracks no longer playable", "40 tracks", NotifTarget.Unplayable("spotify"))
        clock += 1000
        h.add("sync", "Liked songs", "+4", NotifTarget.Sync("a"))
        h.settle()
        assertEquals(listOf("Liked songs", "Tracks no longer playable"), h.entries.value.map { it.title })
        assertEquals(2, NotifyHistory.unread(h.entries.value))
        h.markRead(first); h.settle()
        assertEquals(1, NotifyHistory.unread(h.entries.value))
        assertEquals(NotifTarget.Unplayable("spotify"), NotifTarget.parse(h.entries.value.last().target))
        h.markAllRead(); h.settle()
        assertEquals(0, NotifyHistory.unread(h.entries.value))
        h.clear(); h.settle()
        assertTrue(h.entries.value.isEmpty())
    }

    @Test fun whatWasKeptIsThereAfterARestart() {
        val h = history()
        h.add("signin", "SoundCloud: sign in again", target = NotifTarget.Account("soundcloud"), sent = listOf(NotifyHistory.Sent("Telegram"), NotifyHistory.Sent("Discord", "HTTP 401")))
        h.settle()
        val again = history()
        again.settle()
        val e = again.entries.value.single()
        assertEquals("SoundCloud: sign in again", e.title)
        assertEquals(listOf(NotifyHistory.Sent("Telegram"), NotifyHistory.Sent("Discord", "HTTP 401")), e.sent)
        assertEquals(NotifTarget.Account("soundcloud"), NotifTarget.parse(e.target))
    }

    @Test fun aDamagedFileStartsEmpty() {
        java.io.File(tmp.root, NotifyHistory.FILE).writeText("{not json")
        val h = history(); h.settle()
        assertTrue(h.entries.value.isEmpty())
        h.add("notice", "Backup folder gone"); h.settle()
        assertEquals(1, history().also { it.settle() }.entries.value.size)
    }

    @Test fun thirtyDaysAndTwoHundredAtMost() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val l = (0 until 250).map { NotifyHistory.Entry("e$it", now - it * 60_000L, "sync", "Run $it") } +
            NotifyHistory.Entry("old", now - 31 * day, "sync", "Old")
        val kept = NotifyHistory.prune(l, now)
        assertEquals(NotifyHistory.MAX, kept.size)
        assertEquals("e0", kept.first().id)
        assertTrue(kept.none { it.id == "old" })
        assertEquals(listOf("b", "a"), NotifyHistory.prune(listOf(NotifyHistory.Entry("a", now - 2, "x", "A"), NotifyHistory.Entry("b", now - 1, "x", "B")), now).map { it.id })
    }

    @Test fun theBellsCornerShowsTheUpdateBeforeTheCount() {
        assertNull(NotifyHistory.badge(0, update = false))
        assertEquals("3", NotifyHistory.badge(3, update = false))
        assertEquals("99+", NotifyHistory.badge(120, update = false))
        assertEquals("↑", NotifyHistory.badge(3, update = true))
        assertEquals("↑", NotifyHistory.badge(0, update = true))
    }

    @Test fun aRepeatedNoticeIsKeptOnceWhileUnread() {
        val h = history(); h.settle()
        val t = NotifTarget.Account("spotify")
        val a = h.addOnce("signin", "Spotify: sign in again", "", t)
        repeat(20) { h.addOnce("signin", "Spotify: sign in again", "", t) }
        h.addOnce("signin", "TIDAL: sign in again", "", NotifTarget.Account("tidal"))
        h.settle()
        assertEquals(2, h.entries.value.size)
        assertEquals(a, h.addOnce("signin", "Spotify: sign in again", "", t))
        // Once read, a new one is a new notice.
        h.markAllRead(); h.settle()
        h.addOnce("signin", "Spotify: sign in again", "", t); h.settle()
        assertEquals(3, h.entries.value.size)
    }
}
