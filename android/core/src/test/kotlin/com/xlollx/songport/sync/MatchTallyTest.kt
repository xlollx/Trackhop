package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** One log line per run on how the tracks found their match: counts and reasons, never a title. */
class MatchTallyTest {
    private fun t(title: String, isrc: String? = null) = Track(id = title, title = title, artists = listOf("Secret Artist"), isrc = isrc)

    @Test fun nothingLookedForSaysNothing() = assertNull(MatchTally().line())

    @Test fun countsByHowTheMatchWasMadeAndWhyNot() {
        val m = MatchTally()
        repeat(3) { m.saved() }
        m.searched(t("a", "USRC17607839"), t("a2", "usrc17607839 "))  // the same code on both sides
        m.searched(t("b", "USRC17607839"), t("b2"))                   // found, the service gave no code back
        m.searched(t("c"), t("c2", "GBAYE0601498"))                   // the source had no code
        m.searched(t("d"), null)
        repeat(2) { m.searchedBefore() }
        m.failed()
        assertEquals(10, m.total)
        assertEquals(
            "matching of 10: 3 by saved match, 1 by ISRC, 2 by title and duration; 4 not found (2 searched before without a match, 1 nothing fitting found, 1 search failed)",
            m.line(),
        )
        assertFalse("no title or artist in the line", m.line()!!.contains("Secret"))
    }

    @Test fun sameAccountAndEverythingFound() {
        val m = MatchTally()
        repeat(5) { m.direct() }
        assertEquals("matching of 5: 5 taken as they are", m.line())
    }

    @Test fun nothingMatched() {
        val m = MatchTally()
        m.searched(t("a"), null)
        assertEquals("matching of 1: none matched; 1 not found (1 nothing fitting found)", m.line())
    }
}
