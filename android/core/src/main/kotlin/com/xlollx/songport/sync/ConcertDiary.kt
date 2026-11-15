package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.platform.Env
import kotlinx.serialization.Serializable

/**
 * The concert diary (Discover › Concerts › "I was there"): the concerts the user marked as attended
 * on setlist.fm, with what was played, from the official API (`GET /user/{userId}/attended`, 20 a
 * page) with the same key, pace and daily count as [Setlists]. Read when the user opens the page or
 * a sync of one of its playlists runs ([com.xlollx.songport.providers.DiscoverSource]), and kept
 * [TTL_MS]: a diary of 200 concerts costs 10 of the day's 1440 calls.
 */
object ConcertDiary {
    const val TTL_MS = 12L * 3_600_000
    /** 2000 concerts: more are not read. */
    const val MAX_PAGES = 100
    /** The songs of "not heard live yet". */
    const val NOT_HEARD = 30

    private const val KV = "concert_diary"

    @Serializable
    data class Diary(val user: String, val concerts: List<Setlists.Setlist>, val at: Long)

    /** The setlist.fm user name the diary is read for; null before any. */
    fun user(env: Env): String? = env.keyValues(KV)["user"]?.takeIf { it.isNotBlank() }

    /** Saves the user name (blank forgets it, and the diary kept). */
    fun saveUser(env: Env, user: String) {
        val kv = env.keyValues(KV)
        val u = user.trim().removePrefix("@")
        if (u == kv["user"]) return
        kv["diary"] = ""
        kv["user"] = u
    }

    /** The diary last read, whatever its age; null when none was. */
    fun kept(env: Env): Diary? = env.keyValues(KV)["diary"]?.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(Diary.serializer(), it) }.getOrNull() }
        ?.takeIf { it.user == user(env) }

    class NoUser : Exception("setlist.fm: no user name")

    /**
     * The diary: the one kept while fresh (unless [refresh]), else read again, the latest concert
     * first. Throws [NoUser], [SetlistImport.NoKey], [Setlists.QuotaGone], [Setlists.KeyRefused],
     * or a [ProviderException] when setlist.fm does not know the user or did not answer.
     */
    suspend fun read(env: Env, refresh: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Diary {
        val user = user(env) ?: throw NoUser()
        val key = Setlists.key(env) ?: throw SetlistImport.NoKey()
        val old = kept(env)
        if (!refresh && old != null && Setlists.now() - old.at < TTL_MS) return old
        val all = ArrayList<Setlists.Setlist>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            progress(page - 1, pages)
            val body = try {
                Setlists.get(env, key, "user/${java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20")}/attended?p=$page")
            } catch (e: Setlists.NotFound) {
                // No such user on the first page; past the last page otherwise.
                if (page == 1) throw ProviderException(env.text("diary_no_user", user), code = 404) else null
            } ?: if (page == 1) throw ProviderException(env.text("diary_unanswered")) else break
            val (list, total) = parseAttended(body)
            if (list.isEmpty()) break
            all += list
            pages = ((total + 19) / 20).coerceIn(1, MAX_PAGES)
            page++
        }
        val diary = Diary(user, all.distinctBy { it.id }.sortedByDescending { it.date }, Setlists.now())
        keep(env, diary)
        return diary
    }

    /** Keeps [diary] as the one last read. */
    fun keep(env: Env, diary: Diary) { env.keyValues(KV)["diary"] = json.encodeToString(Diary.serializer(), diary) }

    /** One page of the attended concerts (announced ones and those with no songs written too) and how many there are in all. */
    fun parseAttended(body: String): Pair<List<Setlists.Setlist>, Int> {
        val j = parseJson(body)
        val list = j["setlist"].arr.mapNotNull { Setlists.parseSetlist(it) }
        return list to (j["total"].int ?: list.size)
    }

    // ---------------------------------------------------------------- statistics

    /** [topArtist] the one seen most ([topTimes] times; null when no artist was seen twice). */
    data class Stats(val concerts: Int, val artists: Int, val venues: Int, val firstYear: Int?, val topArtist: String?, val topTimes: Int, val songs: Int)

    private fun norm(s: String) = Matcher.normalizeArtist(s).ifEmpty { s.trim().lowercase() }

    /** The artists seen, the ones seen most first, with how many times. */
    fun artists(concerts: List<Setlists.Setlist>): List<Pair<String, Int>> =
        concerts.filter { it.artist.isNotBlank() }.groupBy { norm(it.artist) }.values
            .map { it.first().artist to it.size }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    fun stats(concerts: List<Setlists.Setlist>): Stats {
        val seen = artists(concerts)
        val top = seen.firstOrNull()?.takeIf { it.second > 1 }
        return Stats(
            concerts = concerts.size, artists = seen.size,
            venues = concerts.filter { it.venue.isNotBlank() }.distinctBy { norm(it.venue) + "|" + norm(it.city) }.size,
            firstYear = concerts.mapNotNull { it.date.take(4).toIntOrNull() }.minOrNull(),
            topArtist = top?.first, topTimes = top?.second ?: 0, songs = heard(concerts).size,
        )
    }

    /** The concerts by year, the latest year first (and within it the latest concert). */
    fun byYear(concerts: List<Setlists.Setlist>): List<Pair<String, List<Setlists.Setlist>>> =
        concerts.sortedByDescending { it.date }.groupBy { it.date.take(4) }.toList()

    // ---------------------------------------------------------------- playlists

    private fun songKey(t: Track) = norm(t.artists.firstOrNull().orEmpty()) + "|" + Matcher.normalizeTitle(t.title).ifEmpty { t.title.lowercase() }

    /** Every song heard live, each once: the one heard most times first, then the one heard last. */
    fun heard(concerts: List<Setlists.Setlist>): List<Track> {
        class Seen(val track: Track, var times: Int, val last: String)
        val songs = LinkedHashMap<String, Seen>()
        for (c in concerts.sortedByDescending { it.date }) for (t in Setlists.tracks(c).distinctBy { songKey(it) }) {
            val s = songs.getOrPut(songKey(t)) { Seen(t, 0, c.date) }
            s.times++
        }
        return songs.values.sortedWith(compareByDescending<Seen> { it.times }.thenByDescending { it.last }).map { it.track }
    }

    /** [popular] (an artist's best known songs, the best known first) without what the user heard at [artist]'s concerts. */
    fun notHeard(concerts: List<Setlists.Setlist>, artist: String, popular: List<Track>, max: Int = NOT_HEARD): List<Track> {
        val want = norm(artist)
        val played = concerts.filter { norm(it.artist) == want }.flatMap { c -> c.songs.filter { !it.tape } }
            .mapTo(HashSet()) { Matcher.normalizeTitle(it.title).ifEmpty { it.title.lowercase() } }
        return popular.filter { Matcher.normalizeTitle(it.title).ifEmpty { it.title.lowercase() } !in played }.take(max)
    }

    /**
     * [artist]'s best known songs the user never heard at one of their concerts: ListenBrainz's most
     * played, else Deezer's top ([ArtistMixSource.popular], as the playlist generator). Throws when
     * MusicBrainz does not know the artist.
     */
    suspend fun notHeardYet(env: Env, concerts: List<Setlists.Setlist>, artist: String): List<Track> {
        val a = ArtistIds.mbid(env, artist)?.let { com.xlollx.songport.ai.ArtistMix.Artist(it, artist) }
            ?: ArtistMixSource.findArtist(artist) ?: throw ProviderException(env.text("lb_artist_not_found", artist))
        return notHeard(concerts, artist, ArtistMixSource.popular(a, ArtistMixSource.listenBrainzToken(env))).map { CsvCodec.withStableId(it.copy(id = "")) }
    }
}
