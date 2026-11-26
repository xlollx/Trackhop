package com.xlollx.songport.sync

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider

/**
 * Saved matches that point to songs a personal server no longer has (#95). Navidrome, Jellyfin
 * and Plex give the songs new ids when the library is scanned again or imported anew: a match saved
 * before is then an id the server answers "not found" for, or, Navidrome, takes into a playlist as
 * an entry that leads nowhere (the playlist counts it and shows nothing). A run checks the saved
 * matches it is about to add before adding them, a few ids, once a day per account; more only when
 * one of those is gone.
 */
internal object LostMatches {
    /** The saved ids asked about first. */
    const val SAMPLE = 5
    /** A sample this large, every id of it gone: the library was imported anew, every saved match to it goes. */
    const val REIMPORT_SAMPLE = 3
    /** After a check that found every id there, the next one waits this long. */
    const val CHECK_EVERY_MS = 24L * 3_600_000
    /** The most ids one run checks one by one after a sample found some gone (the rest are checked by later runs). */
    const val MAX_CHECKED = 2_000

    /** What a check found: the ids gone, and whether the library looks imported anew. */
    data class Found(val lost: Set<String>, val reimported: Boolean, val checked: Int)

    /**
     * Checks [ids], the saved matches a run is about to add on [dst]. [force]: whatever the last
     * check found, each one of them is asked about (a playlist with entries that lead nowhere,
     * an addition the playlist does not show). Null when nothing was asked or the server cannot tell.
     */
    suspend fun check(env: Env, dst: MusicProvider, ids: Collection<String>, force: Boolean = false, now: Long = System.currentTimeMillis()): Found? {
        if (!dst.losesIds || ids.isEmpty()) return null
        val store = env.store
        if (!force && now - (store.data.idChecks[dst.id] ?: 0L) < CHECK_EVERY_MS) return null
        val distinct = ids.distinct()
        val sample = distinct.shuffled().take(SAMPLE)
        val lostInSample = ask(env, dst, sample) ?: return null
        if (lostInSample.isEmpty() && !force) {
            store.putIdCheck(dst.id, now)
            return Found(emptySet(), false, sample.size)
        }
        if (sample.size >= REIMPORT_SAMPLE && lostInSample.size == sample.size) return Found(lostInSample, true, sample.size)
        val rest = (distinct - sample.toSet()).take(MAX_CHECKED)
        val lost = lostInSample + (ask(env, dst, rest) ?: emptySet())
        if (lost.isEmpty()) store.putIdCheck(dst.id, now)
        return Found(lost, false, sample.size + rest.size)
    }

    /** [dst.lostIds][MusicProvider.lostIds], a failure only logged: a check never fails a run. */
    private suspend fun ask(env: Env, dst: MusicProvider, ids: List<String>): Set<String>? {
        if (ids.isEmpty()) return emptySet()
        return try {
            val out = LinkedHashSet<String>()
            for (chunk in ids.chunked(50)) out += dst.lostIds(env, chunk) ?: return null
            out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            env.log("engine", "${dst.displayName}: saved matches not checked: ${e.message?.take(160)}")
            null
        }
    }

    /**
     * Forgets what [found] says is gone on [dst] and says it once in the log; returns how many saved
     * matches went. The tracks are then searched again in the same run.
     */
    fun forget(env: Env, dst: MusicProvider, found: Found): Int {
        if (found.lost.isEmpty()) return 0
        return if (found.reimported) {
            val n = env.store.forgetMatchesTo(dst.id, null)
            env.log("engine", "${dst.displayName} no longer knows any of the ${found.checked} saved matches checked: its library was scanned anew; " +
                "the $n saved matches to it are forgotten and searched again")
            n
        } else {
            val n = env.store.forgetMatchesTo(dst.id, found.lost)
            env.log("engine", "${dst.displayName} no longer knows ${found.lost.size} saved ${if (found.lost.size == 1) "match" else "matches"} of the ${found.checked} checked: " +
                "the library was rescanned; searching again")
            n
        }
    }
}
