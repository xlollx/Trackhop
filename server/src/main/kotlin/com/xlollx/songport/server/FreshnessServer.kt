package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Freshness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * The sync cards' "Up to date", checked rather than assumed (the app's FreshnessChecks, on
 * [Freshness]): a sync whose last run ended well is called up to date only once its source has been
 * seen unchanged since that run. One light call per sync when the list is shown, a few at a time in
 * the background (the page never waits for it), kept [KEEP_MS]; checked again sooner when the sync
 * runs. On a shared server each person's syncs are their own ([ServerEnv.owner]).
 */
internal object FreshnessServer {
    private class Entry(val state: Freshness.State, val at: Long, val lastRun: Long, val seen: String?)

    const val KEEP_MS = 10 * 60_000L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(3)

    private fun key(env: ServerEnv, job: SyncJob) = env.owner + "\u0000" + job.id

    /** The last answer for [job]'s latest run; null when not checked yet (or the sync ran since). */
    fun stateOf(env: ServerEnv, job: SyncJob): Freshness.State? =
        entries[key(env, job)]?.takeIf { it.lastRun == job.lastRunEpoch && it.seen == job.sourceVersion }?.state

    /** On, run before, with a version kept: the others cannot be called up to date. */
    fun worthChecking(job: SyncJob): Boolean = job.enabled && job.radio == null && job.lastRunEpoch > 0 && job.sourceVersion != null

    /** Starts a check of the [jobs] not checked lately; returns at once. */
    fun refresh(env: ServerEnv, jobs: List<SyncJob>, now: Long = System.currentTimeMillis()) {
        for (job in jobs.filter(::worthChecking)) {
            val k = key(env, job)
            val e = entries[k]
            if (e != null && e.lastRun == job.lastRunEpoch && e.seen == job.sourceVersion && now - e.at <= KEEP_MS) continue
            if (!asked.add(k)) continue
            scope.launch {
                try {
                    slots.withPermit { checkNow(env, job) }
                } finally {
                    asked.remove(k)
                }
            }
        }
    }

    /** Checks [job] now and keeps the answer; one that could not get an answer leaves the last for this same run in place. */
    suspend fun checkNow(env: ServerEnv, job: SyncJob): Freshness.State {
        val k = key(env, job)
        val found = Freshness.check(env, job)
        val before = entries[k]?.takeIf { it.lastRun == job.lastRunEpoch && it.seen == job.sourceVersion }
        val state = if (found == Freshness.State.UNKNOWN && before != null) before.state else found
        entries[k] = Entry(state, System.currentTimeMillis(), job.lastRunEpoch, job.sourceVersion)
        return state
    }

    /** The card's note: "✓ Up to date", or that the source changed; nothing when not known. */
    fun badge(env: ServerEnv, job: SyncJob): String = when (stateOf(env, job)) {
        Freshness.State.CURRENT -> """ <span class="badge" title="Its source is as it was at the last run">✓ Up to date</span>"""
        Freshness.State.CHANGED -> """ <span class="badge" title="A run would bring the changes">Source changed since the last run</span>"""
        else -> ""
    }

    fun word(state: Freshness.State?): String? = when (state) {
        Freshness.State.CURRENT -> "current"
        Freshness.State.CHANGED -> "changed"
        Freshness.State.UNKNOWN -> "unknown"
        null -> null
    }
}
