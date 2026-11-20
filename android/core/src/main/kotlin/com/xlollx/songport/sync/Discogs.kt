package com.xlollx.songport.sync

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/**
 * "My records" (Discover): the user's own Discogs collection and wantlist, from the official API
 * with their personal token ([Records.token], the one Records & merch already uses; 60 calls a
 * minute, [Records.tokenPace]):
 * - `GET /oauth/identity` says whose token it is;
 * - `GET /users/{name}/collection/folders/0/releases` the records, `…/collection/value` Discogs' own
 *   estimate of what they are worth (minimum, median, maximum, in the account's currency);
 * - `GET /users/{name}/wants` the wantlist, `GET /marketplace/stats/{release}` what is for sale of
 *   each and from which price ([Records.parseStats]);
 * - `GET /releases/{id}` a record's songs, for "My records as a playlist".
 *
 * Discogs' API terms: the data is shown with [Records.ATTRIBUTION] linking to Discogs, nothing shown
 * is more than six hours older than on Discogs ([Records.DISCOGS_TTL_MS]), and it is not passed on:
 * what is read stays on this device (the kept answers) and goes nowhere. Nothing is bought or paid
 * here: a record's page on Discogs opens in the browser.
 */
object Discogs {
    /** The records of a collection or wantlist read at most: 100 a page. */
    const val MAX_PAGES = 30
    /** A record's songs change seldom: asked once a month. */
    const val TRACKS_TTL_MS = 30L * 24 * 3_600_000
    /** The wanted records whose market is asked when the wantlist is shown (those with an alert first). */
    const val MARKET_SHOWN = 25
    /** The alerts looked at in one background run. */
    const val MAX_ALERTS = 50
    /** The look for the alerts runs once a day, whatever wakes it. */
    const val CHECK_EVERY_MS = 20L * 3_600_000

    private const val KV = "discogs"
    private const val TRACKS = "discogs_tracks"

    /** One record (a release): [url] its page on Discogs, [sellUrl] its copies for sale. */
    @Serializable
    data class Item(val id: Long, val title: String, val artist: String, val year: Int? = null, val thumb: String = "", val formats: List<String> = emptyList()) {
        val url: String get() = "https://www.discogs.com/release/$id"
        val sellUrl: String get() = "https://www.discogs.com/sell/release/$id"
    }

    /** Discogs' estimate of a collection, as it writes it ("€1,240.00"). */
    @Serializable
    data class Value(val minimum: String, val median: String, val maximum: String)

    @Serializable
    data class Collection(val user: String, val items: List<Item>, val value: Value? = null, val at: Long = 0)

    /** What the marketplace has of a record: [lowest] null when none is for sale. */
    @Serializable
    data class Market(val forSale: Int, val lowest: Double? = null, val currency: String? = null, val at: Long = 0)

    /** [markets] by release id, for the records asked so far. */
    @Serializable
    data class Wantlist(val user: String, val items: List<Item>, val markets: Map<String, Market> = emptyMap(), val at: Long = 0)

    class NoToken : Exception("Discogs: no token")

    private fun now() = Records.now()

    private suspend fun get(env: Env, path: String): String {
        val token = Records.token(env) ?: throw NoToken()
        val r = try { Records.discogsGet(token, path) } catch (e: Records.SlowDown) { throw ProviderException(env.text("records_slowed")) }
            catch (e: Records.TokenRefused) { throw ProviderException(env.text("records_token_refused"), code = 401) }
        return r?.body ?: throw ProviderException(env.text("records_discogs_down"))
    }

    /** Whose token it is: asked once per token. */
    suspend fun user(env: Env): String {
        val token = Records.token(env) ?: throw NoToken()
        val kv = env.keyValues(KV)
        kv["user"]?.split('|', limit = 2)?.takeIf { it.size == 2 && it[0] == token.hashCode().toString() && it[1].isNotBlank() }?.let { return it[1] }
        val name = parseJson(get(env, "oauth/identity"))["username"].str ?: throw ProviderException(env.text("records_discogs_down"))
        kv["user"] = "${token.hashCode()}|$name"
        return name
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------------------------------------------------------- reading the answers

    /** A record of a collection or wantlist page ("basic_information"); null without an id or a title. */
    fun parseItem(e: JsonElement?): Item? {
        val b = e["basic_information"]
        val id = b["id"].long ?: e["id"].long ?: return null
        val title = b["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val thumb = webUrl(b["thumb"].str).takeUnless { it.endsWith("spacer.gif") }.orEmpty()
        return Item(
            id, title, b["artists"].arr.mapNotNull { it["name"].str }.joinToString(", ") { Records.creditName(it) },
            b["year"].int?.takeIf { it > 1800 }, thumb, b["formats"].arr.mapNotNull { it["name"].str }.distinct(),
        )
    }

    /** A page's records under [key] ("releases", "wants") and how many pages there are. */
    fun parsePage(body: String, key: String): Pair<List<Item>, Int> {
        val j = parseJson(body)
        return j[key].arr.mapNotNull { parseItem(it) } to (j["pagination"]["pages"].int ?: 1)
    }

    fun parseValue(body: String): Value? {
        val j = parseJson(body)
        return Value(j["minimum"].str ?: return null, j["median"].str.orEmpty(), j["maximum"].str ?: return null)
    }

    /**
     * A release's songs as tracks: its "track" lines (no headings, no index tracks), each under its
     * own artists when it names some (a compilation), else the release's; the album is the release.
     */
    fun parseTracks(body: String): List<Track> {
        val j = parseJson(body)
        val album = j["title"].str.orEmpty()
        val artists = j["artists"].arr.mapNotNull { it["name"].str }.map { Records.creditName(it) }
        return j["tracklist"].arr.mapNotNull { t ->
            if (t["type_"].str?.let { it != "track" } == true) return@mapNotNull null
            val title = t["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val own = t["artists"].arr.mapNotNull { it["name"].str }.map { Records.creditName(it) }
            CsvCodec.withStableId(Track(id = "", title = title, artists = own.ifEmpty { artists }.filter { it.isNotBlank() && !it.equals("Various", ignoreCase = true) }, album = album))
        }
    }

    // ---------------------------------------------------------------- collection and wantlist

    private suspend fun pages(env: Env, path: String, key: String, progress: (Int, Int) -> Unit): List<Item> {
        val all = ArrayList<Item>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            progress(page - 1, pages)
            val (items, total) = parsePage(get(env, "$path?page=$page&per_page=100"), key)
            all += items
            pages = total.coerceIn(1, MAX_PAGES)
            page++
        }
        return all.distinctBy { it.id }
    }

    private fun <T> kept(env: Env, k: String, s: kotlinx.serialization.KSerializer<T>): T? =
        env.keyValues(KV)[k]?.takeIf { it.isNotEmpty() }?.let { runCatching { json.decodeFromString(s, it) }.getOrNull() }

    /** The collection last read, whatever its age (for a page opened again, before it is read anew). */
    fun keptCollection(env: Env): Collection? = kept(env, "collection", Collection.serializer())
    fun keptWantlist(env: Env): Wantlist? = kept(env, "wantlist", Wantlist.serializer())

    fun keep(env: Env, c: Collection) { env.keyValues(KV)["collection"] = json.encodeToString(Collection.serializer(), c) }
    fun keep(env: Env, w: Wantlist) { env.keyValues(KV)["wantlist"] = json.encodeToString(Wantlist.serializer(), w) }

    /**
     * The collection, as added (the latest first), and its value: the one kept while within Discogs' six
     * hours, unless [refresh]. An empty one kept is read again (one call): a record just added on Discogs shows.
     */
    suspend fun collection(env: Env, refresh: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Collection {
        val user = user(env)
        keptCollection(env)?.takeIf { !refresh && it.user == user && it.items.isNotEmpty() && now() - it.at < Records.DISCOGS_TTL_MS }?.let { return it }
        val items = pages(env, "users/${enc(user)}/collection/folders/0/releases", "releases", progress)
        // The value is the owner's to see; a failure leaves the records without it.
        val value = try { parseValue(get(env, "users/${enc(user)}/collection/value")) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return Collection(user, items.asReversed(), value, now()).also { keep(env, it) }
    }

    /**
     * The wantlist with the market of its first [MARKET_SHOWN] records (those in [first], the ones
     * with an alert, before the others), in [currency]: kept while within Discogs' six hours, unless [refresh].
     */
    suspend fun wantlist(env: Env, currency: String, first: Set<String> = emptySet(), refresh: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Wantlist {
        val user = user(env)
        keptWantlist(env)?.takeIf { !refresh && it.user == user && it.items.isNotEmpty() && now() - it.at < Records.DISCOGS_TTL_MS }?.let { return it }
        val items = pages(env, "users/${enc(user)}/wants", "wants") { _, _ -> }
        val ask = (items.filter { it.id.toString() in first } + items).distinctBy { it.id }.take(MARKET_SHOWN)
        val markets = HashMap<String, Market>()
        ask.forEachIndexed { i, item ->
            progress(i, ask.size)
            market(env, item.id, currency)?.let { markets[item.id.toString()] = it }
        }
        return Wantlist(user, items, markets, now()).also { keep(env, it) }
    }

    /** What is for sale of a release and from which price; null when Discogs did not say. */
    suspend fun market(env: Env, releaseId: Long, currency: String): Market? {
        val body = try { get(env, "marketplace/stats/$releaseId?curr_abbr=$currency") } catch (e: ProviderException) { if (e.code == 401) throw e else return null }
        val (n, price, cur) = Records.parseStats(body) ?: return Market(0, null, null, now())
        return Market(n, price, cur, now())
    }

    // ---------------------------------------------------------------- as a playlist

    private val trackList = ListSerializer(Track.serializer())

    /**
     * The songs of the collection's records, record after record as added (the latest first), each
     * record's songs asked once a month. A record Discogs does not answer for is asked at the next
     * run; when none answers the run fails, so a sync never empties its playlist for it.
     */
    suspend fun collectionTracks(env: Env, progress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        val items = collection(env).items
        val kv = env.keyValues(TRACKS)
        val out = ArrayList<Track>()
        var answered = 0
        items.forEachIndexed { i, item ->
            progress(i, items.size)
            val saved = kv[item.id.toString()]?.let { s ->
                val at = s.substringBefore('|').toLongOrNull() ?: 0
                if (now() - at < TRACKS_TTL_MS) runCatching { json.decodeFromString(trackList, s.substringAfter('|')) }.getOrNull() else null
            }
            val tracks = saved ?: try {
                parseTracks(get(env, "releases/${item.id}")).also {
                    if (kv.size >= 5_000) kv.clear()
                    kv[item.id.toString()] = "${now()}|" + json.encodeToString(trackList, it)
                }
            } catch (e: CancellationException) { throw e } catch (e: NoToken) { throw e } catch (_: Exception) { null }
            if (tracks != null) { answered++; out += tracks }
        }
        if (items.isNotEmpty() && answered == 0) throw ProviderException(env.text("records_discogs_down"))
        return out.distinctBy { it.id }
    }

    // ---------------------------------------------------------------- wantlist alerts

    /** What to be told about a wanted record: a [newCopy] for sale, a price [below] an amount (in the app's currency for the user). */
    @Serializable
    data class WantAlert(val newCopy: Boolean = false, val below: Double? = null) {
        val any: Boolean get() = newCopy || below != null
    }

    /** What the last look saw of a wanted record, and the price last told as "below". */
    @Serializable
    data class Seen(val forSale: Int, val lowest: Double? = null, val told: Double? = null)

    enum class Kind { NEW_COPY, BELOW }

    /** Something to tell: [price] the lowest now, in [currency]. */
    data class Hit(val item: Item, val kind: Kind, val price: Double?, val currency: String?, val below: Double? = null)

    /**
     * What is new in [now] against [seen] for [alert], and what to remember. A new copy: more for
     * sale than at the last look (the first look only takes note). A price below the amount: told
     * the first time it is, and again only when it has gone lower still, or back above and below again.
     */
    fun diff(alert: WantAlert, seen: Seen?, now: Market): Pair<Seen, List<Kind>> {
        val kinds = ArrayList<Kind>()
        if (alert.newCopy && seen != null && now.forSale > seen.forSale) kinds += Kind.NEW_COPY
        var told = seen?.told
        val below = alert.below
        if (below != null && now.lowest != null && now.lowest < below) {
            if (told == null || now.lowest < told) { kinds += Kind.BELOW; told = now.lowest }
        } else told = null
        // One thing said per record: the price says more than "a new copy".
        return Seen(now.forSale, now.lowest, told) to (if (Kind.BELOW in kinds) listOf(Kind.BELOW) else kinds)
    }

    private val seenMap = MapSerializer(String.serializer(), Seen.serializer())

    /**
     * The daily look at the wanted records with an alert ([alerts], by release id): at most once in
     * [CHECK_EVERY_MS] and [MAX_ALERTS] records a run, each one call. Never throws: a record Discogs
     * did not answer for is looked at again tomorrow. Without a token nothing is asked.
     */
    suspend fun checkWants(env: Env, alerts: Map<String, WantAlert>, currency: String): List<Hit> {
        val wanted = alerts.filterValues { it.any }
        val kv = env.keyValues(KV)
        if (wanted.isEmpty() || Records.token(env) == null) return emptyList()
        if (now() - (kv["checked"]?.toLongOrNull() ?: 0) < CHECK_EVERY_MS) return emptyList()
        kv["checked"] = now().toString()
        var seen = kv["seen"]?.let { runCatching { json.decodeFromString(seenMap, it) }.getOrNull() }.orEmpty().filterKeys { it in wanted }
        val names = keptWantlist(env)?.items.orEmpty().associateBy { it.id.toString() }
        val hits = ArrayList<Hit>()
        for ((id, alert) in wanted.entries.take(MAX_ALERTS)) {
            val market = try { market(env, id.toLongOrNull() ?: continue, currency) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: continue
            val (s, kinds) = diff(alert, seen[id], market)
            seen = seen + (id to s)
            val item = names[id] ?: Item(id.toLong(), "Discogs #$id", "")
            kinds.forEach { hits += Hit(item, it, market.lowest, market.currency, alert.below) }
        }
        kv["seen"] = json.encodeToString(seenMap, seen)
        if (hits.isNotEmpty()) env.log("discogs", "wantlist: ${hits.size} to tell")
        return hits
    }
}
