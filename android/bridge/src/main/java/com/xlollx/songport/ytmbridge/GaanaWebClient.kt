package com.xlollx.songport.ytmbridge

import com.xlollx.songport.ytmbridge.BridgeNet.withHook
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Gaana through gaana.com's own web player: Gaana has no interface for third-party apps, so the
 * quick way is the site's.
 *
 * The session is the site's user token (`uToken` in the page's storage), which every signed call
 * to `api.gaana.com` carries as `token=` in its address, next to the headers the site names itself
 * with (`deviceType`, `deviceId`, `COUNTRY`, `appVersion`, `gaanaAppVersion`). Trackhop never signs
 * in itself: the user does, on Gaana's page (the app reads the token from the page's requests or
 * its storage) or pastes it (Desktop and Docker).
 *
 * What the site does, from a signed-in session recorded on 07/11/2026 and from the site's scripts
 * (15/11/2026), none of it documented:
 * - GET user.php?type=myplaylists&subtype=myplaylists: the account's own playlists (`playlist[]`);
 * - GET playlist/detail?playlist_id= (or seokey=)&request_type=playlist: a playlist with its `tracks[]`
 *   (each with its ISRC); a public one answers without a token too;
 * - GET mymusic/entities/TRACK: the favourite songs (`entities[]`), Trackhop's liked songs;
 * - GET gsearch.gaana.com/vichitih/go/v2/: the search, without a token; `gr[]` groups, each with its
 *   items in `gd[]` (`id`, `ti`, `sti`, `seo`, `isrc`). It does not find a song by its ISRC as text,
 *   so the ISRC picks among the results of the text search;
 * - GET user.php?type=createplaylist, type=addtoplaylist; POST user.php?type=addeditplaylist
 *   (`playlist_json`: the title, removals in `del_items`); POST user.php?type=myplaylists&subtype=delete_playlist;
 *   POST mymusic/set-like-dislike-entities (`json_post`).
 * The reads of public data were tried without an account on 15/11/2026; the signed calls could
 * not be: what they send is in [Wire], in one place.
 *
 * No Android here: the server build compiles this file as it is (server/build.gradle).
 */
class GaanaWebClient(private val host: WebHost) {

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).withHook().build()

    private fun token(): String = host.get(TOKEN)?.takeIf { it.isNotBlank() } ?: throw BridgeException("not connected")

    /** [signed]: the token goes along in the address; a search and a public playlist need none. */
    private fun request(base: String, path: String, params: List<Pair<String, String>>, signed: Boolean): Request.Builder {
        val all = if (signed) params + (Wire.TOKEN to token()) else params
        val url = base + path + if (all.isEmpty()) "" else "?" + all.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        return Request.Builder().url(url)
            .header("User-Agent", host.userAgent).header("Origin", SITE).header("Referer", "$SITE/").header("Accept", "application/json, text/plain, */*")
            .header("deviceType", DEVICE_TYPE).header("deviceId", host.get(DEVICE_ID)?.takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_ID)
            .header("COUNTRY", host.get(COUNTRY)?.takeIf { it.isNotBlank() } ?: DEFAULT_COUNTRY)
            .header("appVersion", APP_VERSION).header("gaanaAppVersion", GAANA_APP_VERSION)
    }

    /** One exchange; a read that did not get through is asked once more, a write never is (it may have arrived). */
    private fun send(req: Request, again: Boolean): Pair<Int, String> {
        var attempt = 0
        while (true) {
            try {
                pace()
                return http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: java.io.IOException) {
                if (!again || attempt >= 1) throw BridgeException("Gaana unreachable: ${NetWords.of(e)}", e)
                Thread.sleep(retryWaitMs)
                attempt++
            }
        }
    }

    private fun read(req: Request, signed: Boolean, again: Boolean = false): JsonElement {
        val (code, body) = send(req, again)
        if (code == 401) throw BridgeException(EXPIRED)
        if (code == 429) throw BridgeException("Gaana asks to slow down (429): try again in a few minutes")
        val j = runCatching { parseJson(body) }.getOrNull()
        if (signed && loginNeeded(j)) throw BridgeException(EXPIRED)
        if (code !in 200..299) throw BridgeException("Gaana HTTP $code")
        if (j !is JsonObject) throw BridgeException("Gaana answered something that is not JSON")
        return j
    }

    /**
     * A signed call: a session Gaana refused is renewed once from the site's own ([WebHost.renewSession],
     * a hidden page of gaana.com) and the call made again with it (a refused call did nothing, so a
     * write too); the address is built anew, with the new token.
     */
    private fun <T> signed(call: () -> T): T =
        host.renewing("gaana", host.get(TOKEN), EXPIRED, { it is BridgeException && it.message == EXPIRED }, call)

    private fun getOnce(path: String, params: List<Pair<String, String>>, signed: Boolean, base: String): JsonElement =
        read(request(base, path, params, signed).get().build(), signed, again = true)

    /** A read. The site makes some of its writes with a GET too: those go through [write]. */
    private fun get(path: String, params: List<Pair<String, String>> = emptyList(), signed: Boolean = true, base: String = API): JsonElement =
        if (signed) signed { getOnce(path, params, true, base) } else getOnce(path, params, false, base)

    /** A write the site makes with a GET (create, add): never sent twice, but for a refused session. */
    private fun write(path: String, params: List<Pair<String, String>>): JsonElement =
        signed { read(request(API, path, params, signed = true).get().build(), signed = true) }

    /** A write the site makes as a form (`application/x-www-form-urlencoded`, the default of its calls). */
    private fun post(path: String, params: List<Pair<String, String>>, form: Map<String, String>): JsonElement = signed {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        read(request(API, path, params, signed = true).post(body).build(), signed = true)
    }

    // ---- reading

    private fun myPlaylists(): JsonElement = get("/user.php", listOf("type" to "myplaylists", "subtype" to "myplaylists"))

    /** The account's own playlists. */
    fun playlists(): List<PlaylistDto> = myPlaylists()["playlist"].arr.mapNotNull { parsePlaylist(it) }

    /** A playlist as the site answers it: by its id (digits), or by the `seokey` of its link. */
    private fun detail(playlistId: String, signed: Boolean = true): JsonElement {
        val key = if (playlistId.all(Char::isDigit)) "playlist_id" else "seokey"
        fun once(): JsonElement {
            val j = getOnce("/playlist/detail", listOf(key to playlistId, "request_type" to "playlist"), signed, API)
            if (j["playlist"] !is JsonObject) {
                // Signed, a token the site no longer knows comes back as "0" next to an empty answer.
                if (signed && j["user_token_status"].num == 0L && j["status"].num != 2L) throw BridgeException(EXPIRED)
                throw BridgeException("Gaana: " + (j["error"].str?.takeIf { it.isNotBlank() } ?: "the playlist was not found"))
            }
            return j
        }
        return if (signed) signed { once() } else once()
    }

    fun tracks(playlistId: String): List<TrackDto> {
        val j = detail(playlistId)
        val out = j["tracks"].arr.mapNotNull { parseTrack(it) }
        // The site's own charts count more songs than they give; a playlist of one's own that does is said in the log.
        val listed = j["playlist"]["trackids"].str?.split(',')?.count { it.isNotBlank() } ?: 0
        if (listed > out.size) BridgeNet.log("gaana", "playlist: ${out.size} songs answered of the $listed it lists")
        return out
    }

    /** The favourite songs, a page at a time until one comes back empty (or says nothing new). */
    fun liked(): List<TrackDto> {
        val out = LinkedHashMap<String, TrackDto>()
        var page = 1
        while (page <= MAX_PAGES) {
            val items = get("/mymusic/entities/TRACK", listOf("order" to "reverse", "fromtime" to "0", "page" to "$page"))["entities"].arr.mapNotNull { parseEntity(it) }
            val before = out.size
            items.forEach { out.putIfAbsent(it.id, it) }
            // An answer that ignores the page would repeat the first one for ever.
            if (items.isEmpty() || out.size == before) break
            page++
        }
        return out.values.toList()
    }

    /**
     * The catalogue's songs for [query]. With an [isrc], the results that carry that very code are
     * the answer when there are any (the search does not take the code itself as its text).
     */
    fun search(query: String, isrc: String? = null, max: Int = 10): List<TrackDto> {
        if (query.isBlank()) return emptyList()
        val found = parseSearch(get("/", Wire.search(query), signed = false, base = SEARCH))
        val code = isrc?.trim()?.uppercase()?.takeIf { it.length >= 10 }
        val exact = if (code == null) emptyList() else found.filter { it.isrc.equals(code, ignoreCase = true) }
        return exact.ifEmpty { found }.take(max)
    }

    /** Checks the session: reads the playlists; the account's name where the profile gives one. */
    fun probe(): String {
        myPlaylists()
        return runCatching { accountIn(get("/user.php", listOf("type" to "profile"))) }.getOrNull() ?: host.account() ?: "Gaana"
    }

    // ---- writing

    fun create(name: String, public: Boolean = false): PlaylistDto {
        val j = write("/user.php", Wire.create(name, public))
        if (!succeeded(j)) throw BridgeException("Gaana did not create the playlist" + problemIn(j))
        // The site takes the new playlist's id from `Success`; where it says something else, the list names it.
        val id = listOf(j["playlist_id"], j["Success"], j["id"]).firstNotNullOfOrNull { it.str?.trim()?.takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) } }
            ?: playlists().lastOrNull { it.name == name }?.id
            ?: throw BridgeException("Gaana created the playlist but did not say which it is")
        return PlaylistDto(id = id, name = name, count = 0)
    }

    fun add(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val pid = pid(playlistId)
        trackIds.chunked(CHUNK).forEach {
            val j = write("/user.php", Wire.add(pid, it))
            if (!succeeded(j)) throw BridgeException("Gaana did not add the songs" + problemIn(j))
        }
    }

    /** Removes through the playlist's edit, which wants its title and visibility again: both read first. */
    fun remove(playlistId: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val p = detail(playlistId)["playlist"]
        val pid = p["playlist_id"].str?.takeIf { it.isNotBlank() } ?: pid(playlistId)
        val title = p["title"].str ?: throw BridgeException("Gaana: the playlist has no title to keep")
        val public = p["is_public"].num == 1L
        trackIds.chunked(CHUNK).forEach {
            val j = post("/user.php", listOf("type" to "addeditplaylist"), mapOf(Wire.PLAYLIST_JSON to Wire.edit(pid, title, public, it)))
            if (!succeeded(j)) throw BridgeException("Gaana did not remove the songs" + problemIn(j))
        }
    }

    /** The edit again, with the new title and nothing to remove. */
    fun rename(playlistId: String, name: String) {
        val p = detail(playlistId)["playlist"]
        val pid = p["playlist_id"].str?.takeIf { it.isNotBlank() } ?: pid(playlistId)
        val j = post("/user.php", listOf("type" to "addeditplaylist"), mapOf(Wire.PLAYLIST_JSON to Wire.edit(pid, name, p["is_public"].num == 1L, emptyList())))
        if (!succeeded(j)) throw BridgeException("Gaana did not rename the playlist" + problemIn(j))
    }

    fun delete(playlistId: String) {
        val j = post("/user.php", Wire.delete(pid(playlistId)), emptyMap())
        if (!succeeded(j)) throw BridgeException("Gaana did not delete the playlist" + problemIn(j))
    }

    /** Favourite songs are Trackhop's liked songs. */
    fun like(trackIds: List<String>, on: Boolean) = trackIds.chunked(CHUNK).forEach {
        val j = post("/mymusic/set-like-dislike-entities", emptyList(), mapOf(Wire.JSON_POST to Wire.like(it, on)))
        if (j["status"].num == 0L || j["Status"].num == 0L) throw BridgeException("Gaana did not ${if (on) "save" else "remove"} the favourites" + problemIn(j))
    }

    /** The id the writes want: the playlist's number; a `seokey` is looked up. */
    private fun pid(playlistId: String): String =
        if (playlistId.all(Char::isDigit)) playlistId
        else detail(playlistId)["playlist"]["playlist_id"].str?.takeIf { it.isNotBlank() } ?: throw BridgeException("Gaana: the playlist has no id")

    /**
     * What the signed calls send. The addresses and field names are the site's own (its scripts of
     * 15/11/2026, the session recorded on 07/11/2026); none could be tried from here. A capture that
     * shows otherwise is fixed here, one line each.
     */
    object Wire {
        const val TOKEN = "token"
        const val PLAYLIST_JSON = "playlist_json"
        const val JSON_POST = "json_post"
        /** `entity_status` of a favourite: 2 liked, 0 neither (1 is a dislike). NOT seen in a capture: the mobile app's values. */
        const val LIKED = 2
        const val NEUTRAL = 0

        fun search(query: String) = listOf(
            "query" to query, "content_filter" to "2", "include" to "track", "isRegSrch" to "0", "webVersion" to "mix",
            "rType" to "web", "autocomplete" to "0", "startIndex" to "0", "usrLang" to "Hindi,English",
        )

        fun create(name: String, public: Boolean) =
            listOf("request_type" to "web", "type" to "createplaylist", "title" to name, "is_public" to if (public) "1" else "0")

        fun add(pid: String, trackIds: List<String>) =
            listOf("request_type" to "web", "type" to "addtoplaylist", "id" to pid, "trackids" to trackIds.joinToString(","))

        fun delete(pid: String) = listOf("request_type" to "web", "type" to "myplaylists", "subtype" to "delete_playlist", "id" to pid)

        /** `{"playlists":[{"pid":"…","is_public":0,"playlist_title":"…","del_items":[{"tid":"…","is_local":0}]}]}`. */
        fun edit(pid: String, title: String, public: Boolean, remove: List<String>): String = buildJsonObject {
            putJsonArray("playlists") {
                add(buildJsonObject {
                    put("pid", pid)
                    put("is_public", if (public) 1 else 0)
                    put("playlist_title", title)
                    if (remove.isNotEmpty()) put("del_items", buildJsonArray { remove.forEach { add(buildJsonObject { put("tid", it); put("is_local", 0) }) } })
                })
            }
        }.toString()

        /** `{"track":[{"entity_id":"…","entity_status":2}]}`. */
        fun like(trackIds: List<String>, on: Boolean): String = buildJsonObject {
            putJsonArray("track") { trackIds.forEach { add(buildJsonObject { put("entity_id", it); put("entity_status", if (on) LIKED else NEUTRAL) }) } }
        }.toString()
    }

    /** A session as it is kept: the token, and what the page sent with it where that was seen. */
    class Seen(val token: String, val deviceId: String? = null, val country: String? = null)

    companion object {
        /** Calls one after another, at least this far apart, however many syncs read Gaana at once. */
        @Volatile internal var gapMs = 400L
        /** The wait before a read that did not get through is asked again; the tests make it short. */
        @Volatile internal var retryWaitMs = 1_500L
        private val paceLock = Any()
        private var lastCall = 0L

        private fun pace() = synchronized(paceLock) {
            val wait = lastCall + gapMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }

        const val SITE = "https://gaana.com"
        const val LOGIN_URL = "https://gaana.com/"
        const val API_HOST = "api.gaana.com"
        private const val API = "https://api.gaana.com"
        private const val SEARCH = "https://gsearch.gaana.com/vichitih/go/v2"
        /** How the site names itself; without `deviceType` the API says the songs are not available. */
        private const val DEVICE_TYPE = "GaanaWapApp"
        private const val APP_VERSION = "V6"
        private const val GAANA_APP_VERSION = "gaanaAndroid-8.60.2"
        const val DEFAULT_DEVICE_ID = "website"
        const val DEFAULT_COUNTRY = "IN"
        private const val CHUNK = 50
        private const val MAX_PAGES = 200
        const val TOKEN = "userToken"; const val DEVICE_ID = "deviceId"; const val COUNTRY = "country"
        const val EXPIRED = "the Gaana session has ended: sign in again"
        /** The page's storage entry that holds the token; read at sign-in where no request showed it. */
        const val STORAGE_KEY = "uToken"

        /** A token as the site sends it: one word, no spaces (its length and alphabet are not known for sure). */
        private const val TOKEN_CHARS = """[A-Za-z0-9_\-.~+/=]{16,600}"""
        private const val TOKEN_ENCODED = """[A-Za-z0-9_\-.~%+/=]{16,900}"""
        private val TOKEN_PARAM = Regex("""(?i)[?&]token=($TOKEN_ENCODED)""")
        private val TOKEN_NAMED = Regex("""(?i)["']?\buToken["']?\s*[:=]\s*["']?($TOKEN_CHARS)""")
        private val TOKEN_ALONE = Regex("""^$TOKEN_CHARS$""")
        private val DEVICE_HEADER = Regex("""(?i)deviceid["']?\s*[:=]\s*["']?([A-Za-z0-9_\-.]{3,120})""")
        private val COUNTRY_HEADER = Regex("""(?i)\bcountry["']?\s*[:=]\s*["']?([A-Za-z]{2,6})\b""")

        private fun decoded(v: String): String = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

        /**
         * The session in [text]: the token, with the device id and country when [text] shows them.
         * What is pasted can be a request copied from the developer tools (as cURL, or its address
         * with `token=`), the page's `uToken` entry, or the token alone; null without a token.
         */
        fun sessionIn(text: String?): Seen? {
            val t = text?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() } ?: return null
            val token = TOKEN_PARAM.find(t)?.groupValues?.get(1)?.let(::decoded) ?: TOKEN_NAMED.find(t)?.groupValues?.get(1)
                ?: t.takeIf { TOKEN_ALONE.matches(it) && "gaana." !in it && "://" !in it } ?: return null
            return Seen(token, DEVICE_HEADER.find(t)?.groupValues?.get(1), COUNTRY_HEADER.find(t)?.groupValues?.get(1)?.uppercase())
        }

        /** The session a page's request to [url] carries (`token=` in the address of a gaana.com call), or null. */
        fun sessionOf(url: String, headers: Map<String, String>?): Seen? {
            val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
            val h = u.host?.lowercase() ?: return null
            if (u.scheme != "https" || !(h == "gaana.com" || h.endsWith(".gaana.com"))) return null
            val token = u.rawQuery?.split('&')?.firstOrNull { it.startsWith("token=") }?.substringAfter('=')?.let(::decoded)
                ?.takeIf { TOKEN_ALONE.matches(it) && it != "undefined" && it != "null" } ?: return null
            fun header(name: String) = headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
            return Seen(token, header("deviceId"), header("COUNTRY"))
        }

        /** The site's "sign in first": `{"msg":"Login Needed!","user_token_status":0}` (user.php), `{"status":0,"message":"Login needed"}` (mymusic). */
        fun loginNeeded(j: JsonElement?): Boolean =
            listOf(j["msg"].str, j["message"].str).any { it != null && it.contains("login needed", ignoreCase = true) }

        /** Whether a write went through: `{"Status":1,…}` (user.php), also seen in lower case. */
        fun succeeded(j: JsonElement?): Boolean = (j["Status"].num ?: j["status"].num) == 1L

        /** The site's own words for a refusal, for the end of a message; "" when it gave none. */
        fun problemIn(j: JsonElement?): String =
            listOf(j["Error"].str, j["error"].str, j["msg"].str, j["message"].str).firstOrNull { !it.isNullOrBlank() }?.let { ": ${it.take(120)}" }.orEmpty()

        /** The account's name as the profile gives it; never an address or a number. */
        fun accountIn(j: JsonElement?): String? {
            val places = listOf(j["user_info"], j["user"], j["profile"], j["data"], j)
            return places.firstNotNullOfOrNull { p ->
                listOf("fullname", "full_name", "name", "username").firstNotNullOfOrNull { p[it].str?.trim()?.takeIf { s -> s.isNotEmpty() && '@' !in s } }
            }
        }

        fun parsePlaylist(p: JsonElement?): PlaylistDto? {
            val id = p["playlist_id"].str?.takeIf { it.isNotBlank() } ?: p["seokey"].str?.takeIf { it.isNotBlank() } ?: return null
            val name = (p["title"].str ?: p["name"].str)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // The ids it lists are what it holds; the count next to them can say more.
            val count = p["trackids"].str?.split(',')?.count { it.isNotBlank() } ?: p["trackcount"].num?.toInt() ?: -1
            return PlaylistDto(id = id, name = name, count = count)
        }

        /** One song of a playlist's `tracks[]`. */
        fun parseTrack(j: JsonElement?): TrackDto? {
            val id = j["track_id"].str?.takeIf { it.isNotBlank() } ?: return null
            val title = j["track_title"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return TrackDto(
                id = id, title = title,
                artists = j["artist"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct(),
                album = j["album_title"].str.orEmpty().trim(),
                durationMs = (j["duration"].num ?: 0) * 1000,
                uri = j["seokey"].str?.takeIf { it.isNotBlank() }?.let { "$SITE/song/$it" },
                explicit = j["parental_warning"].num?.let { it == 1L },
                year = j["release_date"].str?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 },
                isrc = j["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /** One favourite of `entities[]`: `{entity_id, name, seokey, entity_map:{duration, album:[{name}], artist:[{name}]}}`. */
        fun parseEntity(j: JsonElement?): TrackDto? {
            val id = j["entity_id"].str?.takeIf { it.isNotBlank() } ?: return null
            val title = j["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val m = j["entity_map"]
            return TrackDto(
                id = id, title = title,
                artists = m["artist"].arr.mapNotNull { it["name"].str?.trim()?.takeIf { n -> n.isNotEmpty() } }.distinct(),
                album = m["album"][0]["name"].str.orEmpty().trim(),
                durationMs = (m["duration"].num ?: 0) * 1000,
                uri = j["seokey"].str?.takeIf { it.isNotBlank() }?.let { "$SITE/song/$it" },
                isrc = (m["isrc"].str ?: j["isrc"].str)?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        /** The songs of a search: every group's items that are a `Track` (`ti` title, `sti` artists by commas). */
        fun parseSearch(j: JsonElement?): List<TrackDto> = j["gr"].arr.flatMap { it["gd"].arr }.mapNotNull { i ->
            if (i["ty"].str.let { it != null && !it.equals("Track", ignoreCase = true) }) return@mapNotNull null
            val id = (i["id"].str ?: i["iid"].str)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = i["ti"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TrackDto(
                id = id, title = title,
                artists = i["sti"].str.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                uri = i["seo"].str?.takeIf { it.isNotBlank() }?.let { "$SITE/song/$it" },
                explicit = i["pw"].num?.let { it == 1L },
                isrc = i["isrc"].str?.trim()?.takeIf { it.isNotEmpty() },
            )
        }.distinctBy { it.id }
    }
}

/** A number Gaana may send as a number or as a string ("278"). */
private val JsonElement?.num: Long? get() = long ?: str?.trim()?.toLongOrNull()
