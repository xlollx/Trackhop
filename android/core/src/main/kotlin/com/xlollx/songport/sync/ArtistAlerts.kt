package com.xlollx.songport.sync

import com.xlollx.songport.data.Settings
import com.xlollx.songport.net.json
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Alerts for the artists the user follows (Discover › Followed artists, and the bell on an artist
 * in New releases, Concerts and Records & merch): once a day (or a week) the app looks them up with
 * the same sources and caches as those pages, and says what is new in one notification.
 *
 * - New releases: [NewReleases.ofArtist] (its day's cache).
 * - Concerts: [Concerts.find] in the country and time range last picked, in the background mode
 *   (Ticketmaster only with the user's own key, nothing read in a browser engine).
 * - Merch and records: [Records.find] for the one artist: Discogs (never older than its six hours),
 *   the shops through [Merch] and its one Shopify pace for every shop.
 *
 * Every artist keeps what was seen of each kind ([Seen]); only what was never seen is news. The
 * first look at a kind only takes note of what is there (no flood after following); a kind turned
 * off forgets it, so turning it on again starts over the same way; a source that did not answer
 * changes nothing. Artists are looked up one after another with a pause between them, those
 * looked up longest ago first, within a time budget: the ones left are looked up by a later run
 * ([Outcome.remaining]), and the news waits for them, so one look at all of them is one notification.
 */
object ArtistAlerts {
    @Serializable
    data class Kinds(val releases: Boolean = true, val concerts: Boolean = true, val merch: Boolean = true, val records: Boolean = true) {
        val any: Boolean get() = releases || concerts || merch || records
        operator fun get(k: Kind): Boolean = when (k) {
            Kind.RELEASE -> releases; Kind.CONCERT -> concerts; Kind.MERCH -> merch; Kind.RECORD -> records
        }
        fun with(k: Kind, on: Boolean): Kinds = when (k) {
            Kind.RELEASE -> copy(releases = on); Kind.CONCERT -> copy(concerts = on)
            Kind.MERCH -> copy(merch = on); Kind.RECORD -> copy(records = on)
        }
    }

    /** An artist followed, as the user wrote or tapped it, and what to be told about. */
    @Serializable
    data class Followed(val name: String, val kinds: Kinds = Kinds())

    enum class Kind { RELEASE, CONCERT, MERCH, RECORD }

    /**
     * Something new: [id] what tells it apart from the artist's others of its [kind]; [title] its
     * name; [detail] the release's type (a [NewReleases.Type] name) or the concert's city; [url] its page.
     */
    @Serializable
    data class News(val artist: String, val kind: Kind, val id: String, val title: String = "", val detail: String = "", val url: String = "")

    /** What was seen of an artist, per kind (null: not looked at yet), and when it was last looked up. */
    @Serializable
    data class Seen(
        val releases: List<String>? = null,
        val concerts: List<String>? = null,
        val merch: List<String>? = null,
        val records: List<String>? = null,
        val checkedAt: Long = 0,
    ) {
        operator fun get(k: Kind) = when (k) { Kind.RELEASE -> releases; Kind.CONCERT -> concerts; Kind.MERCH -> merch; Kind.RECORD -> records }
        fun with(k: Kind, ids: List<String>?) = when (k) {
            Kind.RELEASE -> copy(releases = ids); Kind.CONCERT -> copy(concerts = ids)
            Kind.MERCH -> copy(merch = ids); Kind.RECORD -> copy(records = ids)
        }
    }

    /** One look-up of an artist: per kind what is there now; null when not asked or not answered. */
    data class Found(
        val releases: List<News>? = null,
        val concerts: List<News>? = null,
        val merch: List<News>? = null,
        val records: List<News>? = null,
    ) {
        operator fun get(k: Kind) = when (k) { Kind.RELEASE -> releases; Kind.CONCERT -> concerts; Kind.MERCH -> merch; Kind.RECORD -> records }
    }

    /** How an artist is looked up: [live] in the app, a fake in the tests. */
    fun interface Lookup {
        suspend fun look(env: Env, artist: Followed): Found
    }

    /** [notify]: the news to say now (empty while artists are left, or when nothing is new). */
    data class Outcome(val notify: List<News>, val checked: Int, val remaining: Int)

    /** Ids remembered per artist and kind: the newest kept. */
    const val MAX_SEEN = 400
    /** The news kept for the Followed artists page. */
    const val MAX_LATEST = 30
    /** The pause between two artists: the sources are paced too, this spreads the look over time. */
    const val ARTIST_GAP_MS = 4_000L
    /** How long a run looks up artists: well within the 10 minutes Android gives a background job. */
    const val BUDGET_MS = 6L * 60_000
    const val DAY_MS = 24L * 3_600_000

    private const val KV = "artist_alerts"
    private val seenMap = MapSerializer(String.serializer(), Seen.serializer())
    private val newsList = ListSerializer(News.serializer())

    fun key(name: String): String = Matcher.normalizeArtist(name).ifEmpty { name.trim().lowercase() }

    // ---------------------------------------------------------------- the list

    fun isFollowed(list: List<Followed>, name: String): Boolean = key(name).let { k -> list.any { key(it.name) == k } }

    /** [list] with [name] followed (with [defaults]) or no longer followed. */
    fun toggle(list: List<Followed>, name: String, defaults: Kinds): List<Followed> {
        val k = key(name)
        if (k.isEmpty()) return list
        return if (list.any { key(it.name) == k }) list.filter { key(it.name) != k } else list + Followed(name.trim(), defaults)
    }

    fun setKinds(list: List<Followed>, name: String, kinds: Kinds): List<Followed> =
        key(name).let { k -> list.map { if (key(it.name) == k) it.copy(kinds = kinds) else it } }

    // ---------------------------------------------------------------- when

    /** How the background look runs: every [periodDays], on any network or Wi-Fi only, never on a low battery. */
    data class Plan(val periodDays: Long, val unmetered: Boolean, val batteryNotLow: Boolean = true)

    /** The plan for [s]; null when nothing is to run (alerts off, nobody followed or nothing wanted). */
    fun plan(s: Settings): Plan? {
        if (!s.artistAlerts || s.followedArtists.none { it.kinds.any }) return null
        return Plan(if (s.artistAlertsWeekly) 7 else 1, s.artistAlertsWifiOnly)
    }

    /** An artist looked up this long ago is due again: a little less than the period, as the scheduler's runs drift. */
    fun dueAfter(periodDays: Long): Long = periodDays * DAY_MS - 4 * 3_600_000

    // ---------------------------------------------------------------- what is new

    /**
     * What is new in [now] against [seen], and what to remember: nothing is new when the kind was
     * never looked at (the first look takes note) or when the source did not answer ([now] null,
     * [seen] kept). Ids gone stay remembered: a product back on sale is not news again.
     */
    fun diff(seen: List<String>?, now: List<News>?, cap: Int = MAX_SEEN): Pair<List<String>?, List<News>> {
        if (now == null) return seen to emptyList()
        val ids = now.map { it.id }.distinct()
        if (seen == null) return ids.takeLast(cap) to emptyList()
        val old = seen.toHashSet()
        val fresh = now.filter { it.id !in old }.distinctBy { it.id }
        return (seen + fresh.map { it.id }).takeLast(cap) to fresh
    }

    /** [found] against [seen] for the kinds [kinds] wants; the others forgotten. */
    fun compare(seen: Seen, found: Found, kinds: Kinds, at: Long): Pair<Seen, List<News>> {
        var s = seen.copy(checkedAt = at)
        val news = ArrayList<News>()
        for (k in Kind.entries) {
            if (!kinds[k]) { s = s.with(k, null); continue }
            val (ids, fresh) = diff(seen[k], found[k])
            s = s.with(k, ids); news += fresh
        }
        return s to news
    }

    // ---------------------------------------------------------------- the look

    /**
     * Looks up the artists of [followed] that are due (looked up longer than [dueAfter] ago, those
     * longest ago first), with [gapMs] between two, until [budgetMs] has gone. Saves what was seen
     * after each artist. The news waits until no artist is left, then is said once ([Outcome.notify])
     * and kept for the page ([latest]).
     */
    suspend fun check(
        env: Env, followed: List<Followed>, periodDays: Long, lookup: Lookup,
        budgetMs: Long = BUDGET_MS, gapMs: Long = ARTIST_GAP_MS, now: () -> Long = { System.currentTimeMillis() },
    ): Outcome {
        val start = now()
        val wanted = followed.filter { it.kinds.any }.distinctBy { key(it.name) }
        var seen = loadSeen(env)
        // Unfollowed artists are forgotten: following again starts over without a flood.
        val keys = wanted.map { key(it.name) }.toSet()
        if (seen.keys.any { it !in keys }) { seen = seen.filterKeys { it in keys }; saveSeen(env, seen) }
        val due = wanted.filter { start - (seen[key(it.name)]?.checkedAt ?: 0) >= dueAfter(periodDays) }
            .sortedBy { seen[key(it.name)]?.checkedAt ?: 0 }
        var checked = 0
        for (f in due) {
            if (checked > 0) {
                if (now() - start >= budgetMs) break
                delay(gapMs)
            }
            val found = try {
                lookup.look(env, f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.log("alerts", "${f.name}: ${e.message ?: e.javaClass.simpleName}"); Found()
            }
            val (s, news) = compare(seen[key(f.name)] ?: Seen(), found, f.kinds, now())
            seen = seen + (key(f.name) to s)
            saveSeen(env, seen)
            if (news.isNotEmpty()) savePending(env, pending(env) + news)
            checked++
        }
        val remaining = due.size - checked
        env.log("alerts", "looked up $checked of ${wanted.size} artists" + if (remaining > 0) ", $remaining left for later" else "")
        if (remaining > 0) return Outcome(emptyList(), checked, remaining)
        val out = pending(env)
        if (out.isNotEmpty()) {
            savePending(env, emptyList())
            val kv = env.keyValues(KV)
            kv["latest"] = json.encodeToString(newsList, (out + latest(env)).distinctBy { it.artist + it.kind + it.id }.take(MAX_LATEST))
        }
        return Outcome(out, checked, 0)
    }

    /** The last news said, newest first, for Discover › Followed artists. */
    fun latest(env: Env): List<News> = read(env, "latest")

    private fun pending(env: Env): List<News> = read(env, "pending")
    private fun savePending(env: Env, news: List<News>) { env.keyValues(KV)["pending"] = json.encodeToString(newsList, news) }
    private fun read(env: Env, k: String): List<News> =
        env.keyValues(KV)[k]?.let { runCatching { json.decodeFromString(newsList, it) }.getOrNull() }.orEmpty()

    private fun loadSeen(env: Env): Map<String, Seen> =
        env.keyValues(KV)["seen"]?.let { runCatching { json.decodeFromString(seenMap, it) }.getOrNull() }.orEmpty()

    private fun saveSeen(env: Env, seen: Map<String, Seen>) { env.keyValues(KV)["seen"] = json.encodeToString(seenMap, seen) }

    // ---------------------------------------------------------------- what to say

    /** One thing to say: the first of an artist's news of a kind, and how many there are. */
    data class Line(val first: News, val count: Int)

    /** The news as lines, one per artist and kind, in the order found. */
    fun group(news: List<News>): List<Line> =
        news.groupBy { key(it.artist) to it.kind }.values.map { Line(it.first(), it.size) }

    /** "New single by ADTR, Architects concert in Milan, new merch from BMTH", in the user's language. */
    fun summary(env: Env, news: List<News>): String = group(news).joinToString(", ") { phrase(env, it.first) }

    fun phrase(env: Env, n: News): String = when (n.kind) {
        Kind.RELEASE -> env.text(
            when (n.detail) { NewReleases.Type.SINGLE.name -> "alerts_news_single"; NewReleases.Type.EP.name -> "alerts_news_ep"; else -> "alerts_news_album" },
            n.artist,
        )
        Kind.CONCERT -> if (n.detail.isBlank()) env.text("alerts_news_concert_somewhere", n.artist) else env.text("alerts_news_concert", n.artist, n.detail)
        Kind.MERCH -> env.text("alerts_news_merch", n.artist)
        Kind.RECORD -> env.text("alerts_news_record", n.artist)
    }

    // ---------------------------------------------------------------- the sources

    /** What the Discover pages use, for one artist: concerts in [country] (an ISO code) within [range]. */
    fun live(country: String, range: Concerts.Range): Lookup = Lookup { env, f ->
        val name = f.name
        val k = f.kinds
        val releases = if (!k.releases) null else NewReleases.ofArtist(env, name)?.map { r ->
            News(name, Kind.RELEASE, "r|${r.type}|${Matcher.normalizeTitle(r.title)}", r.title, r.type.name, r.link)
        }
        val concerts = if (!k.concerts) null else Concerts.find(env, listOf(name), country, range, whole = false, background = true).let { r ->
            // Ticketmaster out of calls or refusing the key: its dates would look new tomorrow.
            if (r.quotaHit || r.keyRefused) null
            else r.artists.flatMap { it.events }.map { e ->
                News(name, Kind.CONCERT, "c|${e.date}|${Matcher.normalizeArtist(e.city)}", e.venue.ifBlank { e.name }, e.city, e.url)
            }
        }
        var merch: List<News>? = null
        var records: List<News>? = null
        if (k.merch || k.records) {
            val look = if (k.merch && k.records) Records.Look.ALL else if (k.records) Records.Look.RECORDS else Records.Look.MERCH
            val a = Records.find(env, listOf(name), whole = false, look = look).artists.firstOrNull()
            if (a != null && !a.shopsUnread) {
                if (k.merch) merch = a.merch?.map { News(name, Kind.MERCH, "m|${it.url}", it.title, it.type, it.url) }
                // Discogs and the shop both needed: half an answer would make the other half new tomorrow.
                if (k.records && a.records != null) records = a.records.map { News(name, Kind.RECORD, "d|${it.releaseId}", it.title, "", it.url) } +
                    a.shopRecords.orEmpty().map { News(name, Kind.RECORD, "s|${it.url}", it.title, it.type, it.url) }
            }
        }
        Found(releases, concerts, merch, records)
    }
}
