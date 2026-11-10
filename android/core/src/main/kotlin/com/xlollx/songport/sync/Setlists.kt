package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * "Setlists of the artists of this playlist" (Discover), and setlist.fm links shared or pasted
 * ([SetlistImport]): the official setlist.fm API (https://api.setlist.fm/docs/1.0/).
 *
 * The API needs a key (header x-api-key): the user's own, saved in the settings ([KEY_ID] in
 * clientIds), or else the build's ([BUILD_KEY], the Play and AppGallery builds only, like
 * Ticketmaster's). A key has 2 calls a second and 1440 a day, the build's shared by everyone: so
 * calls are spaced ([pace]), counted per day on this device ([DAILY]), sent only when the user asks
 * (never in the background), fewer artists are looked up on the shared key ([MAX_SHARED]), each
 * artist's answer is kept a week ([TTL_MS]), and when the day's calls are gone it stops and says so
 * ([Result.quotaHit]) until the next day (UTC).
 *
 * setlist.fm's API terms: non-commercial use only, and the data shown with attribution, the link
 * each answer carries ([Setlist.url]) plus "Setlists from setlist.fm" ([ATTRIBUTION], [HOME]).
 */
object Setlists {
    /** The user's own key, in Settings.clientIds. */
    const val KEY_ID = "setlistfm"
    /** The build's key, through BuildDefaults (BuildConfig on Android, the environment on a server). */
    const val BUILD_KEY = "SETLISTFM_API_KEY"
    /** How many artists one search looks up: fewer on the shared key, to spare everyone's daily calls. */
    const val MAX_SHARED = 25
    const val MAX_OWN = 100
    /** The latest setlists kept per artist (with songs: the announced, empty ones are left out). */
    const val PER_ARTIST = 5
    const val TTL_MS = 7L * 24 * 3_600_000
    /** setlist.fm's daily calls per key. */
    const val DAILY = 1440
    /** Where to ask for a key (a free setlist.fm account, then "API" in its settings). */
    const val KEY_URL = "https://www.setlist.fm/settings/api"
    const val HOME = "https://www.setlist.fm/"
    const val ATTRIBUTION = "Setlists from setlist.fm"

    /** One song as played: [by] the original artist of a cover; [tape] played from a tape (an intro); [encore] 0 = the main set. */
    @Serializable
    data class Song(val title: String, val by: String? = null, val tape: Boolean = false, val encore: Int = 0)

    /** One concert's setlist: [date] is yyyy-MM-dd, [country] ISO 3166 (two letters), [url] its setlist.fm page. */
    @Serializable
    data class Setlist(
        val id: String,
        val artist: String,
        val date: String,
        val venue: String = "",
        val city: String = "",
        val country: String = "",
        val tour: String = "",
        val url: String = "",
        val songs: List<Song> = emptyList(),
    )

    data class ArtistSetlists(val artist: String, val setlists: List<Setlist>)

    data class Result(
        /** Artists with setlists first (the latest concert first), then those without. */
        val artists: List<ArtistSetlists>,
        /** The artists of the playlist, before [MAX_SHARED]/[MAX_OWN]. */
        val total: Int,
        /** A key is there: without one nothing is asked. */
        val hasKey: Boolean,
        val ownKey: Boolean,
        /** The day's calls ran out: some artists were not looked up. */
        val quotaHit: Boolean,
        /** setlist.fm refused the key (401/403). */
        val keyRefused: Boolean,
        /** Artists setlist.fm did not answer for (an error, not "no setlists"). */
        val unanswered: Int = 0,
    )

    // Tests point these at a local server and a fixed clock.
    @Volatile internal var base = "https://api.setlist.fm/rest/1.0"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }
    /** Under setlist.fm's 2 a second, for the whole process. */
    @Volatile var pace = Pacer(550)
        internal set

    private const val CACHE = "setlists"
    private const val STATE = "setlists_state"
    private val LINK_ID = Regex("""-([0-9a-f]{5,12})\.html""", RegexOption.IGNORE_CASE)

    fun ownKey(env: Env): String? = env.store.data.settings.clientIds[KEY_ID]?.trim()?.takeIf { it.isNotEmpty() }

    /** The key used: the user's, else the build's; null when there is none. */
    fun key(env: Env): String? = ownKey(env) ?: BuildDefaults[BUILD_KEY].trim().takeIf { it.isNotEmpty() }

    /** Saves the user's own key (blank removes it, back to the build's). */
    fun saveKey(env: Env, key: String) {
        val k = key.trim()
        env.store.updateSettings { s -> s.copy(clientIds = if (k.isEmpty()) s.clientIds - KEY_ID else s.clientIds + (KEY_ID to k)) }
    }

    /** The artists to look up, the ones the playlist holds most first (the same pick as Concerts). */
    fun artistsOf(tracks: List<Track>): List<String> = Concerts.artistsOf(tracks)

    /** The setlist id at the end of a setlist.fm link (".../venue-city-63de4613.html"). */
    fun idFromLink(url: String): String? = LINK_ID.findAll(url.substringBefore('?')).lastOrNull()?.groupValues?.get(1)?.lowercase()

    /** The songs of a setlist as tracks, in the order played (encores last): the tapes left out, a cover under its original artist. */
    fun tracks(s: Setlist): List<Track> = s.songs.filter { !it.tape && it.title.isNotBlank() }.map { song ->
        CsvCodec.withStableId(Track(id = "", title = song.title, artists = listOfNotNull((song.by ?: s.artist).takeIf { it.isNotBlank() })))
    }

    /** The playlist's name: "Artist · Venue, City · 2026-11-08". */
    fun playlistName(s: Setlist): String =
        listOf(s.artist, listOf(s.venue, s.city).filter { it.isNotBlank() }.joinToString(", "), s.date).filter { it.isNotBlank() }.joinToString(" · ")

    /** The day's calls ran out: the next call is not worth sending. */
    class QuotaGone : Exception("setlist.fm: today's limit reached. Try again tomorrow.")
    class KeyRefused : Exception("setlist.fm refused the key: check it in Settings.")

    /**
     * The latest setlists of [artists] ([PER_ARTIST] each). [progress] gets (done, total). Never
     * throws for one artist's failure: that artist just has none. Without a key nothing is asked.
     */
    suspend fun find(env: Env, artists: List<String>, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val key = key(env)
        val own = ownKey(env) != null
        val asked = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { norm(it) }
        if (key == null) return Result(emptyList(), asked.size, hasKey = false, ownKey = false, quotaHit = false, keyRefused = false)
        val list = asked.take(if (own) MAX_OWN else MAX_SHARED)
        var quotaHit = false
        var refused = false
        var unanswered = 0
        val out = ArrayList<ArtistSetlists>()
        list.forEachIndexed { i, artist ->
            progress(i, list.size)
            val found = cachedArtist(env, artist) ?: if (quotaHit || refused) null else try {
                searchArtist(env, key, artist)
            } catch (e: QuotaGone) {
                quotaHit = true; null
            } catch (e: KeyRefused) {
                refused = true; null
            }
            if (found == null && !quotaHit && !refused) unanswered++
            out += ArtistSetlists(artist, found.orEmpty())
        }
        progress(list.size, list.size)
        if (quotaHit) env.log("setlists", "setlist.fm: today's limit reached")
        val (with, without) = out.partition { it.setlists.isNotEmpty() }
        return Result(with.sortedByDescending { it.setlists.first().date } + without, asked.size, true, own, quotaHit, refused, unanswered)
    }

    /** One setlist by its id (a shared link), from the cache or the API; null when there is no key. */
    suspend fun byId(env: Env, id: String): Setlist? {
        val key = key(env) ?: return null
        val kv = env.keyValues(CACHE)
        kv["s|$id"]?.let { saved ->
            val at = saved.substringBefore('|').toLongOrNull() ?: 0
            if (now() - at < TTL_MS) runCatching { json.decodeFromString(Setlist.serializer(), saved.substringAfter('|')) }.getOrNull()?.let { return it }
        }
        val body = try { get(env, key, "setlist/${enc(id)}") } catch (e: NotFound) { null }
            ?: throw IllegalStateException("setlist.fm has no setlist $id")
        val s = parseSetlist(parseJson(body)) ?: throw IllegalStateException("setlist.fm: no songs in this setlist")
        if (kv.size >= 2_000) kv.clear()
        kv["s|$id"] = "${now()}|" + json.encodeToString(Setlist.serializer(), s)
        return s
    }

    // ---------------------------------------------------------------- reading the answers

    /**
     * The setlists of a search answer that are [artist]'s (the name search also finds others with
     * a similar name), with songs (an announced concert has none yet), the latest [PER_ARTIST].
     */
    fun parseSearch(body: String, artist: String): List<Setlist> {
        val want = norm(artist)
        return parseJson(body)["setlist"].arr.mapNotNull { parseSetlist(it) }
            .filter { norm(it.artist) == want && it.songs.any { s -> !s.tape } }
            .sortedByDescending { it.date }
            .take(PER_ARTIST)
    }

    /** One setlist object of the API; null without an id or a date. */
    fun parseSetlist(e: JsonElement?): Setlist? {
        val id = e["id"].str ?: return null
        val date = isoDate(e["eventDate"].str) ?: return null
        val artist = e["artist"]["name"].str.orEmpty()
        // The answers have "sets": {"set": [...]}; the documentation's example a bare "set": [...].
        val sets = e["sets"]["set"].arr.ifEmpty { e["set"].arr }
        val songs = sets.flatMap { set ->
            val encore = set["encore"].int ?: 0
            set["song"].arr.mapNotNull { s ->
                val title = s["name"].str?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                Song(title, s["cover"]["name"].str?.trim()?.takeIf { it.isNotEmpty() }, s["tape"].bool == true, encore)
            }
        }
        val venue = e["venue"]
        return Setlist(
            id = id, artist = artist, date = date, venue = venue["name"].str.orEmpty(),
            city = venue["city"]["name"].str.orEmpty(), country = venue["city"]["country"]["code"].str.orEmpty().uppercase(),
            tour = e["tour"]["name"].str.orEmpty(), url = e["url"].str.orEmpty(), songs = songs,
        )
    }

    /** "23-08-1964" (setlist.fm's) as "1964-08-23". */
    private fun isoDate(d: String?): String? {
        val p = d?.split('-')?.takeIf { it.size == 3 && it[2].length == 4 } ?: return null
        return "${p[2]}-${p[1].padStart(2, '0')}-${p[0].padStart(2, '0')}"
    }

    // ---------------------------------------------------------------- cache

    private val listSerializer = ListSerializer(Setlist.serializer())

    private fun cachedArtist(env: Env, artist: String): List<Setlist>? {
        val saved = env.keyValues(CACHE)["a|${norm(artist)}"] ?: return null
        val at = saved.substringBefore('|').toLongOrNull() ?: return null
        if (now() - at >= TTL_MS) return null
        return runCatching { json.decodeFromString(listSerializer, saved.substringAfter('|')) }.getOrNull()
    }

    private fun rememberArtist(env: Env, artist: String, setlists: List<Setlist>) {
        val kv = env.keyValues(CACHE)
        if (kv.size >= 2_000) kv.clear()
        kv["a|${norm(artist)}"] = "${now()}|" + json.encodeToString(listSerializer, setlists)
        // Each one by its id too: a setlist picked from the list (on the server's page) is not asked again.
        setlists.forEach { kv["s|${it.id}"] = "${now()}|" + json.encodeToString(Setlist.serializer(), it) }
    }

    // ---------------------------------------------------------------- the API

    private suspend fun searchArtist(env: Env, key: String, artist: String): List<Setlist>? {
        val found = try {
            parseSearch(get(env, key, "search/setlists?artistName=${enc(artist)}&p=1") ?: return null, artist)
        } catch (e: NotFound) { emptyList() }
        rememberArtist(env, artist, found)
        return found
    }

    private class NotFound : Exception()

    /** Today (UTC) as a day number. */
    private fun today() = Instant.ofEpochMilli(now()).truncatedTo(ChronoUnit.DAYS).toEpochMilli() / 86_400_000

    /** The calls this device sent with [key] today. */
    fun usedToday(env: Env, key: String): Int {
        val s = env.keyValues(STATE)["used"]?.split('|') ?: return 0
        return if (s.size == 3 && s[0] == today().toString() && s[1] == key.hashCode().toString()) s[2].toIntOrNull() ?: 0 else 0
    }

    private fun count(env: Env, key: String) {
        env.keyValues(STATE)["used"] = "${today()}|${key.hashCode()}|${usedToday(env, key) + 1}"
    }

    private fun exhaustedToday(env: Env, key: String): Boolean =
        env.keyValues(STATE)["exhausted"] == "${today()}|${key.hashCode()}"

    private fun markExhausted(env: Env, key: String) {
        env.keyValues(STATE)["exhausted"] = "${today()}|${key.hashCode()}"
    }

    /**
     * One GET to the API: the body, or throws [QuotaGone], [KeyRefused] or [NotFound] (404: "nothing
     * found" for a search). Null when it did not answer.
     */
    private suspend fun get(env: Env, key: String, path: String): String? {
        if (exhaustedToday(env, key) || usedToday(env, key) >= DAILY) throw QuotaGone()
        for (attempt in 0..1) {
            count(env, key)
            val r = try {
                pace.paced {
                    Http.send("GET", "$base/$path", mapOf("x-api-key" to key, "Accept" to "application/json", "Accept-Language" to "en"), maxRateLimitRetries = 0)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                env.log("setlists", "setlist.fm: ${e.message ?: e.javaClass.simpleName}"); return null
            }
            when {
                r.code == 404 -> throw NotFound()
                r.code == 401 || r.code == 403 -> throw KeyRefused()
                r.code == 429 -> {
                    // Over 2 a second passes after a moment; a second refusal is the day's limit.
                    if (attempt == 0) { delay(1_100); continue }
                    markExhausted(env, key)
                    throw QuotaGone()
                }
                !r.ok -> { env.log("setlists", "setlist.fm answered ${r.code}"); return null }
                else -> return r.body
            }
        }
        return null
    }

    private fun norm(s: String) = Matcher.normalizeArtist(s).ifEmpty { s.trim().lowercase() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
