package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Labels
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.TargetLocks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A small JSON API for other tools (Home Assistant, n8n, scripts): the syncs and their runs, start
 * one or all, the services, the File playlists. Under /api/v1, with a token the user makes on the
 * API page (only its SHA-256 is kept) sent as "Authorization: Bearer <token>"; off until then. And a
 * webhook: when a run ends, a POST of it to the user's address, signed with HMAC-SHA256 when a
 * secret is set. Documented in docs/guide/docker.md#api.
 */
object Api {
    private const val KV = "api"

    private fun hash(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    fun enabled(env: ServerEnv) = !env.keyValues(KV)["token"].isNullOrEmpty()

    /** A new token, shown once; the old one stops working. */
    fun newToken(env: ServerEnv): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = "th_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        env.keyValues(KV)["token"] = hash(token)
        return token
    }

    fun revoke(env: ServerEnv) { env.keyValues(KV)["token"] = "" }

    fun authorized(env: ServerEnv, header: String?): Boolean {
        val stored = env.keyValues(KV)["token"].orEmpty().ifEmpty { return false }
        val given = header?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim() ?: return false
        return MessageDigest.isEqual(hash(given).toByteArray(), stored.toByteArray())
    }

    /**
     * The key in the address of the "Missing on my server" list for Lidarr, whose Custom List only
     * GETs a URL and cannot send a token: it opens that list and nothing else, and changes with the
     * token (a new token, a new key; the API off, no key).
     */
    fun listKey(env: ServerEnv): String? {
        val stored = env.keyValues(KV)["token"].orEmpty().ifEmpty { return null }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(stored.toByteArray(), "HmacSHA256")) }
        return mac.doFinal("trackhop wanted list".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    private const val LIST_KEY = "ListKey "

    /**
     * The Authorization a call comes with; for GET /api/v1/wanted without one, its ?key= as
     * "ListKey <key>", so a shared server can tell whose list it is from the key alone.
     */
    fun authOf(ex: HttpExchange): String? = ex.requestHeaders.getFirst("Authorization")?.takeUnless { it.startsWith(LIST_KEY) } ?: run {
        if (ex.requestMethod.uppercase() != "GET" || ex.requestURI.path.trimEnd('/') != "/api/v1/wanted") return null
        ex.requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }?.let { LIST_KEY + it }
    }

    fun listKeyMatches(env: ServerEnv, header: String?): Boolean {
        val given = header?.takeIf { it.startsWith(LIST_KEY) }?.substring(LIST_KEY.length) ?: return false
        val key = listKey(env) ?: return false
        return MessageDigest.isEqual(given.toByteArray(), key.toByteArray())
    }

    fun webhook(env: ServerEnv): String = env.keyValues(KV)["webhook"].orEmpty()
    fun setWebhook(env: ServerEnv, url: String, secret: String?, events: Set<String>? = null) {
        env.keyValues(KV)["webhook"] = url
        if (secret != null) env.keyValues(KV)["secret"] = secret
        if (events != null) env.keyValues(KV)["events"] = EVENTS.filter { it in events }.joinToString(",")
    }

    fun hasSecret(env: ServerEnv) = !env.keyValues(KV)["secret"].isNullOrEmpty()

    /** What the webhook is told about: every event a receiver can ask for, and the two it gets unless it asks otherwise. */
    val EVENTS = listOf("sync.started", "sync.finished", "sync.failed", "backup.finished", "wanted.added")
    private val DEFAULT_EVENTS = setOf("sync.finished", "backup.finished")

    fun events(env: ServerEnv): Set<String> {
        val saved = env.keyValues(KV)["events"] ?: return DEFAULT_EVENTS
        return saved.split(',').map { it.trim() }.filter { it in EVENTS }.toSet()
    }

    // ------------------------------------------------------------------ JSON

    fun job(env: ServerEnv, j: SyncJob, running: Boolean): JsonObject = buildJsonObject {
        put("id", j.id)
        put("name", j.name)
        put("source", ref(env, j.source.provider, j.source.playlistId, j.source.playlistName))
        put("target", ref(env, j.target.provider, j.target.playlistId, j.target.playlistName))
        put("schedule", j.schedule.name.lowercase())
        // The timing in full: every N minutes, at a time of day, on chosen days (1 = Monday).
        put("timing", com.xlollx.songport.sync.Timing.describe(j))
        put("every", com.xlollx.songport.sync.Timing.interval(j))
        put("at", j.at?.let { JsonPrimitive(it) } ?: JsonNull)
        put("days", buildJsonArray { j.days.forEach { add(it) } })
        put("color", j.color?.let { JsonPrimitive(it) } ?: JsonNull)
        // The one label it wears (its colour, with a name when the user gave one), its star and its tags.
        put("label", j.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("labelName", Labels.name(j, env.store.data.settings.labels).takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("favorite", j.favorite)
        put("tags", buildJsonObject { j.tags.forEach { (k, v) -> put(k, v) } })
        put("position", env.store.data.jobs.indexOfFirst { it.id == j.id } + 1)
        put("mirrorRemovals", j.mirrorRemovals)
        // The source changes by itself (a daylist): with mirrorRemovals, big removals need no confirmation.
        put("followsChanges", j.followsChanges)
        // Keeps every track ever seen: nothing is removed from the target, new tracks are added at the end.
        put("archive", j.archive)
        // Half of a two-way pair: the other half's id, whether this is the "↔" half, and whether removals travel too.
        val other = j.linkedJobId?.let { env.store.job(it) }
        put("twoWay", other != null)
        put("linkedSyncId", other?.let { JsonPrimitive(it.id) } ?: JsonNull)
        put("twin", other != null && !other.twin && (j.twin || j.name == other.name + " ↔"))
        put("twoWayRemovals", j.twoWayRemovals)
        put("enabled", j.enabled)
        // Came turned off with a configuration from another device: where its schedule runs (null once turned on here).
        put("scheduledOn", j.scheduledOn?.takeIf { !j.enabled }?.let { JsonPrimitive(it) } ?: JsonNull)
        // Its source is the playlist the Shazam app fills on Spotify or Apple Music.
        put("shazam", j.shazam)
        put("running", running)
        put("lastRun", if (j.lastRunEpoch > 0) JsonPrimitive(j.lastRunEpoch) else JsonNull)
        put("nextRun", Runner.nextRun(j)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastResult", j.lastReportId?.let { env.store.report(it) }?.let { run(it, tracks = false) } ?: JsonNull)
        j.radio?.let { r ->
            put("radio", buildJsonObject {
                put("station", r.station); put("stream", r.stream)
                put("window", com.xlollx.songport.sync.RadioTitles.describe(r))
                put("days", buildJsonArray { r.days.forEach { add(it) } })
                put("start", "%02d:%02d".format(r.startMinute / 60, r.startMinute % 60))
                put("end", "%02d:%02d".format(r.endMinute / 60, r.endMinute % 60))
            })
        }
    }

    /**
     * The syncs a call asks for with "tag" (key:value, key=value or a key alone; several split by
     * commas, all wanted), "label" (id, colour or name) and "favorite" (true or false); every sync
     * without them. Throws IllegalArgumentException for a tag without a key.
     */
    fun selected(env: ServerEnv, query: Map<String, String>): List<SyncJob> {
        val d = env.store.data
        val favorite = query["favorite"]?.let { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("\"favorite\" is true or false.") }
        return Labels.select(d.jobs, d.settings.labels, query["tag"].orEmpty().split(',').filter { it.isNotBlank() }, query["label"], favorite)
    }

    fun grouped(query: Map<String, String>) = listOf("tag", "label", "favorite").any { !query[it].isNullOrBlank() }

    /** A label as the API shows it: the saved ones, and the ready colours (their key as the id, no name until given one). */
    fun label(env: ServerEnv, l: com.xlollx.songport.model.Label) = buildJsonObject {
        put("id", l.id); put("color", l.color)
        put("name", l.name.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) } ?: JsonNull)
        put("syncs", env.store.data.jobs.count { it.label == l.id })
    }

    fun labels(env: ServerEnv): JsonArray {
        val saved = env.store.data.settings.labels
        return JsonArray(Labels.BASE.map { k -> label(env, Labels.find(saved, k)!!) } + saved.filter { !Labels.isBase(it.id) }.map { label(env, it) })
    }

    private fun ref(env: ServerEnv, provider: String, id: String?, name: String) = buildJsonObject {
        put("service", provider)
        put("serviceName", Providers.byId(provider)?.label(env) ?: provider)
        put("playlistId", id?.let { JsonPrimitive(it) } ?: JsonNull)
        put("playlist", name)
    }

    fun run(r: SyncReport, tracks: Boolean = true): JsonObject = buildJsonObject {
        put("id", r.id)
        put("syncId", r.jobId)
        put("syncName", r.jobName)
        put("started", r.startedEpoch)
        put("durationMs", r.durationMs)
        put("ok", r.ok)
        put("error", r.error?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sourceTracks", r.sourceCount)
        put("added", r.added)
        put("removed", r.removed)
        put("notFound", r.unmatched.size)
        put("toCheck", r.reviewTracks.size)
        put("targetTracks", r.targetCount)
        put("partial", r.partial)
        // Removals the run held back (a quarter or more of the target, or of what a two-way pair shared): they wait for a confirmation.
        put("heldRemovals", r.heldBack)
        if (tracks) {
            put("notFoundTracks", buildJsonArray { r.unmatchedTracks.forEach { add(trackRef(it)) } })
            put("addedTracks", buildJsonArray { r.addedTracks.forEach { add(trackRef(it)) } })
            put("removedTracks", buildJsonArray { r.removedTracks.forEach { add(trackRef(it)) } })
            put("toCheckTracks", buildJsonArray { r.reviewTracks.forEach { m -> add(buildJsonObject { put("source", trackRef(m.source)); put("match", trackRef(m.chosen)); put("score", m.score) }) } })
            // What the run had to say along the way (the page's grey lines under a run).
            put("notes", buildJsonArray { r.notes.forEach { add(it) } })
        }
    }

    // ------------------------------------------------------------------ metrics

    /**
     * The syncs as Prometheus metrics (text format 0.0.4), for Grafana and the like: one series per
     * sync, labelled with its id and name, from its last run.
     */
    fun metrics(env: ServerEnv, running: Set<String>): String = buildString {
        fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
        val jobs = env.store.data.jobs
        fun gauge(name: String, help: String, value: Number) { append("# HELP $name $help\n# TYPE $name gauge\n$name $value\n") }
        gauge("trackhop_syncs", "Syncs configured", jobs.size)
        gauge("trackhop_syncs_enabled", "Syncs enabled", jobs.count { it.enabled })
        gauge("trackhop_syncs_running", "Syncs running now", running.size)
        gauge("trackhop_backup_last_run_timestamp_seconds", "Last full backup", Backups.lastRun(env) / 1000)
        val series = listOf(
            Triple("trackhop_sync_last_run_timestamp_seconds", "When the sync last ran") { j: SyncJob, _: SyncReport? -> j.lastRunEpoch / 1000 },
            Triple("trackhop_sync_last_ok", "1 if the last run succeeded") { _: SyncJob, r: SyncReport? -> if (r?.ok == true) 1 else 0 },
            Triple("trackhop_sync_last_added", "Tracks added by the last run") { _: SyncJob, r: SyncReport? -> r?.added ?: 0 },
            Triple("trackhop_sync_last_removed", "Tracks removed by the last run") { _: SyncJob, r: SyncReport? -> r?.removed ?: 0 },
            Triple("trackhop_sync_last_not_found", "Tracks not found by the last run") { _: SyncJob, r: SyncReport? -> r?.unmatched?.size ?: 0 },
            Triple("trackhop_sync_source_tracks", "Tracks in the source at the last run") { _: SyncJob, r: SyncReport? -> r?.sourceCount ?: 0 },
            Triple("trackhop_sync_running", "1 while the sync runs") { j: SyncJob, _: SyncReport? -> if (j.id in running) 1 else 0 },
        )
        for ((name, help, value) in series) {
            append("# HELP $name $help\n# TYPE $name gauge\n")
            for (j in jobs) {
                val r = j.lastReportId?.let { env.store.report(it) }
                append("$name{id=\"${esc(j.id)}\",name=\"${esc(j.name)}\"} ${value(j, r)}\n")
            }
        }
    }

    // ------------------------------------------------------------------ webhook

    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() }

    /**
     * Tells the webhook a run ended (sync.finished), and, when it failed, sync.failed too for a
     * receiver that only wants failures. Never fails the run: an error goes to the log.
     */
    fun notify(env: ServerEnv, job: SyncJob, report: SyncReport) {
        for (event in listOfNotNull("sync.finished", "sync.failed".takeIf { !report.ok })) post(env, event, buildJsonObject {
            put("event", event)
            put("sync", buildJsonObject { put("id", job.id); put("name", job.name) })
            put("run", run(report))
        })
    }

    /** Tells the webhook a run put tracks on the "Missing on my server" list for the first time. */
    fun notifyWanted(env: ServerEnv, job: SyncJob, added: List<com.xlollx.songport.sync.Wanted.Want>) {
        if (added.isEmpty()) return
        post(env, "wanted.added", buildJsonObject {
            put("event", "wanted.added")
            put("sync", buildJsonObject { put("id", job.id); put("name", job.name) })
            put("count", added.size)
            put("tracks", com.xlollx.songport.sync.Wanted.json(added))
        })
    }

    /** Tells the webhook a run started (by hand, by its schedule or by a call). */
    fun notifyStarted(env: ServerEnv, job: SyncJob, scheduled: Boolean) = post(env, "sync.started", buildJsonObject {
        put("event", "sync.started")
        put("sync", buildJsonObject { put("id", job.id); put("name", job.name) })
        put("scheduled", scheduled)
    })

    /** Tells the webhook a full backup ended: how many services, how many failures. */
    fun notifyBackup(env: ServerEnv, services: Int, failures: Int) = post(env, "backup.finished", buildJsonObject {
        put("event", "backup.finished")
        put("services", services)
        put("failures", failures)
        put("ok", failures == 0)
    })

    private fun post(env: ServerEnv, name: String, event: JsonObject) {
        if (!env.may(Permissions.API)) return
        val url = webhook(env).ifEmpty { return }
        if (name !in events(env)) return
        val body = event.toString()
        // The address too is read in here: one that is no URL is a line in the log, never a sync that stops.
        runCatching {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("User-Agent", "Trackhop")
            env.keyValues(KV)["secret"].orEmpty().takeIf { it.isNotEmpty() }?.let { secret ->
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
                req.header("X-Trackhop-Signature", "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) })
            }
            client.sendAsync(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
        }.onFailure { env.log("api", "webhook: ${it.message}") }
    }
}

private const val MAX_API_BODY = 20_000_000

/** /api/v1/…: answered before the page's own password, with the API's token instead. */
internal fun Web.api(ex: HttpExchange, method: String, path: List<String>, query: Map<String, String>) {
    fun json(code: Int, e: JsonElement) = send(ex, code, e.toString(), "application/json")
    fun error(code: Int, message: String) = json(code, buildJsonObject { put("error", message) })
    // The calls' description is public: it holds nothing of the user's, and tools read it before a token is made.
    if (path.drop(2) == listOf("openapi.json") && method == "GET") return send(ex, 200, ApiDocs.openApi(version, env.baseUrl).toString(), "application/json")
    // The command line on this machine (LocalKey) gets in without the API being turned on.
    val auth = Api.authOf(ex)
    if (!env.may(Permissions.API)) return error(403, "The API is not turned on for you on this server: ask its admin.")
    if (!LocalKey.matches(auth, ex.remoteAddress?.address)) {
        if (!Api.enabled(env)) return error(403, "The API is off: make a token on the page, Tools › API.")
        // The list's own key (Lidarr's Custom List cannot send a token) opens GET /wanted only: authOf gives it for that call alone.
        val listOnly = method == "GET" && path.drop(2) == listOf("wanted") && Api.listKeyMatches(env, auth)
        if (!Api.authorized(env, auth) && !listOnly) return error(401, "Send the API token as: Authorization: Bearer <token>")
    }
    val running = SyncState.running.value
    val jobs = env.store.data.jobs
    val rest = path.drop(2)
    if (apiDiag(ex, method, rest, query, ::json, ::error)) return
    if (apiSyncs(ex, method, rest, query, ::json, ::error)) return
    if (apiMore(ex, method, rest, query, ::json, ::error)) return
    if (apiWanted(ex, method, rest, query, ::json, ::error)) return
    when {
        rest == listOf("status") && method == "GET" -> json(200, buildJsonObject {
            put("version", version)
            put("syncs", jobs.size)
            put("running", buildJsonArray { running.keys.forEach { add(it) } })
            put("lastRun", jobs.maxOfOrNull { it.lastRunEpoch } ?: 0)
        })
        // A playlist reserved by another device (a phone set to coordinate with this server) before it
        // writes there, for a while it renews as it runs: this server's syncs skip it meanwhile, and a
        // reservation is refused while one of them writes there (TargetLocks).
        rest == listOf("locks") && method == "POST" -> {
            val b = apiJson(ex) ?: return error(400, "Send JSON: {\"target\": \"service|playlist\", \"holder\": \"who\", \"ttl\": seconds}.")
            val target = b.text("target")?.takeIf { '|' in it && it.length <= 500 } ?: return error(400, "\"target\" is the playlist as \"service|playlist\" (spotify|37i9dQZF1DX…).")
            val holder = b.text("holder")?.take(80) ?: return error(400, "\"holder\" says who reserves it, as people should read it (\"Pixel 8\").")
            val ttl = b.number("ttl")?.takeIf { it > 0 }?.let { it * 1000L } ?: TargetLocks.DEFAULT_TTL_MS
            when (val a = TargetLocks.take(target, holder, ttl)) {
                is TargetLocks.Answer.Taken -> json(200, lockJson(a.lease))
                is TargetLocks.Answer.Busy -> {
                    val who = a.holder ?: productName
                    json(409, buildJsonObject { put("error", "Running on $who."); put("target", target); put("holder", who); put("until", a.until) })
                }
            }
        }
        rest.size == 2 && rest[0] == "locks" && method == "PUT" -> {
            val b = apiJson(ex) ?: return error(400, "Send JSON: {\"ttl\": seconds}, or nothing.")
            val ttl = b.number("ttl")?.takeIf { it > 0 }?.let { it * 1000L } ?: TargetLocks.DEFAULT_TTL_MS
            val lease = TargetLocks.renew(rest[1], ttl) ?: return error(404, "No such reservation: it ended (given back or expired). Ask for it again.")
            json(200, lockJson(lease))
        }
        rest.size == 2 && rest[0] == "locks" && method == "DELETE" -> {
            if (!TargetLocks.release(rest[1])) return error(404, "No such reservation: it had ended already.")
            json(200, buildJsonObject { put("released", true) })
        }
        rest == listOf("syncs") && method == "GET" -> {
            val wanted = runCatching { Api.selected(env, query) }.getOrElse { return error(400, it.message ?: "Wrong request.") }
            json(200, JsonArray(wanted.map { Api.job(env, it, it.id in running) }))
        }
        // A group of syncs at once: those with a tag, a label, or the starred ones (turned on only).
        rest == listOf("syncs", "run") && method == "POST" -> {
            if (!Api.grouped(query)) return error(400, "Say which syncs: ?tag=key:value, ?label=… or ?favorite=true (POST /api/v1/syncs/run-all starts them all).")
            val started = runCatching { Api.selected(env, query) }.getOrElse { return error(400, it.message ?: "Wrong request.") }.filter { it.enabled }
            started.forEach { runner.start(it) }
            json(202, buildJsonObject {
                put("started", started.size)
                put("syncs", buildJsonArray { started.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) } })
            })
        }
        rest == listOf("tags") && method == "GET" -> json(200, buildJsonObject {
            // Every tag key in use, with the values the syncs give it (none for a key used alone).
            Labels.keysInUse(jobs).forEach { k -> put(k, buildJsonArray { Labels.valuesInUse(jobs, k).forEach { add(it) } }) }
        })
        rest == listOf("labels") && method == "GET" -> json(200, Api.labels(env))
        rest == listOf("metrics") && method == "GET" -> send(ex, 200, Api.metrics(env, running.keys), "text/plain; version=0.0.4")
        rest == listOf("config") && method == "GET" -> {
            ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"${com.xlollx.songport.data.ConfigBackup.fileName()}\"")
            send(ex, 200, Notify.intoBackup(env, com.xlollx.songport.data.ConfigBackup.export(env, "$productName $version (API)", history = query["history"] == "true")), "application/json")
        }
        rest == listOf("backup") && method == "GET" -> json(200, buildJsonObject {
            put("running", Tasks.running(Backups.TITLE))
            put("lastRun", Backups.lastRun(env))
            put("schedule", env.store.data.settings.backupSchedule.name.lowercase())
        })
        rest == listOf("backup") && method == "POST" -> {
            if (Tasks.running(Backups.TITLE)) return error(409, "A backup is already running.")
            val task = Tasks.start(Backups.TITLE, "/tools/backup", { env.log("backup", it) }, env.owner) { t -> Backups.run(env) { t.progress = it } }
            json(202, buildJsonObject { put("task", task.id) })
        }
        rest.size == 2 && rest[0] == "tasks" && method == "GET" -> {
            val t = Tasks.get(rest[1], env.sees) ?: return error(404, "No task with that id (finished tasks are kept for an hour).")
            json(200, buildJsonObject {
                put("id", t.id); put("title", t.title); put("done", t.done); put("progress", t.progress)
                put("error", t.error?.let { JsonPrimitive(it) } ?: JsonNull)
                put("finished", if (t.finishedAt > 0) JsonPrimitive(t.finishedAt) else JsonNull)
            })
        }
        rest == listOf("syncs", "run-all") && method == "POST" -> {
            val started = jobs.filter { it.enabled }
            started.forEach { runner.start(it) }
            json(202, buildJsonObject { put("started", started.size) })
        }
        rest.size >= 2 && rest[0] == "syncs" -> {
            val job = env.store.job(rest[1]) ?: return error(404, "No sync with that id.")
            when {
                rest.size == 2 && method == "GET" -> json(200, buildJsonObject {
                    Api.job(env, job, job.id in running).forEach { (k, v) -> put(k, v) }
                    put("runs", JsonArray(env.store.data.reports.filter { it.jobId == job.id }.take(20).map { Api.run(it, tracks = false) }))
                })
                rest.size == 3 && (rest[2] == "enable" || rest[2] == "disable") && method == "POST" -> {
                    enablePair(job, rest[2] == "enable")
                    json(200, Api.job(env, env.store.job(job.id) ?: job, job.id in running))
                }
                rest.size == 3 && rest[2] == "run" && method == "POST" -> {
                    val already = job.id in running
                    // After a run stopped by the target's size check: the caller says the smaller target is real.
                    if (query["anyway"] == "true") env.store.confirmTargetShrink(job.id)
                    // Removals a run holds back (a quarter or more of the target): confirmed for this run, as
                    // the app's preview does. true confirms what the preview shows now; a list of track
                    // ids (the preview's toRemove) only those, so nothing unseen goes.
                    val b = apiJson(ex) ?: JsonObject(emptyMap())
                    val ids = (b["confirmRemovals"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    val confirm = ids == null && (b.flag("confirmRemovals") ?: (query["confirmRemovals"] == "true"))
                    if (already && (confirm || ids != null)) return error(409, "${job.name} is running: confirm the removals when it has ended.")
                    if (ids != null) env.store.confirmRemovals(job.id, ids)
                    runner.start(job, confirmRemovals = confirm)
                    json(202, buildJsonObject { put("started", !already); put("running", true); if (confirm || ids != null) put("removalsConfirmed", true) })
                }
                else -> error(404, "Unknown call.")
            }
        }
        rest == listOf("notifications") && method == "GET" -> json(200, JsonArray(Notify.channels(env).map { Notify.shown(it) }))
        rest.size == 3 && rest[0] == "notifications" && rest[2] == "test" && method == "POST" -> {
            val c = Notify.channel(env, rest[1]) ?: return error(404, "No notification channel with that id.")
            val failed = Notify.test(c)
            env.log("notify", "test to ${c.name} (${c.kind.label}): ${failed ?: "sent"}")
            if (failed != null) return error(502, "${c.name}: $failed")
            json(200, buildJsonObject { put("sent", true); put("channel", Notify.shown(c)) })
        }
        rest.size == 2 && rest[0] == "runs" && method == "GET" -> {
            val report = env.store.data.reports.firstOrNull { it.id == rest[1] } ?: return error(404, "No run with that id.")
            json(200, Api.run(report))
        }
        rest.size >= 3 && rest[0] == "services" -> ApiPlaylists.handle(this, ex, method, rest, ::json, ::error)
        rest == listOf("services") && method == "GET" -> json(200, JsonArray(Providers.connectors().map { p ->
            buildJsonObject {
                put("id", p.id); put("name", p.label(env))
                // Which service, and which of its accounts ("" the main one, "2", "3"… the others): id is "jellyfin@2".
                put("serviceId", p.serviceId); put("slot", p.slot)
                put("connected", !p.requiresAuth || p.isConnected(env))
                put("account", p.accountName(env)?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }))
        rest == listOf("files") && method == "GET" -> json(200, JsonArray(csvFiles().map { JsonPrimitive(it.name) }))
        rest.size == 2 && rest[0] == "files" && (method == "POST" || method == "PUT") -> {
            // A playlist file in the body (CSV, M3U, XSPF, JSPF, iTunes XML, JSON, plain text), named by the path.
            val bytes = ex.requestBody.readNBytes(MAX_API_BODY + 1)
            if (bytes.size > MAX_API_BODY) return error(413, "Files up to ${MAX_API_BODY / 1_000_000} MB.")
            val n = runCatching { com.xlollx.songport.providers.LocalFilesProvider.importFile(env, rest[1], com.xlollx.songport.sync.PlaylistFiles.decode(bytes)) }
                .getOrElse { return error(400, it.message ?: "Could not read the file.") }
            if (n == 0) return error(400, "No tracks found in the file.")
            env.log("files", "imported ${rest[1]} through the API: $n tracks")
            json(201, buildJsonObject { put("tracks", n) })
        }
        rest.size == 2 && rest[0] == "files" && method == "GET" -> {
            val f = csvFiles().firstOrNull { it.name == rest[1] } ?: return error(404, "No such file.")
            val format = ExportFormat.of(query["format"])
            val text = if (format == ExportFormat.CSV) f.readText() else format.encode(f.nameWithoutExtension, com.xlollx.songport.sync.CsvCodec.parse(f.readText()))
            send(ex, 200, text, format.mime)
        }
        rest == listOf("lyrics") && (method == "GET" || method == "POST") -> {
            if (edition != Html.Edition.SERVER || !env.may(Permissions.ADMIN)) return error(403, "Lyrics are for whoever runs the Docker server.")
            val root = Lyrics.musicDir ?: return error(409, "No music folder: mount it and set SONGPORT_MUSIC (see the guide, Lyrics).")
            if (method == "GET") return json(200, buildJsonObject {
                put("folder", root.path)
                put("running", Tasks.running(Lyrics.TITLE))
                put("weekly", Lyrics.weekly(env))
                put("lastRun", Lyrics.lastRun(env)?.let { s ->
                    buildJsonObject {
                        put("at", s.at); put("files", s.files); put("saved", s.saved); put("synced", s.synced); put("plain", s.plain)
                        put("hadLyrics", s.had); put("instrumental", s.instrumental); put("notFound", s.missing); put("unreadable", s.unreadable)
                    }
                } ?: JsonNull)
            })
            if (Tasks.running(Lyrics.TITLE)) return error(409, "Lyrics are already being looked for.")
            val folder = query["folder"].orEmpty()
            if (Lyrics.target(root, folder) == null) return error(400, "$folder is not a folder of the library.")
            val options = Lyrics.Options(folder, syncedOnly = query["synced"] == "true", retryMissing = query["retry"] == "true")
            val task = Tasks.start(Lyrics.TITLE, "/tools/lyrics", { env.log("lyrics", it) }, env.owner) { t -> lyricsResult(Lyrics.run(env, options) { t.progress = it }) }
            json(202, buildJsonObject { put("task", task.id) })
        }
        else -> error(404, "Unknown call. See https://trackhop.org/guide/docker#api")
    }
}

private fun lockJson(l: TargetLocks.Lease) = buildJsonObject {
    put("id", l.id); put("target", l.target); put("holder", l.holder); put("until", l.until)
}

private val WEBHOOK_EVENT_NAMES = mapOf(
    "sync.started" to "A sync starts", "sync.finished" to "A sync ends", "sync.failed" to "A sync fails", "backup.finished" to "A full backup ends",
    "wanted.added" to "New tracks are missing on your server",
)

/** Tools › API: the token (made, shown once, revoked) and the webhook. */
internal fun Web.apiPage(r: Web.Req) {
    if (!env.may(Permissions.API)) return send(r.ex, 403, page("API", NOT_ALLOWED))
    var shown: String? = null
    if (r.method == "POST") {
        when (r.form["action"]) {
            "token" -> shown = Api.newToken(env)
            "revoke" -> { Api.revoke(env); return redirect(r.ex, "/tools/api?ok=${q("The API is off.")}") }
            "webhook" -> {
                val url = r.form["url"].orEmpty().trim()
                if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) return redirect(r.ex, "/tools/api?err=${q("The address starts with http:// or https://.")}")
                // The events' boxes are on the form only from this version: an older form leaves them as they were.
                val events = if (r.form["ev"] == "1") r.rawForm.filter { it.first == "event" }.map { it.second }.toSet() else null
                Api.setWebhook(env, url, r.form["secret"]?.trim()?.takeIf { it.isNotEmpty() }, events)
                return redirect(r.ex, "/tools/api?ok=${q("Saved.")}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p>Other tools (Home Assistant, n8n, Node-RED, a script) can do what this page does, from the syncs and their runs to playlists, files and notifications, through a JSON API at <code>${h(env.baseUrl)}/api/v1</code>. <a href="https://trackhop.org/guide/docker#api">The calls</a> · <a href="/api/v1/openapi.json">OpenAPI description</a></p>""")
    if (shown != null) {
        body.append("""<div class="card ok"><b>Your token</b>: copy it now, it is not shown again.<pre>${h(shown)}</pre><span class="muted">Send it as <code>Authorization: Bearer …</code></span></div>""")
    }
    body.append(if (Api.enabled(env)) """<p class="ok">The API is on.</p>""" else """<p class="muted">The API is off until you make a token.</p>""")
    body.append("<div class=\"actions\">" + Html.postButton("/tools/api", if (Api.enabled(env)) "Make a new token" else "Make a token", fields = mapOf("action" to "token"),
        confirm = if (Api.enabled(env)) "The current token stops working. Make a new one?" else null))
    if (Api.enabled(env)) body.append(Html.postButton("/tools/api", "Turn the API off", "danger", fields = mapOf("action" to "revoke")))
    body.append("</div></div>")
    body.append(
        """<form method="post" action="/tools/api" class="card"><input type="hidden" name="action" value="webhook"><h3>Webhook</h3>
<p class="muted">When something below happens, Trackhop POSTs it as JSON (<code>"event":"sync.finished"</code>) to this address. With a secret, the header <code>X-Trackhop-Signature: sha256=…</code> is the HMAC-SHA256 of the body.</p>
<label>Address</label><input type="text" name="url" value="${h(Api.webhook(env))}" placeholder="https://homeassistant.local:8123/api/webhook/trackhop">
<label>Secret (optional; leave empty to keep the current one)</label><input type="password" name="secret" autocomplete="off">
<input type="hidden" name="ev" value="1"><fieldset><legend>Tell it when</legend>${Api.EVENTS.joinToString("") { e -> """<label class="check"><input type="checkbox" name="event" value="$e"${if (e in Api.events(env)) " checked" else ""}> ${WEBHOOK_EVENT_NAMES[e]} <code>$e</code></label>""" }}</fieldset>
<p><button class="secondary">Save</button></p></form>""",
    )
    send(r.ex, 200, page("API", body.toString()))
}
