package com.xlollx.songport.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One pace for all Shopify shops: spacing, back-off after a 429, Retry-After, shops cooling down; on a fake clock. */
class ShopPacerTest {
    private var clock = 1_000_000L
    private val slept = ArrayList<Long>()
    private fun pacer(gap: Long = 3_000, backoff: Long = 5_000) =
        ShopPacer(gap, backoff, now = { clock }, sleep = { slept += it; clock += it })

    @Test fun callsToAnyShopAreSpacedApart() = runBlocking {
        val p = pacer()
        assertEquals("a", p.paced("a.com") { "a" })
        assertEquals("b", p.paced("b.com") { "b" })
        clock += 1_000
        p.paced("c.com") { "c" }
        // Different shops, one limit: the second waits the whole gap, the third what is left of it.
        assertEquals(listOf(3_000L, 2_000L), slept)
    }

    @Test fun a429BacksEveryShopOffAndDoublesUntilAnAnswer() = runBlocking {
        val p = pacer(gap = 0)
        assertFalse(p.tooMany("a.com", null, mayRetry = true))
        assertEquals(clock + 5_000, p.freeAt("b.com"))
        p.paced("b.com") { }
        assertEquals(listOf(5_000L), slept)
        assertFalse(p.tooMany("b.com", null, mayRetry = false))
        assertEquals(clock + 10_000, p.freeAt("c.com"))
        p.paced("c.com") { }
        assertEquals(listOf(5_000L, 10_000L), slept)
        p.answered()
        assertEquals(0L, p.freeAt("c.com"))
        p.paced("c.com") { }
        assertEquals(2, slept.size)
    }

    @Test fun aShortRetryAfterIsWaitedOnceALongOneCoolsTheShop() = runBlocking {
        val p = pacer(gap = 0, backoff = 1_000)
        assertTrue(p.tooMany("a.com", 4_000, mayRetry = true))
        p.paced("a.com") { }
        assertEquals(listOf(4_000L), slept)
        // Refused again: no third try.
        assertFalse(p.tooMany("a.com", 4_000, mayRetry = false))
        assertNull(p.paced("a.com") { "asked" })
        // A long wait asked: that shop is not asked until then, the others after the back-off.
        assertFalse(p.tooMany("b.com", 600_000, mayRetry = true))
        assertEquals(clock + 600_000, p.freeAt("b.com"))
        assertNull(p.paced("b.com") { "asked" })
        clock += 600_001
        p.answered()
        assertEquals("asked", p.paced("b.com") { "asked" })
    }

    @Test fun aTurnTooFarAwayIsNotWaitedFor() = runBlocking {
        val p = pacer(gap = 0, backoff = 20_000)
        p.tooMany("a.com", null, mayRetry = false)
        p.tooMany("b.com", null, mayRetry = false)
        // 40 s of back-off: "busy", rather than a spinner for minutes or another request.
        assertNull(p.paced("c.com") { "asked" })
        assertTrue(slept.isEmpty())
        clock += 40_000
        assertEquals("asked", p.paced("c.com") { "asked" })
    }

    @Test fun retryAfterInSecondsOrAsADate() {
        val now = java.time.Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()
        assertEquals(30_000L, ShopPacer.retryAfterMs(" 30 ", now))
        assertEquals(0L, ShopPacer.retryAfterMs("0", now))
        assertEquals(120_000L, ShopPacer.retryAfterMs("Wed, 11 Nov 2026 10:02:00 GMT", now))
        assertEquals(0L, ShopPacer.retryAfterMs("Wed, 11 Nov 2026 09:00:00 GMT", now))
        assertNull(ShopPacer.retryAfterMs(null, now))
        assertNull(ShopPacer.retryAfterMs("", now))
        assertNull(ShopPacer.retryAfterMs("soon", now))
        assertNull(ShopPacer.retryAfterMs("-5", now))
    }
}
