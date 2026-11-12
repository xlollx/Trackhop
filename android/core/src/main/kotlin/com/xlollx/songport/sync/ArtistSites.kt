package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.Pacer
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URLEncoder

/**
 * An artist's own site (and Bandcamp, and shop) when MusicBrainz names none: many artists who sell
 * only on their own site (rappers often) have no MusicBrainz links. Two places say it:
 *
 * - Wikidata, the public `wikidata.org/w/api.php`: a search of the artist's name, then the entities
 *   found, of which the artist is the one with the MusicBrainz id we know (P434), or the Spotify id
 *   (P1902), else the only one named exactly so that is a musician or a band ([chooseEntity]); a
 *   namesake (a footballer, another band with another MusicBrainz id) is never taken. Its official
 *   website (P856) and its Bandcamp (P3283). Two requests an artist at most, one at a time, with
 *   `maxlag` (a busy Wikidata is not asked harder: not answered), every request naming the app;
 * - Spotify's artist page ([spotify], set by the app where the Spotify (web) connector is connected,
 *   never in the background): its links and the merch it shows.
 *
 * What is found is kept a week ([TTL_MS]; nothing, [EMPTY_TTL_MS]) and goes with MusicBrainz's links
 * to [Merch] and [SiteConcerts]. [enabled] is set by the app and the server at start.
 */
object ArtistSites {
    const val TTL_MS = 7L * 24 * 3_600_000
    const val EMPTY_TTL_MS = 24L * 3_600_000
    /** Entities read from a name's search. */
    const val CANDIDATES = 7
    /** Wikidata requests per artist. */
    const val MAX_WIKIDATA = 2

    /** Off in the core's own tests of other sources: the app and the server turn it on. */
    @Volatile var enabled = false

    // Tests point this at a local server.
    @Volatile internal var wikidataBase = "https://www.wikidata.org/w/api.php"
    /** One request at a time to Wikidata, at most two a second, for the whole process. */
    @Volatile var wikidataPace = Pacer(500)
        internal set

    /** What else is known of the artist: their MusicBrainz id, their Wikidata item (MusicBrainz's link to it). */
    data class Ids(val musicBrainz: String? = null, val wikidata: String? = null)

    /** An artist's page on Spotify: its [id], the [links] it shows, the addresses of the [merch] on its shelf. */
    data class SpotifyArtist(val id: String, val links: List<String> = emptyList(), val merch: List<String> = emptyList())

    interface SpotifyReader {
        /** The artist the player's search finds under [name]; null when none of that name, or no one to ask. Throws when it failed. */
        suspend fun artist(name: String): SpotifyArtist?
    }

    @Volatile var spotify: SpotifyReader? = null

    /** The pages found ([links]), and whether a source could not be asked ([unread]: not kept, asked again next time). */
    data class Found(val links: List<Records.Link>, val unread: Boolean = false)

    private val serializer = ListSerializer(Records.Link.serializer())

    /**
     * [links] (MusicBrainz's) and, when they name neither an official site nor a shop, the pages found
     * beyond MusicBrainz ([find]). Unchanged when not [enabled].
     */
    suspend fun extend(env: Env, artist: String, links: List<Records.Link>, ids: Ids = Ids(), background: Boolean = false): Found {
        if (!enabled || links.any { it.kind == Records.Kind.OFFICIAL || it.kind == Records.Kind.SHOP }) return Found(links)
        val more = find(env, artist, ids, background)
        val seen = links.map { key(it.url) }.toHashSet()
        return Found(links + more.links.filter { seen.add(key(it.url)) }, more.unread)
    }

    /**
     * The artist's official site: MusicBrainz's when its links are kept ([Records.cachedLinks], no request
     * here), else Wikidata's or Spotify's ([find]). Null when none is known ([Found.unread] when a source did not answer).
     */
    suspend fun official(env: Env, artist: String, background: Boolean = false): Pair<String?, Boolean> {
        if (!enabled) return null to false
        val mb = Records.keptLinks(env, artist)
        mb?.links?.firstOrNull { it.kind == Records.Kind.OFFICIAL }?.let { return it.url to false }
        val found = find(env, artist, if (mb == null || mb.sameName) Ids() else Ids(mb.id, mb.wikidata), background)
        return found.links.firstOrNull { it.kind == Records.Kind.OFFICIAL }?.url to found.unread
    }

    private fun key(url: String) = runCatching { URI(url) }.getOrNull()?.let { (it.host.orEmpty().lowercase().removePrefix("www.") + it.rawPath.orEmpty().trimEnd('/')) } ?: url

    /** The pages Wikidata and Spotify know for [artist], kept a week. */
    suspend fun find(env: Env, artist: String, ids: Ids = Ids(), background: Boolean = false): Found {
        val ck = "sites1|${Records.norm(artist)}|${ids.musicBrainz.orEmpty()}"
        Records.cached(env, ck, TTL_MS) { json.decodeFromString(serializer, it) }
            ?.takeIf { it.isNotEmpty() || Records.cached(env, ck, EMPTY_TTL_MS) { true } != null }?.let { return Found(it) }
        val steps = ArrayList<String>()
        var unread = false
        val out = ArrayList<Records.Link>()
        // Spotify first: its id tells the artist apart on Wikidata.
        val sp = if (background) null else spotify?.let { r ->
            try { r.artist(artist) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                steps += "Spotify (web): ${e.message ?: e.javaClass.simpleName}"; null
            }
        }
        if (sp != null) {
            out += spotifyLinks(sp)
            steps += "Spotify (web): ${sp.links.size + sp.merch.size} links"
        }
        val wd = try { wikidata(artist, ids, sp?.id, steps) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            steps += "Wikidata: ${e.javaClass.simpleName}"; null
        }
        if (wd == null) unread = true else out += wd
        val links = out.distinctBy { key(it.url) }
        env.log("sites", "$artist, beyond MusicBrainz: " + steps.joinToString("; ").ifEmpty { "nothing" } + " → " +
            links.joinToString(", ") { "${it.kind.name.lowercase()} ${it.label}" }.ifEmpty { "no site" })
        // Wikidata not answering is not "no site": asked again next time (Spotify alone is not kept either).
        if (!unread) Records.remember(env, ck, json.encodeToString(serializer, links))
        return Found(links, unread && links.isEmpty())
    }

    private val SOCIAL = listOf(
        "instagram.com", "facebook.com", "twitter.com", "x.com", "tiktok.com", "youtube.com", "youtu.be", "threads.net",
        "soundcloud.com", "wikipedia.org", "spotify.com", "linktr.ee", "snapchat.com", "twitch.tv", "bsky.app", "discord.gg",
    )

    private fun hostOf(url: String) = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()

    /** A Spotify page's links: a site that is not a social network is the official one; the merch on its shelf, the shop's address. */
    internal fun spotifyLinks(sp: SpotifyArtist): List<Records.Link> {
        val out = ArrayList<Records.Link>()
        for (u in sp.links.map { webUrl(it) }.filter { it.isNotEmpty() }) {
            val h = hostOf(u) ?: continue
            if (SOCIAL.any { h == it || h.endsWith(".$it") }) continue
            out += if (h.endsWith("bandcamp.com")) Records.Link(Records.Kind.BANDCAMP, "Bandcamp", u) else Records.Link(Records.Kind.OFFICIAL, Records.siteName(h), u)
        }
        for (u in sp.merch.map { webUrl(it) }.filter { it.isNotEmpty() }) {
            val h = hostOf(u) ?: continue
            if (h.endsWith("spotify.com")) continue
            out += Records.Link(Records.Kind.SHOP, Records.siteName(h), "https://$h/")
        }
        return out.distinctBy { key(it.url) }
    }

    // ---------------------------------------------------------------- Wikidata

    /** The artist's site and Bandcamp on Wikidata (empty when it has no such artist); null when it did not answer. */
    private suspend fun wikidata(artist: String, ids: Ids, spotifyId: String?, steps: MutableList<String>): List<Records.Link>? {
        val candidates = ids.wikidata?.let { listOf(it) } ?: run {
            val body = wdGet(mapOf("action" to "wbsearchentities", "search" to artist, "language" to language(artist), "strictlanguage" to "false",
                "type" to "item", "limit" to "$CANDIDATES")) ?: return null
            parseJson(body)["search"].arr.mapNotNull { it["id"].str?.takeIf { id -> QID.matches(id) } }
        }
        if (candidates.isEmpty()) { steps += "Wikidata: no item"; return emptyList() }
        val body = wdGet(mapOf("action" to "wbgetentities", "ids" to candidates.joinToString("|"), "props" to "labels|aliases|claims",
            "languages" to listOf("en", "mul", language(artist)).distinct().joinToString("|"))) ?: return null
        val id = if (ids.wikidata != null) ids.wikidata else chooseEntity(body, artist, ids.musicBrainz, spotifyId)
        if (id == null) { steps += "Wikidata: no item sure to be theirs"; return emptyList() }
        val links = siteLinks(body, id)
        steps += "Wikidata $id: ${links.size} links"
        return links
    }

    private val QID = Regex("Q\\d+")

    /** The language to search a name in: its script's, else English (Wikidata falls back to the others). */
    internal fun language(name: String): String {
        for (c in name) {
            when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return "ja"
                Character.UnicodeScript.HANGUL -> return "ko"
                Character.UnicodeScript.CYRILLIC -> return "ru"
                Character.UnicodeScript.ARABIC -> return "ar"
                Character.UnicodeScript.HEBREW -> return "he"
                Character.UnicodeScript.GREEK -> return "el"
                Character.UnicodeScript.THAI -> return "th"
                Character.UnicodeScript.DEVANAGARI -> return "hi"
                else -> {}
            }
        }
        return if (name.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) "zh" else "en"
    }

    /** Musical groups of every kind (P31). */
    private val GROUPS = setOf(
        "Q215380", "Q5741069", "Q2088357", "Q9212979", "Q56816954", "Q641066", "Q216337", "Q281643", "Q1802588", "Q107357104",
        "Q1191899", "Q16823951", "Q105543609", "Q4438121",
    )
    /** Occupations of a musician (P106): musician, singer, rapper, singer-songwriter, DJ, producer, composer... */
    private val MUSICIANS = setOf(
        "Q639669", "Q177220", "Q2252262", "Q488205", "Q130857", "Q183945", "Q36834", "Q753110", "Q855091", "Q386854",
        "Q1259917", "Q2643890", "Q1198887", "Q15981151", "Q158852", "Q486748", "Q12800682", "Q1415090", "Q584301", "Q806349",
        "Q1075651", "Q2865819", "Q19723482", "Q55960555", "Q105815787",
    )
    /** Identifiers only a music artist has: MusicBrainz, Spotify, Discogs, AllMusic, Apple Music, Deezer, Bandcamp, Last.fm. */
    private val MUSIC_IDS = listOf("P434", "P1902", "P1953", "P1728", "P2850", "P2722", "P3283", "P3192")

    /** The values of a property's statements (an id's text, an item's Q-id), deprecated ones left out. */
    internal fun claims(e: JsonElement?, p: String): List<String> = e["claims"][p].arr.mapNotNull { c ->
        if (c["rank"].str == "deprecated") return@mapNotNull null
        val v = c["mainsnak"]["datavalue"]["value"]
        v.str ?: v["id"].str
    }

    private fun names(e: JsonElement?): List<String> =
        (e["labels"] as? JsonObject)?.values?.mapNotNull { it["value"].str }.orEmpty() +
            (e["aliases"] as? JsonObject)?.values?.flatMap { a -> a.arr.mapNotNull { it["value"].str } }.orEmpty()

    private fun norm(s: String) = Matcher.normalizeArtist(s).ifEmpty { s.trim().lowercase() }

    /** A musician or a band: its kind or occupation says so, or an identifier only music artists have. */
    private fun musical(e: JsonElement?): Boolean {
        val kinds = claims(e, "P31")
        return kinds.any { it in GROUPS } ||
            ("Q5" in kinds && claims(e, "P106").any { it in MUSICIANS }) ||
            MUSIC_IDS.any { claims(e, it).isNotEmpty() }
    }

    /**
     * Which of the entities of a `wbgetentities` answer is [artist]: the one with [mbid] (P434), else
     * the one with [spotifyId] (P1902); else the only musician or band whose label or alias is exactly
     * the name, those with another MusicBrainz or Spotify id than ours left out. Null when none is sure.
     */
    fun chooseEntity(body: String, artist: String, mbid: String?, spotifyId: String?): String? {
        val entities = (parseJson(body)["entities"] as? JsonObject)?.entries?.filter { (_, e) -> e["missing"] == null }.orEmpty()
        mbid?.let { m -> entities.firstOrNull { (_, e) -> claims(e, "P434").any { it.equals(m, ignoreCase = true) } }?.let { return it.key } }
        spotifyId?.let { s -> entities.firstOrNull { (_, e) -> s in claims(e, "P1902") }?.let { return it.key } }
        val want = norm(artist)
        if (want.isEmpty()) return null
        return entities.filter { (_, e) ->
            (mbid == null || claims(e, "P434").isEmpty()) && (spotifyId == null || claims(e, "P1902").isEmpty()) &&
                names(e).any { norm(it) == want } && musical(e)
        }.singleOrNull()?.key
    }

    /**
     * The entity's official website (P856: a preferred statement first, an ended one left out) and its
     * Bandcamp (P3283), as links.
     */
    fun siteLinks(body: String, id: String): List<Records.Link> {
        val e = parseJson(body)["entities"][id]
        val sites = e["claims"]["P856"].arr.filter { c ->
            c["rank"].str != "deprecated" && c["qualifiers"]["P582"] == null
        }.sortedBy { if (it["rank"].str == "preferred") 0 else 1 }.mapNotNull { webUrl(it["mainsnak"]["datavalue"]["value"].str).takeIf { u -> u.isNotEmpty() } }
        val out = ArrayList<Records.Link>()
        sites.firstOrNull()?.let { u -> hostOf(u)?.let { out += Records.Link(Records.Kind.OFFICIAL, Records.siteName(it), u.replaceFirst("http://", "https://")) } }
        claims(e, "P3283").firstOrNull()?.takeIf { BANDCAMP_ID.matches(it) }?.let { out += Records.Link(Records.Kind.BANDCAMP, "Bandcamp", "https://${it.lowercase()}.bandcamp.com/") }
        return out
    }

    private val BANDCAMP_ID = Regex("[A-Za-z0-9-]{1,63}")

    /** One request to Wikidata's API; null when it did not answer, was busy (maxlag) or refused. */
    private suspend fun wdGet(params: Map<String, String>): String? {
        val q = (params + mapOf("format" to "json", "formatversion" to "2", "maxlag" to "5"))
            .entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val body = try {
            wikidataPace.paced {
                val r = Http.send("GET", "$wikidataBase?$q", mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                    maxRateLimitRetries = 0, retryServerErrors = false)
                r.body.takeIf { r.ok }
            }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        val o = runCatching { parseJson(body) }.getOrNull() ?: return null
        // {"error":{"code":"maxlag"}}: Wikidata is behind; it asks bots to come back later.
        if (o["error"] != null) return null
        return body
    }
}
