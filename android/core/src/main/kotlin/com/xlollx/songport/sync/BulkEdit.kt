package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.SharedGroups

/**
 * Several syncs changed together (the Syncs list's selection, issue #68). Each function answers
 * the whole data with the change made, to be saved as one change (FileStore.commit): all the syncs
 * or none. What the user did not touch is left as each sync has it ("Different values" stay
 * different). A two-way pair is one sync to the user: the half not selected follows the one that is.
 */
object BulkEdit {
    /** The "How often" choices of the selection's Schedule sheet, in minutes: manual, hourly, every 6 hours, daily, weekly. */
    val FREQUENCIES = listOf(0, 60, 360, 1440, 10080)

    /**
     * A sync the selection can take: not one of a shared playlist (changed from its group) nor one
     * handed over to the server. A multi sync is taken by the sync that stands for it in the list
     * ([Nway.anchored]): what is changed there reaches all its services ([reach]).
     */
    fun selectable(d: StoreData, job: SyncJob): Boolean =
        !job.oneOff && (SharedGroups.sharedSide(job) == null || Nway.anchored(d, job) != null) && job.id !in d.handedOver

    /** The selected syncs, in the list's order, without what cannot be selected. */
    fun selected(d: StoreData, ids: Set<String>): List<SyncJob> = d.syncs.filter { it.id in ids && selectable(d, it) }

    /** The multi syncs among [ids] (each by the sync that stands for it), by their group's id. */
    fun groups(d: StoreData, ids: Set<String>): Set<String> =
        selected(d, ids).mapNotNullTo(LinkedHashSet()) { Nway.anchored(d, it)?.id }

    /** [ids], the other halves of their two-way pairs and every sync of their multi syncs: what a change or a deletion reaches. */
    fun reach(d: StoreData, ids: Set<String>): Set<String> {
        val mine = selected(d, ids).mapTo(LinkedHashSet()) { it.id }
        d.jobs.forEach { j -> if (j.id in mine) j.linkedJobId?.let { mine += it } else if (j.linkedJobId in mine) mine += j.id }
        val whole = groups(d, ids)
        if (whole.isNotEmpty()) d.jobs.forEach { j -> if (SharedGroups.sharedSide(j) in whole) mine += j.id }
        return d.jobs.filter { it.id in mine }.mapTo(LinkedHashSet()) { it.id }
    }

    /** The one value all of [jobs] share, or null when they differ ("Different values") or there are none. */
    fun <T : Any> common(jobs: List<SyncJob>, of: (SyncJob) -> T): T? = jobs.map(of).distinct().singleOrNull()

    /** The values [jobs] have, each with how many syncs have it, the most common first: "Daily in two, Weekly in one". */
    fun <T> tally(jobs: List<SyncJob>, of: (SyncJob) -> T): List<Pair<T, Int>> =
        jobs.groupingBy(of).eachCount().toList().sortedByDescending { it.second }

    /**
     * What the Schedule sheet changes; a null field was not touched. [scheduleId] links the syncs to
     * that saved schedule; [every] (one of [FREQUENCIES]) or [at] set by hand give each sync a timing
     * of its own, started from the schedule picked when there is one. [wifiOnly] is each sync's own.
     */
    data class ScheduleChange(val scheduleId: String? = null, val every: Int? = null, val at: String? = null, val wifiOnly: Boolean? = null) {
        val isEmpty: Boolean get() = scheduleId == null && every == null && at == null && wifiOnly == null
        val byHand: Boolean get() = every != null || at != null
    }

    /** A sync that runs by a schedule: a radio sync runs by its window instead. */
    fun scheduled(job: SyncJob): Boolean = job.radio == null

    /**
     * [change] made to the syncs [ids]. [weekday] (1 = Monday) is the day a sync made weekly runs on
     * when it had no single day of its own. A sync whose timing comes out as it was stays as it was,
     * linked to its saved schedule if it is.
     */
    fun schedule(d: StoreData, ids: Set<String>, change: ScheduleChange, weekday: Int): StoreData {
        if (change.isEmpty) return d
        val picked = Schedules.find(d, change.scheduleId)
        val made = HashMap<String, SyncJob>()
        for (j in selected(d, ids)) {
            var n = j
            if (scheduled(j)) {
                if (picked != null && !change.byHand) n = Schedules.link(n, picked)
                else if (change.byHand) {
                    val base = picked?.let { Schedules.specOf(it) } ?: Schedules.specOf(j)
                    val every = change.every ?: base.everyMinutes
                    val days = when {
                        change.every == null -> base.days
                        change.every >= 10080 -> base.days.takeIf { it.size == 1 } ?: listOf(weekday.coerceIn(1, 7))
                        else -> emptyList()
                    }
                    val t = Schedules.spec(every, change.at ?: base.at, days)
                    if (t != Schedules.specOf(j)) n = Timing.apply(n, t.everyMinutes, t.at, t.days).copy(scheduleId = null)
                }
            }
            if (change.wifiOnly != null) n = n.copy(wifiOnly = change.wifiOnly)
            if (n != j) made[j.id] = n
        }
        if (made.isEmpty()) return d
        // The other half of a pair runs with its sync: same timing, same link, same Wi-Fi.
        for (j in d.jobs) {
            val half = made[j.linkedJobId ?: continue] ?: continue
            if (j.id in made || j.radio != null) continue
            val t = Schedules.specOf(half)
            made[j.id] = Timing.apply(j, t.everyMinutes, t.at, t.days).copy(scheduleId = half.scheduleId, wifiOnly = half.wifiOnly)
        }
        // Every service of a multi sync runs when the group does: its syncs take the timing of the one selected.
        for ((anchorId, g) in Nway.anchors(d)) {
            val lead = made[anchorId] ?: continue
            val t = Schedules.specOf(lead)
            for (j in d.jobs) if (j.id != anchorId && SharedGroups.sharedSide(j) == g.id) {
                made[j.id] = Timing.apply(j, t.everyMinutes, t.at, t.days).copy(scheduleId = lead.scheduleId, wifiOnly = lead.wifiOnly)
            }
        }
        return d.copy(jobs = d.jobs.map { made[it.id] ?: it })
    }

    /** A sync whose removals can be set here: not a two-way pair (its own switch), an archive or a radio sync (they never remove). */
    fun takesRemovals(job: SyncJob): Boolean = job.linkedJobId == null && !job.twin && !job.archive && job.radio == null

    /** "Mirror removals" ([mirror]) or "Add only" for the syncs [ids] that take it ([takesRemovals]); the others stay. */
    fun removals(d: StoreData, ids: Set<String>, mirror: Boolean): StoreData {
        val mine = selected(d, ids).filter { takesRemovals(it) }.mapTo(HashSet()) { it.id }
        return with(d, d.jobs.map { j ->
            if (j.id !in mine || j.mirrorRemovals == mirror) j
            else j.copy(mirrorRemovals = mirror, followsChanges = j.followsChanges && mirror)
        })
    }

    /** The syncs [ids] turned on or off, both halves of a pair together. Turned on here, this device schedules them. */
    fun enabled(d: StoreData, ids: Set<String>, on: Boolean): StoreData {
        val mine = reach(d, ids)
        return with(d, d.jobs.map { j ->
            if (j.id !in mine || (j.enabled == on && (!on || j.scheduledOn == null))) j
            else j.copy(enabled = on, scheduledOn = if (on) null else j.scheduledOn)
        })
    }

    /**
     * The syncs that write to [playlistIds] of [provider], playlists just deleted there: what
     * [offForDeleted] turns off. Only plain syncs: one of a shared playlist or of a multi sync (changed
     * from its group's page), and one handed over to the server, are left as they are.
     */
    fun writingTo(d: StoreData, provider: String, playlistIds: Set<String>): List<SyncJob> = d.syncs.filter { j ->
        j.target.provider == provider && j.target.playlistId?.let { it in playlistIds } == true &&
            SharedGroups.sharedSide(j) == null && j.id !in d.handedOver
    }

    /**
     * [writingTo] turned off, with the other half of a two-way pair (one switch for both, as on the
     * card): a sync to a playlist that is gone would only fail. Nothing else is touched.
     */
    fun offForDeleted(d: StoreData, provider: String, playlistIds: Set<String>): StoreData {
        val ids = writingTo(d, provider, playlistIds).mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return d
        val halves = d.jobs.mapNotNullTo(HashSet()) { j -> if (j.id in ids) j.linkedJobId else j.id.takeIf { j.linkedJobId in ids } }
        val off = ids + halves
        return with(d, d.jobs.map { j -> if (j.id in off && j.enabled) j.copy(enabled = false) else j })
    }

    /** [d] with [jobs], or [d] itself when nothing changed. */
    private fun with(d: StoreData, jobs: List<SyncJob>): StoreData = if (jobs == d.jobs) d else d.copy(jobs = jobs)

    /** The colour or label [pick] ([Labels.styled]) for the syncs [ids], both halves of a pair together. A multi sync wears it on the sync that stands for it. */
    fun color(d: StoreData, ids: Set<String>, pick: String?): StoreData {
        val whole = groups(d, ids)
        val mine = reach(d, ids).filterTo(LinkedHashSet()) { id -> id in ids || d.jobs.firstOrNull { it.id == id }?.let { SharedGroups.sharedSide(it) } !in whole }
        return Labels.styled(d, mine, pick, Labels.find(d.settings.labels, pick)?.name.orEmpty())
    }

    /** The ids whose scheduled work has to be brought in line after [before] became [after]. */
    fun rescheduled(before: StoreData, after: StoreData): List<String> {
        val was = before.jobs.associateBy { it.id }
        return after.jobs.filter { j ->
            val o = was[j.id] ?: return@filter true
            o.enabled != j.enabled || o.wifiOnly != j.wifiOnly || Schedules.specOf(o) != Schedules.specOf(j) || o.radio != j.radio
        }.map { it.id }
    }
}
