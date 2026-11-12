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
 * off (or an artist muted) forgets it, so turning it on again starts over the same way; a source that did not answer
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

    /**
     * An artist followed and what to be told about: [name] as MusicBrainz writes it when picked from
     * the suggestions (with its [mbid]), else as the user wrote or tapped it. [muted]: still followed,
     * but not looked up and nothing said until turned on again.
     */
    @Serializable
    data class Followed(val name: String, val kinds: Kinds = Kinds(), val muted: Boolean = false, val mbid: String? = null) {
        /** Looked up by the background look: not muted, and something wanted. */
        val active: Boolean get() = !muted && kinds.any
    }

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

    /**
     * A release in a service's own feed of what is new from the artists the user follows there (the
     * Spotify web player's "What's new"): [artists] as the service names them, [date] "2026-11-07".
     */
    data class FeedRelease(val artists: List<String>, val title: String, val type: NewReleases.Type, val date: String, val url: String = "")

    /**
     * Such a feed, read once per [check]: null when there is no one to ask (no connector connected,
     * or one too old to know it). Throws when it failed; the look then goes on without it.
     */
    fun interface Feed {
        suspend fun releases(): List<FeedRelease>?
    }

    /** Set by the app where a connector has a feed (Spotify (web)); null elsewhere, and then nothing is asked. */
    @Volatile var feed: Feed? = null

    /** A feed's releases older than this are not news: the per-artist sources look back further and would have said them. */
    const val FEED_DAYS = 30L

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
        return if (list.any { key(it.name) == k }) list.filter { key(it.name) != k } else follow(list, name, defaults)
    }

    /**
     * [list] with [name] followed (with [defaults], and the [mbid] a suggestion came with); as it
     * was when the artist is followed already, however the name is written.
     */
    fun follow(list: List<Followed>, name: String, defaults: Kinds, mbid: String? = null): List<Followed> {
        val n = name.trim().replace(Regex("\\s+"), " ")
        if (key(n).isEmpty() || isFollowed(list, n)) return list
        return list + Followed(n, defaults, mbid = MusicBrainz.mbid(mbid))
    }

    /** [list] with [f] back where it was ([at]), after an unfollow undone; unchanged when followed again meanwhile. */
    fun restore(list: List<Followed>, f: Followed, at: Int): List<Followed> =
        if (isFollowed(list, f.name)) list else list.toMutableList().apply { add(at.coerceIn(0, size), f) }

    fun setKinds(list: List<Followed>, name: String, kinds: Kinds): List<Followed> =
        key(name).let { k -> list.map { if (key(it.name) == k) it.copy(kinds = kinds) else it } }

    /** [name]'s alerts as a whole off ([muted]) or on again, the artist still followed and its kinds kept. */
    fun setMuted(list: List<Followed>, name: String, muted: Boolean): List<Followed> =
        key(name).let { k -> list.map { if (key(it.name) == k) it.copy(muted = muted) else it } }

    /**
     * [list] with the artist followed as [name] written as MusicBrainz does ([canonical], with its
     * [mbid]): only when it is the same name but for case, accents and the like.
     */
    fun canonical(list: List<Followed>, name: String, canonical: String, mbid: String?): List<Followed> {
        val k = key(name)
        if (canonical.isBlank() || key(canonical) != k) return list
        return list.map { if (key(it.name) == k) it.copy(name = canonical.trim(), mbid = MusicBrainz.mbid(mbid) ?: it.mbid) else it }
    }

    /** The artists as the page lists them: A to Z, case and accents ignored. */
    fun sorted(list: List<Followed>): List<Followed> = ArtistPick.byName(list) { it.name }

    // ---------------------------------------------------------------- when

    /** How the background look runs: every [periodDays], on any network or Wi-Fi only, never on a low battery. */
    data class Plan(val periodDays: Long, val unmetered: Boolean, val batteryNotLow: Boolean = true)

    /** The plan for [s]; null when nothing is to run (alerts off, nobody followed or nothing wanted). */
    fun plan(s: Settings): Plan? {
        if (!s.artistAlerts || s.followedArtists.none { it.active }) return null
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
        feed: Feed? = this.feed,
    ): Outcome {
        val start = now()
        val wanted = followed.filter { it.active }.distinctBy { key(it.name) }
        var seen = loadSeen(env)
        // Unfollowed and muted artists are forgotten: following or turning on again starts over without a flood.
        val keys = wanted.map { key(it.name) }.toSet()
        if (seen.keys.any { it !in keys }) { seen = seen.filterKeys { it in keys }; saveSeen(env, seen) }
        val due = wanted.filter { start - (seen[key(it.name)]?.checkedAt ?: 0) >= dueAfter(periodDays) }
            .sortedBy { seen[key(it.name)]?.checkedAt ?: 0 }
        // The service's own feed, once for the whole look: an extra source of releases next to each artist's own.
        val fromFeed = if (feed != null && due.any { it.kinds.releases }) readFeed(env, feed, due, now()) else emptyMap()
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
            val before = seen[key(f.name)] ?: Seen()
            val merged = withFeed(found, before.releases, fromFeed[key(f.name)], f.kinds)
            val (s, news) = compare(before, merged, f.kinds, now()).let { (s0, n) -> (if (merged === found) s0 else withAliases(s0, merged, found)) to n }
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

    /**
     * The feed's releases of [due]'s artists, by [key]: out in the last [FEED_DAYS] days or announced.
     * Empty when the feed had nothing to say or failed (the per-artist sources answer as before).
     */
    private suspend fun readFeed(env: Env, feed: Feed, due: List<Followed>, at: Long): Map<String, List<News>> {
        val list = try {
            feed.releases() ?: return emptyMap()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            env.log("alerts", "the service's feed of new releases: ${e.message ?: e.javaClass.simpleName}; the artists' own sources only"); return emptyMap()
        }
        val from = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusDays(FEED_DAYS)
        val wanted = due.filter { it.kinds.releases }.associateBy { key(it.name) }
        val out = HashMap<String, MutableList<News>>()
        for (r in list) {
            val day = NewReleases.dayOf(r.date)
            if (day == null || day.isBefore(from) || r.title.isBlank()) continue
            for (k in r.artists.map { key(it) }.distinct()) {
                val f = wanted[k] ?: continue
                out.getOrPut(k) { ArrayList() } += News(f.name, Kind.RELEASE, "r|${r.type}|${Matcher.normalizeTitle(r.title)}", r.title, r.type.name, r.url)
            }
        }
        env.log("alerts", "the service's feed of new releases: ${list.size} listed, ${out.values.sumOf { it.size }} by the artists followed here")
        return out
    }

    /**
     * [found] with the feed's releases ([extra]) added: one the artist's own sources list, or
     * remembered ([seen]), under the same title and kind is left out, so the same release is never
     * said twice (a single and an EP are one kind: the sources do not agree on which is which; a
     * single and the album of the same name stay two releases, as the sources alone have them). The
     * sources' answer stays as it was when the feed adds nothing; when they did not answer, the
     * feed's releases are the answer (only what was never seen is news).
     */
    internal fun withFeed(found: Found, seen: List<String>?, extra: List<News>?, kinds: Kinds): Found {
        if (!kinds.releases || extra.isNullOrEmpty()) return found
        val known = (found.releases.orEmpty().map { it.id } + seen.orEmpty()).mapNotNullTo(HashSet()) { releaseKey(it) }
        val add = extra.filter { releaseKey(it.id) !in known }.distinctBy { releaseKey(it.id) }
        if (add.isEmpty()) return found
        return found.copy(releases = found.releases.orEmpty() + add)
    }

    /**
     * [s] remembering, next to a single or EP that came from the feed only (in [merged], not in
     * [found]), the same release as the other of the two: the artist's own sources may list it
     * tomorrow as the other, and it is not news again.
     */
    private fun withAliases(s: Seen, merged: Found, found: Found): Seen {
        val own = found.releases.orEmpty().mapTo(HashSet()) { it.id }
        val ids = s.releases ?: return s
        val aliases = merged.releases.orEmpty().filter { it.id !in own }.mapNotNull { n ->
            val type = n.id.split('|').getOrNull(1)
            val other = when (type) { NewReleases.Type.SINGLE.name -> NewReleases.Type.EP.name; NewReleases.Type.EP.name -> NewReleases.Type.SINGLE.name; else -> null }
            other?.let { "r|$it|" + n.id.substringAfter('|').substringAfter('|') }
        }.filter { it !in ids }
        return if (aliases.isEmpty()) s else s.copy(releases = (ids + aliases).takeLast(MAX_SEEN))
    }

    /** A release id's title and kind (a single and an EP alike); null for anything else. */
    private fun releaseKey(id: String): String? {
        val parts = id.split('|', limit = 3)
        if (parts.size < 3 || parts[0] != "r") return null
        val kind = if (parts[1] == NewReleases.Type.SINGLE.name || parts[1] == NewReleases.Type.EP.name) "S" else parts[1]
        return kind + "|" + parts[2]
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
            val a = Records.find(env, listOf(name), whole = false, look = look, background = true).artists.firstOrNull()
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
