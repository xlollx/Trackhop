package com.xlollx.songport.sync

import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Spotify as a source of concert dates for [Concerts], unofficial: the tour dates an artist's page
 * on open.spotify.com shows, read with the user's own Spotify (web) sign-in. [reader] is set by the
 * app, and answers only where that connector is connected (null on Docker and Desktop, and then
 * nothing is asked); without it Concerts goes on with its other sources, saying nothing.
 *
 * An artist's list (the player's search for the artist, then its tour section) says each date and
 * city, not the country: a date is placed by its own page (venue, city, country), one more call,
 * asked only for the dates in the range whose UTC offset is one the country uses that day
 * ([zones]), at most [MAX_DETAILS] an artist a search; each concert's place is remembered for
 * [PLACE_TTL_MS], so the next searches ask for new dates only. The link is the concert's page on
 * Spotify, which lists its ticket sellers. Only when the user runs Concerts, never in the background.
 */
object SpotifyConcerts {
    /** Pages of concerts asked per artist and search; the dates past them wait for the next search. */
    const val MAX_DETAILS = 20
    const val PLACE_TTL_MS = 30L * 24 * 3_600_000
    private const val PLACES = "spotify_concerts"
    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

    /** A date as Spotify gives it: [start] ISO 8601 with the venue's offset; [venue] and [country] from its own page only. */
    data class Date(val uri: String, val title: String, val start: String, val city: String = "", val venue: String = "", val country: String = "")

    interface Reader {
        /** [artist]'s upcoming dates; empty when Spotify knows no artist of that name; null when there is no one to ask. Throws when it failed. */
        suspend fun dates(artist: String): List<Date>?
        /** One concert's own page; null when it holds no concert. Throws when it failed. */
        suspend fun details(uri: String): Date?
    }

    @Volatile var reader: Reader? = null

    /** The time zones of a country (an ISO code), from the platform; empty when it cannot say (every date's page is read then). */
    @Volatile var zones: (String) -> List<ZoneId> = { emptyList() }

    fun pageUrl(uri: String) = "https://open.spotify.com/concert/" + uri.substringAfterLast(':')

    /**
     * [artist]'s dates on Spotify in [country] from [today] to [end]: empty when not asked (no
     * reader, no connector), null when the list could not be read (then not remembered).
     */
    internal suspend fun dates(env: Env, artist: String, country: String, today: LocalDate, end: LocalDate?): List<Concerts.Event>? {
        val read = reader ?: return emptyList()
        val ck = Concerts.cacheKey(Concerts.Source.SPOTIFY, artist, country, end)
        Concerts.cached(env, ck)?.let { return it }
        val listed = try {
            read.dates(artist) ?: return emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            env.log("concerts", "Spotify (unofficial): ${e.message ?: e.javaClass.simpleName}"); return null
        }
        val zs = zones(country)
        val events = ArrayList<Concerts.Event>()
        var unplaced = false
        var asked = 0
        for (d in listed.sortedBy { it.start }) {
            val day = d.start.take(10)
            if (!DAY.matches(day) || day < today.toString() || (end != null && day > end.toString())) continue
            val at = offsetOf(d.start)
            val zone = at?.let { a -> zs.firstOrNull { it.rules.getOffset(a.toInstant()) == a.offset } }
            // An offset the country does not use that day: somewhere else, not worth a call.
            if (at != null && zs.isNotEmpty() && zone == null) continue
            val placed = place(env, d.uri) ?: run {
                if (unplaced || asked >= MAX_DETAILS) { unplaced = true; return@run null }
                asked++
                try {
                    read.details(d.uri)?.also { remember(env, it) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    // Spotify busy or the page changed: the dates placed so far are shown, the rest next time.
                    env.log("concerts", "Spotify (unofficial), a concert's page: ${e.message ?: e.javaClass.simpleName}")
                    unplaced = true; null
                }
            } ?: continue
            if (!placed.country.equals(country, ignoreCase = true)) continue
            events += Concerts.Event(
                artist = artist, name = d.title.ifBlank { placed.title }, date = day, time = at?.toLocalTime()?.toString()?.take(5),
                city = placed.city.ifBlank { d.city }, venue = placed.venue, country = country.uppercase(),
                url = pageUrl(d.uri), source = Concerts.Source.SPOTIFY, timeZone = zone?.id,
            )
        }
        if (!unplaced) Concerts.remember(env, ck, events)
        return events
    }

    /**
     * The offset and local time [start] says; null for a time in UTC ("Z"), which Spotify gives for
     * a date with no known time and place, and for anything unreadable.
     */
    internal fun offsetOf(start: String): OffsetDateTime? =
        if (start.endsWith("Z", ignoreCase = true)) null else runCatching { OffsetDateTime.parse(start) }.getOrNull()

    private fun place(env: Env, uri: String): Date? {
        val s = env.keyValues(PLACES)[uri] ?: return null
        val at = s.substringBefore('|').toLongOrNull() ?: return null
        if (Concerts.now() - at >= PLACE_TTL_MS) return null
        val f = s.substringAfter('|').split('\t')
        if (f.size < 4) return null
        return Date(uri, f[3], "", f[2], f[1], f[0])
    }

    private fun remember(env: Env, d: Date) {
        val kv = env.keyValues(PLACES)
        if (kv.size >= 4_000) kv.clear()
        fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
        kv[d.uri] = "${Concerts.now()}|${clean(d.country)}\t${clean(d.venue)}\t${clean(d.city)}\t${clean(d.title)}"
    }
}
