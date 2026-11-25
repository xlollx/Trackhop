package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.SyncLog
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

    /**
     * Since when the server has had no internet, as its last runs found ([com.xlollx.songport.model.SyncReport.offline]);
     * null once a run reached the services again. The pages say it under the header.
     */
    @Volatile var offlineSince: Long? = null
        private set

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
        // A run by hand is written down while it lasts: cut short by a restart of the server, the next
        // start says so and offers Retry (RunMarks). A scheduled one comes back by its schedule; either
        // way a new run takes the place of an interruption said before.
        if (scheduled) env.marks.end(RunMarks.SYNC, job.id) else env.marks.begin(RunMarks.SYNC, job.id, job.name, "/syncs/${job.id}")
        scope.launch {
            var began = false
            try {
                slots.withPermit { began = true; run(job, scheduled, confirmRemovals && !scheduled) }
            } finally {
                // Cancelled while it waited for its turn (the person's engine stopped): not a restart's doing.
                if (!began) env.marks.end(RunMarks.SYNC, job.id)
            }
        }
    }

    /**
     * [confirmRemovals]: the caller (the API, the command line) says the removals a run would hold
     * back are meant. What the preview shows now is confirmed as the app's tick does, for this run
     * only; a scheduled run never comes here with it.
     */
    private suspend fun run(job: SyncJob, scheduled: Boolean, confirmRemovals: Boolean = false): com.xlollx.songport.model.SyncReport? {
        var ended: com.xlollx.songport.model.SyncReport? = null
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
            ended = report
            env.log(
                "sync",
                "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size} src=${report.sourceCount} dst=${report.targetCount}" +
                    (report.error?.let { " ERR $it" } ?: ""),
            )
            Api.notify(env, job, report)
            Api.notifyWanted(env, job, engine.wantedAdded)
            // The channels the person set up (core's Notify): in the background, never failing the run.
            if (env.may(Permissions.API)) Notify.afterRun(env, job, report)
            // No internet, or a service out of reach: tried again sooner than the next interval (a sync
            // run by hand too, when it has a schedule), never counted as the sync's own failure.
            if (!report.ok && (report.netError != null || (scheduled && isTransientNetwork(report.error)))) {
                retryAt[job.id] = System.currentTimeMillis() + RETRY_MS
                env.log("sync", "no network for ${job.name}: will retry")
            } else retryAt.remove(job.id)
            if (report.offline) { if (offlineSince == null) offlineSince = System.currentTimeMillis() }
            else if (report.netError == null) offlineSince = null
        } catch (e: Exception) {
            env.log("sync", "${job.name}: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Ended, failed or stopped by the person: no longer something a restart could cut short.
            // Before the sync is free to start again, so the mark of a next run is never the one taken away.
            env.marks.end(RunMarks.SYNC, job.id)
            SyncState.finish(job.id)
            env.store.flush()
        }
        return ended
    }

    // ------------------------------------------------------------------ multi syncs

    /** The batch under way of the multi sync [groupId] (SyncState.Batch), or null when it does not run. */
    fun groupBatch(groupId: String): SyncState.Batch? = SyncState.batchOf(groupId)

    /** True while the multi sync [groupId] runs: as a batch, or one of its syncs alone. */
    fun isGroupRunning(groupId: String): Boolean =
        groupBatch(groupId) != null || Nway.jobsOf(env.store.data, groupId).any { isRunning(it.id) }

    /**
     * Runs the multi sync [group] as one batch, as the app's "Sync now" does (its worker's batch): its
     * syncs one after the other in the group's order ([Nway.order]: every main member's changes into
     * the group's list first, then the list into every playlist), each read again before it runs. By
     * hand ([scheduled] false) a paused group runs whole; by its schedule only its syncs that are on.
     * Answers false when it already runs or has nothing to run.
     */
    @Synchronized
    fun startGroup(group: SharedGroup, scheduled: Boolean = false): Boolean {
        if (isGroupRunning(group.id)) return false
        val order = Nway.order(env, group, manual = !scheduled)
        if (order.isEmpty()) return false
        val batchId = "batch-" + java.util.UUID.randomUUID()
        SyncState.batchStart(batchId, group.id, order.map { it.id })
        env.log("sync", SyncLog.batchStart(group.id, group.name, order, scheduled))
        scope.launch {
            var stopped = true
            try {
                // One slot for the whole run: the group's syncs never run side by side, nor two groups' too many at once.
                slots.withPermit {
                    for (j in order) {
                        // Read again: an earlier sync of this run may have written to it (its pair's other half); one deleted meanwhile is skipped.
                        val job = env.store.job(j.id)
                        // Checked and marked under the lock [start] holds: a sync started on its own meanwhile is not run twice at once.
                        val skipped = synchronized(this@Runner) {
                            when {
                                job == null -> "no longer in the list"
                                isRunning(job.id) -> "already running"
                                else -> { SyncState.finish(job.id); SyncState.start(job.id); null }
                            }
                        }
                        if (skipped != null || job == null) {
                            SyncState.batchDone(batchId, j.id, SyncState.JobResult(skipped = skipped))
                            continue
                        }
                        if (!scheduled) env.marks.begin(RunMarks.SYNC, job.id, job.name, "/multi/${group.id}")
                        val report = run(job, scheduled)
                        SyncState.batchDone(batchId, job.id, report?.let { SyncLog.result(it) } ?: SyncState.JobResult(ok = false, error = "not run"))
                    }
                    stopped = false
                }
            } finally {
                SyncState.batchEnd(batchId)?.let { env.log("sync", SyncLog.batchEnd(it, group.name, stopped)) }
                runCatching { Nway.log(env, group.id, "${order.size} syncs run (${if (scheduled) "scheduled" else "manual"})") }
            }
        }
        return true
    }

    /**
     * Starts [jobs] by hand ("Sync all", a tag): the classic syncs each on its own, and each multi sync
     * one of them belongs to as one batch of its own ([startGroup]), as the app's Sync all does.
     * Answers how many syncs and multi syncs were started.
     */
    fun startAll(jobs: List<SyncJob>): Int {
        val d = env.store.data
        val groups = Nway.groups(env).associateBy { it.id }
        var n = 0
        val seen = HashSet<String>()
        for (j in jobs) {
            val g = com.xlollx.songport.shared.SharedGroups.sharedSide(j)?.let { groups[it] }
            if (g == null) { start(j); n++ }
            else if (seen.add(g.id) && Nway.jobsOf(d, g.id).isNotEmpty()) { if (startGroup(g)) n++ }
        }
        return n
    }

    /**
     * The multi syncs due by their schedule at [now]: a group whose syncs are on runs when one of them
     * is due ([isDue]), as one batch ([startGroup]), never its syncs each alone at their own time.
     */
    fun dueGroups(now: Long): List<SharedGroup> = Nway.groups(env).filter { g ->
        Nway.jobsOf(env.store.data, g.id).any { it.enabled && isDue(it, now) } && !isGroupRunning(g.id)
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
        loop(STARTUP_DELAY_MS, TICK_MS) { tick(it) }
    }

    /**
     * [turn] every [every] ms, after [startup]. A turn that throws is written in the log and the next
     * one comes all the same: one error must not end the scheduled syncs until the server is restarted.
     */
    internal fun loop(startup: Long, every: Long, turn: suspend (Long) -> Unit) = scope.launch {
        delay(startup)
        while (isActive) {
            guarded("turn") { turn(System.currentTimeMillis()) }
            delay(every)
        }
    }

    /** One turn of the minute at [now]. Each sync and each other entry by itself: one that throws does not keep the others from starting. */
    internal suspend fun tick(now: Long, beginGroup: (SharedGroup) -> Unit = { startGroup(it, scheduled = true) }, begin: (SyncJob) -> Unit = { start(it, scheduled = true) }) {
        guarded("syncs") {
            // The syncs of a multi sync run with their group, below.
            val grouped = Nway.memberJobIds(env.store.data)
            env.store.data.jobs.forEach { job -> guarded(job.name) { if (job.id !in grouped && isDue(job, now) && !isRunning(job.id)) begin(job) } }
        }
        // Each multi sync due: as one batch, its syncs one after the other (the app's group run).
        guarded("multi syncs") { dueGroups(now).forEach { g -> guarded(g.name) { beginGroup(g) } } }
        // The backup schedule (Tools › Full backup), as the app's BackupWorker.
        guarded("backup") {
            if (Backups.isDue(env, now)) Tasks.start(Backups.TITLE, "/tools/backup", { env.log("backup", it) }, env.owner) { t -> Backups.run(env) { t.progress = it } }
        }
        // The weekly look for unplayable tracks (Tools › Unplayable tracks), as the app's UnplayableWorker.
        guarded("unplayable") {
            if (UnplayableWeekly.isDue(env, now)) Tasks.start(UnplayableWeekly.TITLE, "/tools/unplayable", { env.log("unplayable", it) }, env.owner) { t -> """<div class="card">${Html.h(UnplayableWeekly.run(env) { t.progress = it })}</div>""" }
        }
        // Lyrics for the files added to the library this week (Tools › Lyrics, Docker only).
        guarded("lyrics") {
            if (env.may(Permissions.ADMIN) && Lyrics.isDue(env, edition, now)) Tasks.start(Lyrics.TITLE, "/tools/lyrics", { env.log("lyrics", it) }, env.owner) { t -> lyricsResult(Lyrics.run(env, Lyrics.Options()) { t.progress = it }) }
        }
        // The followed artists' look (Tools › Followed artists), once a day or a week.
        guarded("artist alerts") { if (Features.v2) env.alertsTick(now) }
        // "A new version is out" (Settings › Updates): twice a day at most, where this engine may check.
        guarded("updates") { Updates.checkIfDue(now) }
    }

    /** [block], its error written in the log instead of thrown (a stop of the scheduler still stops it). */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { env.log("scheduler", "$what: ${e.javaClass.simpleName}: ${e.message}") }
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
