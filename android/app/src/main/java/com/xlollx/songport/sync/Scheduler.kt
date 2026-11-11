package com.xlollx.songport.sync

import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.*
import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.widget.SyncWidget
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val scheduled = inputData.getBoolean(KEY_SCHEDULED, false)
        val quiet = inputData.getBoolean(KEY_QUIET, false)
        val ctx = applicationContext
        val store = Store.get(ctx)
        val batch = inputData.getStringArray(KEY_JOBS)
        val jobs = when {
            batch != null -> batch.mapNotNull { store.job(it) }
            jobId == ALL -> store.data.jobs.filter { it.enabled }
            else -> listOfNotNull(store.job(jobId))
        }.filter {
            // Handed over to the user's server: it runs there, never here too (ServerLink).
            it.id !in store.data.handedOver
        }.filter {
            // A scheduled wake of a sync at a time of day: nothing to do until its time.
            !scheduled || batch != null || jobId == ALL || !com.xlollx.songport.sync.Timing.timed(it) ||
                com.xlollx.songport.sync.Timing.isDue(it, System.currentTimeMillis())
        }
        // Rete ancora assente al risveglio: non e' un esito della sync, la si rimette in coda.
        val retryLater = java.util.concurrent.atomic.AtomicBoolean(false)
        // Le sync manuali possono durare piu' dei 10 minuti concessi a un lavoro in background
        // (migliaia di brani verso YouTube): in primo piano, con una notifica di avanzamento.
        // Every worker shares one notification (see SyncNotice): its content is all the syncs running.
        val foreground = !scheduled && runCatching { setForeground(SyncNotice.foregroundInfo(ctx)) }.isSuccess
        if (foreground) SyncNotice.enter(ctx)
        try {
            // Several at once, never two on the same account (see ParallelRuns).
            com.xlollx.songport.sync.ParallelRuns.run(jobs) { job ->
                if (job.id in SyncState.running.value) return@run
                SyncState.start(job.id)
                com.xlollx.songport.widget.Widgets.refresh(ctx)
                val engine = SyncEngine(ctx)
                Diagnostics.log(ctx, "sync", "start ${job.name} (${if (scheduled) "scheduled" else "manual"})")
                try {
                    val report = try {
                        // On IO, not on the worker's Default dispatcher: a paused sync waits by blocking
                        // its thread (SyncState.checkpoint), and a few paused syncs would take every
                        // Default thread, so the others could never reach their own pause, and the
                        // notification, which follows them on Default, would stop updating.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { engine.run(job, unattended = scheduled) { p ->
                            // The shared notification follows SyncState: this update reaches it too.
                            SyncState.progress(job.id, p)
                            // Pausa o stop chiesti dall'utente: qui, fra un passo e l'altro. L'attesa imposta
                            // dal servizio e' tempo che deve passare comunque: in pausa il conto alla rovescia
                            // continua, e' l'abbinamento che si ferma appena l'attesa finisce.
                            if (p.step == Progress.Step.WAITING) SyncState.stopCheck(ctx, job.id) else SyncState.checkpoint(ctx, job.id)
                        } }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Fermato dal sistema, non dall'utente: WorkManager riprende il lavoro da solo e la
                        // cache degli abbinamenti evita di rifare le ricerche. Lo si annota, non e' un errore.
                        val reason = if (Build.VERSION.SDK_INT >= 31) " (stop reason $stopReason)" else ""
                        Diagnostics.log(ctx, "sync", "paused by the system$reason ${job.name}: will resume")
                        throw e
                    }
                    Diagnostics.log(ctx, "sync", "end ${job.name}: +${report.added} -${report.removed} nf=${report.unmatched.size} src=${report.sourceCount} dst=${report.targetCount}" + (report.error?.let { " ERR $it" } ?: ""))
                    // Una sync programmata morta per mancanza di rete non ha "fallito": WorkManager la
                    // riprova col suo backoff, quindi niente notifica d'errore per una giornata persa.
                    val networkGone = scheduled && !report.ok && isTransientNetwork(report.error)
                    if (networkGone) {
                        retryLater.set(true)
                        Diagnostics.log(ctx, "sync", "no network for ${job.name}: will retry")
                    }
                    SupportPrompt.offer(store, report)
                    com.xlollx.songport.automation.Automation.announce(ctx, report)
                    val notable = report.added > 0 || report.removed > 0 || !report.ok
                    if (!networkGone && !quiet && store.data.settings.notifyOnSync && (scheduled || !report.ok) && notable) {
                        Notifications.syncResult(ctx, report)
                    }
                    // The channels set up in Settings › Notification channels (Telegram, ntfy…): sent here, before the
                    // worker ends, so a process closed right after still delivered them. A failure only goes to the log.
                    if (!networkGone) for ((c, m) in com.xlollx.songport.notify.Notify.deliveries(ctx.env, job, report)) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.xlollx.songport.notify.Notify.send(c, m) }
                            ?.let { Diagnostics.log(ctx, "notify", "${c.name} (${c.kind.label}): $it") }
                    }
                } finally {
                    SyncState.finish(job.id)
                }
            }
        } finally {
            if (foreground) SyncNotice.leave(ctx)
        }
        com.xlollx.songport.widget.Widgets.refresh(ctx)
        store.flush()
        return if (retryLater.get()) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = SyncNotice.foregroundInfo(applicationContext)

    companion object {
        const val KEY_JOB = "job"
        const val KEY_SCHEDULED = "scheduled"
        const val ALL = "*"
        /** Several syncs in a single worker (a batch transfer): together where they use different accounts (ParallelRuns). */
        const val KEY_JOBS = "jobs"
        /** No notice of the result: a radio sync's runs while it listens (one every 10 minutes), not the one when its window closes. */
        const val KEY_QUIET = "quiet"
    }
}

/** Copia periodica dei backup nella cartella scelta dall'utente (vedi [BackupExport]). */
private const val BACKUP_NOTICE_ID = 4242

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val r = BackupExport.run(ctx)
        if (r.folderMissing) {
            Notifications.notice(ctx, BACKUP_NOTICE_ID, ctx.getString(R.string.backup_folder_title), ctx.getString(R.string.backup_folder_missing))
            return Result.success()
        }
        // Network gone on every service: not an outcome, WorkManager tries again with its backoff.
        return if (r.services == 0 && r.failed.isNotEmpty() && r.failed.all { isTransientNetwork(it) }) Result.retry() else Result.success()
    }
}

/** The weekly look for entries the services no longer play (see the Unplayable tracks tool): a notice, never a change. */
private const val UNPLAYABLE_NOTICE_ID = 4243

class UnplayableWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        var count = 0
        var services = 0
        val failed = ArrayList<String>()
        for (p in Providers.connectors().filter { it.detectsUnplayable && it.isConnected(ctx) }) {
            try {
                val scan = Tools.scanUnplayable(ctx, p, Tools.unplayableTargets(ctx, p))
                count += scan.count
                services++
                failed += scan.failed.map { it.reason }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                failed += e.message ?: e.javaClass.simpleName
            }
        }
        Diagnostics.log(ctx, "unplayable", "weekly check: $count unplayable in $services services" + (if (failed.isEmpty()) "" else "; ${failed.size} playlists not read"))
        if (count > 0) Notifications.unplayable(ctx, UNPLAYABLE_NOTICE_ID, count)
        return if (services == 0 && failed.isNotEmpty() && failed.all { isTransientNetwork(it) }) Result.retry() else Result.success()
    }
}

/**
 * The daily look for a newer Trackhop (the GitHub build only: stores and updaters tell about their
 * own): a notification once per new version, so it is seen without opening the app. Tap downloads it.
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        com.xlollx.songport.update.AppUpdates.check(ctx)
        val found = com.xlollx.songport.update.UpdateState.available.value ?: return Result.success()
        val name = found.versionName ?: return Result.success()
        val prefs = ctx.getSharedPreferences("app_update_notice", Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == name) return Result.success()
        if (Notifications.appUpdate(ctx, name, found.url)) prefs.edit().putString("notified", name).apply()
        return Result.success()
    }
}

/**
 * The look for news of the artists followed (Discover › Followed artists, [ArtistAlerts]): the
 * sources and caches of the Discover pages, one artist after another. The artists a run had no time
 * for are looked up by a second run a little later; the news is said once, when all are done.
 */
class ArtistAlertsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Store.get(ctx).data.settings
        val plan = ArtistAlerts.plan(s) ?: return Result.success()
        val env = ctx.env
        val country = Concerts.savedCountry(env) ?: com.xlollx.songport.ui.defaultConcertCountry(ctx)
        val out = ArtistAlerts.check(env, s.followedArtists, plan.periodDays, ArtistAlerts.live(country, Concerts.savedRange(env)))
        if (out.remaining > 0) Scheduler.continueArtistAlerts(ctx, plan)
        if (out.notify.isNotEmpty()) Notifications.artistAlerts(ctx, out.notify)
        return Result.success()
    }
}

object Scheduler {
    /** The unique periodic work of a sync (the UI tests read its state). */
    internal fun name(jobId: String) = "sync-$jobId"
    private const val BACKUP_WORK = "backup-export"
    private const val UNPLAYABLE_WORK = "unplayable-check"
    private const val UPDATE_WORK = "app-update-check"
    private const val ALERTS_WORK = "artist-alerts"
    private const val ALERTS_MORE_WORK = "artist-alerts-more"
    private const val KEEP_ALIVE_WORK = "session-keep-alive"

    /** What the keep-alive waits for: the network, a battery not low. */
    internal fun keepAliveConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /** The twice-daily renewal of a web session ([SessionKeepAlive]) while a connector that needs it is there. */
    fun applyKeepAlive(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!SessionKeepAlive.wanted(Store.get(ctx).data.settings.connectors)) {
            wm.cancelUniqueWork(KEEP_ALIVE_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<KeepAliveWorker>(SessionKeepAlive.EVERY_HOURS, TimeUnit.HOURS)
            .setConstraints(keepAliveConstraints())
            .build()
        wm.enqueueUniquePeriodicWork(KEEP_ALIVE_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** What the background look of the artists followed waits for: the network (Wi-Fi when asked), a battery not low. */
    internal fun alertConstraints(plan: ArtistAlerts.Plan): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (plan.unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(plan.batteryNotLow)
        .build()

    /** The daily (or weekly) look for news of the artists followed, in line with the settings and the list. */
    fun applyArtistAlerts(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val plan = ArtistAlerts.plan(Store.get(ctx).data.settings)
        if (plan == null) {
            wm.cancelUniqueWork(ALERTS_WORK); wm.cancelUniqueWork(ALERTS_MORE_WORK)
            return
        }
        com.xlollx.songport.NotificationAsk.onUse()
        val req = PeriodicWorkRequestBuilder<ArtistAlertsWorker>(plan.periodDays, TimeUnit.DAYS)
            .setConstraints(alertConstraints(plan))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // The same plan keeps its run time: following one more artist does not push the next look a day away.
        val key = "${plan.periodDays}|${plan.unmetered}"
        val prefs = ctx.getSharedPreferences("artist_alerts", Context.MODE_PRIVATE)
        val same = prefs.getString("plan", null) == key
        wm.enqueueUniquePeriodicWork(ALERTS_WORK, if (same) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE, req)
        prefs.edit().putString("plan", key).apply()
    }

    /** The artists the last look had no time for, a little later (after the run asking it, which may be this very work). */
    internal fun continueArtistAlerts(ctx: Context, plan: ArtistAlerts.Plan) {
        val req = OneTimeWorkRequestBuilder<ArtistAlertsWorker>()
            .setConstraints(alertConstraints(plan))
            .setInitialDelay(20, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ALERTS_MORE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** The daily update notification on or off with Settings › About's switch (only builds that check GitHub themselves). */
    fun applyUpdateCheck(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val u = com.xlollx.songport.update.AppUpdates
        if (!u.configurable || !u.enabled(ctx) || u.handledElsewhere(ctx)) {
            wm.cancelUniqueWork(UPDATE_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(UPDATE_WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Allinea il controllo settimanale dei brani non riproducibili alle impostazioni. */
    fun applyUnplayable(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!Store.get(ctx).data.settings.unplayableCheck) {
            wm.cancelUniqueWork(UNPLAYABLE_WORK)
            return
        }
        com.xlollx.songport.NotificationAsk.onUse()
        val req = PeriodicWorkRequestBuilder<UnplayableWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(UNPLAYABLE_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Allinea il lavoro periodico di copia dei backup alle impostazioni. */
    fun applyBackup(ctx: Context) {
        val s = Store.get(ctx).data.settings
        val wm = WorkManager.getInstance(ctx)
        // On a schedule the playlists are backed up on the phone; the folder, when chosen, gets copies.
        if (s.backupSchedule == Schedule.MANUAL) {
            wm.cancelUniqueWork(BACKUP_WORK)
            return
        }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(s.backupSchedule.minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(BACKUP_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Allinea il lavoro periodico di WorkManager alla configurazione della sync. */
    fun apply(ctx: Context, job: SyncJob) {
        // A radio sync listens in its window: the listening and its alarm follow the change.
        // Called from the app on screen (editor, card menu, restore), so listening may start now.
        if (job.radio != null || RadioService.running.value) RadioPlan.refresh(ctx, start = true)
        applyWork(ctx, job)
    }

    private fun applyWork(ctx: Context, job: SyncJob) {
        val wm = WorkManager.getInstance(ctx)
        if (!com.xlollx.songport.sync.Timing.scheduled(job)) {
            wm.cancelUniqueWork(name(job.id))
            return
        }
        com.xlollx.songport.NotificationAsk.onUse()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (job.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        // A sync at a time of day wakes every 15 minutes and runs only when its time has come (SyncWorker);
        // one by interval wakes at the interval itself.
        val period = if (com.xlollx.songport.sync.Timing.timed(job)) 15L else com.xlollx.songport.sync.Timing.interval(job).toLong()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(period, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_JOB to job.id, SyncWorker.KEY_SCHEDULED to true))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag("sync")
            .build()
        wm.enqueueUniquePeriodicWork(name(job.id), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context, jobId: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(jobId))
        // A deleted radio sync stops listening (and its alarm goes) at once.
        RadioPlan.refresh(ctx, start = false)
    }

    fun applyAll(ctx: Context) {
        val store = Store.get(ctx)
        store.data.jobs.forEach { applyWork(ctx, it) }
        applyBackup(ctx)
        applyUnplayable(ctx)
        applyUpdateCheck(ctx)
        applyArtistAlerts(ctx)
        applyKeepAlive(ctx)
        // At every start of the process, in the background too: the radio's alarm armed, no listening started from here.
        RadioPlan.refresh(ctx, start = false)
    }

    /**
     * A radio sync's run while it listens: the songs heard so far reach the target. As a scheduled
     * run, on Wi-Fi when the sync asks for it, without a notice of its result but for the last one.
     * [final]: the one when the window closes, queued after a run still going rather than dropped.
     */
    fun runRadio(ctx: Context, jobId: String, final: Boolean) {
        val job = Store.get(ctx).job(jobId)?.takeIf { it.enabled } ?: return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (job.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setInputData(workDataOf(SyncWorker.KEY_JOB to jobId, SyncWorker.KEY_SCHEDULED to true, SyncWorker.KEY_QUIET to !final))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("radio-$jobId", if (final) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Esecuzione manuale immediata (di una sync o di tutte con SyncWorker.ALL). Senza vincolo di
     * rete: WorkManager ferma il lavoro ogni volta che il vincolo viene meno anche per un istante
     * (passaggio Wi-Fi/dati), e una sync lunga finiva "cancellata" a ogni cambio di rete. Se la
     * rete manca davvero, sono le chiamate ai servizi a fallire, con i loro tentativi.
     */
    fun runNow(ctx: Context, jobId: String) {
        // A sync handed over to the user's server: the server is asked to run it.
        if (ServerLink.handedOver(ctx, jobId) != null) return ServerLink.runHandedOver(ctx, jobId)
        com.xlollx.songport.NotificationAsk.onUse()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_JOB to jobId, SyncWorker.KEY_SCHEDULED to false))
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-$jobId", ExistingWorkPolicy.KEEP, req)
    }

    /**
     * Several syncs in one worker: a batch transfer of many playlists would otherwise start them all
     * at once and hit the services' rate limits together. Those on the same account run in turn.
     */
    fun runInSequence(ctx: Context, jobIds: List<String>) {
        if (jobIds.isEmpty()) return
        val batchId = "batch-" + java.util.UUID.randomUUID()
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_JOB to batchId, SyncWorker.KEY_JOBS to jobIds.toTypedArray(), SyncWorker.KEY_SCHEDULED to false))
            .addTag("sync")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("run-$batchId", ExistingWorkPolicy.KEEP, req)
    }
}
