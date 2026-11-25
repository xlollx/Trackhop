package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.RadioStation
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.RadioProvider
import com.xlollx.songport.providers.ShazamProvider
import com.xlollx.songport.sync.RadioConnectors
import com.xlollx.songport.sync.RadioTitles
import com.xlollx.songport.sync.ShazamLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/*
 * Radio stations and Shazam as connectors on the API (2.2, as in the app's Accounts): a radio
 * connector per station, added with its station; Shazam once; a sync from either made with
 * POST /syncs like any other, its source the connector ("source": {"service": "radio@2"}).
 */

/** What a radio or Shazam connector adds to its row in GET /services. */
internal fun Web.connectorExtras(p: MusicProvider): JsonObject? = when (p) {
    is RadioProvider -> p.station(env)?.let { s ->
        buildJsonObject {
            put("station", s.station); put("stream", s.stream)
            put("homepage", s.homepage?.let { JsonPrimitive(it) } ?: JsonNull)
            put("syncs", RadioConnectors.syncsOf(env.store.data.jobs, p.id).size)
        }
    }
    ShazamProvider -> buildJsonObject { put("syncs", RadioConnectors.syncsOf(env.store.data.jobs, p.id).size) }
    else -> null
}

/** "HH:MM" as minutes of the day, or null. */
private fun minuteOf(v: String?): Int? = v?.trim()?.let { Regex("""^(\d{1,2}):(\d{2})$""").find(it) }?.let { m ->
    val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
    if (h in 0..23 && mi in 0..59) h * 60 + mi else null
}

/**
 * [base] with the window [o] gives ({"days": [1..7], "start": "HH:MM", "end": "HH:MM"}, each
 * optional); throws IllegalArgumentException with what is wrong.
 */
internal fun radioWindowOf(base: RadioSource, o: JsonObject?): RadioSource {
    if (o == null) return base
    // Given, it is a list: anything else is an error, never the default week kept in silence.
    o["days"]?.let { if (it !is JsonArray && it !is JsonNull) throw IllegalArgumentException("\"radio.days\" is a list of days from 1 (Monday) to 7 (Sunday): [6, 7].") }
    val days = (o["days"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull?.takeIf { d -> d in 1..7 } ?: throw IllegalArgumentException("\"radio.days\" are numbers from 1 (Monday) to 7 (Sunday).") }
        ?.distinct()?.sorted()
    if (days != null && days.isEmpty()) throw IllegalArgumentException("\"radio.days\" needs at least one day.")
    val start = o.text("start")?.let { minuteOf(it) ?: throw IllegalArgumentException("\"radio.start\" is HH:MM.") }
    val end = o.text("end")?.let { minuteOf(it) ?: throw IllegalArgumentException("\"radio.end\" is HH:MM.") }
    return base.copy(days = days ?: base.days, startMinute = start ?: base.startMinute, endMinute = end ?: base.endMinute)
}

/** The radio and Shazam calls of the API; false for any other. */
internal fun Web.apiRadio(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun connectorJson(p: MusicProvider) = buildJsonObject {
        put("id", p.id); put("name", p.label(env)); put("serviceId", p.serviceId); put("slot", p.slot); put("connected", true)
        connectorExtras(p)?.forEach { (k, v) -> put(k, v) }
    }
    when {
        // The radio directory (radio-browser.info): stations by name, to add one.
        rest == listOf("radio", "stations") && method == "GET" -> {
            if (!env.may(Permissions.RADIO)) return true.also { error(403, "Radio is not allowed for you on this server: ask its admin.") }
            val q = query["q"]?.trim().orEmpty().ifEmpty { return true.also { error(400, "Say which station: ?q=<name>.") } }
            val found = runCatching { RadioBrowser.search(q) }.getOrElse { return true.also { _ -> error(502, it.message ?: "The radio directory did not answer.") } }
            json(200, JsonArray(found.map { s ->
                buildJsonObject {
                    put("name", s.name); put("stream", s.stream); put("homepage", s.homepage)
                    put("country", s.country); put("codec", s.codec); put("bitrate", s.bitrate)
                }
            }))
        }
        // A radio connector for a station: {"station", "stream", "homepage"?, "name"?, "color"?}. The same stream twice is the same connector.
        rest == listOf("services", RadioConnectors.SERVICE) && method == "POST" -> {
            if (!env.may(Permissions.RADIO)) return true.also { error(403, "Radio is not allowed for you on this server: ask its admin.") }
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"station\": \"Radio 105\", \"stream\": \"https://…\"}.") }
            val station = b.text("station") ?: return true.also { error(400, "\"station\" is needed: the station's name.") }
            val stream = b.text("stream")?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?: return true.also { error(400, "\"stream\" is needed: the stream's address (http:// or https://), as GET /api/v1/radio/stations?q= gives it.") }
            val color = b.text("color")?.lowercase()?.takeUnless { it == "none" }
            if (color != null && com.xlollx.songport.data.AccountColors.hex(color) == null) return true.also { error(400, "\"color\" is one of ${com.xlollx.songport.data.AccountColors.ALL.joinToString(", ") { it.first }}.") }
            val before = env.store.data.settings
            var id = ""
            env.store.updateSettings { s ->
                RadioConnectors.add(s, RadioStation(station, stream, b.text("homepage")), b.text("name"), color).let { (next, made) -> id = made; next.copy(connectorsInit = true) }
            }
            val p = Providers.byId(id) ?: return true.also { error(500, "The connector was not made.") }
            env.log("services", "added radio ${p.label(env)}")
            json(if (before.radioStations.containsKey(id)) 200 else 201, connectorJson(p))
        }
        // Shazam's connector: one, so asking twice gives the same.
        rest == listOf("services", RadioConnectors.SHAZAM) && method == "POST" -> {
            val had = RadioConnectors.SHAZAM in env.store.data.settings.connectors
            env.store.updateSettings { RadioConnectors.addShazam(it).copy(connectorsInit = true) }
            if (!had) env.log("services", "added Shazam")
            json(if (had) 200 else 201, connectorJson(ShazamProvider))
        }
        // Taking a radio connector or Shazam away: refused while a sync comes through it.
        rest.size == 2 && rest[0] == "services" && method == "DELETE" && (RadioConnectors.isRadio(rest[1]) || rest[1] == RadioConnectors.SHAZAM) -> {
            val p = Providers.byId(rest[1])?.takeIf { it.id in env.store.data.settings.connectors } ?: return true.also { error(404, "No service with that id here.") }
            if (!Accounts.remove(env, p)) return true.also { error(409, "${p.label(env)} is in use by ${RadioConnectors.syncsOf(env.store.data.jobs, p.id).size} sync(s): delete them first.") }
            env.log("services", "removed ${p.label(env)}")
            json(200, buildJsonObject { put("deleted", p.id) })
        }
        else -> return false
    }
    return true
}

/**
 * POST /syncs from a radio connector: the station's sync, its songs kept in a File playlist of
 * their own, its window from "radio" (every day, 08:00 to 10:00 when not given), as the page makes it.
 */
internal fun Web.createRadioSyncFromApi(b: JsonObject, src: MusicProvider, t: JsonObject, givenId: String?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    if (!env.may(Permissions.RADIO)) return error(403, "Radio is not allowed for you on this server: ask its admin.")
    val dst = t.text("service")?.let { Providers.byId(it) } ?: return error(404, "No target service with that id: see GET /api/v1/services.")
    if (!usable(dst)) return error(409, "${dst.label(env)} is not connected.")
    if (dst.linkOnly || !dst.canWrite) return error(400, "${dst.label(env)} cannot be a destination.")
    val station = (src as? RadioProvider)?.station(env) ?: return error(409, "${src.label(env)} has no station: add it again.")
    val window = runCatching { radioWindowOf(RadioSource(station.station, station.stream, station.homepage, RadioConnectors.DEFAULT_DAYS, RadioConnectors.DEFAULT_START, RadioConnectors.DEFAULT_END), b["radio"] as? JsonObject) }
        .getOrElse { return error(400, it.message ?: "Wrong request.") }
    val dstId = t.text("playlist")
    if (dstId == null && !dst.canCreatePlaylists) return error(400, "${dst.label(env)} cannot create playlists: give \"target.playlist\".")
    val dstName = dstId?.let { id ->
        runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name } }
            .getOrElse { return error(502, it.message ?: it.javaClass.simpleName) }
            ?: return error(400, "${dst.label(env)} has no playlist of yours with the id $id.")
    } ?: "${station.station} radio"
    val made = runBlocking {
        RadioConnectors.newSync(env, src.id, PlaylistRef(dst.id, dstId, dstName), givenId ?: UUID.randomUUID().toString(), b.text("name"), window.days, window.startMinute, window.endMinute)
    } ?: return error(409, "${src.label(env)} has no station: add it again.")
    val job = runCatching { grouped(coloured(made, b), b, env.store.data.settings.labels).let { j -> b.flag("enabled")?.let { j.copy(enabled = it) } ?: j } }
        .getOrElse { return error(400, it.message ?: "Wrong request.") }
    env.store.upsertJob(job)
    runner.radio.tick(java.time.LocalDateTime.now())
    env.log("api", "created radio sync ${job.name}: ${station.station} → ${dst.label(env)}: $dstName (${RadioTitles.describe(job.radio!!)})")
    json(201, Api.job(env, env.store.job(job.id) ?: job, false))
}

/**
 * POST /syncs from Shazam: the playlist the Shazam app fills, found on a connected Spotify (or
 * Apple Music) account, to the target; every hour unless "schedule" or "every" says otherwise.
 */
internal fun Web.createShazamSyncFromApi(b: JsonObject, t: JsonObject, givenId: String?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    val dst = t.text("service")?.let { Providers.byId(it) } ?: return error(404, "No target service with that id: see GET /api/v1/services.")
    if (!usable(dst)) return error(409, "${dst.label(env)} is not connected.")
    if (dst.linkOnly || !dst.canWrite) return error(400, "${dst.label(env)} cannot be a destination.")
    val hosts = ShazamProvider.hosts(env)
    var host: MusicProvider? = null
    var found: com.xlollx.songport.model.Playlist? = null
    for (h in hosts) {
        val lists = runCatching { runBlocking { h.playlists(env) } }.getOrNull() ?: continue
        ShazamLibrary.find(lists)?.let { host = h; found = it }
        if (found != null) break
    }
    val src = host
    val shazam = found
    if (src == null || shazam == null) return error(409, if (hosts.isEmpty()) "Connect the Spotify account Shazam fills first (Shazam › Settings › Connect to Spotify)." else "\"${ShazamLibrary.NAME}\" is not on ${hosts.joinToString(" or ") { it.label(env) }}: in the Shazam app, Settings › Connect to Spotify, then Shazam a song.")
    val dstId = t.text("playlist")
    if (dstId == null && !dst.canCreatePlaylists) return error(400, "${dst.label(env)} cannot create playlists: give \"target.playlist\".")
    if (dstId == shazam.id && dst.id == src.id) return error(400, env.text("error_same_playlist"))
    val dstName = dstId?.let { id ->
        runCatching { runBlocking { (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name } }
            .getOrElse { return error(502, it.message ?: it.javaClass.simpleName) }
            ?: return error(400, "${dst.label(env)} has no playlist of yours with the id $id.")
    } ?: shazam.name
    val base = SyncJob(
        id = givenId ?: UUID.randomUUID().toString(), name = b.text("name") ?: shazam.name,
        source = PlaylistRef(src.id, shazam.id, shazam.name), target = PlaylistRef(dst.id, dstId, dstName),
        schedule = Schedule.HOURLY, shazam = true, via = RadioConnectors.SHAZAM,
    )
    val job = runCatching {
        grouped(coloured(matched(matching(changed(timed(base, b), b), b), b), b), b, env.store.data.settings.labels).let { j -> b.flag("enabled")?.let { j.copy(enabled = it) } ?: j }
    }.getOrElse { return error(400, it.message ?: "Wrong request.") }
    env.store.upsertJob(job)
    env.store.updateSettings { RadioConnectors.addShazam(it) }
    env.log("api", "created Shazam sync ${job.name}: ${src.label(env)} → ${dst.label(env)}: $dstName")
    json(201, Api.job(env, env.store.job(job.id) ?: job, false))
}
