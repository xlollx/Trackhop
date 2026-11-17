package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * How the tracks of one run found their match on the target, for one line of the diagnostic log:
 * counts only, never a title. A track counts as matched by ISRC when the match carries the same
 * code as the source; a service that searches by ISRC without giving the code back counts under
 * title and duration.
 */
class MatchTally {
    var direct = 0; private set
    var saved = 0; private set
    var byIsrc = 0; private set
    var byTitle = 0; private set
    var searchedBefore = 0; private set
    var noMatch = 0; private set
    var failed = 0; private set

    /** Source and target on the same account: the track is its own match. */
    fun direct() { direct++ }
    /** A match kept from an earlier run, or confirmed by hand. */
    fun saved() { saved++ }
    /** Searched not long ago without a match: not asked again. */
    fun searchedBefore() { searchedBefore++ }
    /** The search itself failed (the service refused or did not answer). */
    fun failed() { failed++ }
    /** A search the service answered: [found] is the match, null when nothing fit. */
    fun searched(source: Track, found: Track?) {
        when {
            found == null -> noMatch++
            source.isrcNorm != null && source.isrcNorm == found.isrcNorm -> byIsrc++
            else -> byTitle++
        }
    }

    val notFound: Int get() = searchedBefore + noMatch + failed
    val total: Int get() = direct + saved + byIsrc + byTitle + notFound

    /** Null when nothing was looked for. */
    fun line(): String? {
        if (total == 0) return null
        val found = listOfNotNull(
            "$saved by saved match".takeIf { saved > 0 },
            "$byIsrc by ISRC".takeIf { byIsrc > 0 },
            "$byTitle by title and duration".takeIf { byTitle > 0 },
            "$direct taken as they are".takeIf { direct > 0 },
        )
        // The reasons, the most frequent first.
        val why = listOf(
            searchedBefore to "searched before without a match",
            noMatch to "nothing fitting found",
            failed to "search failed",
        ).filter { it.first > 0 }.sortedByDescending { it.first }.joinToString(", ") { "${it.first} ${it.second}" }
        return "matching of $total: " + found.joinToString(", ").ifEmpty { "none matched" } +
            if (notFound > 0) "; $notFound not found ($why)" else ""
    }
}
