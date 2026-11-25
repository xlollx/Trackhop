package com.xlollx.songport.sync

import com.xlollx.songport.data.Settings
import com.xlollx.songport.net.json
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Ticket and date alerts for the artists and venues followed, in the same daily run as
 * [ArtistAlerts] and from the answers it has just kept ([Concerts]' day-long cache; the venues'
 * events cost one call each, [Venues.events]):
 * - a new date at a venue followed (an artist's new date is [ArtistAlerts]' own news);
 * - the tickets going on sale, to everyone or in a presale (Ticketmaster's `sales.public` and
 *   `sales.presales`), told once while the opening is still to come;
 * - a date moved, postponed or called off (`dates.status` and the event's date). A cancelled date
 *   is still never listed ([Concerts.find]): here it is told.
 *
 * Only Ticketmaster's events carry an id, a status and the sales: the other sources' dates are in
 * the calendar ([calendar]) but cannot be compared. Every artist and venue keeps what was seen of
 * its events ([Snap]); the first look only takes note.
 */
object ConcertAlerts {
    /** Which alerts are wanted, and whether they also go to the notification channels (Telegram, ntfy…). */
    @Serializable
    data class Kinds(val newDates: Boolean = true, val sales: Boolean = true, val changes: Boolean = true, val channels: Boolean = false) {
        val any: Boolean get() = newDates || sales || changes
    }

    enum class Kind { NEW_DATE, ON_SALE, MOVED, POSTPONED, CANCELLED }

    /** Something to tell about [event]: [venue] the followed venue it is at (null: a followed artist's); [oldDate] the date it had ([Kind.MOVED]). */
    data class Alert(val kind: Kind, val event: Concerts.Event, val venue: String? = null, val oldDate: String? = null)

    /** What was seen of an event. */
    @Serializable
    data class Snap(val date: String, val status: String = "", val onSale: String? = null, val presale: String? = null)

    const val CHECK_EVERY_MS = 20L * 3_600_000
    private const val KV = "concert_alerts"
    private val seenMap = MapSerializer(String.serializer(), MapSerializer(String.serializer(), Snap.serializer()))
    private val eventList = ListSerializer(Concerts.Event.serializer())

    private fun future(instant: String?, now: Long): Boolean = instant?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { it > now } == true

    /**
     * What is new in [now] (a subject's Ticketmaster events, cancelled ones included) against
     * [seen], and what to remember. [newDates]: an event never seen is told (a venue's; an artist's
     * is [ArtistAlerts]' to tell). The first look ([seen] null) tells nothing.
     */
    fun diff(seen: Map<String, Snap>?, now: List<Concerts.Event>, at: Long, newDates: Boolean): Pair<Map<String, Snap>, List<Alert>> {
        val events = now.filter { it.id.isNotEmpty() }.distinctBy { it.id }
        val snaps = events.associate { it.id to Snap(it.date, it.status, it.onSale, it.presale) }
        if (seen == null) return snaps to emptyList()
        val out = ArrayList<Alert>()
        for (e in events) {
            val old = seen[e.id]
            val cancelled = e.status == Concerts.CANCELLED
            when {
                old == null -> {
                    if (cancelled) continue
                    if (newDates) out += Alert(Kind.NEW_DATE, e)
                    if (future(e.onSale, at) || future(e.presale, at)) out += Alert(Kind.ON_SALE, e)
                }
                cancelled -> if (old.status != Concerts.CANCELLED) out += Alert(Kind.CANCELLED, e, oldDate = old.date)
                e.date != old.date -> out += Alert(Kind.MOVED, e, oldDate = old.date)
                e.status == "postponed" && old.status != "postponed" -> out += Alert(Kind.POSTPONED, e, oldDate = old.date)
                // The sale's opening said for the first time, or moved: told while it is still to come.
                (e.onSale != old.onSale && future(e.onSale, at)) || (e.presale != old.presale && future(e.presale, at)) -> out += Alert(Kind.ON_SALE, e)
            }
        }
        return snaps to out
    }

    /** [alerts] without the kinds [kinds] does not want. */
    fun wanted(alerts: List<Alert>, kinds: Kinds): List<Alert> = alerts.filter {
        when (it.kind) { Kind.NEW_DATE -> kinds.newDates; Kind.ON_SALE -> kinds.sales; else -> kinds.changes }
    }

    /**
     * The daily look: the events of the artists followed for their concerts and of the venues
     * followed, compared with what was seen, and kept for the calendar ([calendar]). At most once in
     * [CHECK_EVERY_MS] unless [force]. Never throws: a subject no source answered for is left as it was.
     */
    suspend fun check(env: Env, s: Settings, country: String, range: Concerts.Range, force: Boolean = false): List<Alert> {
        val artists = s.followedArtists.filter { it.active && it.kinds.concerts }
        if (artists.isEmpty() && s.followedVenues.isEmpty()) return emptyList()
        val kv = env.keyValues(KV)
        val at = Concerts.now()
        if (!force && at - (kv["checked"]?.toLongOrNull() ?: 0) < CHECK_EVERY_MS) return emptyList()
        kv["checked"] = at.toString()
        var seen = kv["seen"]?.let { runCatching { json.decodeFromString(seenMap, it) }.getOrNull() }.orEmpty()
        val keep = HashSet<String>()
        val alerts = ArrayList<Alert>()
        val all = ArrayList<Concerts.Event>()
        for (a in artists) {
            val k = "a|" + ArtistAlerts.key(a.name)
            keep += k
            val r = try {
                Concerts.find(env, listOf(a.name), country, range, whole = false, background = true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
            val dates = r.artists.firstOrNull() ?: continue
            all += dates.events + dates.cancelled
            // Ticketmaster out of calls or refusing the key: its events would look gone, then new.
            if (!r.ticketmaster || r.quotaHit || r.keyRefused) continue
            val (snaps, found) = diff(seen[k], dates.events + dates.cancelled, at, newDates = false)
            seen = seen + (k to snaps); alerts += found
        }
        for (v in s.followedVenues) {
            val k = "v|" + v.id
            keep += k
            val events = try {
                Venues.events(env, v, background = true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            all += events
            val (snaps, found) = diff(seen[k], events, at, newDates = true)
            seen = seen + (k to snaps); alerts += found.map { it.copy(venue = v.name) }
        }
        kv["seen"] = json.encodeToString(seenMap, seen.filterKeys { it in keep })
        kv["calendar"] = json.encodeToString(eventList, all.distinctBy { it.id.ifEmpty { "${it.date}|${it.artist}|${it.venue}|${it.city}" } }.sortedBy { it.date })
        // The same event at a followed venue by a followed artist: said once.
        val out = wanted(alerts, s.concertAlerts).distinctBy { it.kind.name + "|" + it.event.id }
        if (out.isNotEmpty()) env.log("alerts", "concerts: ${out.size} to tell")
        return out
    }

    /** The dates the last look found (the artists' and the venues', cancelled ones marked), for the calendar. */
    fun calendar(env: Env): List<Concerts.Event> =
        env.keyValues(KV)["calendar"]?.let { runCatching { json.decodeFromString(eventList, it) }.getOrNull() }.orEmpty()

    // ---------------------------------------------------------------- what to say

    private fun day(date: String): String = runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(date)

    /** "Fri 20 Nov, 10:00" in the event's own time zone, else the device's. */
    private fun moment(instant: String, e: Concerts.Event): String = runCatching {
        val zone = e.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: Concerts.zone()
        Instant.parse(instant).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
    }.getOrDefault(instant)

    private fun who(e: Concerts.Event) = e.lineup.ifEmpty { listOf(e.artist) }.joinToString(", ")

    /** "Radiohead: tickets from Fri 20 Nov, 10:00", "Date moved: Massive Attack", in the user's language. */
    fun title(env: Env, a: Alert): String {
        val e = a.event
        val now = Concerts.now()
        return when (a.kind) {
            Kind.NEW_DATE -> env.text("concert_alert_new", who(e), a.venue ?: e.venue.ifBlank { e.city }, day(e.date))
            Kind.ON_SALE -> if (future(e.onSale, now)) env.text("concert_alert_sale", who(e), moment(e.onSale!!, e)) else env.text("concert_alert_presale", who(e), moment(e.presale.orEmpty(), e))
            Kind.MOVED, Kind.POSTPONED -> env.text("concert_alert_moved", who(e))
            Kind.CANCELLED -> env.text("concert_alert_cancelled", who(e))
        }
    }

    fun text(env: Env, a: Alert): String {
        val e = a.event
        val where = e.city.ifBlank { e.venue }
        return when (a.kind) {
            Kind.NEW_DATE -> env.text("concert_alert_new_text")
            Kind.ON_SALE -> listOfNotNull(
                listOf(where, day(e.date)).filter { it.isNotBlank() }.joinToString(", "),
                // Both to come: the title says the sale, here the presale before it.
                e.presale?.takeIf { future(e.onSale, Concerts.now()) && future(it, Concerts.now()) }?.let { env.text("concert_alert_presale_from", moment(it, e)) },
            ).joinToString(" · ")
            Kind.MOVED -> env.text("concert_alert_moved_text", where, day(a.oldDate.orEmpty()), day(e.date))
            Kind.POSTPONED -> env.text("concert_alert_postponed_text", where, day(e.date))
            Kind.CANCELLED -> listOf(where, day(a.oldDate ?: e.date)).filter { it.isNotBlank() }.joinToString(", ")
        }
    }

    /** The alerts through the notification channels that take news of the user's artists (the New releases event), as [ArtistAlerts]' news; how many took them. */
    fun toChannels(env: Env, alerts: List<Alert>): Int {
        if (alerts.isEmpty()) return 0
        val channels = runCatching { Notify.channels(env) }.getOrDefault(emptyList()).filter { it.enabled && it.missing().isEmpty() && Notify.Event.RELEASES in it.events }
        if (channels.isEmpty()) return 0
        // A channel counts when it took every alert; each alert is one entry of the server's history.
        val failed = HashSet<String>()
        for (a in alerts) {
            val m = Notify.Message(title(env, a), text(env, a) + (a.event.url.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: ""))
            val target = a.event.url.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { com.xlollx.songport.notify.NotifTarget.Link(it) }
                ?: com.xlollx.songport.notify.NotifTarget.Followed
            Notify.sendAll(env, channels, m, "concert", target).forEachIndexed { i, r -> if (r.error != null) failed += channels[i].id }
        }
        return channels.count { it.id !in failed }
    }
}

/**
 * A calendar to subscribe to, as iCalendar text (RFC 5545), written by hand: lines end in CRLF and
 * are folded at 75 octets, texts are escaped, an event without a time is a whole day.
 */
object Ics {
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneOffset.UTC)

    internal fun escape(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "")

    /** [line] folded: no more than 75 octets a line, the next ones starting with a space; never inside a character. */
    internal fun fold(line: String): String {
        val out = StringBuilder()
        var octets = 0
        for (cp in line.codePoints().toArray()) {
            val ch = String(Character.toChars(cp))
            val n = ch.toByteArray(Charsets.UTF_8).size
            if (octets + n > 75) { out.append("\r\n "); octets = 1 }
            out.append(ch); octets += n
        }
        return out.toString()
    }

    /** The concerts as a calendar named [name]; [fallbackZone] for a time whose event names no zone. */
    fun calendar(events: List<Concerts.Event>, name: String, now: Long, fallbackZone: ZoneId): String {
        val lines = ArrayList<String>()
        lines += listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Trackhop//Concerts//EN", "CALSCALE:GREGORIAN", "METHOD:PUBLISH",
            "X-WR-CALNAME:" + escape(name), "REFRESH-INTERVAL;VALUE=DURATION:PT12H", "X-PUBLISHED-TTL:PT12H")
        for (e in events) {
            val c = runCatching { Concerts.calendarEntry(e, fallbackZone) }.getOrNull() ?: continue
            val uid = e.id.ifEmpty { Integer.toHexString("${e.artist}|${e.date}|${e.venue}|${e.city}".hashCode()) }
            lines += "BEGIN:VEVENT"
            lines += "UID:" + escape(uid) + "@trackhop"
            lines += "DTSTAMP:" + STAMP.format(Instant.ofEpochMilli(now))
            if (c.allDay) {
                lines += "DTSTART;VALUE=DATE:" + DAY.format(Instant.ofEpochMilli(c.begin))
                lines += "DTEND;VALUE=DATE:" + DAY.format(Instant.ofEpochMilli(c.end))
            } else {
                lines += "DTSTART:" + STAMP.format(Instant.ofEpochMilli(c.begin))
                lines += "DTEND:" + STAMP.format(Instant.ofEpochMilli(c.end))
            }
            lines += "SUMMARY:" + escape(c.title)
            if (c.location.isNotBlank()) lines += "LOCATION:" + escape(c.location)
            if (e.url.isNotBlank()) { lines += "URL:" + e.url; lines += "DESCRIPTION:" + escape(e.url) }
            lines += if (e.status == Concerts.CANCELLED) "STATUS:CANCELLED" else "STATUS:CONFIRMED"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("") { fold(it) + "\r\n" }
    }
}
