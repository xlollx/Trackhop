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

    /**
     * The artists to look up: the ticked ones among [all] (in [all]'s order) plus [typed] (one name
     * written by hand, when not one of them already); [all] when nothing is ticked or typed.
     */
    fun chosen(all: List<String>, ticked: Set<String>, typed: String = ""): List<String> {
        val extra = typed.trim()
        val picked = all.filter { it in ticked }
        if (picked.isEmpty() && extra.isEmpty()) return all
        val same = Labels.fold(extra)
        return if (extra.isEmpty() || picked.any { Labels.fold(it) == same }) picked else picked + extra
    }
}
