package com.xlollx.songport.sync

/**
 * The artist lists of Discover's Concerts, Setlists and Records: alphabetical order and a name
 * filter that ignore case and accents, and the artists one search is run for (the ones played most,
 * or the ones the user picks from the playlist and adds by name).
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

    /**
     * The artists to look up: [picked] null means the whole playlist ([all], cut later to [LookupCaps]);
     * else the picked ones of [all] in its order (most played first), then the ones added by name.
     */
    fun chosen(all: List<String>, picked: Set<String>?): List<String> {
        if (picked == null) return all
        val inPlaylist = all.filter { it in picked }
        return inPlaylist + added(all, picked)
    }

    /** How many artists one search looks up: [cap] of the whole playlist at most ([picked] null), else all the chosen ones. */
    fun count(all: List<String>, picked: Set<String>?, cap: Int): Int =
        if (picked == null) minOf(all.size, cap) else chosen(all, picked).size

    /** The [picked] artists that are not in [all] (added by name), alphabetical. */
    fun added(all: List<String>, picked: Set<String>): List<String> {
        val known = all.mapTo(HashSet()) { Labels.fold(it) }
        return byName(picked.filter { Labels.fold(it) !in known }) { it }
    }

    /** The rows of the artist list for [query]: the playlist's artists and the ones added by name, alphabetical. */
    fun rows(all: List<String>, picked: Set<String>, query: String): List<String> =
        named(byName(all + added(all, picked)) { it }, query) { it }

    /**
     * The name [query] would add by hand: null when blank or already there (in [all] or [picked],
     * ignoring case and accents), so the list shows it instead.
     */
    fun addable(all: List<String>, picked: Set<String>, query: String): String? {
        val name = query.trim().replace(Regex("\\s+"), " ")
        val q = Labels.fold(name)
        if (q.isEmpty() || (all.asSequence() + picked.asSequence()).any { Labels.fold(it) == q }) return null
        return name
    }

    /** [picked] with [name], spelled as in [all] when the playlist has it (ignoring case and accents). */
    fun add(all: List<String>, picked: Set<String>, name: String): Set<String> {
        val q = Labels.fold(name)
        if (q.isEmpty() || picked.any { Labels.fold(it) == q }) return picked
        return picked + (all.firstOrNull { Labels.fold(it) == q } ?: name.trim())
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
