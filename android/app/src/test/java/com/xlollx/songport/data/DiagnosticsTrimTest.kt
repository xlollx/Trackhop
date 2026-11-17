package com.xlollx.songport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log on the phone stays bounded: so many lines in all, so many per area, the newest kept. */
class DiagnosticsTrimTest {
    private fun line(i: Int, tag: String) = "2026-11-17 10:00:%02d [$tag] line $i".format(i % 60)

    @Test fun aTalkativeAreaDoesNotPushTheOthersOut() {
        val lines = listOf(line(0, "spotify"), line(1, "concerts")) + (2 until 40).map { line(it, "engine") }
        val kept = Diagnostics.trim(lines, total = 20, perArea = 5)
        assertEquals(listOf(line(0, "spotify"), line(1, "concerts")) + (35 until 40).map { line(it, "engine") }, kept)
    }

    @Test fun neverMoreThanTheTotalAndTheNewestStay() {
        val lines = (0 until 30).map { line(it, "area${it % 10}") }
        val kept = Diagnostics.trim(lines, total = 12, perArea = 100)
        assertEquals(lines.takeLast(12), kept)
    }

    @Test fun aRepeatCountStaysWithItsLine() {
        val note = "2026-11-17 10:00:09 (the line above came 3 more times)"
        val lines = listOf(line(1, "engine"), note, line(2, "engine"), line(3, "engine"))
        // The oldest engine line is dropped, and its repeat count with it.
        assertEquals(listOf(line(2, "engine"), line(3, "engine")), Diagnostics.trim(lines, total = 10, perArea = 2))
        val kept = Diagnostics.trim(lines, total = 10, perArea = 3)
        assertEquals(lines, kept)
        assertTrue(Diagnostics.trim(emptyList(), 10, 2).isEmpty())
    }
}
