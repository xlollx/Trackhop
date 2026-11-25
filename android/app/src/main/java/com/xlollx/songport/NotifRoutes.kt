package com.xlollx.songport

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroups

/**
 * What the main screen opens for a notice's [NotifTarget]: a tab, and on it the page the notice is
 * about. Read here, apart from the screen, so each kind of notification has a test of where it leads.
 * [tab] -1 keeps the tab as it is (a web page or the update's flow open over the app).
 */
data class NotifRoute(
    val tab: Int,
    /** A sync's card, scrolled to and lit (JobFocus). */
    val focusJob: String? = null,
    /** A run's songs to review, by the report's id. */
    val review: String? = null,
    /** A multi sync's page. */
    val group: String? = null,
    /** The Unplayable tracks tool, on [unplayableProvider] (null: the first connected service it works with). */
    val unplayable: Boolean = false,
    val unplayableProvider: String? = null,
    /** Discover › Followed artists. */
    val followed: Boolean = false,
    /** A service's card on the Accounts tab (ConnectorFocus). */
    val connector: String? = null,
    /** The log, over Settings. */
    val log: Boolean = false,
    /** A web page, opened in the browser. */
    val link: String? = null,
    /** The new version's flow (its download on GitHub, Google Play's own flow). */
    val update: Boolean = false,
)

object NotifRoutes {
    /** Where [t] leads with the state [d] as it is now: a sync or group gone since leads to the list, a report gone to its sync. */
    fun resolve(t: NotifTarget, d: StoreData): NotifRoute = when (t) {
        is NotifTarget.Sync -> sync(t.jobId, d)
        is NotifTarget.Review ->
            if (d.reports.any { it.id == t.reportId }) NotifRoute(MainActivity.TAB_SYNCS, review = t.reportId) else sync(t.jobId, d)
        is NotifTarget.Group ->
            if (d.groups.any { it.id == t.groupId && Nway.isGroup(it) }) NotifRoute(MainActivity.TAB_SYNCS, group = t.groupId) else NotifRoute(MainActivity.TAB_SYNCS)
        is NotifTarget.Unplayable -> NotifRoute(MainActivity.TAB_TOOLS, unplayable = true, unplayableProvider = t.providerId)
        NotifTarget.Followed -> NotifRoute(MainActivity.TAB_DISCOVER, followed = true)
        is NotifTarget.Link -> NotifRoute(-1, link = t.url)
        is NotifTarget.Account -> NotifRoute(MainActivity.TAB_ACCOUNTS, connector = t.connectorId)
        NotifTarget.Settings -> NotifRoute(MainActivity.TAB_SETTINGS)
        NotifTarget.Log -> NotifRoute(MainActivity.TAB_SETTINGS, log = true)
        NotifTarget.Tools -> NotifRoute(MainActivity.TAB_TOOLS)
        is NotifTarget.Update -> NotifRoute(-1, update = true, link = t.url)
    }

    /** A sync: its multi sync's page when it is one of a group's, else its card; the list when it is gone. */
    private fun sync(jobId: String, d: StoreData): NotifRoute {
        val job = d.jobs.firstOrNull { it.id == jobId } ?: return NotifRoute(MainActivity.TAB_SYNCS)
        val gid = SharedGroups.sharedSide(job)
        if (gid != null && d.groups.any { it.id == gid && Nway.isGroup(it) }) return NotifRoute(MainActivity.TAB_SYNCS, group = gid)
        return NotifRoute(MainActivity.TAB_SYNCS, focusJob = jobId)
    }

    /**
     * The target of a finished run's notification: a one-off transfer's songs to review, else Tools
     * (its Recent transfers); any other run as [NotifTarget.forRun] says (its review, its group, its sync).
     */
    fun forResult(d: StoreData, job: com.xlollx.songport.model.SyncJob?, report: com.xlollx.songport.model.SyncReport): NotifTarget = when {
        job == null -> NotifTarget.Sync(report.jobId)
        job.oneOff && com.xlollx.songport.sync.OneOffs.toReview(report) > 0 -> NotifTarget.Review(job.id, report.id)
        job.oneOff -> NotifTarget.Tools
        else -> NotifTarget.forRun(d, job, report)
    }

    /**
     * A PendingIntent's request code for [target]: notifications whose taps lead to different places
     * never share one, so Android never hands one notification's extras to another.
     */
    fun requestCode(base: Int, target: NotifTarget): Int = base * 31 + target.encode().hashCode()
}
