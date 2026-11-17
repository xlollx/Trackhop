package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import java.time.ZoneId
import java.util.UUID

/**
 * Saved schedules (Settings › Schedules): a timing kept once, with a name if the user gives it one,
 * and used by any number of syncs. A sync that uses one is LINKED to it ([SyncJob.scheduleId]): it
 * carries the schedule's timing in its own fields (so everything that reads a sync's timing, here, on
 * the server and in an older Trackhop, reads it as ever), and the store keeps the two the same
 * ([normalized], run by FileStore on every change):
 *
 * - the schedule is edited: every linked sync takes the new timing, in the same change;
 * - the schedule is deleted: the syncs keep the timing they have, as their own;
 * - a linked sync's timing is changed by itself (the editor's "Set by hand", the spreading of a
 *   crowded hour, the server's page): it is detached, with the timing it was given.
 *
 * "Manual only" is not a schedule. Wi-Fi only is not part of one: it stays with each sync. A
 * schedule by interval has no clock of its own: each sync counts from its own last run.
 */
object Schedules {
    const val MAX_NAME = 40

    /** A timing: how often in minutes, the time of day, the days (as [Timing.apply] keeps them). */
    data class Spec(val everyMinutes: Int, val at: String?, val days: List<Int>) {
        /** Runs by itself: anything but "manual only". */
        val automatic: Boolean get() = everyMinutes > 0 || days.isNotEmpty()
    }

    private val BLANK = SyncJob("", "", PlaylistRef("", null, ""), PlaylistRef("", null, ""))

    /** The timing as a sync would hold it: steps clamped, seven days made daily, a time only where one is used. */
    fun spec(everyMinutes: Int, at: String?, days: List<Int>): Spec = specOf(Timing.apply(BLANK, everyMinutes, at, days))

    /** [job]'s own timing (an old sync's [SyncJob.schedule] read as its interval). */
    fun specOf(job: SyncJob): Spec = Spec(Timing.interval(job), job.at, job.days)

    fun specOf(s: SavedSchedule): Spec = spec(s.everyMinutes, s.at, s.days)

    /** A schedule that can be saved: it runs by itself. */
    fun valid(s: SavedSchedule): Boolean = s.id.isNotBlank() && specOf(s).automatic

    /** A new schedule, or null for "manual only". */
    fun make(name: String, everyMinutes: Int, at: String?, days: List<Int>, newId: () -> String = { "s-" + UUID.randomUUID().toString().take(8) }): SavedSchedule? {
        val t = spec(everyMinutes, at, days)
        if (!t.automatic) return null
        return SavedSchedule(newId(), name.trim().take(MAX_NAME), t.everyMinutes, t.at, t.days)
    }

    fun find(d: StoreData, id: String?): SavedSchedule? = id?.let { i -> d.settings.savedSchedules.firstOrNull { it.id == i } }

    /** [job] with [s]'s timing and linked to it. */
    fun link(job: SyncJob, s: SavedSchedule): SyncJob = specOf(s).let { t -> Timing.apply(job, t.everyMinutes, t.at, t.days).copy(scheduleId = s.id) }

    /** [job] with the timing it has, as its own: it no longer follows a saved schedule. */
    fun detach(job: SyncJob): SyncJob = if (job.scheduleId == null) job else job.copy(scheduleId = null)

    /** [job] has exactly [s]'s timing. */
    fun matches(job: SyncJob, s: SavedSchedule): Boolean = specOf(job) == specOf(s) && job.everyMinutes != null

    /**
     * The syncs that use [id], as the user counts them: the "↔" half of a two-way pair follows its
     * sync and is not counted beside it.
     */
    fun usedBy(d: StoreData, id: String): List<SyncJob> {
        val linked = d.syncs.filter { it.scheduleId == id }
        val ids = linked.mapTo(HashSet()) { it.id }
        return linked.filterNot { it.twin && it.linkedJobId in ids }
    }

    /**
     * Adds [s], or replaces the schedule with its id: every sync linked to it takes its timing in the
     * same change ([normalized]). A schedule that would be "manual only" is not saved.
     */
    fun save(d: StoreData, s: SavedSchedule): StoreData {
        val t = specOf(s)
        if (s.id.isBlank() || !t.automatic) return d
        val clean = s.copy(name = s.name.trim().take(MAX_NAME), everyMinutes = t.everyMinutes, at = t.at, days = t.days)
        val list = d.settings.savedSchedules
        val next = if (list.any { it.id == clean.id }) list.map { if (it.id == clean.id) clean else it } else list + clean
        return normalized(d, d.copy(settings = d.settings.copy(savedSchedules = next)))
    }

    /** Deletes the schedule [id]: the syncs that used it keep running at the same times, as their own timing. */
    fun delete(d: StoreData, id: String): StoreData =
        normalized(d, d.copy(settings = d.settings.copy(savedSchedules = d.settings.savedSchedules.filter { it.id != id })))

    /**
     * [made] with the syncs and the saved schedules in line (see the head of this file); [before] is
     * the data [made] was made from, null when it was just read from disk. A linked sync whose timing
     * is not its schedule's follows the schedule when the schedule changed or the link is new, and is
     * detached, keeping its timing, otherwise. Both halves of a two-way pair share the link.
     */
    fun normalized(before: StoreData?, made: StoreData): StoreData {
        val all = made.settings.savedSchedules
        if (all.isEmpty() && made.jobs.none { it.scheduleId != null }) return made
        val schedules = all.filter { valid(it) }.distinctBy { it.id }
        val byId = schedules.associateBy { it.id }
        val was = before?.settings?.savedSchedules?.associateBy { it.id }.orEmpty()
        val jobsWere = before?.jobs?.associateBy { it.id }.orEmpty()
        val jobs = made.jobs.map { j ->
            val id = j.scheduleId ?: return@map j
            val s = byId[id]
            when {
                // Deleted: the sync keeps its timing, as its own. A radio sync runs by its window, never by a schedule.
                s == null || j.radio != null -> j.copy(scheduleId = null)
                matches(j, s) -> j
                before != null && (was[id] != s || jobsWere[j.id]?.scheduleId != id) -> link(j, s)
                else -> j.copy(scheduleId = null)
            }
        }
        return if (jobs == made.jobs && schedules == all) made else made.copy(jobs = jobs, settings = made.settings.copy(savedSchedules = schedules))
    }

    /** The timing in English, as [Timing.describe]: "daily at 07:30", "every 6 h", "Sat, Sun at 10:00". */
    fun describe(s: SavedSchedule): String = Timing.describe(link(BLANK, s))

    /** How the schedule is listed: its name, or its timing when it has none. */
    fun title(s: SavedSchedule): String = s.name.ifBlank { describe(s) }

    /**
     * The next time a schedule that runs at a time of day (daily, or on days of the week) comes round,
     * for the library's "Next run". Null for a schedule by interval, of hours or of days: it has no
     * clock of its own, each sync runs within the interval of its own last run ([Timing.nextRun]).
     */
    fun nextRun(s: SavedSchedule, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val t = specOf(s)
        if (t.days.isEmpty() && t.everyMinutes != 1440) return null
        val time = Timing.parseAt(t.at) ?: java.time.LocalTime.of(9, 0)
        var day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(8) {
            val at = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if ((t.days.isEmpty() || day.dayOfWeek.value in t.days) && at > now) return at
            day = day.plusDays(1)
        }
        return null
    }
}
