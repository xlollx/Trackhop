package com.xlollx.songport.ui

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Which connectors are signed in, read once off the main thread and shared by the screens that only
 * need to know it (the sync editors above all). Asking a web connector costs a call to the connectors
 * (another app on the Play build) and an OAuth one a read of the encrypted store: done for every
 * service each time "One target" ↔ "Several targets" swapped the editors, it froze the screen.
 *
 * Kept up to date: read again when the app comes back to the front, dropped when it leaves (a sign-in
 * happens in another screen or app), one service dropped whenever its sign-in is written or taken
 * away. With no fresh answer for a service, it is asked as before ([isConnected] falls back to it).
 */
object Connections {
    private class Snap(val at: Long, val connected: Map<String, Boolean>)

    /** How long an answer is trusted: a sign-in can also run out by itself, while the app is open. */
    const val MAX_AGE_MS = 60_000L

    @Volatile private var snap: Snap? = null
    // Bumped by every invalidation: a reading started before it is not kept.
    private val generation = AtomicLong()
    private val reading = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The clock, in milliseconds; tests move it. */
    @Volatile internal var now: () -> Long = { System.nanoTime() / 1_000_000 }

    /** The fresh answers kept, by connector id; empty when there is none. */
    internal fun known(): Map<String, Boolean> = snap?.takeIf { now() - it.at <= MAX_AGE_MS }?.connected.orEmpty()

    /** Whether [p] is signed in: the answer kept, else asked now (as every screen did before). */
    fun isConnected(env: Env, p: MusicProvider): Boolean = known()[p.id] ?: p.isConnected(env)

    /** The connectors of [among] that are signed in, in their order: one lookup of the answers kept for all of them. */
    fun connected(env: Env, among: List<MusicProvider> = Providers.connectors()): List<MusicProvider> {
        val k = known()
        return among.filter { k[it.id] ?: it.isConnected(env) }
    }

    /** Forgets every answer: the next reads ask again until [refresh] has run. */
    fun invalidate() {
        generation.incrementAndGet()
        snap = null
    }

    /** Forgets the answer about [id] alone (its sign-in was just written or taken away). */
    fun invalidate(id: String) {
        generation.incrementAndGet()
        snap?.let { s -> snap = Snap(s.at, s.connected - id) }
    }

    /**
     * Asks every connector of [among] now, on the calling thread, and keeps the answers unless an
     * invalidation came meanwhile. Gives back the time each took, in milliseconds (for the log).
     */
    fun refresh(env: Env, among: List<MusicProvider> = Providers.connectors()): Map<String, Long> {
        val gen = generation.get()
        val out = LinkedHashMap<String, Boolean>()
        val took = LinkedHashMap<String, Long>()
        for (p in among) {
            val t0 = now()
            out[p.id] = runCatching { p.isConnected(env) }.getOrDefault(false)
            took[p.id] = now() - t0
        }
        if (generation.get() == gen) snap = Snap(now(), out)
        return took
    }

    /** [refresh] in the background, one at a time; [slow] hears about the connectors that took long. */
    fun refreshAsync(env: Env, slow: (Map<String, Long>) -> Unit = {}) {
        if (!reading.compareAndSet(false, true)) return
        scope.launch {
            try {
                val took = refresh(env)
                took.filterValues { it >= SLOW_MS }.takeIf { it.isNotEmpty() }?.let(slow)
            } finally { reading.set(false) }
        }
    }

    /** A connector that takes this long to say whether it is signed in is worth a line in logcat. */
    const val SLOW_MS = 30L
}
