package com.xlollx.songport.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The How to search: every word typed, anywhere in a guide, case and accents aside. */
class HowToSearchTest {
    private val guides = listOf(
        "Your first sync\nConnect a service, choose, preview, run.",
        "Concerti\nLe date in città dei tuoi artisti.",
        "Paylaşılan çalma listeleri\nGrubun ortak listesi.",
        "共有プレイリスト\nグループで一つのプレイリスト。",
    )

    @Test fun aBlankQueryKeepsEveryGuideInOrder() {
        assertEquals(guides, HowToSearch.filter(guides, "", { it }))
        assertEquals(guides, HowToSearch.filter(guides, "   ", { it }))
    }

    @Test fun caseDoesNotCount() {
        assertEquals(listOf(guides[0]), HowToSearch.filter(guides, "FIRST", { it }))
    }

    @Test fun accentsDoNotCountEitherWay() {
        assertTrue(HowToSearch.matches("citta", guides[1]))
        assertTrue(HowToSearch.matches("CITTÀ", guides[1]))
        assertTrue(HowToSearch.matches("Città", "citta"))
    }

    @Test fun turkishLettersFoldToo() {
        assertTrue(HowToSearch.matches("PAYLAŞILAN", guides[2]))
        assertTrue(HowToSearch.matches("paylasilan calma", guides[2]))
        assertTrue(HowToSearch.matches("İSTANBUL", "istanbul"))
    }

    @Test fun everyWordMustBeThereInAnyOrder() {
        assertTrue(HowToSearch.matches("run preview", guides[0]))
        assertFalse(HowToSearch.matches("run concerts", guides[0]))
    }

    @Test fun theBodyCountsNotOnlyTheTitle() {
        assertEquals(listOf(guides[0]), HowToSearch.filter(guides, "connect", { it }))
    }

    @Test fun scriptsWithoutSpacesMatchAsTyped() {
        assertEquals(listOf(guides[3]), HowToSearch.filter(guides, "プレイリスト", { it }))
    }

    @Test fun nothingMatchesGivesAnEmptyList() {
        assertTrue(HowToSearch.filter(guides, "zzz", { it }).isEmpty())
    }
}
