package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Followed podcasts: how one show is told from another across services and files, and the feed
 * addresses an OPML file needs.
 *
 * A show is the same show when, in this order:
 *  1. both sides know its RSS feed and the feed is the same (an OPML file, Apple's directory);
 *  2. both sides know its id in Apple's podcast directory (iTunes): the same id, the same show; two
 *     ids, two shows;
 *  3. otherwise by name: the show's title, normalised, and its publisher where both sides have one.
 *
 * Spotify, Deezer and YouTube Music give neither a feed nor an Apple id, so between them the name
 * decides. Apple's public directory (the iTunes Search API, no key, no account) fills the gaps: a
 * show read from an OPML file gets its publisher from there (found by its feed), and a show of a
 * service gets its feed from there for an OPML export (found by its name and publisher). Apple asks
 * for about twenty calls a minute: every call goes through [pace], names Trackhop
 * ([ArtistMixSource.userAgent]), and its answer is remembered for weeks. Podcast Index is not used:
 * it has no path without a key.
 */
object Podcasts {
    /** Apple's documented ceiling is "approximately 20 calls per minute": one call every three seconds. */
    @Volatile var pace = Pacer(3_000)
        internal set

    /** Tests point it at a local server. */
    @Volatile internal var base = "https://itunes.apple.com"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }

    private const val PREFS = "itunes_podcasts"
    private const val TTL_MS = 30L * 24 * 3_600_000
    /** "Nothing found" is asked again sooner: the directory gains shows every day. */
    private const val NEGATIVE_TTL_MS = 7L * 24 * 3_600_000
    private const val MAX = 5_000
    private const val MAX_FAILURES = 3
    private const val REST_MS = 15L * 60_000

    private val memory = object : LinkedHashMap<String, List<Track>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Track>>?) = size > 1_000
    }
    @Volatile private var failures = 0
    @Volatile private var restUntil = 0L

    /** Tests only: forget what was looked up and any rest after failures. */
    internal fun reset() {
        synchronized(memory) { memory.clear() }
        failures = 0
        restUntil = 0
    }

    // ---------------------------------------------------------------- telling shows apart

    private val SCHEME = Regex("""^(?:feed:|itpc:|pcast:|podcast:)?(?:[a-z][a-z0-9+.-]*:)?//""")
    private val DEFAULT_PORT = Regex("""^([^/]+):(?:80|443)(?=/|$)""")

    /**
     * A feed address reduced to what names the feed: no scheme ("http", "https", "feed:", "itpc:"),
     * no "www.", no default port, no fragment, no trailing slash, in lower case. Null when it is not
     * an address.
     */
    fun feedKey(url: String?): String? {
        var s = url?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val addressed = SCHEME.containsMatchIn(s)
        s = SCHEME.replace(s, "").removePrefix("www.").substringBefore('#').trimEnd('/', '?')
        s = DEFAULT_PORT.replace(s, "$1")
        val host = s.substringBefore('/')
        return s.takeIf { host.isNotEmpty() && s.none(Char::isWhitespace) && (addressed || '.' in host) }
    }

    /** An Apple podcast id: digits only, from a bare id or a podcasts.apple.com / itunes.apple.com link. */
    fun itunesId(raw: String?): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.all { it.isDigit() }) return s.trimStart('0').ifEmpty { null }
        return APPLE_LINK.find(s)?.groupValues?.get(1)
    }
    private val APPLE_LINK = Regex("""(?:podcasts|itunes)\.apple\.com/.*?\bid(\d{3,})""", RegexOption.IGNORE_CASE)

    private val APOSTROPHES = Regex("""[’'`´]""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val TRAILING_PODCAST = Regex("""\s+(?:the\s+)?podcast$""")

    /** A show's title for comparing: no accents, case or punctuation, no leading "the", no trailing "podcast". */
    fun showTitle(s: String): String {
        var t = Matcher.stripDiacritics(s).lowercase().replace("&", " and ")
        t = APOSTROPHES.replace(t, "")
        t = NON_ALNUM.replace(t, " ").trim()
        t = t.removePrefix("the ").trim()
        val bare = TRAILING_PODCAST.replace(t, "").trim()
        return bare.ifEmpty { t }
    }

    /** True when either show carries a feed or an Apple id: only then is [score] used by the matcher. */
    fun knowsMore(a: Track, b: Track): Boolean =
        listOf(a, b).any { feedKey(it.feedUrl) != null || itunesId(it.itunesId) != null }

    /**
     * 1.0 when [a] and [b] are surely one show (the same feed, or the same Apple id), 0.0 when they
     * are surely two (two Apple ids), null when only their names can tell.
     */
    fun identity(a: Track, b: Track): Double? {
        val fa = feedKey(a.feedUrl)
        if (fa != null && fa == feedKey(b.feedUrl)) return 1.0
        val ia = itunesId(a.itunesId)
        val ib = itunesId(b.itunesId)
        if (ia != null && ib != null) return if (ia == ib) 1.0 else 0.0
        return null
    }

    /**
     * How surely [b] is the show [a], 0..1, on the scale of [Matcher.score]: [identity] first, then
     * the title, with the publisher where both have one. The same title without a publisher on one
     * side is a match (0.92); a different publisher sends it to the review.
     */
    fun score(a: Track, b: Track): Double {
        identity(a, b)?.let { return it }
        val title = Matcher.similarity(showTitle(a.title), showTitle(b.title))
        val names = if (a.artists.isNotEmpty() && b.artists.isNotEmpty()) 0.75 * title + 0.25 * Matcher.artistScore(a.artists, b.artists)
            else 0.92 * title
        // Two feeds, both known and different: often the same show moved, sometimes another one.
        val feedsDiffer = feedKey(a.feedUrl) != null && feedKey(b.feedUrl) != null
        return if (feedsDiffer) names * 0.9 else names
    }

    // ---------------------------------------------------------------- Apple's directory

    /** Shows of Apple's directory for [term]; empty when none, or when the directory could not be asked. Never throws. */
    suspend fun search(env: Env, term: String): List<Track> {
        val t = term.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return cached(env, "s:" + showTitle(t).ifEmpty { t.lowercase() }) {
            "$base/search?media=podcast&entity=podcast&limit=10&term=${Http.enc(t)}"
        }
    }

    /** The show with Apple id [id]; null when the directory does not know it, or could not be asked. Never throws. */
    suspend fun lookup(env: Env, id: String): Track? {
        val k = itunesId(id) ?: return null
        return cached(env, "l:$k") { "$base/lookup?entity=podcast&id=$k" }.firstOrNull { it.itunesId == k }
    }

    private suspend fun cached(env: Env, key: String, url: () -> String): List<Track> {
        synchronized(memory) { memory[key] }?.let { return it }
        val prefs = env.keyValues(PREFS)
        prefs[key]?.let { saved ->
            val at = saved.substringBefore('|').toLongOrNull() ?: 0
            val shows = runCatching { decode(saved.substringAfter('|')) }.getOrNull()
            if (shows != null && now() - at < (if (shows.isEmpty()) NEGATIVE_TTL_MS else TTL_MS)) {
                synchronized(memory) { memory[key] = shows }
                return shows
            }
        }
        if (now() < restUntil) return emptyList()
        val shows = try {
            pace.paced {
                // No retry inside: Apple answers a burst with 403 for a while, and asking again at once makes it longer.
                val r = Http.send("GET", url(), mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                    maxRateLimitRetries = 0, retryServerErrors = false)
                if (r.ok) results(parseJson(r.body)) else null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (shows == null) {
            // Not remembered: a busy directory is not "no such show".
            if (++failures >= MAX_FAILURES) {
                failures = 0
                restUntil = now() + REST_MS
                env.log("podcasts", "Apple's podcast directory is not answering: left alone for ${REST_MS / 60_000} minutes")
            }
            return emptyList()
        }
        failures = 0
        synchronized(memory) { memory[key] = shows }
        if (prefs.size >= MAX) prefs.clear()
        prefs[key] = "${now()}|${encode(shows)}"
        return shows
    }

    /** The shows of an iTunes Search API answer (search or lookup): podcasts only, each with its Apple id. */
    internal fun results(j: JsonElement?): List<Track> = j["results"].arr.mapNotNull { r ->
        if (r["kind"].str?.let { it != "podcast" } == true) return@mapNotNull null
        if (r["wrapperType"].str?.let { it != "track" && it != "collection" } == true) return@mapNotNull null
        val id = (r["collectionId"].long ?: r["trackId"].long)?.toString() ?: return@mapNotNull null
        val title = (r["collectionName"].str ?: r["trackName"].str)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Track(
            id = id, title = title, artists = listOfNotNull(r["artistName"].str?.trim()?.takeIf { it.isNotEmpty() }),
            kind = Track.KIND_PODCAST, feedUrl = r["feedUrl"].str?.trim()?.takeIf { feedKey(it) != null }, itunesId = id,
        )
    }

    private fun encode(shows: List<Track>): String = JsonArray(shows.map { s ->
        buildJsonObject {
            put("id", s.itunesId ?: s.id); put("t", s.title)
            s.artists.firstOrNull()?.let { put("p", it) }
            s.feedUrl?.let { put("f", it) }
        }
    }).toString()

    private fun decode(text: String): List<Track> = parseJson(text).arr.mapNotNull { o ->
        val id = o["id"].str ?: return@mapNotNull null
        Track(id = id, title = o["t"].str ?: return@mapNotNull null, artists = listOfNotNull(o["p"].str),
            kind = Track.KIND_PODCAST, feedUrl = o["f"].str, itunesId = id)
    }

    // ---------------------------------------------------------------- what the syncs and exports ask

    /**
     * [show] with what Apple's directory says of it where that is sure: a show known by its feed or
     * its Apple id (an OPML file) gets the publisher and the id it lacked, so the target's search
     * results can be told apart by publisher. A show known by its name alone is left as it is: the
     * directory would only guess. Anything else, and any failure, gives [show] back.
     */
    suspend fun enrich(env: Env, show: Track): Track {
        if (show.kind != Track.KIND_PODCAST) return show
        if (show.artists.isNotEmpty() && show.itunesId != null) return show
        val feed = feedKey(show.feedUrl)
        val hit = when {
            itunesId(show.itunesId) != null -> lookup(env, show.itunesId!!)
            feed != null && show.title.isNotBlank() -> search(env, show.title).firstOrNull { feedKey(it.feedUrl) == feed }
            else -> null
        } ?: return show
        return show.copy(
            artists = show.artists.ifEmpty { hit.artists },
            itunesId = show.itunesId ?: hit.itunesId,
            feedUrl = show.feedUrl ?: hit.feedUrl,
        )
    }

    /** Below this a directory entry is not taken as the show: a wrong feed would follow another podcast. */
    const val FEED_THRESHOLD = Matcher.REVIEW_THRESHOLD

    /**
     * [shows] each with its feed address, for an OPML file: the ones that have none are looked up in
     * Apple's directory, by Apple id when known, else by name (and publisher). A show the directory
     * does not know surely stays without one. [onProgress] gets the lookups done and to do.
     */
    suspend fun withFeeds(env: Env, shows: List<Track>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Track> {
        val total = shows.count { feedKey(it.feedUrl) == null }
        var done = 0
        return shows.map { s ->
            if (feedKey(s.feedUrl) != null) return@map s
            val hit = itunesId(s.itunesId)?.let { lookup(env, it) }
                ?: search(env, s.title).map { it to score(s, it) }.filter { it.second >= FEED_THRESHOLD }.maxByOrNull { it.second }?.first
            onProgress(++done, total)
            if (hit?.feedUrl == null) s else s.copy(feedUrl = hit.feedUrl, itunesId = s.itunesId ?: hit.itunesId)
        }
    }
}
