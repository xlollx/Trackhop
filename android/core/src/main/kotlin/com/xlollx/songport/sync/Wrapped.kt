package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * "Your Wrapped, whenever you want": a few playful figures about the user's playlists, computed on
 * the phone from tracks the app has already read. Pure Kotlin, covered by tests.
 *
 * The "musical age" is a joke with a rule behind it: people tend to love most the music that came
 * out when they were about 17, so the median release year of the tracks, minus 17, is the year
 * the user "listens like someone born in"; the age is the years since then.
 */
object Wrapped {
    /** The age at which, the joke goes, one's taste is set. */
    const val FORMATIVE_AGE = 17

    /** Fewer dated tracks than this and the musical age is not told: a guess from three songs says nothing. */
    const val MIN_DATED = 5

    data class Summary(
        val topArtist: String?,
        val topArtistTracks: Int,
        /** The decade with most tracks, as its first year (1990); null when no track has a year. */
        val topDecade: Int?,
        /** Different tracks across all playlists read (the same song in two playlists counts once). */
        val trackCount: Int,
        val medianYear: Int?,
        /** "You listen like someone born in …"; null with fewer than [MIN_DATED] dated tracks. */
        val bornIn: Int?,
        val age: Int?,
    )

    /** The year one "is born in" and the age it gives in [currentYear]. */
    fun musicalAge(medianYear: Int, currentYear: Int): Pair<Int, Int> {
        val born = minOf(medianYear, currentYear) - FORMATIVE_AGE
        return born to (currentYear - born)
    }

    /** The middle year (the lower of the two middle ones for an even count); null for none. */
    fun median(years: List<Int>): Int? {
        if (years.isEmpty()) return null
        val s = years.sorted()
        return s[(s.size - 1) / 2]
    }

    /** A track's identity across playlists and services: its ISRC, else its normalized title and first artist. */
    fun key(t: Track): String =
        t.isrcNorm ?: (Matcher.normalizeTitle(t.title) + "|" + (t.artists.firstOrNull()?.let { Matcher.normalizeArtist(it) } ?: ""))

    fun compute(tracks: List<Track>, currentYear: Int): Summary {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }.distinctBy { key(it) }
        // The artist signing most different tracks, spelled as first met.
        val counts = HashMap<String, Int>()
        val spelling = LinkedHashMap<String, String>()
        for (t in songs) for (a in t.artists.distinct()) {
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty() || k == "various artists") continue
            spelling.putIfAbsent(k, a.trim())
            counts[k] = (counts[k] ?: 0) + 1
        }
        val top = counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        val years = songs.map { it.year }.filter { it in 1900..(currentYear + 1) }
        val decade = years.groupingBy { it / 10 * 10 }.eachCount().maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key
        val median = median(years)
        val age = median?.takeIf { years.size >= MIN_DATED }?.let { musicalAge(it, currentYear) }
        return Summary(
            topArtist = top?.key?.let { spelling[it] }, topArtistTracks = top?.value ?: 0,
            topDecade = decade, trackCount = songs.size, medianYear = median,
            bornIn = age?.first, age = age?.second,
        )
    }
}
