package com.xlollx.songport.shared

import com.xlollx.songport.model.SyncReport

/**
 * A multi sync's runs, as its card's figures and its run history show them (as a sync's own card
 * shows its reports): its services' reports put together run by run. A "Sync now" on the group runs
 * its syncs one after the other, so the reports of one run follow each other closely; a sync seen
 * twice, or a long pause, starts the next run. A service run alone by its schedule is a run of its own.
 * Kotlin puro: coperto dai test.
 */
object NwayHistory {
    /** Longer than this between the end of one sync and the start of the next: another run. */
    const val GAP_MS = 10 * 60_000L

    /** The group's runs, oldest first, each the reports of its syncs in the order they ran (none still running). */
    fun batches(reports: List<SyncReport>, members: List<Nway.Member>): List<List<SyncReport>> {
        val ids = members.flatMapTo(HashSet()) { listOfNotNull(it.down.id, it.up?.id) }
        val mine = reports.filter { it.jobId in ids && !it.partial }.sortedBy { it.startedEpoch }
        val out = ArrayList<List<SyncReport>>()
        var now = ArrayList<SyncReport>()
        for (r in mine) {
            val prev = now.lastOrNull()
            if (prev != null && (now.any { it.jobId == r.jobId } || r.startedEpoch - (prev.startedEpoch + prev.durationMs) > GAP_MS)) {
                out += now; now = ArrayList()
            }
            now += r
        }
        if (now.isNotEmpty()) out += now
        return out
    }

    /**
     * One run of the group as one report: what its syncs added, removed and did not find, added up
     * (their lists one after the other), the source's size, and failed when any of them failed.
     * Added and removed count the services' playlists only: the syncs in [intoHub] write the group's
     * own list (the hub), which nobody sees, so its changes would count a song once more than the
     * card's added/removed lists show.
     */
    fun sum(batch: List<SyncReport>, intoHub: Set<String> = emptySet()): SyncReport {
        val first = batch.first()
        val failed = batch.firstOrNull { !it.ok && !it.offline } ?: batch.firstOrNull { !it.ok }
        val playlists = batch.filterNot { it.jobId in intoHub }
        return SyncReport(
            id = "group:" + first.id, jobId = "", jobName = first.jobName,
            startedEpoch = first.startedEpoch,
            durationMs = batch.maxOf { it.startedEpoch + it.durationMs } - first.startedEpoch,
            sourceCount = batch.maxOf { it.sourceCount },
            added = playlists.sumOf { it.added }, removed = playlists.sumOf { it.removed },
            unmatched = batch.flatMap { it.unmatched }, unmatchedTracks = batch.flatMap { it.unmatchedTracks },
            reviewTracks = batch.flatMap { it.reviewTracks },
            addedTracks = playlists.flatMap { it.addedTracks }, removedTracks = playlists.flatMap { it.removedTracks },
            heldBack = batch.sumOf { it.heldBack },
            notAdded = batch.flatMap { it.notAdded },
            error = failed?.error, netError = failed?.netError,
        )
    }

    /** The group's last runs, each as one report ([sum]), oldest first: at most [n]. */
    fun runs(reports: List<SyncReport>, members: List<Nway.Member>, n: Int = 10): List<SyncReport> =
        members.mapNotNullTo(HashSet()) { it.up?.id }.let { intoHub -> batches(reports, members).takeLast(n).map { sum(it, intoHub) } }
}
