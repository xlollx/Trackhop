package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URLEncoder
import java.util.Currency
import java.util.Locale

/**
 * "Records and merch of the artists of this playlist" (Discover): for each artist, their records
 * on CD, vinyl and cassette from the Discogs database, the official shop and pages MusicBrainz
 * links to the artist (official site, Bandcamp, mail-order shop, downloads, social), and searches
 * on Bandcamp, Discogs and general shops. Links only: no shop page is ever read, and no link
 * carries an affiliate tag.
 *
 * Discogs needs no key: anonymous calls are allowed 25 a minute ([anonPace]). With the user's own
 * personal access token ([TOKEN_ID] in the token vault, encrypted on the phone) it is 60 a minute
 * ([tokenPace]), more artists are looked up, and the first records get "N for sale from X" from
 * the marketplace ([PRICED]). Discogs' API terms: every call names the app in its User-Agent, the
 * data is shown with "Data provided by Discogs" linking to Discogs, and nothing shown is more than
 * six hours older than on Discogs ([DISCOGS_TTL_MS]). MusicBrainz's links change seldom and are
 * kept a week ([LINKS_TTL_MS]), through the app's one-a-second MusicBrainz pace.
 */
object Records {
    /** The user's own Discogs token, in the token vault (never in settings or the exported set-up). */
    const val TOKEN_ID = "__discogs__"
    /** Where a personal access token is made: Discogs › Settings › Developers. */
    const val TOKEN_URL = "https://www.discogs.com/settings/developers"
    const val MAX_ANON = 30
    const val MAX_TOKEN = 60
    /** Records shown per artist, the most owned first. */
    const val PER_ARTIST = 8
    /** With a token, how many of an artist's records get the marketplace's copies and lowest price. */
    const val PRICED = 3
    /** Discogs' terms: nothing shown more than six hours older than on Discogs. */
    const val DISCOGS_TTL_MS = 6L * 3_600_000
    const val LINKS_TTL_MS = 7L * 24 * 3_600_000
    /** The attribution Discogs' terms ask for, next to its data, linking to it. */
    const val ATTRIBUTION = "Data provided by Discogs"
    /** The currencies Discogs' marketplace answers in. */
    val CURRENCIES = setOf("USD", "GBP", "EUR", "CAD", "AUD", "JPY", "CHF", "MXN", "BRL", "NZD", "SEK", "ZAR")

    enum class Format(val label: String) { VINYL("Vinyl"), CD("CD"), CASSETTE("Cassette") }

    /**
     * One record (a Discogs master, or a release with none): [year] the earliest seen, [formats]
     * those of its editions, [url] its page on Discogs, [releaseId] its most owned edition.
     * [forSale]/[lowestPrice]/[currency] from the marketplace, with a token only.
     */
    @Serializable
    data class Record(
        val title: String,
        val year: Int? = null,
        val formats: List<Format>,
        val url: String,
        val releaseId: Long,
        val have: Int = 0,
        val forSale: Int? = null,
        val lowestPrice: Double? = null,
        val currency: String? = null,
    ) {
        /** Where the copies for sale are listed. */
        val sellUrl: String get() = "https://www.discogs.com/sell/release/$releaseId"
    }

    enum class Kind { OFFICIAL, SHOP, BANDCAMP, DOWNLOAD, SOCIAL, DISCOGS }

    /** A page MusicBrainz links to the artist; [label] the site's name. */
    @Serializable
    data class Link(val kind: Kind, val label: String, val url: String)

    /** A search on a shop: [site] its name, null for a web search. */
    data class Search(val site: String?, val url: String)

    data class ArtistRecords(
        val artist: String,
        /** Null when Discogs could not be asked (limit, network): the searches still work. */
        val records: List<Record>?,
        val links: List<Link>,
        val searches: List<Search>,
        /** The artist on Discogs: MusicBrainz's link when it has one, else a search. The attribution links here. */
        val discogsPage: String,
    )

    data class Result(
        val artists: List<ArtistRecords>,
        /** The artists of the playlist, before [MAX_ANON]/[MAX_TOKEN]. */
        val total: Int,
        val token: Boolean,
        /** Discogs refused the token (401): the rest was asked without it. */
        val tokenRefused: Boolean,
        /** Discogs asked to slow down (429): the artists after have no records this time. */
        val slowedDown: Boolean,
        val currency: String,
    )

    // Tests point these at a local server and a fixed clock.
    @Volatile internal var discogsBase = "https://api.discogs.com"
    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }
    /** Under Discogs' 25 a minute without a token, and 60 with one, for the whole process. */
    @Volatile var anonPace = Pacer(2_500)
        internal set
    @Volatile var tokenPace = Pacer(1_050)
        internal set

    private const val CACHE = "records"

    fun token(env: Env): String? = env.tokens.get(TOKEN_ID)?.accessToken?.trim()?.takeIf { it.isNotEmpty() }

    /** Saves the user's token (blank removes it). */
    fun saveToken(env: Env, token: String) {
        val t = token.trim()
        if (t.isEmpty()) env.tokens.clear(TOKEN_ID) else env.tokens.set(TOKEN_ID, Tokens(accessToken = t))
    }

    /** The currency of [locale] when Discogs knows it, else euros. */
    fun currencyFor(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()?.takeIf { it in CURRENCIES } ?: "EUR"

    /** Searches on shops for an artist's records and merch: links only, no affiliate tag. */
    fun searches(artist: String): List<Search> {
        val q = enc(artist)
        return listOf(
            Search("Bandcamp", "https://bandcamp.com/search?q=$q&item_type=b"),
            Search("Discogs", "https://www.discogs.com/search/?q=$q&type=all"),
            Search("eBay", "https://www.ebay.com/sch/i.html?_nkw=" + enc("$artist vinyl")),
            Search(null, "https://duckduckgo.com/?q=" + enc("$artist official store merch")),
        )
    }

    fun discogsSearch(artist: String) = "https://www.discogs.com/search/?q=${enc(artist)}&type=artist"

    /**
     * Records, links and searches for [artists] (the playlist's, most held first). [progress] gets
     * (done, total). One artist's failure never stops the rest: that artist has fewer things.
     */
    suspend fun find(env: Env, artists: List<String>, currency: String = currencyFor(), progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val cur = currency.uppercase().takeIf { it in CURRENCIES } ?: "EUR"
        var token = token(env)
        val hadToken = token != null
        val asked = artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Matcher.normalizeArtist(it).ifEmpty { it.lowercase() } }
        val list = asked.take(if (hadToken) MAX_TOKEN else MAX_ANON)
        var refused = false
        var slowed = false
        val out = ArrayList<ArtistRecords>()
        list.forEachIndexed { i, artist ->
            progress(i, list.size)
            val (records, links) = coroutineScope {
                val links = async { links(env, artist) }
                val records = if (slowed) null else try {
                    discogs(env, token, artist, cur)
                } catch (e: TokenRefused) {
                    refused = true; token = null
                    runCatching { discogs(env, null, artist, cur) }.getOrNull()
                } catch (e: SlowDown) {
                    slowed = true; null
                }
                records to links.await()
            }
            val page = links.firstOrNull { it.kind == Kind.DISCOGS }?.url ?: discogsSearch(artist)
            out += ArtistRecords(artist, records, links.filter { it.kind != Kind.DISCOGS }, searches(artist), page)
        }
        progress(list.size, list.size)
        if (slowed) env.log("records", "Discogs asked to slow down: stopped for now")
        return Result(out, asked.size, hadToken, refused, slowed, cur)
    }

    class TokenRefused : Exception("Discogs refused the token")
    class SlowDown : Exception("Discogs: too many requests")

    // ---------------------------------------------------------------- cache

    private val recordsSerializer = ListSerializer(Record.serializer())
    private val linksSerializer = ListSerializer(Link.serializer())

    private fun <T> cached(env: Env, k: String, ttl: Long, read: (String) -> T): T? {
        val saved = env.keyValues(CACHE)[k] ?: return null
        val at = saved.substringBefore('|').toLongOrNull() ?: return null
        if (now() - at >= ttl) return null
        return runCatching { read(saved.substringAfter('|')) }.getOrNull()
    }

    private fun remember(env: Env, k: String, value: String) {
        val kv = env.keyValues(CACHE)
        if (kv.size >= 4_000) kv.clear()
        kv[k] = "${now()}|$value"
    }

    private fun norm(artist: String) = Matcher.normalizeArtist(artist).ifEmpty { artist.lowercase() }

    // ---------------------------------------------------------------- Discogs

    /** The artist's records on Discogs; null when Discogs did not answer (then nothing is remembered). */
    private suspend fun discogs(env: Env, token: String?, artist: String, currency: String): List<Record>? {
        val ck = if (token != null) "dg|$currency|${norm(artist)}" else "dg|${norm(artist)}"
        cached(env, ck, DISCOGS_TTL_MS) { json.decodeFromString(recordsSerializer, it) }?.let { return it }
        val search = discogsGet(token, "database/search?" + Http.query(mapOf(
            "artist" to artist, "type" to "release", "sort" to "have", "sort_order" to "desc", "per_page" to "100",
        ))) ?: return null
        var records = parseSearch(search.body, artist)
        if (token != null) records = records.mapIndexed { i, r ->
            if (i >= PRICED) r else {
                val stats = runCatching { discogsGet(token, "marketplace/stats/${r.releaseId}?curr_abbr=$currency") }.getOrNull()
                stats?.let { s -> parseStats(s.body)?.let { (n, price, cur) -> r.copy(forSale = n, lowestPrice = price, currency = cur) } } ?: r
            }
        }
        remember(env, ck, json.encodeToString(recordsSerializer, records))
        return records
    }

    /** A GET to Discogs, paced; null when it did not answer. Throws [SlowDown] on 429 and [TokenRefused] on 401 with a token. */
    private suspend fun discogsGet(token: String?, path: String): HttpResponse? {
        val headers = buildMap {
            put("User-Agent", ArtistMixSource.userAgent)
            put("Accept", "application/vnd.discogs.v2.discogs+json")
            if (token != null) put("Authorization", "Discogs token=$token")
        }
        val r = try {
            (if (token != null) tokenPace else anonPace).paced {
                Http.send("GET", "$discogsBase/$path", headers, maxRateLimitRetries = 0, retryServerErrors = false)
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        if (r.code == 429) throw SlowDown()
        if (r.code == 401 && token != null) throw TokenRefused()
        return r.takeIf { it.ok }
    }

    private val DISAMBIGUATION = Regex("""\s*\(\d+\)$""")

    /** "Radiohead = レディオヘッド*" or "Mina (3)" as the name it is: Discogs' variant, alias and number marks gone. */
    internal fun creditName(s: String): String = s.substringBefore(" = ").trim().trimEnd('*').trim().replace(DISAMBIGUATION, "")

    /** A Discogs format name as one of ours, or null (files, video, unofficial marks...). */
    fun format(name: String?): Format? = when (name?.trim()?.lowercase()) {
        "vinyl", "lathe cut", "flexi-disc" -> Format.VINYL
        "cd", "cdr", "sacd", "shm-cd", "blu-spec cd" -> Format.CD
        "cassette" -> Format.CASSETTE
        else -> null
    }

    /**
     * A database search for releases by [artist], most owned first: those credited to the artist
     * by that very name ("Artist - Title"), on CD, vinyl or cassette, not unofficial, one per
     * master (its editions' formats together, its first year), the first [PER_ARTIST].
     */
    fun parseSearch(body: String, artist: String): List<Record> {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        class Group(val first: JsonObject, val formats: LinkedHashSet<Format>, var year: Int?)
        val groups = LinkedHashMap<String, Group>()
        for (e in parseJson(body)["results"].arr) {
            val o = e as? JsonObject ?: continue
            val full = o["title"].str ?: continue
            val credit = full.substringBefore(" - ", "")
            if (credit.isEmpty() || Matcher.normalizeArtist(creditName(credit)) != want) continue
            val names = o["format"].arr.mapNotNull { it.str }
            if (names.any { it.equals("Unofficial Release", ignoreCase = true) }) continue
            val formats = names.mapNotNull { format(it) }
            if (formats.isEmpty()) continue
            val id = o["id"].long ?: continue
            val master = o["master_id"].long?.takeIf { it > 0 }
            val year = o["year"].str?.toIntOrNull()?.takeIf { it > 1800 }
            val g = groups.getOrPut(master?.let { "m$it" } ?: "r$id") { Group(o, LinkedHashSet(), year) }
            g.formats += formats
            if (year != null && (g.year == null || year < g.year!!)) g.year = year
        }
        return groups.values.take(PER_ARTIST).mapNotNull { g ->
            val o = g.first
            val id = o["id"].long ?: return@mapNotNull null
            val master = o["master_id"].long?.takeIf { it > 0 }
            val title = o["title"].str!!.substringAfter(" - ").trim()
            val uri = o["uri"].str
            val url = when {
                master != null -> "https://www.discogs.com/master/$master"
                uri != null && uri.startsWith("/") -> "https://www.discogs.com$uri"
                else -> "https://www.discogs.com/release/$id"
            }
            Record(title, g.year, Format.entries.filter { it in g.formats }, url, id, o["community"]["have"].int ?: 0)
        }
    }

    /** The marketplace's copies for sale, lowest price and its currency; null when it gives no copies or price. */
    fun parseStats(body: String): Triple<Int, Double?, String?>? {
        val o = parseJson(body)
        if (o["blocked_from_sale"].bool == true) return null
        val n = o["num_for_sale"].int ?: return null
        val p = o["lowest_price"]
        val value = (p["value"] as? JsonPrimitive)?.doubleOrNull ?: (p as? JsonPrimitive)?.doubleOrNull
        return Triple(n, value.takeIf { n > 0 }, p["currency"].str.takeIf { n > 0 && value != null })
    }

    // ---------------------------------------------------------------- MusicBrainz

    /** The artist's pages on MusicBrainz (empty when it does not know the artist, or could not be asked). */
    private suspend fun links(env: Env, artist: String): List<Link> {
        val ck = "mb|${norm(artist)}"
        cached(env, ck, LINKS_TTL_MS) { json.decodeFromString(linksSerializer, it) }?.let { return it }
        val q = "artist:\"${artist.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val found = mbGet("artist?query=${enc(q)}&limit=5&fmt=json") ?: return emptyList()
        val id = pickArtist(found, artist)
        val links = if (id == null) emptyList() else parseLinks(mbGet("artist/$id?inc=url-rels&fmt=json") ?: return emptyList())
        remember(env, ck, json.encodeToString(linksSerializer, links))
        return links
    }

    /** The id of the artist search's best answer named exactly [artist] (score 90 or more), or null. */
    fun pickArtist(body: String, artist: String): String? {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return null
        return parseJson(body)["artists"].arr.firstOrNull { a ->
            (a["score"].int ?: 0) >= 90 && Matcher.normalizeArtist(a["name"].str.orEmpty()) == want
        }?.let { MusicBrainz.mbid(it["id"].str) }
    }

    private val ORDER = Kind.entries

    /**
     * The url relations of an artist that lead to their music and merch: official homepage,
     * Bandcamp, mail-order shop, downloads to buy, social networks and Discogs. Ended relations
     * left out, each address once.
     */
    fun parseLinks(body: String): List<Link> {
        val seen = HashSet<String>()
        return parseJson(body)["relations"].arr.mapNotNull { r ->
            if (r["ended"].bool == true) return@mapNotNull null
            val url = r["url"]["resource"].str?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return@mapNotNull null
            val kind = when (r["type"].str?.lowercase()) {
                "official homepage" -> Kind.OFFICIAL
                "purchase for mail-order" -> Kind.SHOP
                "bandcamp" -> Kind.BANDCAMP
                "purchase for download" -> Kind.DOWNLOAD
                "social network" -> Kind.SOCIAL
                "discogs" -> Kind.DISCOGS
                else -> return@mapNotNull null
            }
            val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return@mapNotNull null
            if (!seen.add(host + URI(url).rawPath.orEmpty().trimEnd('/').lowercase())) return@mapNotNull null
            Link(kind, siteName(host), url.replaceFirst("http://", "https://"))
        }.sortedBy { ORDER.indexOf(it.kind) }
    }

    private val SITES = listOf(
        "bandcamp.com" to "Bandcamp", "instagram.com" to "Instagram", "facebook.com" to "Facebook", "twitter.com" to "X",
        "x.com" to "X", "tiktok.com" to "TikTok", "threads.net" to "Threads", "bsky.app" to "Bluesky", "apple.com" to "Apple",
        "qobuz.com" to "Qobuz", "beatport.com" to "Beatport", "7digital.com" to "7digital", "amazon." to "Amazon", "discogs.com" to "Discogs",
    )

    /** "Instagram" for instagram.com, "radiohead.com" for a site we do not name. */
    fun siteName(host: String): String = SITES.firstOrNull { (h, _) -> host == h || host.endsWith(".$h") || (h.endsWith(".") && h in host) }?.second ?: host

    private suspend fun mbGet(path: String): String? = try {
        MusicBrainz.pace.paced {
            val r = Http.send(
                "GET", "${MusicBrainz.base}/$path",
                mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                maxRateLimitRetries = 0, retryServerErrors = false,
            )
            r.body.takeIf { r.ok }
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
