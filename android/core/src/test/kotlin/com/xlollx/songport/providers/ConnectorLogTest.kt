package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/** The connectors' log lines: counts, and an account as its first letters only. */
class ConnectorLogTest {
    @Test fun anAccountShowsAsItsFirstFourLetters() {
        assertEquals("mari…", ConnectorLog.account("mariarossi"))
        assertEquals("Me", ConnectorLog.account("Me"))
        assertEquals("abcd", ConnectorLog.account("abcd"))
        // An e-mail used as a name: never its domain, never the whole name.
        assertEquals("mari…", ConnectorLog.account("maria.rossi@example.org"))
        assertEquals("ab", ConnectorLog.account("ab@example.org"))
        assertEquals("", ConnectorLog.account(null))
        assertEquals("", ConnectorLog.account("  "))
    }

    @Test fun aReadSaysWhatWasListedDeclaredAndUnreadable() {
        assertEquals("playlists: 12 listed, total 14, 2 unreadable (account mari…)", ConnectorLog.line("playlists", 12, 14, 2, "mariarossi"))
        // A service that declares no total, everything read, no account name.
        assertEquals("playlists: 7 listed", ConnectorLog.line("playlists", 7))
        assertEquals("playlists: 7 listed, 1 unreadable", ConnectorLog.line("playlists", 7, unreadable = 1))
        assertEquals("playlists: 0 listed, total 5, 0 unreadable", ConnectorLog.line("playlists", 0, total = 5))
    }
}
