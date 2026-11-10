package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.isTransientNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs syncs, by hand from the page or by their schedule. What WorkManager does in the app: a loop
 * that wakes every minute and starts the syncs whose interval has passed since their last run, a
 * few at a time.
 */
class Runner(private val env: ServerEnv, private val edition: Html.Edition = Html.Edition.SERVER) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(MAX_PARALLEL)
    /** A scheduled sync that found no network is tried again sooner than its next interval. */
    private val retryAt = ConcurrentHashMap<String, Long>()

    /** Radio syncs listen to their station while their window is open (see RadioListener). */
    val radio by lazy { RadioListener(env, this) }

    fun isRunning(jobId: String) = jobId in SyncState.running.value

    /** Starts [job] now unless it is already running (checked and marked at once: posts come in parallel). */
    @Synchronized
    fun start(job: SyncJob, scheduled: Boolean = false, confirmRemovals: Boolean = false) {
        if (isRunning(job.id)) return
        // A Stop or Pause posted after the previous run had ended must not reach this one.
        SyncState.finish(job.id)
        SyncState.start(job.id)
        scope.launch {
            slots.withPermit { run(job, scheduled, confirmRemovals && !scheduled) }
        }
    }

    /**
     * [confirmRemovals]: the caller (the API, the command line) says the removals a run would hold
     * back are meant. What the preview shows now is confirmed as the app's tick does, for this run
     * only; a scheduled run never comes here with it.
     */
    private suspend fun run(job: SyncJob, scheduled: Boolean, confirmRemovals: Boolean = false) {
        try {
            env.log("sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
            Api.notifyStarted(env, job, scheduled)
            if (confirmRemovals && job.radio == null) runCatching {
                val seen = SyncEngine(env).plan(job, createTarget = false, interactive = true)
                if (seen.removalsNeedConfirmation) {
                    env.store.confirmRemovals(job.id, seen.toRemove.map { it.id })
                    env.log("sync", "${job.name}: ${seen.toRemove.size} removals confirmed by the caller")
                }
            }
            val engine = SyncEngine(env)
            val report = engine.run(job, unattended = scheduled) { p ->
                SyncState.progress(job.id, p)
                if (p.step == Progress.Step.WAITING) SyncState.stopCheck(env, job.id) else SyncState.checkpoint(env, job.id)
            }
            env.log(
                "sync",
                "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size} src=${report.sourceCount} dst=${report.targetCount}" +
                    (report.error?.let { " ERR $it" } ?: ""),
            )
            Api.notify(env, job, report)
            Api.notifyWanted(env, job, engine.wantedAdded)
            // The channels the person set up (core's Notify): in the background, never failing the run.
            if (env.may(Permissions.API)) Notify.afterRun(env, job, report)
            if (scheduled && !report.ok && isTransientNetwork(report.error)) {
                retryAt[job.id] = System.currentTimeMillis() + RETRY_MS
                env.log("sync", "no network for ${job.name}: will retry")
            } else retryAt.remove(job.id)
        } catch (e: Exception) {
            env.log("sync", "${job.name}: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            SyncState.finish(job.id)
            env.store.flush()
        }
    }

    /** True when [job] is due by its schedule at [now]. */
    fun isDue(job: SyncJob, now: Long): Boolean {
        if (!com.xlollx.songport.sync.Timing.scheduled(job)) return false
        retryAt[job.id]?.let { if (now >= it) return true }
        return com.xlollx.songport.sync.Timing.isDue(job, now)
    }

    /** Stops the scheduler, the radio listeners and the runs under way (a user removed from a shared server). */
    fun stop() {
        radio.stop()
        scope.cancel()
    }

    /** The scheduler: once a minute, the syncs that are due. */
    fun startScheduler() {
        if (env.may(Permissions.RADIO)) radio.start()
        scope.launch {
            delay(STARTUP_DELAY_MS)
            while (isActive) {
                val now = System.currentTimeMillis()
                env.store.data.jobs.filter { isDue(it, now) && !isRunning(it.id) }.forEach { start(it, scheduled = true) }
                // The backup schedule (Tools › Full backup), as the app's BackupWorker.
                if (Backups.isDue(env, now)) Tasks.start(Backups.TITLE, "/tools/backup", { env.log("backup", it) }, env.owner) { t -> Backups.run(env) { t.progress = it } }
                // Lyrics for the files added to the library this week (Tools › Lyrics, Docker only).
                if (env.may(Permissions.ADMIN) && Lyrics.isDue(env, edition, now)) Tasks.start(Lyrics.TITLE, "/tools/lyrics", { env.log("lyrics", it) }, env.owner) { t -> lyricsResult(Lyrics.run(env, Lyrics.Options()) { t.progress = it }) }
                // "A new version is out" (Settings › Updates): twice a day at most, where this engine may check.
                runCatching { Updates.checkIfDue(now) }
                delay(TICK_MS)
            }
        }
    }

    companion object {
        private const val MAX_PARALLEL = 2
        private const val TICK_MS = 60_000L
        private const val STARTUP_DELAY_MS = 15_000L
        private const val RETRY_MS = 15 * 60_000L

        /** When [job] runs next by its schedule, or null for a manual one. */
        fun nextRun(job: SyncJob): Long? =
            com.xlollx.songport.sync.Timing.nextRun(job, System.currentTimeMillis())?.let { maxOf(System.currentTimeMillis(), it) }
    }
}
