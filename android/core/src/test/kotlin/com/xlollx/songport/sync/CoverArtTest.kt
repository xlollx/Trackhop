package com.xlollx.songport.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverArtTest {
    /** A monospaced stand-in for a font: each character is 0.6 of the size wide. */
    private val mono: (String, Float) -> Float = { s, size -> s.length * size * 0.6f }

    @Test
    fun seedIsStableAndVaries() {
        assertEquals(CoverArt.seed("Road Trip"), CoverArt.seed("  road trip "))
        assertNotEquals(CoverArt.seed("Road Trip"), CoverArt.seed("Road Trip", 1))
        assertNotEquals(CoverArt.seed("Road Trip"), CoverArt.seed("Road Trap"))
        // Fixed across runs and devices: FNV-1a of the name.
        assertEquals(-0x340d631b7bdddcdbL, CoverArt.seed(""))
    }

    @Test
    fun namePaletteIsDeterministicAndWhiteReadsOnItsBackground() {
        assertArrayEquals(CoverArt.namePalette("Chill"), CoverArt.namePalette("Chill"))
        assertFalse(CoverArt.namePalette("Chill").contentEquals(CoverArt.namePalette("Chill", 1)))
        for (name in listOf("Chill", "Metal", "", "日本の歌", "a".repeat(200))) {
            val p = CoverArt.namePalette(name)
            assertEquals(5, p.size)
            assertTrue("$name: white on the background", CoverArt.contrast(CoverArt.WHITE, p[0]) >= 4.5)
        }
    }

    @Test
    fun dominantFindsTheColourfulPartFirst() {
        val white = CoverArt.argb(250, 250, 250)
        val red = CoverArt.argb(220, 20, 30)
        // Mostly white with a red logo: red counts more for being colourful, white still comes.
        val px = IntArray(1000) { if (it < 450) red else white }
        val d = CoverArt.dominant(px, 3)
        assertEquals(2, d.size)
        assertTrue(CoverArt.red(d[0]) > 200 && CoverArt.green(d[0]) < 40)
        // Transparent pixels are ignored; nothing at all gives nothing.
        assertEquals(0, CoverArt.dominant(IntArray(10) { 0x00FF0000 }).size)
        assertEquals(0, CoverArt.dominant(IntArray(0)).size)
    }

    @Test
    fun paletteFromCoversHasADeepBaseAndFallsBackToTheName() {
        val sampled = intArrayOf(CoverArt.argb(240, 230, 120), CoverArt.argb(30, 90, 200))
        val p = CoverArt.palette(sampled, "x")
        assertEquals(5, p.size)
        assertTrue(CoverArt.contrast(CoverArt.WHITE, p[0]) >= 4.5)
        assertArrayEquals(CoverArt.namePalette("x"), CoverArt.palette(IntArray(0), "x"))
        assertFalse(p.contentEquals(CoverArt.palette(sampled, "x", 1)))
    }

    @Test
    fun textColourIsTheOneThatReads() {
        assertEquals(CoverArt.INK, CoverArt.textOn(CoverArt.argb(255, 230, 80)))
        assertEquals(CoverArt.WHITE, CoverArt.textOn(CoverArt.argb(20, 20, 60)))
        assertEquals(21.0, CoverArt.contrast(CoverArt.WHITE, CoverArt.argb(0, 0, 0)), 0.01)
    }

    @Test
    fun hslRoundTrips() {
        val c = CoverArt.hsl(210f, 0.6f, 0.4f)
        val back = CoverArt.toHsl(c)
        assertEquals(210f, back[0], 2f); assertEquals(0.6f, back[1], 0.02f); assertEquals(0.4f, back[2], 0.02f)
    }

    @Test
    fun aShortTitleIsBigOnOneLine() {
        val f = CoverArt.fitTitle("Chill", 900f, 400f, 3, 200f, 40f, measure = mono)
        assertEquals(listOf("Chill"), f.lines)
        assertEquals(200f, f.size, 0.01f)
        assertFalse(f.cut)
    }

    @Test
    fun aLongTitleWrapsSmallerAndStaysInsideTheBox() {
        val title = "Songs to listen to on a very long night drive across the country"
        val f = CoverArt.fitTitle(title, 900f, 400f, 3, 200f, 40f, measure = mono)
        assertTrue(f.lines.size in 2..3)
        assertFalse(f.cut)
        assertTrue(f.lines.all { mono(it, f.size) <= 900f })
        assertTrue(f.lines.size * f.size * 1.08f <= 400f)
        assertEquals(title, f.lines.joinToString(" "))
    }

    @Test
    fun aTitleTooLongForTheBoxEndsInAnEllipsisNeverOutside() {
        val title = (1..60).joinToString(" ") { "word$it" }
        val f = CoverArt.fitTitle(title, 600f, 200f, 3, 120f, 40f, measure = mono)
        assertTrue(f.cut)
        assertTrue(f.lines.size <= 3)
        assertTrue(f.lines.last().endsWith("…"))
        assertTrue(f.lines.all { mono(it, f.size) <= 600f })
        assertTrue(f.lines.size * f.size * 1.08f <= 200f + 0.01f)
    }

    @Test
    fun aWordWiderThanTheLineIsSplit() {
        val lines = CoverArt.wrap("Supercalifragilisticexpialidocious yes", 10 * 6f) { it.length * 6f }
        assertTrue(lines.all { it.length <= 10 })
        assertEquals("Supercalifragilisticexpialidociousyes", lines.joinToString("").replace(" ", ""))
    }

    // ------------------------------------------------------------------ line breaks

    @Test
    fun titlesBreakAtSpacesOrAfterSeparatorsNeverInsideAWord() {
        val title = "Dark Trap/Phonk/Aesthetic rap"
        // Narrow: "AESTHETIC" must never be split across lines.
        val f = CoverArt.fitTitle(title, 12 * 0.6f * 100f, 1000f, 3, 100f, 60f, measure = mono)
        assertFalse(f.cut)
        assertTrue(f.lines.toString(), f.lines.any { "Aesthetic" in it })
        assertTrue(f.lines.all { mono(it, f.size) <= 12 * 0.6f * 100f + 0.01f })
        // Joined back (a break after "/" drops no space), the title is whole.
        assertEquals(title, f.lines.reduce { a, b -> if (a.endsWith("/")) a + b else "$a $b" })
        assertEquals(listOf("Dark", "Trap/", "Phonk/", "Aesthetic", "rap"), CoverArt.atoms(title).map { it.text })
        assertEquals(listOf(false, true, false, false, true), CoverArt.atoms(title).map { it.space })
        assertEquals(listOf("Rock", "&", "Roll", "Hip-", "Hop", "+", "Jazz"), CoverArt.atoms("Rock & Roll Hip-Hop + Jazz").map { it.text })
    }

    @Test
    fun aVeryLongSingleWordShrinksTheTitleBeforeItIsSplit() {
        val word = "Supercalifragilistic" // 20 characters
        // At the smallest size (40) it is 480 wide; the box is 400: the title goes under 40, whole.
        val f = CoverArt.fitTitle(word, 400f, 400f, 3, 100f, 40f, measure = mono)
        assertEquals(listOf(word), f.lines)
        assertTrue(f.size < 40f && f.size >= 40f * CoverArt.WORD_FLOOR)
        assertFalse(f.cut)
        // Too long even at the floor: only then split, inside the box.
        val g = CoverArt.fitTitle(word.repeat(3), 400f, 400f, 3, 100f, 40f, measure = mono)
        assertTrue(g.lines.all { mono(it, g.size) <= 400.01f })
    }

    @Test
    fun cjkBreaksBetweenCharacters() {
        val title = "夜のドライブのための音楽"
        assertEquals(title.length, CoverArt.atoms(title).size)
        val lines = CoverArt.wrap(title, 5f) { it.length.toFloat() }
        assertEquals(listOf("夜のドライ", "ブのための", "音楽"), lines)
        assertEquals(title, lines.joinToString(""))
    }

    @Test
    fun aTwoLineTitleIsBalanced() {
        // Greedy would leave "sempre" alone on the second line; balanced, the lines are even.
        val lines = CoverArt.balance("Brani preferiti di sempre", 2, 20f) { it.length.toFloat() }
        assertEquals(listOf("Brani preferiti", "di sempre"), lines)
    }

    @Test
    fun aCutTitleEndsAfterAWholeWord() {
        assertEquals("Songs to…", CoverArt.cutAtWord("Songs to listen to", 9f) { it.length.toFloat() })
        assertEquals("Songs to listen to", CoverArt.cutAtWord("Songs to listen to", 40f) { it.length.toFloat() })
        assertEquals("", CoverArt.cutAtWord("Supercalifragilistic", 5f) { it.length.toFloat() })
        assertEquals("Supe…", CoverArt.cutAtWord("Supercalifragilistic", 5f, force = true) { it.length.toFloat() })
        assertEquals("Trap…", CoverArt.cutAtWord("Trap/Phonk", 7f) { it.length.toFloat() })
    }

    // ------------------------------------------------------------------ names

    private val chars: (String, Float) -> Float = { s, scale -> s.length * scale }

    @Test
    fun namesAreWholeThenPlusN() {
        val names = listOf("A Day To Remember", "Bring Me The Horizon", "Nothing But Thieves", "Muse", "Placebo")
        // 17 + 5 + 20 = 42; with "  ·  +3" it is 49.
        val fit = CoverArt.fitNames(names, 50f, measure = chars)
        assertEquals("A Day To Remember  ·  Bring Me The Horizon  ·  +3", fit.text)
        assertEquals(3, fit.more)
        assertEquals(1f, fit.scale)
        // All fit: no "+N".
        assertEquals("Muse  ·  Placebo", CoverArt.fitNames(listOf("Muse", "Placebo"), 50f, measure = chars).text)
        // Never a name cut: room for one and a half names gives one and "+4".
        assertEquals("A Day To Remember  ·  +4", CoverArt.fitNames(names, 30f, measure = chars).text)
    }

    @Test
    fun aTooLongSingleNameIsSetSmallerThenLeftOut() {
        val long = "X".repeat(44)
        // 44 wide in 40: fits at 0.9 (39.6).
        val smaller = CoverArt.fitNames(listOf(long), 40f, measure = chars)
        assertEquals(long, smaller.text)
        assertEquals(0.9f, smaller.scale, 0.001f)
        // 60 wide in 40: not even at 0.85: left out, the next name shown with "+1".
        val dropped = CoverArt.fitNames(listOf("Y".repeat(60), "Muse"), 40f, measure = chars)
        assertEquals("Muse  ·  +1", dropped.text)
        assertEquals("", CoverArt.fitNames(listOf("Y".repeat(60)), 40f, measure = chars).text)
    }

    @Test
    fun noNamesNoLine() {
        val fit = CoverArt.fitNames(emptyList(), 100f, measure = chars)
        assertEquals("", fit.text)
        assertTrue(fit.shown.isEmpty())
        assertTrue(CoverArt.stackNames(emptyList(), 5, 100f, 10f, { 1f }) { s, _, k -> s.length * k }.isEmpty())
    }

    @Test
    fun aStackOfNamesEndsWithPlusN() {
        val names = listOf("Metallica", "Ghost", "Muse", "Tool", "Korn", "Slayer")
        // Four rows of room: three names and how many are not shown.
        val rows = CoverArt.stackNames(names, 5, 20f, 4f, { 1f }) { s, _, k -> s.length * k }
        assertEquals(listOf("Metallica", "Ghost", "Muse", "+3"), rows.map { it.text })
        assertTrue(rows.last().isMore)
        assertEquals(listOf("Ghost", "Muse"), CoverArt.stackNames(listOf("Ghost", "Muse"), 5, 20f, 4f, { 1f }) { s, _, k -> s.length * k }.map { it.text })
    }

    @Test
    fun aLongNameInAStackGoesOnTwoRowsNeverCut() {
        // "A Day To Remember" (17) in 12: not even at 0.85; on two rows, between words.
        val rows = CoverArt.stackNames(listOf("A Day To Remember", "Muse", "Tool"), 5, 12f, 10f, { 1f }) { s, _, k -> s.length * k }
        assertEquals(listOf("A Day To", "Remember", "Muse", "Tool"), rows.map { it.text })
        assertEquals(listOf(0, 0, 1, 2), rows.map { it.name })
        // A single word wider than the column even so: left out, and said.
        val skipped = CoverArt.stackNames(listOf("Supercalifragilistic", "Muse"), 5, 12f, 10f, { 1f }) { s, _, k -> s.length * k }
        assertEquals(listOf("Muse", "+1"), skipped.map { it.text })
    }

    @Test
    fun namesOverTwoLinesThenPlusN() {
        val names = listOf("A Day To Remember", "Bring Me The Horizon", "Nothing But Thieves", "Muse", "Placebo")
        val lines = CoverArt.fitNameLines(names, 2, 25f, measure = chars)
        assertEquals(listOf("A Day To Remember", "Bring Me The Horizon  ·  +3"), lines.map { it.text })
        assertEquals(listOf("Muse  ·  Placebo"), CoverArt.fitNameLines(listOf("Muse", "Placebo"), 2, 25f, measure = chars).map { it.text })
        assertTrue(CoverArt.fitNameLines(emptyList(), 2, 25f, measure = chars).isEmpty())
    }

    // ------------------------------------------------------------ the poster's header

    /** Ten units a character, times the scale. */
    private val tenEach = { str: String, k: Float -> str.length * 10f * k }
    private fun head(name: String, width: Float) = CoverArt.fitHead(name, "SPOTIFY", "2220 TRACKS", "140 H 12 MIN", width, 40f, measure = tenEach)

    @Test fun theHeaderKeepsEveryPartWholeOnOneLineWhenAllFit() {
        assertEquals(listOf(CoverArt.HeadLine("GIULIA MARCHETTI  ·  SPOTIFY", "2220 TRACKS  ·  140 H 12 MIN")), head("GIULIA MARCHETTI", 600f))
    }

    @Test fun theDurationGivesWayFirstNeverCutWithAnEllipsis() {
        // A real poster, 12/11: "GIULIA MARCHETTI · SPO… 2220 TRACKS · 140 H 1…".
        assertEquals(listOf(CoverArt.HeadLine("GIULIA MARCHETTI  ·  SPOTIFY", "2220 TRACKS")), head("GIULIA MARCHETTI", 590f))
    }

    @Test fun aNameThatLeavesNoRoomTakesALineOfItsOwn() {
        assertEquals(
            listOf(CoverArt.HeadLine("GIULIA MARCHETTI", ""), CoverArt.HeadLine("SPOTIFY", "2220 TRACKS")),
            head("GIULIA MARCHETTI", 300f),
        )
    }

    @Test fun aNameTooLongForALineIsSetSmallerOrBrokenBetweenWords() {
        val name = "A VERY LONG NAME FOR A POSTER"
        val lines = head(name, 200f)
        val nameLines = lines.dropLast(1)
        assertEquals(name, nameLines.joinToString(" ") { it.left })
        for (l in lines) {
            assertFalse(l.left + l.right, (l.left + l.right).contains('…'))
            val w = tenEach(l.left, l.scale) + (if (l.right.isBlank() || l.left.isBlank()) 0f else 40f) + tenEach(l.right, l.scale)
            assertTrue("$l is $w wide", w <= 200f)
        }
        assertEquals("2220 TRACKS", lines.last().right)
    }
}
