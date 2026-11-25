package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * An artist's other names, from MusicBrainz, for the songs whose artist one service writes in its own
 * script and another in Latin letters: 周杰倫 is Jay Chou, 五月天 is Mayday, 아이유 is IU (#81).
 *
 * Asked only when it can help: a source artist written in another script than Latin, or a candidate
 * of the same title whose artist is and the source's is not. One request per artist
 * (`artist?query=…`, aliases come with the answer), paced with every other MusicBrainz request (one a
 * second) and remembered for months, "nothing found" for a month. A name that several MusicBrainz
 * artists answer to exactly ("Mayday" is more than one band) teaches nothing: it would make two bands one.
 * What is known lives in memory for [Matcher], which stays pure: [same] tells two names of one artist.
 */
object ArtistAliases {
    private const val PREFS = "musicbrainz_artist_aliases"
    private const val TTL_MS = 180L * 24 * 3_600_000
    private const val NEGATIVE_TTL_MS = 30L * 24 * 3_600_000
    private const val MAX = 5_000
    private const val SEP = '\u001F'

    /** A normalised name to every normalised name of the same artist (itself included). */
    private val known = ConcurrentHashMap<String, Set<String>>()
    /** Names asked in this process already (found or not): never twice. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    private fun key(name: String) = Hanzi.simplified(Matcher.normalizeArtist(name))

    /** [a] and [b] (normalised as [Matcher.norm] does) are names of one artist that MusicBrainz knows. */
    fun same(a: String, b: String): Boolean = a != b && known[a]?.contains(b) == true

    /** The other names known for [name], as written (Latin ones first): for a search by another name. */
    fun namesOf(name: String): List<String> = names[key(name)].orEmpty()

    /** The names as written, by normalised name: what [namesOf] answers. */
    private val names = ConcurrentHashMap<String, List<String>>()

    /** Tests, and what [learn] found: [all] are names of one artist. */
    fun teach(all: Collection<String>) {
        val written = all.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val group = written.map(::key).filter { it.isNotEmpty() }.toSet()
        if (group.size < 2) return
        for (n in group) known.merge(n, group) { x, y -> x + y }
        val latinFirst = written.sortedBy { Translit.foreign(it) }
        for (w in written) names.merge(key(w), latinFirst.filter { key(it) != key(w) }) { x, y -> (x + y).distinct() }
    }

    /**
     * Learns the other names of [track]'s first artist when they can matter against [found]: its name
     * is in another script than Latin, or a candidate with a title alike is by an artist who is.
     */
    suspend fun learnFor(env: Env, track: Track, found: List<Track> = emptyList()) {
        if (track.kind.isNotEmpty()) return
        val artist = track.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val needed = Translit.foreign(artist) || found.any { c ->
            c.artists.any { Translit.foreign(it) } &&
                Matcher.similarity(Hanzi.simplified(Matcher.normalizeTitle(c.title)), Hanzi.simplified(Matcher.normalizeTitle(track.title))) >= 0.8
        }
        if (needed) learn(env, artist)
    }

    /** Asks MusicBrainz (or the memory of an earlier answer) for [artist]'s other names. Never throws. */
    suspend fun learn(env: Env, artist: String) {
        val k = key(artist)
        if (k.isEmpty() || !asked.add(k)) return
        val prefs = env.keyValues(PREFS)
        prefs[k]?.let { saved ->
            val found = saved.substringBeforeLast('|').split(SEP).filter { it.isNotEmpty() }
            val at = saved.substringAfterLast('|', "0").toLongOrNull() ?: 0
            if (MusicBrainz.now() - at < (if (found.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)) { teach(found); return }
        }
        val query = URLEncoder.encode("artist:\"$artist\" OR alias:\"$artist\"", "UTF-8")
        val body = MusicBrainz.get("artist/?query=$query&fmt=json&limit=5")
        if (body == null) { asked.remove(k); return } // Not answering: asked again later, nothing remembered.
        val found = runCatching { parse(body, k) }.getOrDefault(emptyList())
            .filter { SEP !in it && '|' !in it }.take(24)
        if (prefs.size >= MAX) prefs.clear()
        prefs[k] = found.joinToString(SEP.toString()) + "|" + MusicBrainz.now()
        teach(found)
    }

    /**
     * The names of the one MusicBrainz artist that answers to [k] exactly (its name, sort name or an
     * alias), empty when none does or more than one does.
     */
    internal fun parse(body: String, k: String): List<String> {
        val artists = parseJson(body)["artists"].arr
        val exact = artists.filter { a ->
            (a["score"].int ?: 0) >= 90 && allNames(a).any { key(it) == k }
        }
        if (exact.size != 1) return emptyList()
        return allNames(exact.single())
    }

    private fun allNames(a: kotlinx.serialization.json.JsonElement): List<String> {
        val out = ArrayList<String>()
        a["name"].str?.let { out += it }
        a["sort-name"].str?.let { out += unsort(it) }
        for (al in a["aliases"].arr) {
            al["name"].str?.let { out += it }
            al["sort-name"].str?.let { out += unsort(it) }
        }
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** "Chou, Jay" is "Jay Chou"; a sort name without a comma as it is. */
    private fun unsort(s: String): String {
        val parts = s.split(", ")
        return if (parts.size == 2 && parts.none { it.isBlank() }) parts[1] + " " + parts[0] else s
    }

    /** Tests: forgets everything learned in memory. */
    internal fun reset() { known.clear(); names.clear(); asked.clear() }
}
