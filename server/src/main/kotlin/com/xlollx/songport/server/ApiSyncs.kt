package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.SyncEngine
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.Timing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The syncs through /api/v1 beyond listing and starting them (Api.kt): made, changed, deleted and
 * moved in the list as on the page, a run stopped, paused or resumed, what a run would do (the
 * app's preview), every run page by page, and a run's review (Review.kt: not found, to check,
 * removed). The same checks as the page's forms, so a script cannot make a sync the page would refuse.
 */
private const val MAX_JSON = 2_000_000

/** The request's JSON object: empty when there is no body, null when it is not JSON. */
internal fun apiJson(ex: HttpExchange): JsonObject? {
    val bytes = ex.requestBody.readNBytes(MAX_JSON + 1)
    if (bytes.size > MAX_JSON) return null
    if (bytes.isEmpty() || String(bytes, Charsets.UTF_8).isBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }.getOrNull()
}

internal fun JsonObject.text(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.flag(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
internal fun JsonObject.number(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

/** A track as the API shows it in runs and reviews: its id on its service, artists and title. */
internal fun trackRef(t: Track) = buildJsonObject {
    put("id", t.id); put("artist", t.artistLine); put("title", t.title)
    if (t.album.isNotBlank()) put("album", t.album)
}

private fun review(m: MatchReview) = buildJsonObject {
    put("source", trackRef(m.source)); put("match", trackRef(m.chosen)); put("score", m.score)
}

/** The schedule names the API takes, as minutes between runs (Timing). */
private val SCHEDULES = mapOf("manual" to 0, "hourly" to 60, "every_6h" to 360, "every 6 h" to 360, "daily" to 1440, "weekly" to 10080)

private val AT = Regex("""([01]?\d|2[0-3]):[0-5]\d""")

/** [job] with what [b] says of its timing: "schedule" or "every" (minutes), "at" (HH:MM), "days" (1 = Monday). */
private fun timed(job: SyncJob, b: JsonObject): SyncJob {
    val named = b.text("schedule")?.let { s -> SCHEDULES[s.lowercase()] ?: throw IllegalArgumentException("\"schedule\" is one of manual, hourly, every_6h, daily, weekly (or give \"every\" in minutes).") }
    val every = b.number("every") ?: named
    val at = b.text("at")?.also { if (!AT.matches(it)) throw IllegalArgumentException("\"at\" is a time as HH:MM.") }
    val days = (b["days"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull?.takeIf { d -> d in 1..7 } ?: throw IllegalArgumentException("\"days\" are numbers from 1 (Monday) to 7 (Sunday).") }
    if (every == null && at == null && days == null) return job
    val minutes = every ?: Timing.interval(job)
    // Weekly with no day named: today's, as the page does.
    val d = days ?: if (every != null) emptyList() else job.days
    val chosen = if (minutes >= 10080 && d.isEmpty()) listOf(java.time.LocalDate.now().dayOfWeek.value) else d
    return Timing.apply(job, minutes, at ?: job.at, chosen)
}

/** [job] with what [b] says of the changes: "changes" (add, mirror, archive) and "follows", or the GET's own booleans. */
private fun changed(job: SyncJob, b: JsonObject): SyncJob {
    val mode = b.text("changes")?.lowercase() ?: when {
        b.flag("archive") == true -> "archive"
        b.flag("mirrorRemovals") == true -> "mirror"
        b.flag("archive") == false || b.flag("mirrorRemovals") == false -> "add"
        else -> null
    }
    if (mode != null && mode !in setOf("add", "mirror", "archive")) throw IllegalArgumentException("\"changes\" is add, mirror or archive.")
    val follows = b.flag("follows") ?: b.flag("followsChanges")
    if (mode == null) return if (follows != null && job.mirrorRemovals) job.copy(followsChanges = follows) else job
    return job.copy(mirrorRemovals = mode == "mirror", followsChanges = mode == "mirror" && (follows ?: job.followsChanges), archive = mode == "archive")
}

/** [job] with the colour in [b]: a name of the palette, "#RRGGBB", or null/"" for none. */
private fun coloured(job: SyncJob, b: JsonObject): SyncJob {
    if (!b.containsKey("color")) return job
    // A colour given as such takes the label's place (a ready colour is its own label again when saved).
    val c = b.text("color") ?: return job.copy(color = null, label = null)
    val key = c.lowercase().takeIf { it in Html.JOB_COLORS } ?: Html.customColor(c)
        ?: throw IllegalArgumentException("\"color\" is one of ${Html.JOB_COLORS.keys.joinToString(", ")}, or #RRGGBB.")
    return job.copy(color = key, label = null)
}

/**
 * [job] with the star, the label and the tags in [b]: "favorite" (true/false); "label" (a label's
 * id or name, a ready colour, or null/"" for none: the card takes the label's colour); "tags" (an
 * object of key to value, a value may be "" or null: it replaces the sync's tags).
 */
private fun grouped(job: SyncJob, b: JsonObject, labels: List<com.xlollx.songport.model.Label>): SyncJob {
    var j = job
    b.flag("favorite")?.let { j = j.copy(favorite = it) }
    if (b.containsKey("label")) {
        val wanted = b.text("label")
        j = if (wanted == null) j.copy(label = null, color = null)
        else j.copy(label = (Labels.resolve(labels, wanted) ?: throw IllegalArgumentException("No label called \"$wanted\": see GET /api/v1/labels (POST one there to make it).")).id)
    }
    if (b.containsKey("tags")) {
        val t = b["tags"]
        j = when (t) {
            is JsonObject -> j.copy(tags = Labels.cleanTags(t.entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()) }))
            null, is JsonNull -> j.copy(tags = emptyMap())
            else -> throw IllegalArgumentException("\"tags\" is an object: {\"genre\": \"metal\", \"gym\": \"\"}.")
        }
    }
    return j
}

/** The label calls: named colours the syncs can wear. False for any other call. */
private fun Web.apiLabels(ex: HttpExchange, method: String, rest: List<String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "labels" || rest.size > 2 || (rest.size == 1 && method != "POST") || (rest.size == 2 && method !in setOf("PATCH", "DELETE"))) return false
    fun colour(c: String): String = c.lowercase().takeIf { Labels.isBase(it) } ?: Html.customColor(c)
        ?: throw IllegalArgumentException("\"color\" is one of ${Labels.BASE.joinToString(", ")}, or #RRGGBB.")
    fun shown(id: String) = env.store.data.settings.labels.firstOrNull { it.id == id }?.let { Api.label(env, it) }
    if (rest.size == 1) {
        // Names a ready colour, or saves a colour of one's own as a label.
        val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"color\": \"#RRGGBB\", \"name\": \"Gym\"}.") }
        val name = b.text("name") ?: return true.also { error(400, "\"name\" is needed.") }
        val color = runCatching { colour(b.text("color") ?: throw IllegalArgumentException("\"color\" is needed.")) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
        var id: String? = null
        env.store.update { d -> Labels.save(d, color, name).also { id = it.second }.first }
        json(201, Api.label(env, Labels.find(env.store.data.settings.labels, id)!!))
        return true
    }
    val l = env.store.data.settings.labels.firstOrNull { it.id == rest[1] } ?: Labels.find(emptyList(), rest[1])
        ?: return true.also { error(404, "No label with that id.") }
    if (method == "DELETE") {
        // Its syncs keep their colour, without the name.
        env.store.update { Labels.delete(it, l.id) }
        json(200, buildJsonObject { put("deleted", l.id) })
        return true
    }
    val b = apiJson(ex) ?: return true.also { error(400, "Send JSON.") }
    val color = runCatching { b.text("color")?.let { colour(it) } }.getOrElse { return true.also { _ -> error(400, it.message ?: "Wrong request.") } }
    if (color != null && (Labels.isBase(l.id) || Labels.isBase(color))) return true.also { error(400, "Only a label saved from a colour of your own can change colour, to another #RRGGBB.") }
    env.store.update { d ->
        var n = d
        if (color != null) n = Labels.recolor(n, l.id, color)
        if (b.containsKey("name")) n = Labels.rename(n, l.id, b.text("name").orEmpty())
        n
    }
    // Renamed to nothing, the label is gone (a ready colour is then shown without its name).
    json(200, shown(l.id) ?: Labels.find(emptyList(), l.id)?.let { Api.label(env, it) } ?: buildJsonObject { put("deleted", l.id) })
    return true
}

/** Answers the sync and run calls it knows; false for any other, left to Api.kt. */
internal fun Web.apiSyncs(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun bad(e: Throwable) = error(400, e.message ?: "Wrong request.")
    if (apiLabels(ex, method, rest, json, error)) return true
    when {
        rest == listOf("syncs") && method == "POST" -> {
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON (see GET /api/v1/openapi.json).") }
            createSyncFromApi(b, json, error)
        }
        rest == listOf("runs") && method == "GET" -> runsPage(query["sync"], query, json, error)
        rest.size == 2 && rest[0] == "syncs" && rest[1] != "run-all" && rest[1] != "run" && (method == "PATCH" || method == "DELETE") -> {
            val job = env.store.job(rest[1]) ?: return true.also { error(404, "No sync with that id.") }
            if (method == "DELETE") {
                if (runner.isRunning(job.id)) SyncState.stop(job.id)
                env.store.deleteJob(job.id)
                env.log("api", "deleted sync ${job.name}")
                return true.also { json(200, buildJsonObject { put("deleted", job.id) }) }
            }
            val b = apiJson(ex) ?: return true.also { error(400, "Send JSON.") }
            val edited = runCatching {
                var j = job
                b.text("name")?.let { j = j.copy(name = it) }
                b.flag("enabled")?.let { j = j.copy(enabled = it) }
                // A radio sync's source only grows and is timed by its window: its schedule and changes stay.
                if (j.radio == null) j = changed(timed(j, b), b)
                grouped(coloured(j, b), b, env.store.data.settings.labels)
            }.getOrElse { e -> return true.also { bad(e) } }
            val warning = conflictWarning(edited)
            env.store.upsertJob(edited)
            // As kept: the label's colour on the card, the tags tidied.
            val saved = env.store.job(edited.id) ?: edited
            json(200, buildJsonObject {
                Api.job(env, saved, runner.isRunning(edited.id)).forEach { (k, v) -> put(k, v) }
                warning?.let { put("warning", it) }
            })
        }
        rest.size == 3 && rest[0] == "syncs" -> {
            val job = env.store.job(rest[1]) ?: return false
            when {
                rest[2] == "move" && method == "POST" -> {
                    val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"to\": 3} or {\"to\": \"up\"}.") }
                    val all = env.store.data.jobs
                    val at = all.indexOfFirst { it.id == job.id }
                    val to = (b.text("to") ?: query["to"])?.lowercase() ?: return true.also { error(400, "Say where: {\"to\": 3}, or up, down, top, bottom.") }
                    val index = when (to) {
                        "up" -> at - 1; "down" -> at + 1; "top" -> 0; "bottom" -> all.size - 1
                        else -> to.toIntOrNull()?.minus(1) ?: return true.also { error(400, "\"to\" is a position from 1, or up, down, top, bottom.") }
                    }.coerceIn(0, all.size - 1)
                    env.store.moveJob(job.id, index)
                    json(200, buildJsonObject { put("id", job.id); put("position", env.store.data.jobs.indexOfFirst { it.id == job.id } + 1); put("of", all.size) })
                }
                rest[2] in setOf("stop", "pause", "resume") && method == "POST" -> {
                    if (!runner.isRunning(job.id)) return true.also { error(409, "${job.name} is not running.") }
                    when (rest[2]) { "stop" -> SyncState.stop(job.id); "pause" -> SyncState.pause(job.id); else -> SyncState.resume(job.id) }
                    json(200, buildJsonObject { put("id", job.id); put("running", runner.isRunning(job.id)); put("paused", SyncState.isPaused(job.id)); put("stopping", rest[2] == "stop") })
                }
                rest[2] == "preview" && method == "GET" -> preview(job, json, error)
                rest[2] == "runs" && method == "GET" -> runsPage(job.id, query, json, error)
                else -> return false
            }
        }
        rest.size == 3 && rest[0] == "runs" && rest[2] == "review" -> {
            val report = env.store.data.reports.firstOrNull { it.id == rest[1] } ?: return true.also { error(404, "No run with that id.") }
            val job = env.store.job(report.jobId) ?: return true.also { error(404, "The sync of that run is gone.") }
            when (method) {
                "GET" -> json(200, reviewOf(report))
                "POST" -> {
                    val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"action\": \"ignore\", \"tracks\": [\"<id>\"]}.") }
                    reviewAction(job, report, b, json, error)
                }
                else -> error(405, "GET or POST.")
            }
        }
        else -> return false
    }
    return true
}

private fun Web.createSyncFromApi(b: JsonObject, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    val s = b["source"] as? JsonObject ?: return error(400, "\"source\" is needed: {\"service\": \"spotify\", \"playlist\": \"<id>\"} (or \"link\" for a public playlist).")
    val t = b["target"] as? JsonObject ?: return error(400, "\"target\" is needed: {\"service\": \"…\", \"playlist\": \"<id>\"}, or without playlist for a new one.")
    val src = s.text("service")?.let { Providers.byId(it) } ?: return error(404, "No source service with that id: see GET /api/v1/services.")
    val dst = t.text("service")?.let { Providers.byId(it) } ?: return error(404, "No target service with that id: see GET /api/v1/services.")
    if (!src.linkOnly && !usable(src)) return error(409, "${src.label(env)} is not connected.")
    if (!usable(dst)) return error(409, "${dst.label(env)} is not connected.")
    if (dst.linkOnly || !dst.canWrite) return error(400, "${dst.label(env)} cannot be a destination.")
    // As the page: only playlists the services list (an id is a path or a URL part for some), and a
    // link-only source (SoundCloud, NetEase, QQ Music) from a link, read now to say whether it is public.
    val chosen = runCatching {
        runBlocking {
            val linked = if (src.linkOnly || s.text("link") != null) {
                val path = linkedPlaylist(src, s.text("link") ?: s.text("playlist").orEmpty()) ?: throw IllegalArgumentException("Not a link to a public playlist on ${src.label(env)}.")
                src.playlistInfo(env, path)
            } else null
            val srcId = linked?.id ?: s.text("playlist") ?: throw IllegalArgumentException("\"source.playlist\" is needed: an id from GET /api/v1/services/${src.id}/playlists.")
            val srcName = linked?.name ?: (src.libraryEntries(env, false) + src.playlists(env)).firstOrNull { it.id == srcId }?.name
                ?: throw IllegalArgumentException("${src.label(env)} has no playlist with the id $srcId.")
            val dstId = t.text("playlist")
            if (dstId == null && !dst.canCreatePlaylists) throw IllegalArgumentException("${dst.label(env)} cannot create playlists: give \"target.playlist\".")
            if (src.id == dst.id && dstId == srcId) throw IllegalArgumentException(env.text("error_same_playlist"))
            val dstName = dstId?.let { id ->
                (dst.libraryEntries(env, true) + dst.playlists(env).filter { it.ownedByMe }).firstOrNull { it.id == id }?.name
                    ?: throw IllegalArgumentException("${dst.label(env)} has no playlist of yours with the id $id.")
            } ?: srcName
            Triple(PlaylistRef(src.id, srcId, srcName), PlaylistRef(dst.id, dstId, dstName), srcName)
        }
    }.getOrElse { return if (it is IllegalArgumentException) error(400, it.message ?: "Wrong request.") else error(502, it.message ?: it.javaClass.simpleName) }
    val job = runCatching {
        val base = SyncJob(id = UUID.randomUUID().toString(), name = b.text("name") ?: chosen.third, source = chosen.first, target = chosen.second)
        grouped(coloured(changed(timed(base, b), b), b), b, env.store.data.settings.labels).let { j -> b.flag("enabled")?.let { j.copy(enabled = it) } ?: j }
    }.getOrElse { return error(400, it.message ?: "Wrong request.") }
    val warning = conflictWarning(job)
    env.store.upsertJob(job)
    env.log("api", "created sync ${job.name}")
    json(201, buildJsonObject {
        Api.job(env, env.store.job(job.id) ?: job, false).forEach { (k, v) -> put(k, v) }
        warning?.let { put("warning", it) }
    })
}

/** What a run would do now, without changing anything (the app's preview): a target not made yet is not made. */
private fun Web.preview(job: SyncJob, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    if (job.radio != null) return error(400, "A radio sync's tracks come from the station as it plays: there is nothing to preview.")
    val plan = runCatching { runBlocking { SyncEngine(env).plan(job, createTarget = false) } }
        .getOrElse { return error(502, it.message ?: it.javaClass.simpleName) }
    json(200, buildJsonObject {
        put("syncId", job.id)
        put("sourceTracks", plan.sourceCount)
        put("targetTracks", plan.targetCount)
        put("alreadyThere", plan.alreadyPresent)
        put("ignored", plan.ignored)
        put("toAdd", buildJsonArray { plan.toAdd.forEach { add(trackRef(it)) } })
        put("toRemove", buildJsonArray { plan.toRemove.forEach { add(trackRef(it)) } })
        put("notFound", buildJsonArray { plan.unmatched.forEach { add(trackRef(it)) } })
        put("toCheck", buildJsonArray { plan.uncertain.forEach { add(review(it)) } })
        put("removalsNeedConfirmation", plan.removalsNeedConfirmation)
        put("targetShrunkFrom", plan.targetShrunkFrom)
        put("notes", buildJsonArray { plan.notes.forEach { add(it) } })
    })
}

/** The runs, newest first, [sync]'s only when given: "limit" (1–200, 50) from "offset". */
private fun Web.runsPage(sync: String?, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    if (sync != null && env.store.job(sync) == null && env.store.data.reports.none { it.jobId == sync }) return error(404, "No sync with that id.")
    val all = env.store.data.reports.filter { sync == null || it.jobId == sync }
    val limit = (query["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
    val offset = (query["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
    json(200, buildJsonObject {
        put("total", all.size); put("offset", offset); put("limit", limit)
        put("runs", JsonArray(all.drop(offset).take(limit).map { Api.run(it, tracks = false) }))
    })
}

private fun reviewOf(r: SyncReport) = buildJsonObject {
    put("runId", r.id); put("syncId", r.jobId); put("started", r.startedEpoch)
    put("notFound", buildJsonArray { r.unmatchedTracks.forEach { add(trackRef(it)) } })
    put("toCheck", buildJsonArray { r.reviewTracks.forEach { add(review(it)) } })
    put("removed", buildJsonArray { r.removedTracks.forEach { add(trackRef(it)) } })
}

/**
 * One of the page's review actions on a run. "tracks" names the rows by their source ids ("all" for
 * every row of the section); "choice" is the track to use instead (an id on the target service, a
 * link from any service, "Artist - Title", or an object as the playlist calls take).
 */
private fun Web.reviewAction(job: SyncJob, report: SyncReport, b: JsonObject, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    val action = b.text("action") ?: return error(400, "\"action\" is one of ignore, keep, discard, pick, replace, restore.")
    val ids: Set<String>? = when (val t = b["tracks"]) {
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        is JsonPrimitive -> if (t.contentOrNull == "all") null else setOfNotNull(t.contentOrNull)
        else -> emptySet()
    }
    fun notFound() = report.unmatchedTracks.filter { ids == null || it.id in ids }
    fun toCheck() = report.reviewTracks.filter { ids == null || it.source.id in ids }
    val engine = SyncEngine(env)
    val dst: MusicProvider? = Providers.byId(job.target.provider)
    suspend fun choice(): Track {
        val asked = b["choice"]?.let { ApiPlaylists.asWanted(it) } ?: throw IllegalArgumentException("\"choice\" is needed: the track to use.")
        val p = dst ?: throw IllegalArgumentException("The sync's destination is not on this server.")
        return ApiPlaylists.find(env, p, asked) ?: throw IllegalArgumentException("${p.label(env)} has nothing for ${ApiPlaylists.describe(asked)}.")
    }
    val done = runCatching {
        runBlocking {
            when (action) {
                "ignore" -> notFound().ifEmpty { throw IllegalArgumentException("Name tracks of the run's not-found list (their ids).") }
                    .also { engine.ignore(job, report.id, it) }.let { "Ignored ${it.size}: not searched for again on this service." }
                "keep" -> toCheck().ifEmpty { throw IllegalArgumentException("Name tracks of the run's to-check list (their source ids).") }
                    .also { engine.confirmMatches(report.id, it) }.let { "Kept ${it.size}." }
                "discard" -> toCheck().ifEmpty { throw IllegalArgumentException("Name tracks of the run's to-check list (their source ids).") }
                    .let { "Removed ${engine.discardMatches(job, report.id, it)}; this sync no longer adds them." }
                "pick" -> {
                    val one = notFound().singleOrNull() ?: throw IllegalArgumentException("Name one track of the not-found list.")
                    val c = choice()
                    engine.resolveManually(job, report.id, one, c)
                    "Added ${c.artistLine} - ${c.title}."
                }
                "replace" -> {
                    val one = toCheck().singleOrNull() ?: throw IllegalArgumentException("Name one track of the to-check list.")
                    val c = choice()
                    engine.replaceMatch(job, report.id, one, c)
                    "Replaced with ${c.artistLine} - ${c.title}."
                }
                "restore" -> {
                    if (report.removedTracks.isEmpty()) throw IllegalArgumentException("That run removed nothing.")
                    engine.restoreRemoved(job, report)
                    "Put back ${report.removedTracks.size}."
                }
                else -> throw IllegalArgumentException("\"action\" is one of ignore, keep, discard, pick, replace, restore.")
            }
        }
    }
    env.store.flush()
    done.fold(
        onSuccess = { msg ->
            val now = env.store.data.reports.firstOrNull { it.id == report.id } ?: report
            json(200, buildJsonObject { put("done", msg); put("review", reviewOf(now)) })
        },
        onFailure = { if (it is IllegalArgumentException) error(400, it.message ?: "Wrong request.") else error(502, it.message ?: it.javaClass.simpleName) },
    )
}
