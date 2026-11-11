package com.xlollx.songport.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Paces the calls to a family of shops that share one limit per client, not per shop: every
 * Shopify shop sits behind the same edge, which counts all of a phone's requests to any of them
 * and answers a run of them with 429 for every shop at once (November 2026: a page of 50 artists).
 *
 * Calls go one at a time, at least [gapMs] apart. A 429 backs every later call off ([backoffMs],
 * doubling with each 429 in a row, back to normal after an answer); a Retry-After of up to
 * [SHORT_RETRY_MS] may be waited out once, a longer one (or none) cools that shop down for as long
 * as it asks ([COOL_MS] when it does not say). A call whose turn is further than [MAX_WAIT_MS] away,
 * or to a shop cooling down, is not made at all: the shop is "busy", not asked again and again.
 */
class ShopPacer(
    private val gapMs: Long,
    private val backoffMs: Long = gapMs * 2,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val turn = Mutex()
    private var next = 0L
    private var strikes = 0
    private val cooling = HashMap<String, Long>()

    /** When [host] may be asked again, or 0 when it may be now: its cool-down, or the back-off after a 429. */
    @Synchronized fun freeAt(host: String): Long {
        val n = now()
        val until = maxOf(cooling[host] ?: 0L, if (strikes > 0) next else 0L)
        return if (until > n) until else 0L
    }

    /** Runs [block] in its turn; null without running it when [host] cools down or the turn is too far. */
    suspend fun <T> paced(host: String, block: suspend () -> T): T? {
        turn.withLock {
            val wait = synchronized(this) {
                if ((cooling[host] ?: 0L) > now()) return null
                next - now()
            }
            if (wait > MAX_WAIT_MS) return null
            if (wait > 0) sleep(wait)
            synchronized(this) { next = maxOf(now(), next) + gapMs }
        }
        return block()
    }

    /**
     * [host] answered 429, asking to wait [retryAfterMs] (null: it did not say). Every later call
     * backs off. True when the call may be made once more ([mayRetry], a short wait: its turn waits
     * it out); otherwise [host] cools down.
     */
    @Synchronized fun tooMany(host: String, retryAfterMs: Long?, mayRetry: Boolean): Boolean {
        val n = now()
        strikes++
        val back = minOf(backoffMs shl minOf(strikes - 1, 16), MAX_BACKOFF_MS)
        next = maxOf(next, n + back, n + (retryAfterMs ?: 0L))
        if (mayRetry && retryAfterMs != null && retryAfterMs <= SHORT_RETRY_MS) return true
        cooling[host] = n + maxOf(retryAfterMs ?: COOL_MS, back)
        return false
    }

    /** An answer that was not a 429: the back-off is over. */
    @Synchronized fun answered() { strikes = 0 }

    companion object {
        const val SHORT_RETRY_MS = 30_000L
        const val MAX_WAIT_MS = 30_000L
        const val MAX_BACKOFF_MS = 10 * 60_000L
        const val COOL_MS = 10 * 60_000L

        /** A Retry-After header as milliseconds from [now]: delay-seconds or an HTTP date; null when absent or unreadable. */
        fun retryAfterMs(value: String?, now: Long): Long? {
            val v = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            v.toLongOrNull()?.let { return if (it < 0) null else it * 1000 }
            val at = runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() ?: return null
            return maxOf(0L, at - now)
        }
    }
}
