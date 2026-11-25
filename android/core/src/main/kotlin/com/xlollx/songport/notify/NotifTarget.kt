package com.xlollx.songport.notify

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where a notice leads when it is tapped: the app's notification, its entry in the bell, and the
 * server's Notifications page all open the same place. Kept as a short text ("sync:<id>") so it
 * travels in an Intent's extras and in the history's file; [parse] reads it back, null when it is
 * not one of ours (an older or a damaged entry opens nothing rather than the wrong page).
 */
sealed class NotifTarget {
    /** A sync's card with its last run; the platform opens its group's page when it belongs to a multi sync. */
    data class Sync(val jobId: String) : NotifTarget()
    /** A run's songs to review. */
    data class Review(val jobId: String, val reportId: String) : NotifTarget()
    /** A multi sync's page. */
    data class Group(val groupId: String) : NotifTarget()
    /** The Unplayable tracks tool, on [providerId] when known. */
    data class Unplayable(val providerId: String?) : NotifTarget()
    /** Discover › Followed artists. */
    data object Followed : NotifTarget()
    /** A web page (a concert's tickets, a record for sale): http(s) only. */
    data class Link(val url: String) : NotifTarget()
    /** A service's card in Accounts (Services on the server): sign in again. */
    data class Account(val connectorId: String) : NotifTarget()
    /** Settings (the backup folder gone, the state damaged…). */
    data object Settings : NotifTarget()
    /** The log: several runs at once. */
    data object Log : NotifTarget()
    /** Tools (a one-off transfer with nothing to review). */
    data object Tools : NotifTarget()
    /** The new version's flow: the build's own (download, the store's update, or only the notice). */
    data class Update(val url: String?) : NotifTarget()

    fun encode(): String = when (this) {
        is Sync -> "sync:${e(jobId)}"
        is Review -> "review:${e(jobId)}:${e(reportId)}"
        is Group -> "group:${e(groupId)}"
        is Unplayable -> "unplayable:${e(providerId.orEmpty())}"
        Followed -> "followed"
        is Link -> "link:${e(url)}"
        is Account -> "account:${e(connectorId)}"
        Settings -> "settings"
        Log -> "log"
        Tools -> "tools"
        is Update -> "update:${e(url.orEmpty())}"
    }

    override fun toString(): String = encode()

    companion object {
        /**
         * Where a run's notice leads: its songs to review when it has some, else its multi sync's page
         * when it is one of a group's syncs, else the sync itself.
         */
        fun forRun(d: com.xlollx.songport.data.StoreData, job: com.xlollx.songport.model.SyncJob, report: com.xlollx.songport.model.SyncReport): NotifTarget {
            if (report.unmatchedTracks.isNotEmpty() || report.reviewTracks.isNotEmpty()) return Review(job.id, report.id)
            val gid = com.xlollx.songport.shared.SharedGroups.sharedSide(job)
            if (gid != null && d.groups.any { it.id == gid && com.xlollx.songport.shared.Nway.isGroup(it) }) return Group(gid)
            return Sync(job.id)
        }

        private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun d(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull()

        fun parse(s: String?): NotifTarget? {
            if (s.isNullOrBlank()) return null
            val kind = s.substringBefore(':')
            val args = if (':' in s) s.substringAfter(':').split(':').map { d(it) ?: return null } else emptyList()
            fun one(): String? = args.singleOrNull()
            return when (kind) {
                "sync" -> one()?.takeIf { it.isNotEmpty() }?.let { Sync(it) }
                "review" -> if (args.size == 2 && args.all { it.isNotEmpty() }) Review(args[0], args[1]) else null
                "group" -> one()?.takeIf { it.isNotEmpty() }?.let { Group(it) }
                "unplayable" -> Unplayable(one()?.ifEmpty { null })
                "followed" -> Followed
                "link" -> one()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { Link(it) }
                "account" -> one()?.takeIf { it.isNotEmpty() }?.let { Account(it) }
                "settings" -> Settings
                "log" -> Log
                "tools" -> Tools
                "update" -> Update(one()?.ifEmpty { null })
                else -> null
            }
        }
    }
}
