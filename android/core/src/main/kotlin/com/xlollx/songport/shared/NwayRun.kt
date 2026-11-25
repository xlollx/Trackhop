package com.xlollx.songport.shared

import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.SyncState

/**
 * A multi sync while it runs: which of its services is being synced now, which are done, which
 * wait, which failed. Read from the syncs running ([SyncState.running]) and, for a "Sync now" on
 * the whole group, from the worker's record of the batch ([SyncState.Batch]). Kotlin puro: coperto dai test.
 */
object NwayRun {
    /** IDLE: not part of what runs now (one service run alone, by its own schedule: the others just stand by). */
    enum class Phase { DONE, NOW, WAITING, FAILED, IDLE }

    data class Service(
        val member: Nway.Member, val phase: Phase,
        /** The sync of this service running now ([Phase.NOW]). */
        val job: SyncJob? = null,
        /** How its syncs of this run ended, added up ([Phase.DONE]), or the one that failed ([Phase.FAILED]). */
        val result: SyncState.JobResult? = null,
    ) {
        val provider: String get() = member.playlist.provider
    }

    data class State(
        val services: List<Service>,
        /** The service being synced now; none for the moment between two syncs of a batch. */
        val now: Service?,
        /** Where the run stands among the services, from 1 ("3 of 6"); 0 when a service runs alone. */
        val place: Int, val total: Int,
        /** How far the whole run is, 0 to 1; null when not known (the bar then just moves). */
        val fraction: Float?,
        /** The step of the sync running now. */
        val progress: Progress?,
    )

    /** Null when nothing of the group runs. [members]: the source first, as the group's page lists them. */
    fun of(members: List<Nway.Member>, running: Map<String, Progress?>, batch: SyncState.Batch?): State? {
        fun jobs(m: Nway.Member) = listOfNotNull(m.up, m.down)
        val ids = members.flatMap { jobs(it) }.mapTo(HashSet()) { it.id }
        val live = running.keys.any { it in ids }
        if (!live && batch == null) return null
        val services = members.map { m ->
            val mine = jobs(m)
            val active = mine.firstOrNull { it.id in running }
            val asked = mine.filter { batch != null && it.id in batch.order }
            val ended = asked.mapNotNull { j -> batch?.outcomes?.get(j.id)?.takeIf { j.id in batch.finished } }
            val failed = ended.firstOrNull { it.failed }
            when {
                active != null -> Service(m, Phase.NOW, job = active)
                failed != null -> Service(m, Phase.FAILED, result = failed)
                asked.isNotEmpty() && ended.size == asked.size -> Service(m, Phase.DONE, result = ended.reduce { a, b ->
                    a.copy(ok = a.ok && b.ok, offline = a.offline || b.offline, added = a.added + b.added, removed = a.removed + b.removed, notFound = a.notFound + b.notFound, held = a.held + b.held)
                }.let { r ->
                    // Added and removed on this service's playlist only (its down sync), as the card's
                    // figures and lists count them: what its up sync wrote went to the group's own list.
                    val down = batch?.outcomes?.get(m.down.id)?.takeIf { m.down.id in batch.finished }
                    r.copy(added = down?.added ?: 0, removed = down?.removed ?: 0)
                })
                asked.isNotEmpty() -> Service(m, Phase.WAITING)
                else -> Service(m, Phase.IDLE)
            }
        }
        val now = services.firstOrNull { it.phase == Phase.NOW }
        val progress = now?.job?.let { running[it.id] }
        if (batch == null) return State(services, now, 0, services.size, progress?.percent?.let { it / 100f }, progress)
        val inRun = services.filter { it.phase != Phase.IDLE }
        val over = inRun.count { it.phase == Phase.DONE || it.phase == Phase.FAILED }
        val steps = batch.order.size
        val fraction = if (steps == 0) null else ((batch.finished.size + (progress?.percent ?: 0) / 100f) / steps).coerceIn(0f, 1f)
        return State(services, now, (over + 1).coerceAtMost(inRun.size), inRun.size, fraction, progress)
    }

    /** The members with the group's source first ([sourceJobId]: the sync the group's card stands on), the others in their order. */
    fun sourceFirst(members: List<Nway.Member>, sourceJobId: String?): List<Nway.Member> =
        members.sortedBy { if (it.down.id == sourceJobId) 0 else 1 }
}
