package com.xlollx.songport.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The service row's tile width: a cut last tile whenever the row scrolls, none when all fit. */
class TileLayoutTest {
    private val gap = 4f

    @Test fun allFitKeepTheUsualWidthAndNoPeek() {
        val l = tileLayout(360.dp, 3)
        assertEquals(76f, l.width.value, 0.01f)
        assertEquals(3f, l.inView, 0.01f)
    }

    @Test fun moreThanFitLeaveAThirdOfTheNextTileShowing() {
        for (w in 300..480 step 4) for (n in 4..9) {
            val l = tileLayout(w.dp, n)
            if (n * 76f + (n - 1) * gap <= w) continue
            val tile = l.width.value
            assertTrue("$w dp, $n tiles: width $tile", tile in 64f..92f)
            // What shows of the first tile past the whole ones.
            val whole = kotlin.math.floor(l.inView).toInt()
            val peek = (w - whole * (tile + gap)) / tile
            assertTrue("$w dp, $n tiles: peek $peek", peek in 0.25f..0.45f)
        }
    }

    @Test fun aPhoneShowsFourTilesAndAPeek() {
        val l = tileLayout(344.dp, 5)
        assertEquals(4.35f, l.inView, 0.01f)
    }
}
