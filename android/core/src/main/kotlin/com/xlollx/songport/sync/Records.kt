package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.Track
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
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
 * links to the artist (official site, Bandcamp, mail-order shop, downloads, social), the merch on
 * sale in those shops ([Merch]: their public storefront data; the records sold there go with the
 * records) and searches on Bandcamp, Discogs
 * and general shops. No link carries an affiliate tag. [Look] says which part is looked up.
 *
 * Discogs needs no key: anonymous calls are allowed 25 a minute ([anonPace]). With the user's own
 * personal access token ([TOKEN_ID] in the token vault, encrypted on the phone) it is 60 a minute
 * ([tokenPace]), more artists are looked up, and the first records get "N for sale from X" from
 * the marketplace ([PRICED]). Discogs' API terms: every call names the app in its User-Agent, the
 * data is shown with "Data provided by Discogs" linking to Discogs, and nothing shown is more than
 * six hours older than on Discogs ([DISCOGS_TTL_MS]). MusicBrainz's links change seldom and are
 * kept a week ([LINKS_TTL_MS]), through the app's one-a-second MusicBrainz pace.
 *
 * Two artists can share a name ("Eldritch"): Discogs numbers them ("Eldritch (2)"), MusicBrainz
 * gives each an id. The artist shown is the playlist's own: MusicBrainz's link to the artist's
 * Discogs page names the exact one; else the one whose records carry the titles the playlist holds
 * ([Evidence]); when nothing tells them apart, the most owned is shown with [ArtistRecords.sameName].
 */
object Records {
    /** The user's own Discogs token, in the token vault (never in settings or the exported set-up). */
    const val TOKEN_ID = "__discogs__"
    /** Where a personal access token is made: Discogs › Settings › Developers. */
    const val TOKEN_URL = "https://www.discogs.com/settings/developers"
    /** Records shown per artist, the most owned first. */
    const val PER_ARTIST = 8
    /** With a token, how many of an artist's records get the marketplace's copies and lowest price. */
    const val PRICED = 3
    /** Discogs' terms: nothing shown more than six hours older than on Discogs. */
    const val DISCOGS_TTL_MS = 6L * 3_600_000
    const val LINKS_TTL_MS = 7L * 24 * 3_600_000
    /** An artist MusicBrainz did not know is asked again sooner: it may be added. */
    const val NO_LINKS_TTL_MS = 24L * 3_600_000
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
        /** Discogs' small cover (the search's thumb, as its API gives it); "" when none. */
        val thumb: String = "",
    ) {
        /** Where the copies for sale are listed. */
        val sellUrl: String get() = "https://www.discogs.com/sell/release/$releaseId"
    }

    enum class Kind { OFFICIAL, SHOP, BANDCAMP, DOWNLOAD, SOCIAL, DISCOGS }

    /** What is looked up and shown: records and merch, the records on Discogs only, or the shops' merch only. */
    enum class Look {
        ALL, RECORDS, MERCH;
        val records: Boolean get() = this != MERCH
        val merch: Boolean get() = this != RECORDS
        /** Whether a search for this covers [other] too. */
        fun covers(other: Look) = this == ALL || this == other

        companion object {
            /** "all", "records" or "merch" (any case); null for anything else. */
            fun of(name: String?): Look? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
        }
    }

    /** A page MusicBrainz links to the artist; [label] the site's name. */
    @Serializable
    data class Link(val kind: Kind, val label: String, val url: String)

    /** A search on a shop: [site] its name, null for a web search. */
    data class Search(val site: String?, val url: String)

    data class ArtistRecords(
        val artist: String,
        /** Null when Discogs could not be asked (limit, network) or was not ([Look.MERCH]): the searches still work. */
        val records: List<Record>?,
        val links: List<Link>,
        val searches: List<Search>,
        /** The artist on Discogs: MusicBrainz's link when it has one, else a search. The attribution links here. */
        val discogsPage: String,
        /** The merch in the artist's shops ([Merch]), records left out; null when not shown ([Look.RECORDS]). */
        val merch: List<Merch.Item>? = null,
        /** The records (vinyl, CD, cassette) on sale in the artist's own shops ([Merch.Item.format]); null when not shown ([Look.MERCH]). */
        val shopRecords: List<Merch.Item>? = null,
        /** Their shops (or MusicBrainz, which names them) could not be read: nothing found is not "nothing there" ([Merch.Found.unread]). */
        val shopsUnread: Boolean = false,
        /** Of those, a shop said "too many requests": try again in a few minutes ([Merch.Found.busy]). */
        val shopsBusy: Boolean = false,
        /** Others share the artist's name and nothing told which is the playlist's: what is shown may be another's. */
        val sameName: Boolean = false,
    )

    /**
     * What a playlist says about one of its artists, to tell them from others of the same name: the
     * titles of its [albums] and [tracks] there ([Matcher.normalizeTitle]).
     */
    data class Evidence(val albums: Set<String>, val tracks: Set<String>) {
        val titles: Set<String> get() = albums + tracks
    }

    /** The playlist's album and track titles of each artist (its first), by [norm] of the name. */
    fun evidenceOf(tracks: List<Track>): Map<String, Evidence> {
        val albums = HashMap<String, MutableSet<String>>()
        val titles = HashMap<String, MutableSet<String>>()
        fun add(to: HashMap<String, MutableSet<String>>, k: String, title: String) {
            // Two letters say nothing: only real titles count.
            val t = Matcher.normalizeTitle(title).takeIf { it.length >= 3 } ?: return
            to.getOrPut(k) { LinkedHashSet() } += t
        }
        for (t in tracks) {
            val name = t.artists.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val k = norm(name)
            if (t.kind == Track.KIND_ALBUM) add(albums, k, t.title) else { add(titles, k, t.title); add(albums, k, t.album) }
        }
        return (albums.keys + titles.keys).associateWith { Evidence(albums[it].orEmpty(), titles[it].orEmpty()) }
    }

    data class Result(
        val artists: List<ArtistRecords>,
        /** The artists asked for, before [LookupCaps.RECORDS_TOKEN]/[LookupCaps.RECORDS_ANON]. */
        val total: Int,
        val token: Boolean,
        /** Discogs refused the token (401): the rest was asked without it. */
        val tokenRefused: Boolean,
        /** Discogs asked to slow down (429): the artists after have no records this time. */
        val slowedDown: Boolean,
        val currency: String,
        val look: Look = Look.ALL,
        /** When the oldest of the Discogs answers shown was asked (kept ones are older than the search); 0 when none. */
        val at: Long = 0L,
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
     * [look] says what is asked and shown: Discogs is not called for [Look.MERCH]; the artists' shops
     * are read for every look (their records are shown with the records, the rest as merch).
     */
    suspend fun find(
        env: Env, artists: List<String>, currency: String = currencyFor(), whole: Boolean = true, look: Look = Look.ALL,
        progress: (Int, Int) -> Unit = { _, _ -> }, shops: Boolean = true,
        evidence: Map<String, Evidence> = emptyMap(), partial: (Result) -> Unit = {},
    ): Result {
        val cur = currency.uppercase().takeIf { it in CURRENCIES } ?: "EUR"
        var token = token(env)
        val hadToken = token != null
        val asked = asked(artists)
        val list = LookupCaps.cut(asked, if (hadToken) LookupCaps.RECORDS_TOKEN else LookupCaps.RECORDS_ANON, whole)
        val startedAt = now()
        var refused = false
        var slowed = false
        val out = ArrayList<ArtistRecords>()
        list.forEachIndexed { i, artist ->
            currentCoroutineContext().ensureActive()
            progress(i, list.size)
            val ev = evidence[norm(artist)]
            val (records, mb) = coroutineScope {
                val mb = async { musicBrainz(env, artist, ev) }
                val records = if (slowed || !look.records) null else try {
                    discogs(env, token, artist, cur, ev) { mb.await() }
                } catch (e: TokenRefused) {
                    refused = true; token = null
                    try { discogs(env, null, artist, cur, ev) { mb.await() } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                } catch (e: SlowDown) {
                    slowed = true; null
                }
                records to mb.await()
            }
            val links = mb?.links
            // The shops are read for every look: their records go with the records, the rest is the merch.
            // Without MusicBrainz no shop is known: not read, rather than nothing there. Without [shops]
            // the app reads them itself, one artist at a time as their card shows.
            if (links == null) env.log("records", "$artist: MusicBrainz did not answer")
            out += artistRecords(artist, records, mb, look, when {
                links == null -> Merch.Found(emptyList(), unread = true)
                shops -> Merch.find(env, artist, links)
                else -> null
            })
            // What is found so far: kept when the user stops the search.
            partial(Result(ArtistPick.byName(out) { it.artist }, asked.size, hadToken, refused, slowed, cur, look, if (look.records) startedAt else 0L))
        }
        progress(list.size, list.size)
        if (slowed) env.log("records", "Discogs asked to slow down: stopped for now")
        val at = if (look.records) list.mapNotNull { discogsAt(env, if (refused) null else token, it, cur) }.minOrNull() ?: startedAt else 0L
        return Result(ArtistPick.byName(out) { it.artist }, asked.size, hadToken, refused, slowed, cur, look, at)
    }

    private fun asked(artists: List<String>) =
        artists.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { Matcher.normalizeArtist(it).ifEmpty { it.lowercase() } }

    private fun artistRecords(artist: String, discogs: DiscogsKept?, mb: MbKept?, look: Look, found: Merch.Found?): ArtistRecords {
        val (shopRecords, merch) = found?.items.orEmpty().partition { it.format != null }
        val links = mb?.links
        // A Discogs page MusicBrainz is not sure of is not linked to: the search is.
        val page = links?.firstOrNull { it.kind == Kind.DISCOGS && !mb.sameName }?.url ?: discogsSearch(artist)
        return ArtistRecords(
            artist, discogs?.records, links.orEmpty().filter { it.kind != Kind.DISCOGS }, searches(artist), page,
            found?.let { merch.takeIf { look.merch } }, found?.let { shopRecords.takeIf { look.records } },
            found?.unread == true, found?.busy == true,
            sameName = (look.records && discogs?.sameName == true) || mb?.sameName == true,
        )
    }

    /**
     * What [find] would show, from what is kept alone, with no request at all: null unless every artist's
     * links (and, for records, Discogs answer) are still fresh. The shops are left to the app ([find]'s shops = false).
     * The page shows this at once when opened again, and asks only when something is missing or too old.
     */
    fun fromCache(env: Env, artists: List<String>, currency: String = currencyFor(), whole: Boolean = true, look: Look = Look.ALL): Result? {
        val cur = currency.uppercase().takeIf { it in CURRENCIES } ?: "EUR"
        val token = token(env)
        val asked = asked(artists)
        val list = LookupCaps.cut(asked, if (token != null) LookupCaps.RECORDS_TOKEN else LookupCaps.RECORDS_ANON, whole)
        if (list.isEmpty()) return null
        val out = list.map { artist ->
            val links = cachedLinks(env, artist) ?: return null
            val records = if (!look.records) null else cached(env, discogsKey(token, artist, cur), DISCOGS_TTL_MS) { json.decodeFromString(DiscogsKept.serializer(), it) } ?: return null
            artistRecords(artist, records, links, look, null)
        }
        val at = if (look.records) list.mapNotNull { discogsAt(env, token, it, cur) }.minOrNull() ?: return null else 0L
        return Result(ArtistPick.byName(out) { it.artist }, asked.size, token != null, false, false, cur, look, at)
    }

    class TokenRefused : Exception("Discogs refused the token")
    class SlowDown : Exception("Discogs: too many requests")

    // ---------------------------------------------------------------- cache

    /** An artist's records as kept: [sameName] when other artists share the name and nothing told them apart. */
    @Serializable
    internal data class DiscogsKept(val records: List<Record>, val sameName: Boolean = false)

    /** An artist's MusicBrainz pages as kept: [sameName] when several artists have the name and nothing told them apart. */
    @Serializable
    internal data class MbKept(val links: List<Link>, val sameName: Boolean = false)

    internal fun <T> cached(env: Env, k: String, ttl: Long, read: (String) -> T): T? {
        val saved = env.keyValues(CACHE)[k] ?: return null
        val at = saved.substringBefore('|').toLongOrNull() ?: return null
        if (now() - at >= ttl) return null
        return runCatching { read(saved.substringAfter('|')) }.getOrNull()
    }

    /** When [k] was kept, or null. */
    private fun keptAt(env: Env, k: String): Long? = env.keyValues(CACHE)[k]?.substringBefore('|')?.toLongOrNull()

    /** When the artist's Discogs answer in use was asked, while still fresh. */
    private fun discogsAt(env: Env, token: String?, artist: String, currency: String): Long? =
        keptAt(env, discogsKey(token, artist, currency))?.takeIf { now() - it < DISCOGS_TTL_MS }

    // "dg3"/"mb2": answers kept before artists of the same name were told apart are not shown again.
    private fun discogsKey(token: String?, artist: String, currency: String) = if (token != null) "dg3|$currency|${norm(artist)}" else "dg3|${norm(artist)}"
    private fun linksKey(artist: String) = "mb2|${norm(artist)}"

    /** The artist's links kept a week, or a day when MusicBrainz knew none. */
    private fun cachedLinks(env: Env, artist: String): MbKept? {
        val k = linksKey(artist)
        val kept = cached(env, k, LINKS_TTL_MS) { json.decodeFromString(MbKept.serializer(), it) } ?: return null
        return kept.takeIf { it.links.isNotEmpty() || cached(env, k, NO_LINKS_TTL_MS) { true } != null }
    }

    internal fun remember(env: Env, k: String, value: String) {
        val kv = env.keyValues(CACHE)
        if (kv.size >= 4_000) kv.clear()
        kv[k] = "${now()}|$value"
    }

    internal fun norm(artist: String) = Matcher.normalizeArtist(artist).ifEmpty { artist.lowercase() }

    // ---------------------------------------------------------------- Discogs

    /**
     * The artist's records on Discogs; null when Discogs did not answer (then nothing is remembered).
     * When the search holds several artists of that name, [mb] (MusicBrainz's answer, with its link
     * to the artist on Discogs) and [ev] (the playlist's titles) say which ([chooseCredit]).
     */
    private suspend fun discogs(env: Env, token: String?, artist: String, currency: String, ev: Evidence?, mb: suspend () -> MbKept?): DiscogsKept? {
        val ck = discogsKey(token, artist, currency)
        cached(env, ck, DISCOGS_TTL_MS) { json.decodeFromString(DiscogsKept.serializer(), it) }?.let { return it }
        val search = discogsGet(token, "database/search?" + Http.query(mapOf(
            "artist" to artist, "type" to "release", "sort" to "have", "sort_order" to "desc", "per_page" to "100",
        ))) ?: return null
        val credits = credits(search.body, artist)
        // MusicBrainz's link names the very artist: one more call, kept with the records for six hours.
        val exact = if (credits.isEmpty()) null else mb()?.takeIf { !it.sameName }?.links
            ?.firstNotNullOfOrNull { if (it.kind == Kind.DISCOGS) discogsArtistId(it.url) else null }
            ?.let { id -> discogsGet(token, "artists/$id")?.let { r -> runCatching { parseJson(r.body)["name"].str }.getOrNull() } }
        val choice = chooseCredit(credits, exact, ev)
        if (choice.sameName) env.log("records", "$artist: several artists of this name on Discogs, none matching the playlist")
        var records = parseSearch(search.body, artist, choice.name)
        if (token != null) records = records.mapIndexed { i, r ->
            if (i >= PRICED) r else {
                val stats = runCatching { discogsGet(token, "marketplace/stats/${r.releaseId}?curr_abbr=$currency") }.getOrNull()
                stats?.let { s -> parseStats(s.body)?.let { (n, price, cur) -> r.copy(forSale = n, lowestPrice = price, currency = cur) } } ?: r
            }
        }
        val kept = DiscogsKept(records, choice.sameName)
        remember(env, ck, json.encodeToString(DiscogsKept.serializer(), kept))
        return kept
    }

    private val DISCOGS_ARTIST = Regex("^https?://(?:www\\.)?discogs\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?artist/(\\d+)", RegexOption.IGNORE_CASE)

    /** The artist id in a Discogs artist page's address ("https://www.discogs.com/artist/123-Name"), or null. */
    fun discogsArtistId(url: String): Long? = DISCOGS_ARTIST.find(url.trim())?.groupValues?.get(1)?.toLongOrNull()

    /** One artist credited in a Discogs search: [name] as Discogs writes it ("Eldritch (2)"), the [titles] of its records there. */
    data class Credit(val name: String, val titles: Set<String>)

    /** Which artist's records to show: [name] null for any of that name (nothing to choose), [sameName] when it is a guess. */
    data class Choice(val name: String?, val sameName: Boolean)

    /** [credit] as Discogs names the artist: variant and alias marks gone, its number kept ("Eldritch (2)"). */
    private fun rawCredit(credit: String) = credit.substringBefore(" = ").trim().trimEnd('*').trim()

    /**
     * The artists named [artist] in a database search (Discogs numbers the others: "Eldritch (2)"),
     * the most owned first, each with the titles of its records there.
     */
    fun credits(body: String, artist: String): List<Credit> {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        val names = LinkedHashMap<String, String>()
        val titles = HashMap<String, MutableSet<String>>()
        for (e in parseJson(body)["results"].arr) {
            val full = (e as? JsonObject)?.get("title")?.str ?: continue
            val credit = full.substringBefore(" - ", "")
            if (credit.isEmpty() || Matcher.normalizeArtist(creditName(credit)) != want) continue
            val raw = rawCredit(credit)
            val k = Labels.fold(raw)
            names.putIfAbsent(k, raw)
            titles.getOrPut(k) { HashSet() } += Matcher.normalizeTitle(full.substringAfter(" - "))
        }
        return names.map { (k, name) -> Credit(name, titles[k].orEmpty()) }
    }

    /**
     * Which of the artists [credits] (most owned first) is the playlist's. One alone is it.
     * [exact], the name of the one MusicBrainz links to, is it, unless the playlist's titles ([ev])
     * are only on another's records. Else the one whose records hold the playlist's titles; when
     * none (or several alike) do, the most owned, as a guess ([Choice.sameName]).
     */
    fun chooseCredit(credits: List<Credit>, exact: String?, ev: Evidence?): Choice {
        if (credits.isEmpty()) return Choice(exact, false)
        val titles = ev?.titles.orEmpty()
        val overlap = credits.associateWith { c -> c.titles.count { it in titles } }
        val top = overlap.values.max()
        val best = credits.singleOrNull { overlap[it] == top }?.takeIf { top > 0 }
        if (exact != null) {
            val named = credits.firstOrNull { Labels.fold(it.name) == Labels.fold(exact) }
            val evidenceElsewhere = (named == null || overlap[named] == 0) && best != null
            return Choice(if (evidenceElsewhere) best!!.name else named?.name ?: exact, false)
        }
        return when {
            credits.size == 1 -> Choice(credits[0].name, false)
            best != null -> Choice(best.name, false)
            else -> Choice(credits[0].name, true)
        }
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
     * master (its editions' formats together, its first year), the first [PER_ARTIST]. With
     * [credit] (one of [credits]), only that artist's: "Eldritch (2)" is not "Eldritch".
     */
    fun parseSearch(body: String, artist: String, credit: String? = null): List<Record> {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        val exact = credit?.let { Labels.fold(it) }
        class Group(val first: JsonObject, val formats: LinkedHashSet<Format>, var year: Int?)
        val groups = LinkedHashMap<String, Group>()
        for (e in parseJson(body)["results"].arr) {
            val o = e as? JsonObject ?: continue
            val full = o["title"].str ?: continue
            val credit = full.substringBefore(" - ", "")
            if (credit.isEmpty() || Matcher.normalizeArtist(creditName(credit)) != want) continue
            if (exact != null && Labels.fold(rawCredit(credit)) != exact) continue
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
            // Discogs puts a blank spacer where there is no picture.
            val thumb = webUrl(o["thumb"].str).takeUnless { it.endsWith("spacer.gif") }.orEmpty()
            Record(title, g.year, Format.entries.filter { it in g.formats }, url, id, o["community"]["have"].int ?: 0, thumb = thumb)
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

    /**
     * The artist's pages on MusicBrainz (empty when it does not know the artist); null when it could
     * not be asked. Of several artists of that name, the one with the playlist's titles ([ev]).
     */
    private suspend fun musicBrainz(env: Env, artist: String, ev: Evidence?): MbKept? {
        val ck = linksKey(artist)
        cachedLinks(env, artist)?.let { return it }
        val found = mbGet("artist?query=${enc("artist:" + phrase(artist))}&limit=5&fmt=json") ?: return null
        val ids = pickArtists(found, artist)
        val (id, sameName) = if (ids.size <= 1) ids.firstOrNull() to false else chooseArtist(ids, ev?.let { mbHits(ids, it) })
        val links = if (id == null) emptyList() else parseLinks(mbGet("artist/$id?inc=url-rels&fmt=json") ?: return null)
        val kept = MbKept(links, sameName)
        // A guess made with no titles to go by is not kept: the page, which has them, asks again.
        if (!sameName || ev != null) remember(env, ck, json.encodeToString(MbKept.serializer(), kept))
        return kept
    }

    private fun phrase(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * How many of the playlist's titles ([ev]) MusicBrainz has by each of [ids]: one search, of the
     * albums (or, with none, of the tracks), by those artists only. Null when it could not be asked.
     */
    private suspend fun mbHits(ids: List<String>, ev: Evidence): Map<String, Int>? {
        val (entity, field, titles) = if (ev.albums.isNotEmpty()) Triple("release-group", "releasegroup", ev.albums) else Triple("recording", "recording", ev.tracks)
        if (titles.isEmpty()) return null
        val q = "(" + ids.joinToString(" OR ") { "arid:$it" } + ") AND (" + titles.take(8).joinToString(" OR ") { "$field:" + phrase(it) } + ")"
        return parseHits(mbGet("$entity?query=${enc(q)}&limit=50&fmt=json") ?: return null, ids, titles)
    }

    /** Of a release-group or recording search, how many answers with one of [titles] each of [ids] is credited on. */
    fun parseHits(body: String, ids: List<String>, titles: Set<String>): Map<String, Int> {
        val o = parseJson(body)
        val hits = HashMap<String, Int>()
        for (r in o["release-groups"].arr + o["recordings"].arr) {
            if (Matcher.normalizeTitle(r["title"].str.orEmpty()) !in titles) continue
            r["artist-credit"].arr.mapNotNull { MusicBrainz.mbid(it["artist"]["id"].str) }.distinct()
                .filter { it in ids }.forEach { hits[it] = (hits[it] ?: 0) + 1 }
        }
        return hits
    }

    /** Of several artists of the same name ([ids], best first), the one with most of the playlist's titles ([hits]); else the first, as a guess. */
    fun chooseArtist(ids: List<String>, hits: Map<String, Int>?): Pair<String, Boolean> {
        val top = hits?.values?.maxOrNull() ?: 0
        val best = ids.filter { (hits?.get(it) ?: 0) == top }.singleOrNull()?.takeIf { top > 0 }
        return if (best != null) best to false else ids.first() to true
    }

    /** The ids of the artist search's answers named exactly [artist] (score 90 or more), best first. */
    fun pickArtists(body: String, artist: String): List<String> {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return emptyList()
        return parseJson(body)["artists"].arr.filter { a ->
            (a["score"].int ?: 0) >= 90 && Matcher.normalizeArtist(a["name"].str.orEmpty()) == want
        }.mapNotNull { MusicBrainz.mbid(it["id"].str) }.distinct()
    }

    /** The id of the artist search's best answer named exactly [artist] (score 90 or more), or null. */
    fun pickArtist(body: String, artist: String): String? = pickArtists(body, artist).firstOrNull()

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

    /**
     * The links shown as the artist's official pages: each page once, and each name once (of two
     * Apple, Qobuz or X pages the shortest address, of two official sites the shortest). The shops
     * are named by their site ("Amazon", "band-store.com") when there are several, each once; the
     * one official site, and a shop when it is the only one, get an empty [Link.label]: shown as
     * "Official site" / "Official shop".
     */
    fun shownLinks(links: List<Link>): List<Link> {
        fun address(l: Link) = runCatching { URI(l.url) }.getOrNull()
            ?.let { (it.host.orEmpty().lowercase().removePrefix("www.") + it.rawPath.orEmpty().trimEnd('/')) + (it.rawQuery?.let { q -> "?$q" } ?: "") }
            ?: l.url
        val pages = links.distinctBy { address(it) }
        val shops = pages.filter { it.kind == Kind.SHOP }.distinctBy { it.label }.size
        fun name(l: Link) = when {
            l.kind == Kind.OFFICIAL || (l.kind == Kind.SHOP && shops == 1) -> ""
            else -> l.label
        }
        val best = pages.groupBy { "${if (name(it).isEmpty()) it.kind.name else ""}|${name(it)}" }
            .mapValues { (_, same) -> same.minBy { address(it).length } }
        return pages.mapNotNull { l -> best["${if (name(l).isEmpty()) l.kind.name else ""}|${name(l)}"]?.takeIf { it === l }?.copy(label = name(l)) }
    }

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
