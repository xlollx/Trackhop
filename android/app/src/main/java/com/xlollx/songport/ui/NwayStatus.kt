package com.xlollx.songport.ui

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.shared.Nway

/**
 * How a multi sync stands, from its services' last runs, by the rules of every other card
 * ([cardStatus]): each of its syncs is read as a card would read it, and the most serious thing left
 * for the user is what the group says, with the service it is about. Kotlin puro: coperto dai test.
 */
internal object NwayStatus {
    /** One service of the group: the state its card would show, the sync and the run it comes from, and the count that goes with it. */
    data class Member(
        val member: Nway.Member, val status: CardStatus, val job: SyncJob, val report: SyncReport?,
        /** Removals on hold ([CardStatus.HELD_BACK]), songs to add next run ([CardStatus.NOT_ADDED]), not found ([CardStatus.NOT_FOUND]) or matches to check ([CardStatus.TO_CHECK]); else 0. */
        val count: Int = 0,
    ) {
        val provider: String get() = member.playlist.provider
    }

    /**
     * The group: [status] is its most serious state, [about] the services in it (one: the card names
     * it), [count] their removals on hold, songs not found or matches to check added up.
     */
    data class Group(
        val status: CardStatus, val about: List<Member>, val count: Int, val members: List<Member>,
        val last: Long, val paused: Boolean,
    ) {
        /** The one service the state is about, when it is one. */
        val provider: String? get() = about.map { it.provider }.distinct().singleOrNull()
    }

    /**
     * What waits for the user first, then what needs nothing from them. A real failure comes before
     * removals on hold, as on a card; no internet is not a failure (the sync runs again by itself),
     * so it only shows when nothing else is waiting.
     */
    private val SERIOUS = listOf(CardStatus.ERROR, CardStatus.HELD_BACK, CardStatus.INCOMPLETE, CardStatus.NOT_ADDED, CardStatus.NOT_FOUND, CardStatus.TO_CHECK, CardStatus.OFFLINE)

    private fun rank(s: CardStatus): Int = SERIOUS.indexOf(s).let { if (it < 0) SERIOUS.size else it }

    private fun report(d: StoreData, job: SyncJob): SyncReport? = d.reports.firstOrNull { it.id == job.lastReportId }

    /** One sync of a member, at rest. An empty source is no news here: a new playlist that only receives sends nothing. */
    private fun one(d: StoreData, job: SyncJob): Triple<CardStatus, SyncJob, SyncReport?> {
        val r = report(d, job)
        return Triple(cardStatus(isRunning = false, enabled = true, report = r, radio = true), job, r)
    }

    /** [m] at rest: the more serious of its two syncs (a target has one). */
    fun member(d: StoreData, m: Nway.Member): Member {
        val (status, job, r) = listOfNotNull(one(d, m.down), m.up?.let { one(d, it) }).minByOrNull { rank(it.first) }!!
        val count = when (status) {
            CardStatus.HELD_BACK -> listOfNotNull(report(d, m.down), m.up?.let { report(d, it) }).maxOf { it.heldBack }
            // Matched songs the service did not take this time: the next run adds them.
            CardStatus.NOT_ADDED -> r?.notAdded?.size ?: 0
            // What the service lacks is in the report of the sync that brings the group to it.
            CardStatus.NOT_FOUND -> r?.unmatchedTracks?.size ?: 0
            // Matches to double-check, as a card counts them (SyncFilter's "to fix" counts them too).
            CardStatus.TO_CHECK -> r?.reviewTracks?.size ?: 0
            else -> 0
        }
        return Member(m, status, job, r, count)
    }

    fun group(d: StoreData, members: List<Nway.Member>): Group {
        val jobs = members.flatMap { listOfNotNull(it.down, it.up) }
        val all = members.map { member(d, it) }
        val last = jobs.maxOfOrNull { it.lastRunEpoch } ?: 0L
        val paused = jobs.isNotEmpty() && jobs.none { it.enabled }
        val worst = all.minByOrNull { rank(it.status) }?.status
        val status = when {
            paused -> CardStatus.DISABLED
            worst == null || all.all { it.status == CardStatus.NEVER } -> CardStatus.NEVER
            worst in SERIOUS -> worst
            else -> CardStatus.OK
        }
        val about = if (status in SERIOUS) all.filter { it.status == status } else emptyList()
        return Group(status, about, about.sumOf { it.count }, all, last, paused)
    }

    /** The services that failed: the group's page lists them first, each leading to its fix. */
    fun failing(g: Group): List<Member> = g.members.filter { it.status == CardStatus.ERROR }
}
