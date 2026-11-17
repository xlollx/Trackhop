package com.xlollx.songport.providers

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import java.util.Locale

/**
 * The charts as a sync source: Apple Music's Top 100 songs of a country (Apple's public RSS feed
 * generator, rss.marketingtools.apple.com) and Deezer's country charts (the public playlists of
 * Deezer's own "Deezer Charts" account, read through api.deezer.com). Both official, public and
 * without an account, so on every build, Google Play's too. Read-only: a sync from a chart keeps a
 * playlist of the user's on any service up to date as the chart changes, once a day or as scheduled.
 *
 * Where an Apple Music developer token is there (the build's, or the user's own), Apple's chart
 * playlists too: the Daily Top 100 of the world and of each country and the Top 25 of each city,
 * listed by the Apple Music API's charts call ([AppleMusicProvider.chartPlaylists]) and read as the
 * catalogue playlists they are. Official as well; without a token they are simply not listed.
 */
object ChartsProvider : MusicProvider {
    const val SERVICE = "charts"
    private const val APPLE = "apple:"
    private const val DEEZER = "deezer:"
    /** An Apple chart playlist (Daily Top 100, a city's Top 25): the prefix and its catalogue id, `pl.…`. */
    internal const val APPLE_LIST = "applelist:"
    /** Deezer's account that publishes the "Top <country>" playlists. */
    private const val DEEZER_CHARTS_USER = 637006841L
    private const val DEEZER_WORLDWIDE = "3155776842"

    /** Apple's storefronts offered, the user's own first; Apple has more, these cover most users. */
    private val APPLE_COUNTRIES = listOf(
        "us", "gb", "it", "de", "fr", "es", "nl", "be", "pl", "se", "no", "dk", "fi", "at", "ch", "ie", "pt",
        "tr", "ru", "br", "mx", "ar", "co", "cl", "ca", "au", "nz", "jp", "kr", "in", "id", "ph", "za", "ng", "eg", "sa", "ae",
    )

    override val serviceId = SERVICE
    override val displayName = "Charts"
    override val brandColor = 0xFFFF7043
    override val noteName = "provider_note_charts"
    override val requiresAuth = false
    override val supportsMultipleAccounts = false
    override val canWrite = false
    override val canCreatePlaylists = false
    override val canRemoveTracks = false
    override val searchParallelism = 1

    override fun label(ctx: Env): String = ctx.text("charts_label")
    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = true
    override fun accountName(ctx: Env): String? = null
    override fun startAuth(ctx: Env) = Unit
    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = Unit

    /** The user's country, for the order of the list: theirs first. */
    @Volatile var country: () -> String = { Locale.getDefault().country.lowercase() }

    private fun countryName(cc: String): String = Locale("", cc.uppercase()).getDisplayCountry(Locale.getDefault()).ifBlank { cc.uppercase() }

    /** The country's name as Apple may write it in a chart's name: in the device's language, in English and in the country's own languages. */
    internal fun countryNames(cc: String): Set<String> {
        val c = Locale("", cc.uppercase())
        val own = Locale.getAvailableLocales().filter { it.country.equals(cc, ignoreCase = true) }
        return (listOf(Locale.getDefault(), Locale.ENGLISH) + own).map { c.getDisplayCountry(it) }.filter { it.isNotBlank() && !it.equals(cc, ignoreCase = true) }.toSet()
    }

    @Volatile private var deezerList: Pair<Long, List<Pair<String, String>>>? = null

    /** The official Apple Music connector, for its developer token and its catalogue calls; never signed in here. */
    private val apple = AppleMusicProvider()
    @Volatile private var appleLists: Triple<Long, String, List<AppleMusicProvider.ChartList>>? = null

    /** Tests: forget what was listed. */
    internal fun forgetLists() { appleLists = null; deezerList = null }

    /** Whether Apple's chart playlists can be asked for: only with a developer token. */
    fun hasAppleLists(ctx: Env): Boolean = runCatching { apple.developerToken(ctx).isNotBlank() }.getOrDefault(false)

    /**
     * Apple's chart playlists for the user's storefront, kept a day, in Apple's order (the daily charts,
     * then the cities), with the daily chart of the user's own country moved to the top when Apple's
     * name for it ends with the country's.
     * None without a token, or when Apple does not answer (an expired token): the rest of the list stays.
     */
    private suspend fun appleCharts(ctx: Env): List<AppleMusicProvider.ChartList> {
        if (!hasAppleLists(ctx)) return emptyList()
        val sf = country().takeIf { it in APPLE_COUNTRIES } ?: "us"
        appleLists?.let { (at, of, list) -> if (of == sf && System.currentTimeMillis() - at < 24 * 3_600_000L && list.isNotEmpty()) return list }
        val found = try {
            apple.chartPlaylists(ctx, sf)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ctx.log("charts", "Apple Music: chart playlists not listed: ${e.message ?: e.javaClass.simpleName}")
            return emptyList()
        }
        val mine = countryNames(sf)
        val sorted = found.sortedBy { c -> if (c.kind == AppleMusicProvider.CHART_KINDS[0] && mine.any { c.name.endsWith(it, ignoreCase = true) }) 0 else 1 }
        appleLists = Triple(System.currentTimeMillis(), sf, sorted)
        return sorted
    }

    /** Deezer's country charts as (playlist id, title): "Top Italy"; the yearly and themed ones left out. */
    private suspend fun deezerCharts(): List<Pair<String, String>> {
        deezerList?.let { (at, list) -> if (System.currentTimeMillis() - at < 24 * 3_600_000L && list.isNotEmpty()) return list }
        val found = ArrayList<Pair<String, String>>()
        var url: String? = "https://api.deezer.com/user/$DEEZER_CHARTS_USER/playlists?limit=200"
        var pages = 0
        while (url != null && pages++ < 5) {
            val r = Http.send("GET", url)
            if (!r.ok) break
            val j = parseJson(r.body)
            for (p in j["data"].arr) {
                val title = p["title"].str?.trim() ?: continue
                val id = p["id"].long?.toString() ?: continue
                if (Regex("""^Top [\p{L} .'-]+$""").matches(title) && !Regex("""\b(Femmes|Mujeres|Mulheres|Women)\b""").containsMatchIn(title)) found += id to title
            }
            url = j["next"].str
        }
        if (found.none { it.first == DEEZER_WORLDWIDE }) found += DEEZER_WORLDWIDE to "Top Worldwide"
        deezerList = System.currentTimeMillis() to found
        return found
    }

    override suspend fun playlists(ctx: Env): List<Playlist> {
        val mine = country()
        val apple = (listOf(mine).filter { it in APPLE_COUNTRIES } + APPLE_COUNTRIES.filter { it != mine })
            .map { cc -> Playlist(APPLE + cc, ctx.text("charts_apple_name", countryName(cc)), 100) }
        val deezer = runCatching { deezerCharts() }.getOrDefault(listOf(DEEZER_WORLDWIDE to "Top Worldwide"))
            .sortedWith(compareBy({ it.first != DEEZER_WORLDWIDE && !it.second.endsWith(" " + Locale("", mine.uppercase()).getDisplayCountry(Locale.ENGLISH)) }, { it.first != DEEZER_WORLDWIDE }, { it.second }))
            .map { (id, title) -> Playlist(DEEZER + id, ctx.text("charts_deezer_name", title), 100) }
        val lists = appleCharts(ctx).map { Playlist(APPLE_LIST + it.id, ctx.text("charts_apple_list_name", it.name), -1) }
        // The user's own country first on both, then Deezer's worldwide chart: what most will want.
        // Apple's daily and city charts after them, where there is a token to ask for them.
        return apple.take(1) + deezer.take(2) + lists + apple.drop(1) + deezer.drop(2)
    }

    override fun canRead(ctx: Env, playlistId: String) =
        playlistId.startsWith(APPLE) || playlistId.startsWith(DEEZER) || (playlistId.startsWith(APPLE_LIST) && hasAppleLists(ctx))

    override suspend fun playlistInfo(ctx: Env, playlistId: String): Playlist = when {
        playlistId.startsWith(APPLE) -> Playlist(playlistId, ctx.text("charts_apple_name", countryName(playlistId.removePrefix(APPLE))), 100)
        playlistId.startsWith(DEEZER) -> {
            val id = playlistId.removePrefix(DEEZER)
            val title = runCatching { deezerCharts().firstOrNull { it.first == id }?.second }.getOrNull() ?: "Top"
            Playlist(playlistId, ctx.text("charts_deezer_name", title), 100)
        }
        playlistId.startsWith(APPLE_LIST) -> {
            val id = appleListId(playlistId)
            // Its name as listed; a chart no longer listed (or a list not read yet) is asked by its id.
            val name = appleLists?.third?.firstOrNull { it.id == id }?.name ?: apple.playlistInfo(ctx, id).name
            Playlist(playlistId, ctx.text("charts_apple_list_name", name), -1)
        }
        else -> throw ProviderException(ctx.text("charts_unknown"), code = 404)
    }

    /** The catalogue id of an Apple chart playlist: `pl.` and Apple's letters, digits and dashes, nothing else. */
    private fun appleListId(playlistId: String): String =
        playlistId.removePrefix(APPLE_LIST).also { require(it.matches(Regex("""pl\.[A-Za-z0-9-]+"""))) { "playlist" } }

    override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = when {
        playlistId.startsWith(APPLE) -> appleTop(ctx, playlistId.removePrefix(APPLE))
        playlistId.startsWith(DEEZER) -> deezerTop(ctx, playlistId.removePrefix(DEEZER))
        // A catalogue playlist, read by the Apple Music connector with the developer token: ISRC, album and length come with it.
        playlistId.startsWith(APPLE_LIST) -> apple.tracks(ctx, appleListId(playlistId)).map { it.copy(id = "am:" + it.id) }
        else -> throw ProviderException(ctx.text("charts_unknown"), code = 404)
    }

    /** A chart changes once a day or so: its first entries tell a new one. */
    override suspend fun playlistVersion(ctx: Env, playlistId: String): String? =
        runCatching { tracks(ctx, playlistId).take(100).joinToString(",") { it.id }.hashCode().toString() }.getOrNull()

    private suspend fun appleTop(ctx: Env, cc: String): List<Track> {
        require(cc.matches(Regex("[a-z]{2}"))) { "country" }
        val r = Http.send("GET", "https://rss.marketingtools.apple.com/api/v2/$cc/music/most-played/100/songs.json")
        if (!r.ok) throw ProviderException(ctx.text("charts_unavailable", "Apple Music", r.code), code = r.code)
        return parseApple(r.body)
    }

    private suspend fun deezerTop(ctx: Env, id: String): List<Track> {
        require(id.all { it.isDigit() }) { "playlist" }
        val r = Http.send("GET", "https://api.deezer.com/playlist/$id/tracks?limit=100")
        if (!r.ok || r.body.contains("\"error\"")) throw ProviderException(ctx.text("charts_unavailable", "Deezer", r.code), code = r.code)
        return parseDeezer(r.body)
    }

    /** Apple's feed: results[] with name, artistName, id; no album or duration. */
    fun parseApple(body: String): List<Track> = parseJson(body)["feed"]["results"].arr.mapNotNull { e ->
        val title = e["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Track(id = "am:" + (e["id"].str ?: title), title = title, artists = listOfNotNull(e["artistName"].str?.trim()?.ifEmpty { null }))
    }

    /** Deezer's tracks: data[] with title, artist.name, album.title, duration (s). */
    fun parseDeezer(body: String): List<Track> = parseJson(body)["data"].arr.mapNotNull { e ->
        val title = e["title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Track(
            id = "dz:" + (e["id"].long ?: 0L), title = title,
            artists = listOfNotNull(e["artist"]["name"].str?.trim()?.ifEmpty { null }),
            album = e["album"]["title"].str.orEmpty(), durationMs = (e["duration"].long ?: 0L) * 1000,
        )
    }

    /** Never a destination. */
    override suspend fun search(ctx: Env, track: Track): List<Track> = emptyList()
    override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist = readOnly(ctx)
    override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) = readOnly(ctx)
    private fun readOnly(ctx: Env): Nothing = throw ProviderException(ctx.text("error_read_only", label(ctx)))
}
