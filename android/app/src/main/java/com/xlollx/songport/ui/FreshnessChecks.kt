package com.xlollx.songport.ui

import androidx.compose.runtime.mutableStateMapOf
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Freshness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The sync cards' "Up to date", checked rather than assumed (Freshness): a sync whose last run ended
 * well is called up to date only once its source has been seen unchanged since that run. One light
 * call per sync when the list shows, a few at a time, kept [KEEP_MS]; checked again sooner when the
 * sync runs (its last run or the version seen at it change).
 */
internal object FreshnessChecks {
    private class Entry(val state: Freshness.State, val at: Long, val lastRun: Long, val seen: String?)

    private const val KEEP_MS = 10 * 60_000L
    private val entries = ConcurrentHashMap<String, Entry>()
    private val slots = Semaphore(3)

    /** What the cards read: written on the main thread, so a card recomposes when its check ends. */
    private val states = mutableStateMapOf<String, Freshness.State>()

    /** The checked state of [job], or null while it is not checked for its latest run. */
    fun stateOf(job: SyncJob): Freshness.State? {
        val state = states[job.id] ?: return null
        val e = entries[job.id] ?: return null
        return state.takeIf { e.lastRun == job.lastRunEpoch && e.seen == job.sourceVersion }
    }

    /** The syncs worth checking: on, run before with a version kept (others cannot be called up to date). */
    fun worthChecking(jobs: List<SyncJob>): List<SyncJob> =
        jobs.filter { it.enabled && it.radio == null && it.lastRunEpoch > 0 && it.sourceVersion != null }

    /** Checks the [jobs] not checked lately for their latest run. */
    suspend fun refresh(env: Env, jobs: List<SyncJob>) = coroutineScope {
        val now = System.currentTimeMillis()
        jobs.filter { job ->
            val e = entries[job.id]
            e == null || e.lastRun != job.lastRunEpoch || e.seen != job.sourceVersion || now - e.at > KEEP_MS
        }.forEach { job ->
            launch(Dispatchers.IO) {
                slots.withPermit {
                    val state = Freshness.check(env, job)
                    entries[job.id] = Entry(state, System.currentTimeMillis(), job.lastRunEpoch, job.sourceVersion)
                    withContext(Dispatchers.Main) { states[job.id] = state }
                }
            }
        }
    }
}
