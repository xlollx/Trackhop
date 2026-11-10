package com.xlollx.songport.sync

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * "Coordinate with my server": before a run writes to a playlist, this device reserves it on the
 * user's own Trackhop server (POST /api/v1/locks, with the API token made there), renews the
 * reservation while the run goes on and gives it back at the end. The server's own syncs respect
 * the same reservations ([TargetLocks]), so the phone and the server never write to one playlist at
 * once. A playlist reserved by another device: the run is skipped ([TargetBusyException]). A server
 * that cannot be reached, or refuses the token: the run goes on as without the option, and the log
 * says so; syncs never wait on the server.
 */
class ServerLocks(
    address: String,
    private val token: String,
    /** How this device calls itself ("Pixel 8"): the other devices read it in their notes. */
    holder: String,
    private val log: (String) -> Unit,
    private val ttlMs: Long = TargetLocks.DEFAULT_TTL_MS,
    private val renewEveryMs: Long = ttlMs / 3,
) : TargetCoordinator {
    val base: String = normalize(address)
    private val holder = holder.trim().ifEmpty { "Trackhop" }

    private sealed class Got {
        class Lock(val id: String) : Got()
        class Busy(val holder: String, val until: Long) : Got()
        /** No answer, or one that is no reservation: [code] 0 when the server was not reached; [error] the server's own words. */
        class Failed(val code: Int, val why: String, val error: String? = null) : Got()
    }

    private class Reply(val code: Int, val body: JsonElement)

    private suspend fun call(method: String, path: String, body: JsonElement?): Reply = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/v1/$path")
            .header("Authorization", "Bearer $token").header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { r ->
            Reply(r.code, runCatching { parseJson(r.body?.string().orEmpty()) }.getOrDefault(JsonNull))
        }
    }

    private fun ttlBody() = buildJsonObject { put("ttl", ttlMs / 1000) }

    private suspend fun take(target: String, ttl: Long = ttlMs): Got = try {
        val r = call("POST", "locks", buildJsonObject { put("target", target); put("holder", holder); put("ttl", ttl / 1000) })
        when {
            r.code in 200..299 -> r.body["id"].str?.let { Got.Lock(it) } ?: Got.Failed(r.code, "answered without a reservation")
            r.code == 409 -> Got.Busy(r.body["holder"].str ?: "another device", r.body["until"].long ?: 0)
            else -> r.body["error"].str.let { Got.Failed(r.code, "answered ${r.code}" + (it?.let { e -> ": $e" } ?: ""), it) }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Got.Failed(0, "not reachable (${e.message ?: e.javaClass.simpleName})")
    }

    private suspend fun release(ids: Collection<String>) = ids.forEach { id ->
        runCatching { call("DELETE", "locks/$id", null) }.onFailure { log("$base: could not give back a reservation (${it.message}); it ends by itself") }
    }

    override suspend fun <T> holding(targets: List<String>, block: suspend () -> T): T {
        val keys = targets.filter { TargetLocks.shared(it) }
        if (keys.isEmpty()) return block()
        /** Target → the id of its reservation. */
        val held = ConcurrentHashMap<String, String>()
        for (k in keys) {
            when (val g = take(k)) {
                is Got.Lock -> held[k] = g.id
                is Got.Busy -> {
                    withContext(NonCancellable) { release(held.values.toList()) }
                    throw TargetBusyException(g.holder, g.until)
                }
                is Got.Failed -> {
                    log("$base ${g.why}: syncing without it")
                    withContext(NonCancellable) { release(held.values.toList()) }
                    return block()
                }
            }
        }
        return coroutineScope {
            // A renewal is done whole even when the run ends meanwhile, so what is given back below is
            // what the server holds.
            val renewer = launch { while (isActive) { delay(renewEveryMs); withContext(NonCancellable) { renew(held) } } }
            try {
                block()
            } finally {
                withContext(NonCancellable) {
                    renewer.cancelAndJoin()
                    release(held.values.toList())
                }
            }
        }
    }

    /** Each reservation extended; one that ended meanwhile (a server restarted) is taken again where it can be. */
    private suspend fun renew(held: MutableMap<String, String>) {
        for ((k, id) in held.toMap()) {
            val code = try { call("PUT", "locks/$id", ttlBody()).code } catch (e: Exception) { 0 }
            when {
                code in 200..299 -> Unit
                code == 404 -> when (val g = take(k)) {
                    is Got.Lock -> held[k] = g.id
                    is Got.Busy -> { held.remove(k); log("$base: $k is now reserved by ${g.holder}") }
                    is Got.Failed -> log("$base ${g.why}: the reservation of $k could not be renewed")
                }
                else -> log("$base: the reservation of $k could not be renewed (${if (code == 0) "not reachable" else "answered $code"})")
            }
        }
    }

    sealed class Check {
        object Ok : Check()
        /** The server does not know reservations yet: an older version. */
        object Old : Check()
        /** The token is not the server's (made again since, or mistyped). */
        object Token : Check()
        class Unreachable(val why: String) : Check()
        /** Refused, in the server's words (the API turned off, …). */
        class Refused(val why: String) : Check()
    }

    /** Tries the address and the token: a reservation of a playlist nobody has, given back at once. */
    suspend fun check(): Check = when (val g = take("check|" + UUID.randomUUID(), TargetLocks.MIN_TTL_MS)) {
        is Got.Lock -> { withContext(NonCancellable) { release(listOf(g.id)) }; Check.Ok }
        is Got.Busy -> Check.Ok
        is Got.Failed -> when (g.code) {
            0 -> Check.Unreachable(g.why)
            401 -> Check.Token
            404 -> Check.Old
            else -> Check.Refused(g.error ?: g.why)
        }
    }

    /** The server's address and the API token made there. */
    data class Config(val address: String, val token: String)

    companion object {
        private const val STORE_ID = "__server_locks__"
        private val JSON = "application/json".toMediaType()
        /** Short waits: a server that does not answer must not hold a sync up for long. */
        private val client by lazy {
            Http.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
        }

        /** The server's address and token, kept with the other secrets of this device (never in a configuration file). */
        fun config(env: Env): Config? = env.tokens.get(STORE_ID)?.let { t ->
            t.extra["address"]?.takeIf { it.isNotBlank() && t.accessToken.isNotBlank() }?.let { Config(it, t.accessToken) }
        }

        fun save(env: Env, c: Config) = env.tokens.set(STORE_ID, Tokens(accessToken = c.token.trim(), extra = mapOf("address" to normalize(c.address))))

        fun clear(env: Env) = env.tokens.clear(STORE_ID)

        /** The coordinator of [env]'s runs, as [holder]; null while the option is off. */
        fun of(env: Env, holder: String): ServerLocks? =
            config(env)?.let { c -> ServerLocks(c.address, c.token, holder, { env.log("server", it) }) }

        /** "nas:8080/", "http://nas:8080/api/v1" → "http://nas:8080". */
        fun normalize(address: String): String {
            val a = address.trim().trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
            return if (a.startsWith("http://", true) || a.startsWith("https://", true) || a.isEmpty()) a else "http://$a"
        }
    }
}
