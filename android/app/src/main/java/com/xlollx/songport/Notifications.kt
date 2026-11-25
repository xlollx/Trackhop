package com.xlollx.songport

import com.xlollx.songport.providers.*
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.notify.NotifyHistory

/**
 * Notifications are asked for when they are first needed, a sync run or scheduled, not on first
 * start: the visible activity registers the request here and the scheduler calls it.
 */
object NotificationAsk {
    @Volatile var request: (() -> Unit)? = null
    @Volatile private var asked = false

    fun onUse() {
        if (asked) return
        val r = request ?: return
        asked = true
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { r() } }
    }
}

object Notifications {
    private const val CHANNEL_ID = "sync_results"
    const val PROGRESS_CHANNEL_ID = "sync_progress"
    /**
     * The one ongoing notification of the syncs running: every worker that goes to the foreground
     * posts under this id, so WorkManager's service and the progress updates share a single entry.
     */
    const val PROGRESS_ID = 1001
    /** The completion notices, bundled under one summary when several syncs finish. */
    const val RESULTS_GROUP = "sync_results"
    private const val RESULTS_SUMMARY_ID = 1000
    /** The value of [com.xlollx.songport.sync.SyncControlReceiver.EXTRA_JOB] that means every running sync. */
    const val ALL_JOBS = "*"

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_channel_desc) }
        )
        mgr.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL_ID, context.getString(R.string.notif_progress_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.notif_progress_desc); setShowBadge(false) }
        )
        // Quiet, like the progress: the ongoing "Listening to …" of a radio sync, and its rare "tap to listen".
        mgr.createNotificationChannel(
            NotificationChannel(RADIO_CHANNEL_ID, context.getString(R.string.radio_notif_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.radio_notif_channel_desc); setShowBadge(false) }
        )
    }

    const val RADIO_CHANNEL_ID = "radio_listening"
    /** The ongoing notification of the radio listening service (see com.xlollx.songport.sync.RadioService). */
    const val RADIO_ID = 1003
    /** "Tap to listen": the listening could not start by itself. */
    const val RADIO_BLOCKED_ID = 1004

    private fun openApp(context: Context, code: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCS)
        }
        return PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The tap of a notice: the app on [target] (NotifRoutes), the same place its entry in the bell
     * opens. Its own request code per target, so two notices never share their extras. [historyId]:
     * the bell's entry, marked read by the tap.
     */
    internal fun open(context: Context, base: Int, target: NotifTarget, historyId: String?): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TARGET, target.encode())
            historyId?.let { putExtra(MainActivity.EXTRA_HISTORY_ID, it) }
        }
        return PendingIntent.getActivity(context, NotifRoutes.requestCode(base, target), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * Keeps a notice in the bell (NotifyHistory), also when Android's notifications are off for
     * Trackhop or for its channel: the bell is where they are found then. Its entry's id, or null.
     */
    private fun remember(context: Context, kind: String, title: String, text: String, target: NotifTarget?): String? =
        runCatching { NotifyHistory.of(context.filesDir).add(kind, title, text, target) }.getOrNull()

    /**
     * While radio syncs listen: "Listening to <station>" and the last song heard (or that it waits
     * for Wi-Fi, or what went wrong). Several stations at once share the one notification.
     */
    fun radio(context: Context, lines: List<Pair<String, String>>): android.app.Notification {
        val title = context.getString(R.string.radio_notif_listening, lines.joinToString(", ") { it.first }.ifEmpty { "…" })
        val text = lines.joinToString(" · ") { it.second }
        val b = NotificationCompat.Builder(context, RADIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context, RADIO_ID))
        if (lines.size > 1) {
            val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
            lines.forEach { inbox.addLine(it.first + " — " + it.second) }
            b.setStyle(inbox)
        }
        return b.build()
    }

    /** A radio sync's window is open but Android did not let the listening start in the background: a tap opens the app, which starts it. */
    fun radioBlocked(context: Context, station: String) {
        if (!canPost(context)) return
        val text = context.getString(R.string.radio_notif_blocked_text)
        val n = NotificationCompat.Builder(context, RADIO_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.radio_notif_blocked_title, station))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context, RADIO_BLOCKED_ID))
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(RADIO_BLOCKED_ID, n)
    }

    fun cancelRadioBlocked(context: Context) {
        runCatching { ContextCompat.getSystemService(context, NotificationManager::class.java)?.cancel(RADIO_BLOCKED_ID) }
    }

    /**
     * One running sync as the ongoing notification shows it: its name, the step it is at, and where
     * it stands ([SyncState.Run]: "Pausing…" while its step finishes, "Paused" once it waits).
     */
    class RunningSync(
        val jobId: String, val name: String, val step: String, val done: Int = 0, val total: Int = 0,
        val run: SyncState.Run = SyncState.Run.RUNNING,
    ) {
        /** Waiting for real: its bar stands still. */
        val paused: Boolean get() = run == SyncState.Run.PAUSED
        /** Pause asked for, reached or not: its button is Resume. */
        val pauseAsked: Boolean get() = run == SyncState.Run.PAUSED || run == SyncState.Run.PAUSING
    }

    /**
     * The ongoing notification while syncs run, built from all of them at once. One sync: its name,
     * its step, its bar, Pause/Resume and Stop. Several: "N syncs running", one line per sync with
     * its step, the overall bar, and Pause all/Resume all and Stop all.
     *
     * Paused syncs say so: the title becomes "N syncs paused" (all of them) or "N running · M paused",
     * each line "Paused" once its sync really waits ("Pausing…" while its step finishes), and the bar
     * stands still, never indeterminate, when nothing is moving.
     */
    fun running(context: Context, syncs: List<RunningSync>): android.app.Notification {
        val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(context, PROGRESS_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
        // Request codes differ per job: PendingIntents with equal codes and intents that differ
        // only in their extras replace each other, and Pause on one sync would pause another.
        fun action(jobId: String, act: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, (jobId.hashCode() shl 2) or code,
            Intent(context, com.xlollx.songport.sync.SyncControlReceiver::class.java).setAction(act).putExtra(com.xlollx.songport.sync.SyncControlReceiver.EXTRA_JOB, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val one = syncs.singleOrNull()
        if (syncs.size <= 1) {
            val text = one?.let { lineText(context, it) }.orEmpty()
            b.setContentTitle(one?.name ?: context.getString(R.string.running))
                .setContentText(text)
            if (one != null && one.paused) b.setProgress(100, percent(one), false)
            else b.setProgress(one?.total?.coerceAtLeast(0) ?: 0, one?.done ?: 0, (one?.total ?: 0) <= 0)
            if (one != null) {
                if (one.pauseAsked) b.addAction(0, context.getString(R.string.sync_resume), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_RESUME, 1))
                else b.addAction(0, context.getString(R.string.sync_pause), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_PAUSE, 2))
                b.addAction(0, context.getString(R.string.sync_stop), action(one.jobId, com.xlollx.songport.sync.SyncControlReceiver.ACTION_STOP, 3))
            }
            return b.build()
        }
        val tally = SyncState.Tally(syncs.size, syncs.count { it.paused })
        val title = when {
            tally.allPaused -> context.resources.getQuantityString(R.plurals.syncs_paused, syncs.size, syncs.size)
            tally.somePaused -> context.getString(R.string.syncs_running_paused, tally.running, tally.paused)
            else -> context.resources.getQuantityString(R.plurals.syncs_running, syncs.size, syncs.size)
        }
        val lines = syncs.map { "${it.name} — ${lineText(context, it)}" }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach { inbox.addLine(it) }
        if (lines.size > MAX_LINES) inbox.setSummaryText(context.resources.getQuantityString(R.plurals.notif_more_running, lines.size - MAX_LINES, lines.size - MAX_LINES))
        // The overall bar: each sync weighs the same, one with no count yet as not started.
        val known = syncs.filter { it.total > 0 }
        val overall = syncs.sumOf { s -> percent(s).toDouble() } / syncs.size
        b.setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(inbox)
            .setNumber(syncs.size)
            // Everything paused: a still bar at where they stopped, not a moving one.
            .setProgress(100, overall.toInt(), known.isEmpty() && !tally.allPaused)
        if (syncs.all { it.pauseAsked }) b.addAction(0, context.getString(R.string.sync_resume_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_RESUME, 1))
        else b.addAction(0, context.getString(R.string.sync_pause_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_PAUSE, 2))
        b.addAction(0, context.getString(R.string.sync_stop_all), action(ALL_JOBS, com.xlollx.songport.sync.SyncControlReceiver.ACTION_STOP, 3))
        return b.build()
    }

    /** Lines past this many are counted ("+N more syncs running"), not listed. */
    private const val MAX_LINES = 6

    /** One sync's share of the bar: none until its step has a count. */
    private fun percent(s: RunningSync): Int = if (s.total > 0) (s.done.coerceIn(0, s.total) * 100L / s.total).toInt() else 0

    /** What one sync is doing, with "Paused" (or "Pausing…") before it and its percentage after. */
    private fun lineText(context: Context, s: RunningSync): String =
        when (s.run) {
            SyncState.Run.PAUSED -> context.getString(R.string.sync_paused) + " · "
            SyncState.Run.PAUSING -> context.getString(R.string.sync_pausing_short) + " · "
            else -> ""
        } + s.step +
            (if (s.total > 0) " · ${(s.done.coerceIn(0, s.total) * 100L / s.total)}%" else "")

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Testo riassuntivo di un report, condiviso tra notifiche e UI. */
    fun summary(context: Context, r: SyncReport): String {
        // The connection's failure is already said in plain words; an older report's line, or one passed
        // on as text, gets them here.
        if (!r.ok && r.netError != null) return r.error.orEmpty()
        if (!r.ok) return context.getString(R.string.result_error, com.xlollx.songport.ui.userText(context, r.error))
        return summaryParts(r).joinToString(", ") { (res, args) -> context.getString(res, *args.toTypedArray()) }
    }

    /**
     * The parts of [summary] for a run that went through, as string ids and their numbers. Songs the
     * service did not take this time (it limited the requests) are said: a run that added none of
     * them is not "Up to date" (2.1.4).
     */
    internal fun summaryParts(r: SyncReport): List<Pair<Int, List<Int>>> {
        val parts = ArrayList<Pair<Int, List<Int>>>()
        if (r.added > 0 || r.removed > 0) parts += R.string.result_added_removed to listOf(r.added, r.removed)
        if (r.notAdded.isNotEmpty()) parts += R.string.result_not_added to listOf(r.notAdded.size)
        if (r.unmatched.isNotEmpty()) parts += R.string.result_unmatched to listOf(r.unmatched.size)
        if (r.ignored > 0) parts += R.string.result_ignored to listOf(r.ignored)
        if (parts.isEmpty()) parts += R.string.result_ok to emptyList()
        return parts
    }

    /** A one-line notice that opens the app on its Settings (the backup folder gone, and the like). */
    fun notice(context: Context, id: Int, title: String, text: String) {
        val entry = remember(context, "notice", title, text, NotifTarget.Settings)
        if (!canPost(context)) return
        val pi = open(context, id, NotifTarget.Settings, entry)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(id, n)
    }

    private const val UPDATE_CHANNEL_ID = "app_updates"
    private const val UPDATE_ID = 4244

    /** "Trackhop X is out": a tap downloads its APK. Its own channel, so it can be muted alone. False when it could not be posted. */
    fun appUpdate(context: Context, version: String, url: String?): Boolean {
        if (!canPost(context)) return false
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, context.getString(R.string.notif_update_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.notif_update_channel_desc) }
        )
        // An explicit intent to our own screen, which opens the download page (CodeQL: no implicit
        // intent inside a PendingIntent). Not an entry of the bell: the bell shows the update itself, on top.
        val pi = open(context, UPDATE_ID, NotifTarget.Update(url ?: MainActivity.RELEASES_URL), null)
        val n = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_update_title, version))
            .setContentText(context.getString(R.string.notif_update_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(UPDATE_ID, n)
        return true
    }

    private const val ALERTS_CHANNEL_ID = "artist_alerts"
    private const val ALERTS_ID = 4245

    /**
     * "3 updates from your artists: new single by ADTR, Architects concert in Milan, new merch from
     * BMTH": one notification per look, on its own channel; a tap opens Discover › Followed artists.
     */
    fun artistAlerts(context: Context, news: List<com.xlollx.songport.sync.ArtistAlerts.News>) {
        if (news.isEmpty()) return
        val lines = com.xlollx.songport.sync.ArtistAlerts.group(news).size
        val title = context.resources.getQuantityString(R.plurals.alerts_notif_title, lines, lines)
        val text = com.xlollx.songport.sync.ArtistAlerts.summary(com.xlollx.songport.platform.AndroidEnv.of(context), news).replaceFirstChar { it.titlecase() }
        val entry = remember(context, "alerts", title, text, NotifTarget.Followed)
        if (!canPost(context)) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.alerts_channel_desc) }
        )
        val pi = open(context, ALERTS_ID, NotifTarget.Followed, entry)
        val n = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(ALERTS_ID, n)
    }

    /**
     * "Kid A under €30 · A copy at €28 on Discogs. Tap to see it.": one per wanted record (tagged
     * with its id), on the alerts' channel; a tap opens the record's copies for sale on Discogs, in
     * the browser. Nothing is bought here.
     */
    fun discogsWant(context: Context, hit: com.xlollx.songport.sync.Discogs.Hit) {
        val price = hit.price?.let { com.xlollx.songport.ui.price(it, hit.currency) }.orEmpty()
        val below = hit.kind == com.xlollx.songport.sync.Discogs.Kind.BELOW
        val title = if (below) context.getString(R.string.myrecords_notif_below, hit.item.title, com.xlollx.songport.ui.price(hit.below ?: 0.0, hit.currency))
            else context.getString(R.string.myrecords_notif_new, hit.item.title)
        val text = context.getString(if (below) R.string.myrecords_notif_below_text else R.string.myrecords_notif_new_text, price)
        // Through our own screen, which opens the page (CodeQL: no implicit intent inside a PendingIntent).
        val target = hit.item.sellUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { NotifTarget.Link(it) } ?: NotifTarget.Followed
        val entry = remember(context, "record", title, text, target)
        if (!canPost(context)) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.alerts_channel_desc) }
        )
        val pi = open(context, ALERTS_ID xor hit.item.id.hashCode(), target, entry)
        val n = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        mgr.notify("discogs-${hit.item.id}", ALERTS_ID, n)
    }

    /**
     * "Radiohead: tickets from Fri 20 Nov, 10:00", "Date moved: Massive Attack": one per event and
     * kind, on the alerts' channel; a tap opens the event's page (its tickets), or Followed artists
     * when it has none.
     */
    fun concertAlert(context: Context, alert: com.xlollx.songport.sync.ConcertAlerts.Alert) {
        val env = com.xlollx.songport.platform.AndroidEnv.of(context)
        val url = alert.event.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val target = url?.let { NotifTarget.Link(it) } ?: NotifTarget.Followed
        val tag = "concert-${alert.kind}-${alert.event.id}"
        val text = com.xlollx.songport.sync.ConcertAlerts.text(env, alert)
        val title = com.xlollx.songport.sync.ConcertAlerts.title(env, alert)
        val entry = remember(context, "concert", title, text, target)
        if (!canPost(context)) return
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(ALERTS_CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.alerts_channel_desc) }
        )
        val pi = open(context, ALERTS_ID xor tag.hashCode(), target, entry)
        val n = NotificationCompat.Builder(context, ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        mgr.notify(tag, ALERTS_ID, n)
    }

    private const val SIGN_IN_ID = 4246

    /**
     * "SoundCloud: sign in again to keep your syncs running": a web session gone for good. A tap
     * opens Accounts on that connector. One per connector (tagged with its id), so two sessions gone
     * are two notifications. False when it could not be posted.
     */
    fun signInAgain(context: Context, service: String, connectorId: String): Boolean {
        val target = NotifTarget.Account(connectorId)
        val text = context.getString(R.string.notif_signin_again_text)
        val title = context.getString(R.string.notif_signin_again_title, service)
        val entry = remember(context, "signin", title, text, target)
        if (!canPost(context)) return false
        ensureChannel(context)
        // Its own request code per connector: another connector's notification keeps its own target.
        val pi = open(context, SIGN_IN_ID xor connectorId.hashCode(), target, entry)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(connectorId, SIGN_IN_ID, n)
        return true
    }

    /** Takes back [connectorId]'s "sign in again" ([signInAgain]): the person signed in, or out. */
    fun cancelSignInAgain(context: Context, connectorId: String) {
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.cancel(connectorId, SIGN_IN_ID)
    }

    /**
     * "N tracks can no longer be played": opens the Unplayable tracks tool, on [providerId] (the
     * service with the most of them) when known.
     */
    fun unplayable(context: Context, id: Int, count: Int, providerId: String? = null) {
        val target = NotifTarget.Unplayable(providerId)
        val text = context.resources.getQuantityString(R.plurals.notif_unplayable_text, count, count)
        val title = context.getString(R.string.notif_unplayable_title)
        val entry = remember(context, "unplayable", title, text, target)
        if (!canPost(context)) return
        val pi = open(context, id, target, entry)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ContextCompat.getSystemService(context, NotificationManager::class.java)?.notify(id, n)
    }

    fun syncResult(context: Context, report: SyncReport) {
        // A one-off transfer: its songs to review, else Tools (Recent transfers); a sync: its review,
        // its multi sync's page, or its card with this run (NotifRoutes.forResult).
        val store = com.xlollx.songport.data.Store.get(context)
        val job = store.job(report.jobId)
        val target = NotifRoutes.forResult(store.data, job, report)
        // 1.18: the same message as the other channels (result, route, figures, what to do); a run with
        // nothing to tell, or before 1.18, the summary as ever.
        val rich = if (Features.v2 && job != null) runCatching {
            val releases = job.source.provider == com.xlollx.songport.providers.NewReleasesProvider.SERVICE
            com.xlollx.songport.notify.Notify.richMessage(com.xlollx.songport.platform.AndroidEnv.of(context), job, report, com.xlollx.songport.notify.Notify.Event.entries.toSet(), releases)?.rich
        }.getOrNull() else null
        val text = rich?.let { listOfNotNull(it.countsLine.ifEmpty { null }, it.detail).joinToString(" · ").ifEmpty { it.route } } ?: summary(context, report)
        val title = rich?.let { "${it.icon} ${it.title}" } ?: context.getString(R.string.notif_sync_title, report.jobName)
        val entry = remember(context, com.xlollx.songport.notify.Notify.runKind(report), title, text, target)
        if (!canPost(context)) return
        val pi = open(context, report.jobId.hashCode(), target, entry)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(rich?.lines?.joinToString("\n") ?: text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setGroup(RESULTS_GROUP)
            .build()
        val mgr = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        mgr.notify(report.jobId.hashCode(), n)
        resultsSummary(context, mgr)
    }

    /**
     * The summary that bundles the completion notices: posted once two or more are in the shade, so
     * syncs finishing together collapse into one group. A lone notice stays as it is.
     */
    private fun resultsSummary(context: Context, mgr: NotificationManager) {
        val children = runCatching { mgr.activeNotifications.toList() }.getOrDefault(emptyList())
            .filter { it.notification.group == RESULTS_GROUP && it.id != RESULTS_SUMMARY_ID }
        if (children.size < 2) return
        val pi = open(context, RESULTS_SUMMARY_ID, NotifTarget.Log, null)
        val title = context.getString(R.string.notif_channel_name)
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        children.mapNotNull { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) }.forEach { inbox.addLine(it) }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(inbox)
            .setNumber(children.size)
            .setGroup(RESULTS_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { mgr.notify(RESULTS_SUMMARY_ID, n) }
    }
}
