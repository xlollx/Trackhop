package com.xlollx.songport.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xlollx.songport.data.Diagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/**
 * Syncs asked to run while the phone had no internet: they wait for it instead of failing. Each one
 * waits in WorkManager with a network constraint, so it starts once the connection is back even if
 * the app is closed by then; the first to start takes every sync still waiting with it, in one run
 * ([Scheduler.runInSequence]: together where they use different accounts). The cards show them as
 * "Waiting for internet" ([waiting]); a run asked again by hand while waiting starts at once.
 */
object OfflineQueue {
    private const val PREFS = "offline_queue"
    private const val KEY_IDS = "ids"
    private const val TAG = "wait-net"
    /** Runs stopped by a lost connection that are queued again by themselves before one waits for a person. */
    private const val MAX_AUTO = 3

    private val state = MutableStateFlow<Set<String>>(emptySet())
    /** The syncs waiting (their ids; [SyncWorker.ALL] for "Sync all"). */
    val waiting: StateFlow<Set<String>> = state.asStateFlow()
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        state.value = prefs(ctx).getStringSet(KEY_IDS, emptySet()).orEmpty().toSet()
    }

    private fun save(ctx: Context, ids: Set<String>) {
        state.value = ids
        prefs(ctx).edit().putStringSet(KEY_IDS, ids).apply()
    }

    /** Reads what waited before the app was closed: the cards say it from the start. */
    fun init(ctx: Context) = load(ctx)

    /** True when [jobId] waits for the internet (on its own, or with "Sync all" while it is enabled). */
    fun isWaiting(jobId: String, enabled: Boolean, ids: Set<String> = state.value): Boolean =
        jobId in ids || (enabled && SyncWorker.ALL in ids)

    internal fun name(id: String) = "$TAG-$id"

    /**
     * Whether [job] needs the internet: not when both its sides are files or servers of the user's own
     * (Jellyfin, Subsonic…), which a home network without internet still reaches. Unknown: it does.
     */
    fun needsInternet(job: com.xlollx.songport.model.SyncJob?): Boolean {
        job ?: return true
        fun local(p: String) = p == com.xlollx.songport.providers.LocalFilesProvider.id || Wanted.isServer(p)
        return !(local(job.source.provider) && local(job.target.provider)) || job.radio != null
    }

    /** [id] (a sync, or [SyncWorker.ALL]) starts once there is internet. */
    @Synchronized
    fun add(ctx: Context, id: String) {
        load(ctx)
        save(ctx, state.value + id)
        val req = OneTimeWorkRequestBuilder<NetworkWaitWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(NetworkWaitWorker.KEY_ID to id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, req)
        Diagnostics.log(ctx, "sync", "no internet: ${if (id == SyncWorker.ALL) "all syncs" else id} will start once the connection is back")
    }

    /** [id] no longer waits (Undo, or it was started by hand). */
    @Synchronized
    fun cancel(ctx: Context, id: String) {
        load(ctx)
        if (id !in state.value) return
        save(ctx, state.value - id)
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
    }

    /** Every sync waiting, taken out of the queue; [except]'s own wait (the worker running now) is left to end by itself. */
    @Synchronized
    internal fun drain(ctx: Context, except: String?): Set<String> {
        load(ctx)
        val ids = state.value
        save(ctx, emptySet())
        val wm = WorkManager.getInstance(ctx)
        ids.filter { it != except }.forEach { wm.cancelUniqueWork(name(it)) }
        return ids
    }

    /**
     * A run started by hand stopped because the connection went away: it waits for the internet again,
     * a few times in a row at most, so a connection that keeps dropping does not keep it going forever.
     * Answers whether it was queued.
     */
    fun requeueAfterLoss(ctx: Context, jobId: String): Boolean {
        val p = prefs(ctx)
        val n = p.getInt("auto-$jobId", 0)
        if (n >= MAX_AUTO) {
            p.edit().remove("auto-$jobId").apply()
            return false
        }
        p.edit().putInt("auto-$jobId", n + 1).apply()
        add(ctx, jobId)
        return true
    }

    /** A run that ended with the connection there: the count of [requeueAfterLoss] starts again. */
    fun settled(ctx: Context, jobId: String) {
        val p = prefs(ctx)
        if (p.contains("auto-$jobId")) p.edit().remove("auto-$jobId").apply()
    }
}

/** Starts the syncs that waited for the internet ([OfflineQueue]) once WorkManager sees a connection. */
class NetworkWaitWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = OfflineQueue.drain(ctx, except = inputData.getString(KEY_ID))
        if (ids.isEmpty()) return Result.success()
        Diagnostics.log(ctx, "sync", "back online: starting ${ids.size} waiting")
        val store = com.xlollx.songport.data.Store.get(ctx)
        if (SyncWorker.ALL in ids) {
            Scheduler.runNow(ctx, SyncWorker.ALL, force = true)
            // Asked by name and not in "all" (turned off): as asked.
            val rest = ids.filter { it != SyncWorker.ALL && store.job(it)?.enabled == false }
            if (rest.isNotEmpty()) Scheduler.runInSequence(ctx, rest)
        } else if (ids.size == 1) {
            Scheduler.runNow(ctx, ids.single(), force = true)
        } else {
            Scheduler.runInSequence(ctx, ids.filter { store.job(it) != null })
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
    }
}
