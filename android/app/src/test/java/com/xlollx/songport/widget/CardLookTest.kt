package com.xlollx.songport.widget

import com.xlollx.songport.ui.CardAccent
import com.xlollx.songport.ui.JobColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A widget wears its sync's card: the card's colour for light and dark, and the card's style. */
class CardLookTest {
    @Test fun aSyncWithNoColourKeepsThePhonesColours() {
        assertNull(CardLook.of(null, null, "classic"))
        assertNull(CardLook.of("no-such-colour", "pill", "classic"))
    }

    @Test fun aReadyColourShowsAsOnTheCardInLightAndDark() {
        val look = CardLook.of("teal", null, "classic")!!
        assertEquals(JobColor.TEAL.light, look.light)
        assertEquals(JobColor.TEAL.dark, look.dark)
        // The card's own tint: 13% of the colour in light mode, 20% in dark.
        assertEquals(0.13f, look.tintLight.alpha, 0.01f)
        assertEquals(0.20f, look.tintDark.alpha, 0.01f)
        // The controls' ink is deeper than the colour in light mode, the colour itself in dark.
        assertTrue(look.inkLight.green < look.light.green)
        assertEquals(look.dark, look.inkDark)
    }

    @Test fun aColourOfOneSOwnShowsToo() {
        val look = CardLook.of("#000460", null, null)
        assertNotNull(look)
        assertTrue("still blue", look!!.light.blue > look.light.red + 0.2f)
    }

    @Test fun theStyleIsTheSyncsOwnElseTheOneInSettings() {
        assertEquals(CardAccent.PILL, CardLook.of("red", "pill", "outline")!!.style)
        assertEquals(CardAccent.OUTLINE, CardLook.of("red", null, "outline")!!.style)
        assertEquals(CardAccent.CLASSIC, CardLook.of("red", null, null)!!.style)
    }

    @Test fun eachStyleHasItsShapeOnAWidget() {
        fun look(style: String) = CardLook.of("red", style, null)!!
        // A band as tall as the card, along its edge.
        for (s in listOf("classic", "fade", "crescent")) look(s).mark!!.let { assertFalse(s, it.short); assertEquals(s, 0f, it.insetDp); assertTrue(s, it.widthDp in 3f..6f) }
        // The pill: a short bar, in from the edge.
        look("pill").mark!!.let { assertTrue(it.short); assertTrue(it.insetDp > 0f) }
        // The outline goes all round; the wash is the tint alone.
        assertNull(look("outline").mark); assertTrue(look("outline").outlined)
        assertNull(look("wash").mark); assertFalse(look("wash").outlined)
    }
}
