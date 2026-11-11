package com.xlollx.songport.sync

/**
 * The artist lists of Discover's Concerts and Setlists: alphabetical order and a name filter that
 * ignore case and accents, and the artists one search is run for (some of the playlist's, or one
 * typed in, instead of all of them).
 */
object ArtistPick {
    /** [items] in alphabetical order of [name], ignoring case and accents ("Ásgeir" before "Azealia Banks"). */
    fun <T> byName(items: List<T>, name: (T) -> String): List<T> =
        items.sortedWith(compareBy<T> { Labels.fold(name(it)) }.thenBy { name(it) })

    /** The [items] whose [name] holds [query], ignoring case and accents; all of them for a blank one. */
    fun <T> named(items: List<T>, query: String, name: (T) -> String): List<T> {
        val q = Labels.fold(query)
        return if (q.isEmpty()) items else items.filter { Labels.fold(name(it)).contains(q) }
    }

    /** Nothing ticked (among [all]) nor typed: the search runs on the whole playlist, capped ([LookupCaps]). */
    fun isWhole(all: List<String>, ticked: Set<String>, typed: String): Boolean = typed.isBlank() && all.none { it in ticked }

    /**
     * The artists to look up: the ticked ones among [all] (in [all]'s order) plus [typed] (one name
     * written by hand, when not one of them already); [all] when nothing is ticked or typed.
     */
    fun chosen(all: List<String>, ticked: Set<String>, typed: String = ""): List<String> {
        if (isWhole(all, ticked, typed)) return all
        val extra = typed.trim()
        val picked = all.filter { it in ticked }
        val same = Labels.fold(extra)
        return if (extra.isEmpty() || picked.any { Labels.fold(it) == same }) picked else picked + extra
    }
}

/**
 * How many artists one Discover search looks up when it runs on the whole playlist (the ones it
 * holds most first), sized so a search takes about a minute and a day's calls last many searches.
 * Artists the user picks ([ArtistPick.chosen]) are all looked up: these caps are only the default's.
 */
object LookupCaps {
    /** Concerts: MusicBrainz answers one request a second (its places are remembered), Ticketmaster 2 a second, 5000 a day. */
    const val CONCERTS = 50
    /** Setlists: setlist.fm takes 2 requests a second and 1440 a day per key, one per artist: about 28 searches a day. */
    const val SETLISTS = 50
    /** Records & merch with a Discogs token: Discogs takes 60 requests a minute with one. */
    const val RECORDS_TOKEN = 50
    /** Records & merch without a token: Discogs takes 25 a minute. */
    const val RECORDS_ANON = 25

    /** [list] cut to [cap] when it is the whole playlist ([whole]); the artists picked by hand, all of them. */
    fun <T> cut(list: List<T>, cap: Int, whole: Boolean): List<T> = if (whole) list.take(cap) else list
}
