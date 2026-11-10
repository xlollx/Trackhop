package com.xlollx.songport.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a sync's colour shows on a tile of the minimal view. */
class CardAccentTest {
    private val edge = listOf(CardAccent.CLASSIC, CardAccent.FADE, CardAccent.CRESCENT, CardAccent.PILL)

    @Test fun eachEdgeStyleKeepsItsOwnShapeOnATile() {
        val shapes = edge.map { assertNotNull(it.name, TileAccent.of(it)); TileAccent.of(it) }
        assertEquals("every style looks different", edge.size, shapes.toSet().size)
        assertTrue("the crescent thins towards the corners", TileAccent.of(CardAccent.CRESCENT)!!.tapers)
        assertTrue("the fade fades", TileAccent.of(CardAccent.FADE)!!.fades)
        assertTrue("the pill is short and off the edge", TileAccent.of(CardAccent.PILL)!!.let { it.length < 1f && it.inset > 0.dp })
        assertTrue("the band is plain", TileAccent.of(CardAccent.CLASSIC)!!.let { !it.tapers && !it.fades && it.length == 1f })
    }

    @Test fun noneReachesTheText() {
        for (s in edge) TileAccent.of(s)!!.let { assertTrue("${s.name}: ${it.inset + it.width}", it.inset + it.width <= TileAccent.TILE_CLEAR) }
        // The clearance is at most half the tile's 16 dp text padding: at least 8 dp of air before the text.
        assertTrue(16.dp - TileAccent.TILE_CLEAR >= 8.dp)
    }

    @Test fun theOutlineAndTheWashAreWornAsOnTheCard() {
        assertNull(TileAccent.of(CardAccent.OUTLINE))
        assertNull(TileAccent.of(CardAccent.WASH))
    }
}
