package com.xlollx.songport.sync

import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.2: "Why wasn't it matched?" tells the matcher's score and its parts, deciding nothing. */
class MatchExplainTest {
    private val src = Track("s", "Rain on Me (Purple Disco Machine Remix)", listOf("Lady Gaga", "Ariana Grande"), album = "Dawn of Chromatica", durationMs = 232_000)

    @Test fun theScoreIsTheMatchersOwn() {
        val cand = Track("c", "Rain on Me", listOf("Lady Gaga", "Ariana Grande"), album = "Chromatica", durationMs = 182_000)
        val e = MatchExplain.explain(src, cand)
        assertEquals(Matcher.score(src, cand), e.score, 1e-9)
        assertEquals(0.70, e.threshold, 1e-9)
        assertTrue(e.reasons.contains(MatchExplain.Reason.LENGTH))
        assertTrue(e.reasons.contains(MatchExplain.Reason.VERSION))
        assertEquals(-50_000L, e.lengthDiffMs)
        assertEquals("-0:50", MatchExplain.signedLength(e.lengthDiffMs))
        assertFalse(e.matched)
    }

    @Test fun anotherArtistIsSaid() {
        val e = MatchExplain.explain(src, Track("c", "Rain On Me (Live)", listOf("Tribute Band"), durationMs = 230_000))
        assertTrue(e.reasons.contains(MatchExplain.Reason.OTHER_ARTIST))
    }

    @Test fun theSameIsrcSettlesIt() {
        val a = src.copy(isrc = "USUM72005901")
        val e = MatchExplain.explain(a, Track("c", "Rain on me - remix", listOf("Gaga"), isrc = "usum72005901"))
        assertEquals(listOf(MatchExplain.Reason.SAME_ISRC), e.reasons)
        assertTrue(e.matched)
    }

    @Test fun theBestFirstAndAStricterSyncNeedsMore() {
        val list = MatchExplain.explainAll(src, listOf(Track("a", "Other", listOf("X")), Track("b", "Rain on Me (Purple Disco Machine Remix)", listOf("Lady Gaga")), Track("b", "dup")), MatchPolicy(strictness = 1))
        assertEquals(listOf("b", "a"), list.map { it.track.id })
        assertEquals(0.78, list[0].threshold, 1e-9)
    }
}
