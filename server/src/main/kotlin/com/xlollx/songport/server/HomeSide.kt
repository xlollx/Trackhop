package com.xlollx.songport.server

import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.UsageStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The home page's side column on a wide screen (below the syncs on a narrow one): the tracks added
 * day by day, the numbers, the latest runs and the services in use. Read from the run history the
 * store keeps; nothing is asked of the services.
 */
internal fun Web.homeSide(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val show = HomeSide.cards(env)
    if (HomeSide.place(env) == HomeSide.HIDDEN || show.isEmpty()) return ""
    val data = env.store.data
    val reports = data.reports.filter { !it.partial }
    val sb = StringBuilder("""<aside class="dash-side">""")
    if ("activity" in show) sb.append(activityCard(reports.map { it.startedEpoch to it.added }, now, zone))
    val s = UsageStats.compute(data, now, zone)
    if ("numbers" in show && !s.isEmpty) {
        sb.append("""<div class="card"><h3>Your numbers</h3><dl class="figures">""")
        sb.append("""<div><dt>Tracks moved</dt><dd>${s.added}</dd></div>""")
        sb.append("""<div><dt>This month</dt><dd>${s.addedThisMonth}</dd></div>""")
        s.matchPercent?.let { sb.append("""<div><dt>Found</dt><dd>$it%</dd></div>""") }
        sb.append("""<div><dt>Runs</dt><dd>${s.runs}</dd></div>""")
        sb.append("""</dl><a href="/tools/stats">All the numbers ›</a></div>""")
    }
    val latest = reports.sortedByDescending { it.startedEpoch }.take(5)
    if ("runs" in show && latest.isNotEmpty()) {
        sb.append("""<div class="card"><h3>Latest runs</h3><ul class="runs">""")
        latest.forEach { r ->
            val result = if (r.error != null) """<span class="err">failed</span>""" else """<span class="ok">+${r.added} −${r.removed}</span>"""
            sb.append("""<li><a href="/syncs/${q(r.jobId)}">${h(r.jobName)}</a> $result<br><span class="muted">${h(Html.date(r.startedEpoch))}</span></li>""")
        }
        sb.append("</ul></div>")
    }
    val services = Providers.connectors().filter { it.requiresAuth && it.isConnected(env) }
    if ("services" in show && services.isNotEmpty()) {
        sb.append("""<div class="card"><h3>Services</h3><ul class="services">""")
        services.forEach { p -> sb.append("""<li>${logo(p.id, 24)} ${h(p.label(env))}</li>""") }
        sb.append("""</ul><a href="/connectors">Manage ›</a></div>""")
    }
    sb.append("</aside>")
    return sb.toString()
}

/** Tracks added on each of the last 14 days, as bars: one series, the day's count on hover. */
internal fun activityCard(runs: List<Pair<Long, Int>>, now: Long, zone: ZoneId, days: Int = 14): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val first = today.minusDays(days - 1L)
    val perDay = IntArray(days)
    runs.forEach { (at, added) ->
        val d = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        if (!d.isBefore(first) && !d.isAfter(today)) perDay[(d.toEpochDay() - first.toEpochDay()).toInt()] += added
    }
    val total = perDay.sum()
    val max = perDay.maxOrNull()?.coerceAtLeast(1) ?: 1
    val w = 280.0; val top = 6.0; val base = 88.0; val step = w / days; val bar = step - 4
    fun day(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.dayOfMonth
    val bars = StringBuilder()
    perDay.forEachIndexed { i, n ->
        val x = i * step + 2
        val d = first.plusDays(i.toLong())
        val label = "${day(d)}: $n ${if (n == 1) "track" else "tracks"} added"
        // The hover target is the whole column, taller than the bar.
        bars.append("""<g class="col"><title>${h(label)}</title><rect x="${"%.1f".format(Locale.ROOT, i * step)}" y="0" width="${"%.1f".format(Locale.ROOT, step)}" height="$base" fill="transparent"/>""")
        if (n > 0) {
            val hgt = ((base - top) * n / max).coerceAtLeast(3.0)
            val y = base - hgt
            val r = minOf(4.0, bar / 2, hgt)
            bars.append("""<path class="bar" d="M${f(x)} ${f(base)}V${f(y + r)}Q${f(x)} ${f(y)} ${f(x + r)} ${f(y)}H${f(x + bar - r)}Q${f(x + bar)} ${f(y)} ${f(x + bar)} ${f(y + r)}V${f(base)}Z"/>""")
        }
        bars.append("</g>")
    }
    return """<div class="card"><h3>Last two weeks</h3><p class="muted">$total ${if (total == 1) "track" else "tracks"} added${if (total > 0) ", at most $max in a day" else ""}</p>
<svg class="activity" viewBox="0 0 280 104" role="img" aria-label="Tracks added each day, last 14 days: ${h(perDay.joinToString(", "))}">
<line x1="0" y1="$base" x2="280" y2="$base" class="axis"/>$bars
<text x="0" y="102" class="tick">${h(day(first))}</text><text x="280" y="102" class="tick" text-anchor="end">Today</text></svg></div>"""
}

private fun f(v: Double) = "%.1f".format(Locale.ROOT, v)
