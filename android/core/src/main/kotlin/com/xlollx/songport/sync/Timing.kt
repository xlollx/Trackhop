package com.xlollx.songport.sync

import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a sync runs by itself. A sync has either an interval (the five old choices, or any number of
 * minutes from an hour to a week) or a time of day ("07:30") with, optionally, the days of the week
 * ("Monday, Wednesday, Friday"). Old syncs carry only [SyncJob.schedule] and keep working as they were.
 */
object Timing {
    /** The steps the slider stops at, in minutes: manual, 1–23 hours, 1–6 days, a week. */
    val STEPS: List<Int> = listOf(0) + (1..23).map { it * 60 } + (1..6).map { it * 1440 } + listOf(10080)

    const val MIN_MINUTES = 60
    const val MAX_MINUTES = 10080

    /** The interval in minutes (0 = manual). */
    fun interval(job: SyncJob): Int =
        job.everyMinutes?.coerceIn(0, MAX_MINUTES)?.let { if (it in 1 until MIN_MINUTES) MIN_MINUTES else it } ?: job.schedule.minutes.toInt()

    /** Runs by itself at all. */
    fun scheduled(job: SyncJob): Boolean = job.enabled && (interval(job) > 0 || job.days.isNotEmpty())

    /** Runs at a time of day (a day or more apart, or on chosen days), so it waits for a clock time. */
    fun timed(job: SyncJob): Boolean = job.days.isNotEmpty() || (job.at != null && interval(job) >= 1440)

    fun parseAt(at: String?): LocalTime? = at?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }

    /**
     * The next run after the last one ([SyncJob.lastRunEpoch]), or null for a manual sync. A sync that
     * never ran is due now when it has an interval, and at its first time of day when it is timed.
     */
    fun nextRun(job: SyncJob, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!scheduled(job)) return null
        val last = job.lastRunEpoch
        if (!timed(job)) return if (last <= 0) now else last + interval(job) * 60_000L
        val time = parseAt(job.at) ?: LocalTime.of(9, 0)
        // From when the next one may come: right away for a sync that never ran; otherwise the interval
        // after the last run, less half a day, so a run that came late does not push the next one a day on.
        val from = if (last <= 0) now else if (job.days.isNotEmpty()) last + 60_000L
            else last + interval(job) * 60_000L - 12 * 3_600_000L
        var day: LocalDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        repeat(15) {
            val at: ZonedDateTime = day.atTime(time).atZone(zone)
            val ok = job.days.isEmpty() || day.dayOfWeek.value in job.days
            if (ok && at.toInstant().toEpochMilli() >= from) return at.toInstant().toEpochMilli()
            day = day.plusDays(1)
        }
        return null
    }

    fun isDue(job: SyncJob, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        nextRun(job, now, zone)?.let { now >= it } ?: false

    /** The closest of the old choices, for what still reads [SyncJob.schedule] (the app's old widget, exports). */
    fun legacy(minutes: Int): Schedule = when {
        minutes <= 0 -> Schedule.MANUAL
        minutes < 360 -> Schedule.HOURLY
        minutes < 1440 -> Schedule.EVERY_6H
        minutes < 10080 -> Schedule.DAILY
        else -> Schedule.WEEKLY
    }

    /** [job] with a new timing; [SyncJob.schedule] kept as the closest old choice. */
    fun apply(job: SyncJob, minutes: Int, at: String?, days: List<Int>): SyncJob {
        var m = if (minutes in 1 until MIN_MINUTES) MIN_MINUTES else minutes.coerceIn(0, MAX_MINUTES)
        var d = days.filter { it in 1..7 }.distinct().sorted()
        // Every day of the week is daily.
        if (d.size == 7) { d = emptyList(); m = 1440 }
        return job.copy(
            schedule = if (d.size == 1) Schedule.WEEKLY else if (d.isNotEmpty()) Schedule.DAILY else legacy(m),
            everyMinutes = m,
            at = if (d.isNotEmpty() || m >= 1440) (parseAt(at) ?: LocalTime.of(9, 0)).toString() else null,
            days = d,
        )
    }

    private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** The step's name in English (the web page and the command line): "manual", "every 3 h", "every 2 days", "weekly". */
    fun stepName(minutes: Int): String = when {
        minutes <= 0 -> "manual"
        minutes == 60 -> "hourly"
        minutes < 1440 -> "every ${minutes / 60} h"
        minutes == 1440 -> "daily"
        minutes < 10080 -> "every ${minutes / 1440} days"
        else -> "weekly"
    }

    /** The whole timing in English: "every 3 h", "daily at 07:30", "every Sat at 09:00", "Mon, Wed, Fri at 08:00". */
    fun describe(job: SyncJob): String {
        if (job.days.size == 1) return "every " + DAY_NAMES[(job.days[0] - 1).coerceIn(0, 6)] + " at " + (job.at ?: "09:00")
        if (job.days.isNotEmpty()) return job.days.joinToString(", ") { DAY_NAMES[(it - 1).coerceIn(0, 6)] } + " at " + (job.at ?: "09:00")
        val name = stepName(interval(job))
        return if (timed(job) && job.at != null) "$name at ${job.at}" else name
    }

    /** Several syncs that reach one service at about the same time: what the Syncs screen warns about. */
    data class Crowd(val service: String, val at: String, val jobIds: List<String>)

    /** Gaps the spreading tries, in minutes: the shortest that leaves no crowd wins. */
    private val SPREAD_GAPS = listOf(15, 20, 30, 45, 60)

    /**
     * The largest group of at least [min] scheduled syncs that read or write the same service and
     * start within [windowMin] minutes of each other on one same day of the week, or null. Day by
     * day, so a weekly Monday sync at 09:00 meets the daily ones at 09:00 on Mondays only (issue #56:
     * eleven syncs from one service at 09:00, and the service turned part of them away). Only the
     * services [sensitive] says turn bursts away; files never. Syncs without a time of day (every
     * few hours) have no time to plan and are left out; one every few days counts on every day.
     */
    fun crowded(jobs: List<SyncJob>, windowMin: Int = 30, min: Int = 4, sensitive: (String) -> Boolean = { true }): Crowd? {
        class Slot(val job: SyncJob, val minute: Int, val days: Set<Int>)
        val slots = jobs.filter { scheduled(it) && timed(it) }.mapNotNull { j ->
            val t = parseAt(j.at) ?: return@mapNotNull null
            Slot(j, t.hour * 60 + t.minute, j.days.toSet().ifEmpty { (1..7).toSet() })
        }
        var best: Crowd? = null
        val services = slots.flatMap { listOf(it.job.source.provider, it.job.target.provider) }.toSet() - LOCAL
        for (service in services.filter(sensitive)) {
            val mine = slots.filter { it.job.source.provider == service || it.job.target.provider == service }.sortedBy { it.minute }
            for (day in 1..7) {
                val today = mine.filter { day in it.days }
                for (first in today) {
                    val group = today.filter { it.minute in first.minute..first.minute + windowMin }
                    if (group.size >= min && group.size > (best?.jobIds?.size ?: 0))
                        best = Crowd(service, first.job.at!!.trim(), group.map { it.job.id })
                }
            }
        }
        return best
    }

    /**
     * New times for the syncs of [crowd], for the user to confirm: the first keeps its time, each next
     * one starts a gap later (15 minutes, longer when that still meets other syncs of the service),
     * kept within the same day. The two halves of a two-way pair move together. Days, frequency and
     * everything else stay. Only the syncs whose time changes are in the answer, in [jobs]' order.
     */
    fun spread(jobs: List<SyncJob>, crowd: Crowd, sensitive: (String) -> Boolean = { true }): Map<String, String> {
        val group = jobs.filter { it.id in crowd.jobIds }
        // One unit per pair: a half whose partner is in the group follows it.
        val units = group.filter { j -> j.linkedJobId == null || group.none { it.id == j.linkedJobId } || j.id < j.linkedJobId }
        val start = (parseAt(crowd.at) ?: LocalTime.of(9, 0)).let { it.hour * 60 + it.minute }
        var answer: Map<String, String> = emptyMap()
        for (gap in SPREAD_GAPS) {
            val first = minOf(start, 1439 - (units.size - 1) * gap)
            if (first < 0) break
            val times = HashMap<String, String>()
            units.forEachIndexed { i, u ->
                val m = first + i * gap
                val at = String.format(java.util.Locale.ROOT, "%02d:%02d", m / 60, m % 60)
                times[u.id] = at
                u.linkedJobId?.let { times[it] = at }
            }
            answer = times.filter { (id, at) -> group.firstOrNull { it.id == id }?.at?.trim() != at }
            val after = jobs.map { j -> times[j.id]?.let { j.copy(at = it) } ?: j }
            val left = crowded(after, sensitive = sensitive)
            if (left == null || left.jobIds.none { it in crowd.jobIds }) break
        }
        return jobs.mapNotNull { j -> answer[j.id]?.let { j.id to it } }.toMap()
    }

    /** Services on the phone itself: files. */
    private val LOCAL = setOf("csv")
}
