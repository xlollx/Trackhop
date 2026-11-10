package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.parseJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * This device and the user's own Trackhop server (the address and API token of "Coordinate with my
 * server", [ServerLocks.Config]), through the server's /api/v1: the server's syncs with their last
 * result, a run started there, a run's review, a sync handed over (made there from one of this
 * device's) or taken back, and the review's choices exchanged ([ReviewDecisions]).
 *
 * Whatever the server answers is checked before use: ids are plain, numbers are numbers, a list is a
 * list. Nothing of this device's accounts is ever sent: a sync goes as its two services' names and
 * its playlists' ids, and the server uses its own accounts.
 */
class ServerSyncs(address: String, private val token: String) {
    val base: String = ServerLocks.normalize(address)

    /** Why a call did not give what was asked, for words on screen. */
    class Failure(val kind: Kind, val detail: String = "") : Exception(detail.ifEmpty { kind.name }) {
        enum class Kind {
            /** No answer: offline, wrong address, server down. */
            UNREACHABLE,
            /** The token is not (or no longer) the server's. */
            TOKEN,
            /** A server older than these calls. */
            OLD,
            /** Refused, in the server's words ([detail]). */
            REFUSED,
            /** Not found there (a sync deleted on the server). */
            GONE,
            /** An answer that is not what the API gives. */
            ODD,
        }
    }

    private class Reply(val code: Int, val body: JsonElement)

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement {
        val r = try {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$base/api/v1/$path")
                    .header("Authorization", "Bearer $token").header("Accept", "application/json")
                    .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "POST") "".toRequestBody(JSON) else null)
                    .build()
                client.newCall(req).execute().use { res ->
                    // A sync list of a few hundred syncs is far below this; anything larger is not the API.
                    val text = res.body?.source()?.let { s -> s.request(MAX_BODY); s.buffer.readUtf8(minOf(s.buffer.size, MAX_BODY)) }.orEmpty()
                    Reply(res.code, runCatching { parseJson(text) }.getOrDefault(JsonNull))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw Failure(Failure.Kind.UNREACHABLE, e.message.orEmpty())
        } catch (e: java.io.IOException) {
            throw Failure(Failure.Kind.UNREACHABLE, e.message ?: e.javaClass.simpleName)
        }
        if (r.code in 200..299) return r.body
        val said = ((r.body as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull?.take(300).orEmpty()
        throw when (r.code) {
            401 -> Failure(Failure.Kind.TOKEN, said)
            404 -> if (said.startsWith("Unknown call")) Failure(Failure.Kind.OLD, said) else Failure(Failure.Kind.GONE, said)
            else -> Failure(Failure.Kind.REFUSED, said.ifEmpty { "HTTP ${r.code}" })
        }
    }

    /** The server's services: which are connected there. */
    suspend fun services(): List<Service> = parseServices(call("GET", "services"))

    /** Every sync of the server, with its last result. */
    suspend fun syncs(): List<Sync> = parseSyncs(call("GET", "syncs"))

    /** Starts [id] on the server. */
    suspend fun run(id: String) { call("POST", "syncs/${safeId(id)}/run") }

    /** Deletes [id] on the server (its playlists stay as they are). A sync gone already is no failure. */
    suspend fun delete(id: String) {
        try { call("DELETE", "syncs/${safeId(id)}") } catch (e: Failure) { if (e.kind != Failure.Kind.GONE) throw e }
    }

    /**
     * Makes [job] on the server with its services [plan] picked there; answers the new sync's id. It
     * asks for the id [HandOver.serverId]: sent again after an answer lost on the way, the server
     * gives back the sync it made then, not a second one (a server that does not take it picks its own).
     */
    suspend fun create(job: SyncJob, plan: HandOver.Plan.Ready): String {
        val made = call("POST", "syncs", HandOver.body(job, plan))
        return made.idOrNull("id") ?: throw Failure(Failure.Kind.ODD, "no id")
    }

    /** A run's review there: not found, and matches to check. */
    suspend fun review(runId: String): Review = parseReview(call("GET", "runs/${safeId(runId)}/review"))

    /** Ignores ([ignore]) tracks not found, or keeps matches to check, of a run there; answers the review left. */
    suspend fun act(runId: String, ignore: Boolean, sourceIds: List<String>): Review {
        val body = buildJsonObject {
            put("action", if (ignore) "ignore" else "keep")
            put("tracks", buildJsonArray { sourceIds.forEach { add(JsonPrimitive(it)) } })
        }
        return parseReview((call("POST", "runs/${safeId(runId)}/review", body) as? JsonObject)?.get("review"))
    }

    /**
     * Sends this device's review choices for the server's sync [id] and answers the server's, both
     * sides' merged there ([ReviewDecisions]). A server without the call: [Failure.Kind.OLD].
     */
    suspend fun exchange(id: String, mine: Map<String, ReviewDecision>, now: Long = System.currentTimeMillis()): Map<String, ReviewDecision> {
        val body = buildJsonObject { put("decisions", ReviewDecisions.toJson(mine)) }
        val got = call("POST", "syncs/${safeId(id)}/decisions", body) as? JsonObject ?: throw Failure(Failure.Kind.ODD, "not an object")
        return try { ReviewDecisions.parse(got["decisions"], now) } catch (e: IllegalArgumentException) { throw Failure(Failure.Kind.ODD, e.message.orEmpty()) }
    }

    /** A service on the server: its connector id ("jellyfin@2"), its service ("jellyfin"), connected there. */
    data class Service(val id: String, val serviceId: String, val name: String, val connected: Boolean)

    /** One side of a server's sync. */
    data class Side(val service: String, val serviceName: String, val playlistId: String?, val playlist: String)

    /** A server's run, in short. */
    data class Run(
        val id: String, val started: Long, val ok: Boolean, val error: String?,
        val added: Int, val removed: Int, val notFound: Int, val toCheck: Int,
    )

    /** A sync of the server, as its list says. */
    data class Sync(
        val id: String, val name: String, val source: Side, val target: Side, val timing: String,
        val enabled: Boolean, val running: Boolean, val lastRun: Long?, val nextRun: Long?, val last: Run?,
    )

    data class ReviewTrack(val id: String, val artist: String, val title: String)
    data class ReviewMatch(val source: ReviewTrack, val match: ReviewTrack)
    data class Review(val notFound: List<ReviewTrack>, val toCheck: List<ReviewMatch>)

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_BODY = 8L * 1024 * 1024
        private const val MAX_TEXT = 500
        // Not dots alone: "." and ".." in an address would reach another call of the server.
        private val ID = Regex("(?!\\.+$)[A-Za-z0-9_.@:-]{1,128}")
        private val client by lazy {
            Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
        }

        /** The calls of "Coordinate with my server", or null while it is off. */
        fun of(env: com.xlollx.songport.platform.Env): ServerSyncs? = ServerLocks.config(env)?.let { ServerSyncs(it.address, it.token) }

        /** An id the server gave, put in an address only when it is a plain one. */
        fun safeId(id: String): String = id.takeIf { ID.matches(it) } ?: throw Failure(Failure.Kind.ODD, "id")

        private fun JsonElement?.textOrNull(k: String): String? =
            ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull?.take(MAX_TEXT)
        private fun JsonElement?.idOrNull(k: String): String? = textOrNull(k)?.takeIf { ID.matches(it) }
        private fun JsonElement?.count(k: String): Int = (((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L).coerceIn(0, 1_000_000).toInt()
        private fun JsonElement?.time(k: String): Long? = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
        private fun JsonElement?.flag(k: String): Boolean = ((this as? JsonObject)?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true

        private fun list(e: JsonElement?): JsonArray = e as? JsonArray ?: throw Failure(Failure.Kind.ODD, "not a list")

        fun parseServices(e: JsonElement?): List<Service> = list(e).mapNotNull { s ->
            val id = s.idOrNull("id") ?: return@mapNotNull null
            Service(id, s.idOrNull("serviceId") ?: id.substringBefore('@'), s.textOrNull("name") ?: id, s.flag("connected"))
        }

        private fun side(e: JsonElement?): Side? {
            val service = e.idOrNull("service") ?: return null
            return Side(service, e.textOrNull("serviceName") ?: service, e.textOrNull("playlistId"), e.textOrNull("playlist").orEmpty())
        }

        private fun run(e: JsonElement?): Run? {
            val o = e as? JsonObject ?: return null
            val id = o.idOrNull("id") ?: return null
            return Run(id, o.time("started") ?: 0, o.flag("ok"), o.textOrNull("error"), o.count("added"), o.count("removed"), o.count("notFound"), o.count("toCheck"))
        }

        /** The server's GET /syncs, the entries that are not a sync left out. */
        fun parseSyncs(e: JsonElement?): List<Sync> = list(e).mapNotNull { s ->
            val id = s.idOrNull("id") ?: return@mapNotNull null
            val source = side((s as JsonObject)["source"]) ?: return@mapNotNull null
            val target = side(s["target"]) ?: return@mapNotNull null
            Sync(
                id, s.textOrNull("name") ?: source.playlist, source, target, s.textOrNull("timing").orEmpty(),
                s.flag("enabled"), s.flag("running"), s.time("lastRun"), s.time("nextRun"), run(s["lastResult"]),
            )
        }

        private fun track(e: JsonElement?): ReviewTrack? {
            val id = e.textOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
            return ReviewTrack(id, e.textOrNull("artist").orEmpty(), e.textOrNull("title").orEmpty())
        }

        fun parseReview(e: JsonElement?): Review {
            val o = e as? JsonObject ?: throw Failure(Failure.Kind.ODD, "not a review")
            return Review(
                (o["notFound"] as? JsonArray).orEmpty().mapNotNull { track(it) },
                (o["toCheck"] as? JsonArray).orEmpty().mapNotNull { m -> (m as? JsonObject)?.let { mo -> track(mo["source"])?.let { s -> track(mo["match"])?.let { ReviewMatch(s, it) } } } },
            )
        }
    }
}

/**
 * "Hand over to the server": whether a sync of this device can be made on the user's server, with
 * which of the server's accounts, and the API body that makes it there.
 */
object HandOver {
    sealed class Plan {
        /** It can go: the server's connector for each side. */
        data class Ready(val source: String, val target: String) : Plan()
        /** The server has no connected account of these services (their ids on this device). */
        data class Missing(val services: List<String>) : Plan()
        /** A kind of sync that lives on this device only. */
        data class NotHere(val why: Why) : Plan()
    }

    enum class Why { RADIO, TWO_WAY, SHAZAM, FILES }

    /**
     * What [job] needs on a server with [services]. A side goes to the connector with the same id
     * when it is connected there, otherwise to a connected account of the same service (the main
     * one first); the playlists are the server's to check when the sync is made.
     */
    fun plan(job: SyncJob, services: List<ServerSyncs.Service>): Plan {
        when {
            job.radio != null -> return Plan.NotHere(Why.RADIO)
            job.linkedJobId != null -> return Plan.NotHere(Why.TWO_WAY)
            job.shazam -> return Plan.NotHere(Why.SHAZAM)
            // File playlists are each device's own.
            FileStore.family(job.source.provider) == "csv" || FileStore.family(job.target.provider) == "csv" -> return Plan.NotHere(Why.FILES)
        }
        val src = pick(job.source.provider, services)
        val dst = pick(job.target.provider, services)
        if (src != null && dst != null) return Plan.Ready(src, dst)
        return Plan.Missing(listOfNotNull(job.source.provider.takeIf { src == null }, job.target.provider.takeIf { dst == null }).distinct())
    }

    /** The server's connector for this device's [provider], or null when none is connected there. */
    fun pick(provider: String, services: List<ServerSyncs.Service>): String? {
        val connected = services.filter { it.connected }
        connected.firstOrNull { it.id == provider }?.let { return it.id }
        val family = FileStore.family(provider)
        return connected.filter { FileStore.family(it.id) == family }.sortedBy { if ('@' in it.id) 1 else 0 }.firstOrNull()?.id
    }

    /**
     * The id [job] asks for on the server: always the same for a sync of this device, so a hand-over
     * whose answer was lost (or cut short) finds the sync it made there instead of making another.
     */
    fun serverId(job: SyncJob): String = "phone-" + job.id.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }.take(80)

    /** The body of POST /api/v1/syncs that makes [job] on the server, with the connectors of [plan]. */
    fun body(job: SyncJob, plan: Plan.Ready): JsonObject = buildJsonObject {
        put("id", serverId(job))
        put("name", job.name.ifBlank { job.source.playlistName })
        put("source", buildJsonObject {
            put("service", plan.source)
            if (job.source.link != null) put("link", job.source.link) else job.source.playlistId?.let { put("playlist", it) }
        })
        put("target", buildJsonObject {
            put("service", plan.target)
            job.target.playlistId?.let { put("playlist", it) }
        })
        put("every", Timing.interval(job))
        job.at?.let { put("at", it) }
        put("days", buildJsonArray { job.days.forEach { add(JsonPrimitive(it)) } })
        put("changes", if (job.archive) "archive" else if (job.mirrorRemovals) "mirror" else "add")
        if (job.mirrorRemovals && !job.archive) put("follows", job.followsChanges)
        put("enabled", job.enabled)
        put("favorite", job.favorite)
        if (job.tags.isNotEmpty()) put("tags", buildJsonObject { job.tags.forEach { (k, v) -> put(k, v) } })
    }

    /**
     * A sync of this device that is the same as the server's [s] (the same two playlists, on the
     * same services), not handed over: its review choices are shared too.
     */
    fun sameAs(job: SyncJob, s: ServerSyncs.Sync): Boolean =
        job.source.playlistId != null && job.target.playlistId != null &&
            FileStore.family(job.source.provider) == FileStore.family(s.source.service) && job.source.playlistId == s.source.playlistId &&
            FileStore.family(job.target.provider) == FileStore.family(s.target.service) && job.target.playlistId == s.target.playlistId
}
