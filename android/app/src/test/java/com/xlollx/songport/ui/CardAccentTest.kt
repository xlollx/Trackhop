package com.xlollx.songport.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** How a sync's colour shows on a tile of the minimal view. */
class CardAccentTest {
    @Test fun aTileWearsTheEdgeStylesAsThePlainBand() {
        for (a in listOf(CardAccent.CLASSIC, CardAccent.FADE, CardAccent.CRESCENT, CardAccent.PILL)) assertEquals(a.name, CardAccent.CLASSIC, a.onTile)
    }

    @Test fun theOutlineAndTheWashFitATileAsTheyAre() {
        assertEquals(CardAccent.OUTLINE, CardAccent.OUTLINE.onTile)
        assertEquals(CardAccent.WASH, CardAccent.WASH.onTile)
    }
}
