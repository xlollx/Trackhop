package com.xlollx.songport.notify

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.platform.Env
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Notifications: a short message to the person's phone or chat when a sync run ends with something
 * worth knowing (changes, an error, tracks to review, removals held back, new releases of their
 * artists). Any number of channels, each with the events it wants: Telegram, ntfy, Gotify, Discord,
 * Slack, Matrix and Pushover. The same on the server (Docker, Desktop) and in the Android app; the
 * server's webhook (Api.kt) stays as it was, for tools that want the whole run as JSON.
 *
 * Channels live in the "notify" key-values of the [Env]. On the server that is `kv/notify.json`,
 * secrets included, written readable by its owner only like the webhook's secret and the sign-ins
 * (Storage.kt: the data folder is the boundary on a server); on a shared server each person has
 * their own, since each engine has its own data folder (MultiUser.kt). The app keeps the secrets
 * apart, in the store its sign-ins are in ([Env.tokens], encrypted with the Android Keystore):
 * [secretsInVault]. Secrets are never shown back in full, never put in a message, and left out of
 * the configuration backups, as the services' sign-ins are (ConfigBackup).
 *
 * Sending never holds or fails a sync: it happens on a thread of its own, at most [TIMEOUT_S]
 * seconds per channel, and a failure only goes to the log.
 */
object Notify {
    private const val KV = "notify"
    private const val VAULT = "notify:"

    /** Secrets kept in [Env.tokens] rather than with the channels: the app turns it on at start. */
    @Volatile var secretsInVault = false
    private const val TIMEOUT_S = 10L

    /** What a channel can be told about. */
    enum class Event(val key: String, val label: String) {
        CHANGES("changes", "A sync added or removed tracks"),
        FAILED("failed", "A sync failed (a sign-in expired, a service did not answer)"),
        REVIEW("review", "Tracks to review (uncertain matches)"),
        REMOVALS("removals", "Removals waiting for your confirmation"),
        RELEASES("releases", "New releases of your artists (a weekly New releases sync found some)"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    /** One setting of a channel; [secret] ones are masked when shown and kept out of backups. */
    class Field(
        val key: String,
        val label: String,
        val secret: Boolean = false,
        val optional: Boolean = false,
        val default: String = "",
        val placeholder: String = "",
        /** An address: must be http:// or https://. */
        val url: Boolean = false,
    )

    enum class Kind(val id: String, val label: String, val help: String, val fields: List<Field>) {
        TELEGRAM(
            "telegram", "Telegram", "Make a bot with @BotFather and paste its token; send the bot a message, then put your chat id (@userinfobot tells it).",
            listOf(Field("token", "Bot token", secret = true, placeholder = "123456:ABC…"), Field("chat", "Chat id", placeholder = "123456789 or @channel")),
        ),
        NTFY(
            "ntfy", "ntfy", "Subscribe to a topic in the ntfy app. Pick a topic hard to guess: on ntfy.sh anyone who knows it can read it.",
            listOf(
                Field("server", "Server", default = "https://ntfy.sh", url = true),
                // Secret too: on ntfy.sh whoever knows the topic reads its messages.
                Field("topic", "Topic", secret = true, placeholder = "trackhop-…"),
                Field("token", "Access token (protected topics only)", secret = true, optional = true, placeholder = "tk_…"),
            ),
        ),
        GOTIFY(
            "gotify", "Gotify", "In Gotify, Apps › Create application, and paste its token.",
            listOf(Field("server", "Server", url = true, placeholder = "https://gotify.example.org"), Field("token", "App token", secret = true)),
        ),
        DISCORD(
            "discord", "Discord", "Channel settings › Integrations › Webhooks › New webhook › Copy webhook URL.",
            listOf(Field("url", "Webhook URL", secret = true, url = true, placeholder = "https://discord.com/api/webhooks/…")),
        ),
        SLACK(
            "slack", "Slack", "Make an app with an incoming webhook for a channel (api.slack.com/messaging/webhooks) and paste its URL.",
            listOf(Field("url", "Webhook URL", secret = true, url = true, placeholder = "https://hooks.slack.com/services/…")),
        ),
        MATRIX(
            "matrix", "Matrix", "A (bot) account in the room: its access token (Element: Settings › Help & About), and the room's id (Room settings › Advanced).",
            listOf(
                Field("server", "Homeserver", url = true, default = "https://matrix.org"),
                Field("token", "Access token", secret = true),
                Field("room", "Room id", placeholder = "!abcdef:matrix.org"),
            ),
        ),
        PUSHOVER(
            "pushover", "Pushover", "Your user key is on pushover.net's front page; create an application there for its API token.",
            listOf(Field("token", "Application API token", secret = true), Field("user", "User key", secret = true)),
        ),
        ;

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    data class Channel(
        val id: String,
        val kind: Kind,
        val name: String,
        val enabled: Boolean = true,
        val events: Set<Event> = Event.entries.toSet(),
        val values: Map<String, String> = emptyMap(),
    ) {
        fun value(key: String): String = values[key].orEmpty()

        /** The settings it still needs (a backup brings no secrets): empty when it can send. */
        fun missing(): List<Field> = kind.fields.filter { !it.optional && value(it.key).isBlank() }

        /** Its addresses (server, webhook), hidden from technical reports like the user's own servers. */
        fun addresses(): List<String> = kind.fields.filter { it.url }.map { value(it.key) }.filter { it.isNotBlank() }

        /** Its secrets' values, to keep them out of anything shown (an error message). */
        fun secrets(): List<String> = kind.fields.filter { it.secret }.map { value(it.key) }.filter { it.length >= 4 }
    }

    data class Message(val title: String, val text: String)

    // ------------------------------------------------------------------ storage

    fun channels(env: Env): List<Channel> {
        val raw = env.keyValues(KV)["channels"].orEmpty().ifEmpty { return emptyList() }
        val list = runCatching { Json.parseToJsonElement(raw).jsonArray.mapNotNull { fromJson(it, withSecrets = true) } }.getOrDefault(emptyList())
        if (!secretsInVault) return list
        return list.map { c ->
            val kept = env.tokens.get(VAULT + c.id)?.extra.orEmpty()
            c.copy(values = c.values + c.kind.fields.filter { it.secret }.associate { it.key to kept[it.key].orEmpty() })
        }
    }

    fun channel(env: Env, id: String): Channel? = channels(env).firstOrNull { it.id == id }

    private fun save(env: Env, list: List<Channel>) {
        if (secretsInVault) {
            val before = channels(env).map { it.id }.toSet()
            list.forEach { c -> env.tokens.set(VAULT + c.id, Tokens(accessToken = "", extra = c.kind.fields.filter { it.secret }.associate { it.key to c.value(it.key) })) }
            (before - list.map { it.id }.toSet()).forEach { env.tokens.clear(VAULT + it) }
        }
        val mode = if (secretsInVault) Secrets.DROP else Secrets.KEEP
        env.keyValues(KV)["channels"] = JsonArray(list.map { toJson(it, secrets = mode) }).toString()
    }

    /** Adds [c], or replaces the channel with its id. */
    fun put(env: Env, c: Channel) {
        val list = channels(env)
        save(env, if (list.any { it.id == c.id }) list.map { if (it.id == c.id) c else it } else list + c)
    }

    fun remove(env: Env, id: String): Boolean {
        val list = channels(env)
        if (list.none { it.id == id }) return false
        save(env, list.filter { it.id != id })
        return true
    }

    fun newId(): String = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /**
     * What a form or a call asks for [kind], over [old] (an edit): a secret left empty keeps its
     * value, the others are taken as given. Returns the channel, or why it cannot be saved.
     */
    fun build(id: String, kind: Kind, name: String, enabled: Boolean, events: Set<Event>, given: Map<String, String>, old: Channel?): Result<Channel> {
        val values = LinkedHashMap<String, String>()
        val prev = old?.takeIf { it.kind == kind }
        // A saved secret stays only while the channel still points where it did: a new address asks for it again.
        val keep = prev != null && sameAddresses(kind, prev) { f -> given[f.key]?.trim().orEmpty().ifEmpty { if (f.secret) prev.value(f.key) else "" } }
        for (f in kind.fields) {
            val v = given[f.key]?.trim().orEmpty()
            values[f.key] = if (v.isEmpty() && f.secret) (if (keep) prev!!.value(f.key) else "") else v
            val now = values.getValue(f.key)
            if (now.isEmpty() && !f.optional) return Result.failure(IllegalArgumentException("${f.label} is needed."))
            if (f.url && now.isNotEmpty() && (!now.startsWith("http://") && !now.startsWith("https://") || now.toHttpUrlOrNull() == null)) {
                return Result.failure(IllegalArgumentException("${f.label}: an address that starts with http:// or https://."))
            }
        }
        return Result.success(Channel(id, kind, name.trim().ifEmpty { kind.label }, enabled, events, values))
    }

    /** Every address of [kind] (server, webhook) the same in [now] as in [old]. */
    private fun sameAddresses(kind: Kind, old: Channel, now: (Field) -> String): Boolean =
        kind.fields.filter { it.url }.all { f -> now(f).trimEnd('/').equals(old.value(f.key).trimEnd('/'), ignoreCase = true) }

    // ------------------------------------------------------------------ JSON

    private enum class Secrets { KEEP, MASK, DROP }

    private fun toJson(c: Channel, secrets: Secrets): JsonObject = buildJsonObject {
        put("id", c.id)
        put("kind", c.kind.id)
        put("name", c.name)
        put("enabled", c.enabled)
        put("events", buildJsonArray { Event.entries.filter { it in c.events }.forEach { add(it.key) } })
        put("settings", buildJsonObject {
            for (f in c.kind.fields) {
                val v = c.value(f.key)
                when {
                    !f.secret || secrets == Secrets.KEEP -> put(f.key, v)
                    secrets == Secrets.MASK -> put(f.key, mask(v))
                    // DROP: left out, so a restore asks for it again.
                }
            }
        })
    }

    private fun fromJson(e: JsonElement, withSecrets: Boolean): Channel? {
        val o = e as? JsonObject ?: return null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val kind = Kind.of(s("kind")) ?: return null
        val id = s("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,40}")) } ?: return null
        val settings = o["settings"] as? JsonObject ?: JsonObject(emptyMap())
        val values = kind.fields.filter { withSecrets || !it.secret }
            .associate { f -> f.key to ((settings[f.key] as? JsonPrimitive)?.contentOrNull ?: "") }
        val events = (o["events"] as? JsonArray)?.mapNotNull { Event.of((it as? JsonPrimitive)?.contentOrNull) }?.toSet() ?: Event.entries.toSet()
        return Channel(id, kind, s("name") ?: kind.label, (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true, events, values)
    }

    /** A channel for the API and the command line: its secrets masked. */
    fun shown(c: Channel): JsonObject = buildJsonObject {
        toJson(c, Secrets.MASK).forEach { (k, v) -> put(k, v) }
        put("kindName", c.kind.label)
        put("ready", c.missing().isEmpty())
    }

    /** A secret as it may be shown: its last four characters at most, and only when it is long. */
    fun mask(v: String): String = when {
        v.isEmpty() -> ""
        v.length < 12 -> "••••"
        else -> "••••" + v.takeLast(4)
    }

    // ------------------------------------------------------------------ backups

    private val pretty = Json { prettyPrint = true }

    /**
     * The configuration file ([ConfigBackup]'s text) with this person's channels in it, without their
     * secrets, under "notifications": the app, which does not know that key, reads the file as before.
     */
    fun intoBackup(env: Env, text: String): String {
        val list = channels(env).ifEmpty { return text }
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { return text }
        val out = JsonObject(o + ("notifications" to JsonArray(list.map { toJson(it, Secrets.DROP) })))
        return pretty.encodeToString(JsonElement.serializer(), out)
    }

    /** The channels a configuration file holds (without secrets), or none. */
    fun inBackup(text: String): List<Channel> = runCatching {
        Json.parseToJsonElement(text.removePrefix("﻿")).jsonObject["notifications"]?.jsonArray?.mapNotNull { fromJson(it, withSecrets = false) }
    }.getOrNull().orEmpty()

    /**
     * Brings [incoming] in, as the syncs are: the same id replaces the channel but keeps the secrets
     * this server has for it; the others are added, off until their secrets are entered again.
     * Returns how many.
     */
    fun restore(env: Env, incoming: List<Channel>): Int {
        if (incoming.isEmpty()) return 0
        val here = channels(env).associateBy { it.id }
        val merged = incoming.map { c ->
            val old = here[c.id]?.takeIf { it.kind == c.kind }
            // The secrets this Trackhop has for it, while the file sends them to the same addresses.
            val keep = old != null && sameAddresses(c.kind, old) { f -> c.value(f.key) }
            val values = c.kind.fields.associate { f -> f.key to (if (f.secret) (if (keep) old!!.value(f.key) else "") else c.value(f.key)) }
            val next = c.copy(values = values)
            // On only for a channel already here: one that came with a file (shared, perhaps) waits to be turned on.
            if (keep && next.missing().isEmpty()) next else next.copy(enabled = false)
        }
        val ids = merged.map { it.id }.toSet()
        save(env, channels(env).filter { it.id !in ids } + merged)
        return merged.size
    }

    // ------------------------------------------------------------------ messages

    /** What [report] has to tell; [releases]: the run of a "New releases" sync, whose additions are news of their own. */
    fun events(r: SyncReport, releases: Boolean = false): Set<Event> = buildSet {
        if (!r.ok) add(Event.FAILED)
        if (r.ok && releases && r.added > 0) add(Event.RELEASES)
        if (r.ok && !releases && (r.added > 0 || r.removed > 0)) add(Event.CHANGES)
        if (r.reviewTracks.isNotEmpty()) add(Event.REVIEW)
        if (r.heldBack > 0) add(Event.REMOVALS)
    }

    /** The message for a channel that wants [wanted], or null when the run has nothing of it. */
    fun message(syncName: String, r: SyncReport, wanted: Set<Event>, releases: Boolean = false): Message? {
        val happened = events(r, releases) intersect wanted
        if (happened.isEmpty()) return null
        val lines = buildList {
            if (Event.FAILED in happened) add("Failed: ${errorLine(r.error)}")
            if (Event.CHANGES in happened) add("+${r.added} −${r.removed}")
            if (Event.RELEASES in happened) add("New releases: ${r.added} ${if (r.added == 1) "track" else "tracks"} added")
            if (Event.REVIEW in happened) add("${r.reviewTracks.size} ${if (r.reviewTracks.size == 1) "track" else "tracks"} to review")
            if (Event.REMOVALS in happened) add("${r.heldBack} ${if (r.heldBack == 1) "removal waits" else "removals wait"} for your confirmation")
        }
        return Message("Trackhop: $syncName", lines.joinToString("\n"))
    }

    /** An error's first line, short, with any address taken out (a server's, a link with a token). */
    internal fun errorLine(error: String?): String =
        error.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            .replace(Regex("""\b[a-z][a-z0-9+.-]*://\S+""", RegexOption.IGNORE_CASE), "…")
            .let { com.xlollx.songport.data.Redact.text(it) }
            .let { if (it.length > 200) it.take(199) + "…" else it }
            .ifEmpty { "unknown error" }

    val TEST = Message("Trackhop: test", "Notifications work: this channel is set up.")

    // ------------------------------------------------------------------ sending

    /** Telegram's and Pushover's fixed addresses; the tests point them elsewhere. */
    @Volatile var telegramApi = "https://api.telegram.org"
    @Volatile var pushoverApi = "https://api.pushover.net"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val txn = AtomicLong(System.currentTimeMillis())

    private val client by lazy {
        OkHttpClient.Builder()
            // The app's contacted-servers list sees these hosts too (Http.onRequest).
            .addInterceptor(com.xlollx.songport.net.Http.hostInterceptor)
            .callTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** One thread or two, apart from the syncs: a slow channel never holds a run. */
    private val pool: ExecutorService by lazy {
        Executors.newFixedThreadPool(2) { r -> Thread(r, "notify").apply { isDaemon = true } }
    }

    private fun base(url: String): HttpUrl = url.trim().trimEnd('/').toHttpUrlOrNull() ?: throw IllegalArgumentException("not an address")

    private fun json(body: JsonObject) = body.toString().toRequestBody(JSON_TYPE)

    /** The HTTP request that delivers [m] through [c]. */
    fun request(c: Channel, m: Message): Request {
        val b = Request.Builder().header("User-Agent", "Trackhop")
        return when (c.kind) {
            Kind.TELEGRAM -> b.url(base(telegramApi).newBuilder().addPathSegment("bot" + c.value("token")).addPathSegment("sendMessage").build())
                .post(json(buildJsonObject {
                    put("chat_id", c.value("chat")); put("text", "${m.title}\n${m.text}"); put("disable_web_page_preview", true)
                }))
            Kind.NTFY -> b.url(base(c.value("server"))).apply {
                c.value("token").takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.post(json(buildJsonObject {
                // JSON publishing: titles with accents, which HTTP headers cannot carry.
                put("topic", c.value("topic")); put("title", m.title); put("message", m.text)
                put("tags", buildJsonArray { add("musical_note") })
            }))
            Kind.GOTIFY -> b.url(base(c.value("server")).newBuilder().addPathSegment("message").build())
                .header("X-Gotify-Key", c.value("token"))
                .post(json(buildJsonObject { put("title", m.title); put("message", m.text); put("priority", 5) }))
            Kind.DISCORD -> b.url(base(c.value("url"))).post(json(buildJsonObject {
                put("content", "**${m.title}**\n${m.text}")
                // A sync called "@everyone" pings no one.
                put("allowed_mentions", buildJsonObject { put("parse", JsonArray(emptyList())) })
            }))
            Kind.SLACK -> b.url(base(c.value("url"))).post(json(buildJsonObject {
                fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                put("text", "*${esc(m.title)}*\n${esc(m.text)}")
            }))
            Kind.MATRIX -> b.url(
                base(c.value("server")).newBuilder().addPathSegments("_matrix/client/v3/rooms").addPathSegment(c.value("room"))
                    .addPathSegments("send/m.room.message").addPathSegment("trackhop${txn.incrementAndGet()}").build(),
            ).header("Authorization", "Bearer ${c.value("token")}")
                .put(json(buildJsonObject { put("msgtype", "m.text"); put("body", "${m.title}\n${m.text}") }))
            Kind.PUSHOVER -> b.url(base(pushoverApi).newBuilder().addPathSegments("1/messages.json").build())
                .post(FormBody.Builder().add("token", c.value("token")).add("user", c.value("user")).add("title", m.title).add("message", m.text).build())
        }.build()
    }

    /** Sends [m] through [c] now (this thread). Null when it arrived, else why not, with no secret in it. */
    fun send(c: Channel, m: Message): String? {
        c.missing().firstOrNull()?.let { return "${it.label} is missing." }
        val error = try {
            client.newCall(request(c, m)).execute().use { res ->
                if (res.isSuccessful) null else "HTTP ${res.code}" + (reason(res.body?.string())?.let { ": $it" } ?: "")
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        return error?.let { scrub(c, it) }
    }

    /** The service's own word on a refusal (Telegram's "description", Gotify's "errorDescription"…), short. */
    private fun reason(body: String?): String? {
        val o = runCatching { Json.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull() ?: return null
        val keys = listOf("description", "errorDescription", "error", "message", "errors")
        val v = keys.firstNotNullOfOrNull { k ->
            when (val e = o[k]) {
                is JsonPrimitive -> e.contentOrNull
                is JsonArray -> e.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }
        return v?.take(200)
    }

    private fun scrub(c: Channel, s: String): String = c.secrets().fold(s) { acc, secret -> acc.replace(secret, "…") }

    /** What a run has to tell, and to whom: each enabled, ready channel that wants something of it. */
    fun deliveries(env: Env, job: SyncJob, report: SyncReport): List<Pair<Channel, Message>> {
        val releases = job.source.provider == com.xlollx.songport.providers.NewReleasesProvider.SERVICE
        return runCatching { channels(env) }.getOrDefault(emptyList()).filter { it.enabled && it.missing().isEmpty() }
            .mapNotNull { c -> message(job.name, report, c.events, releases)?.let { c to it } }
    }

    /** After a run: [deliveries] sent in the background. Never throws. */
    fun afterRun(env: Env, job: SyncJob, report: SyncReport) {
        for ((c, m) in deliveries(env, job, report)) {
            runCatching { pool.execute { send(c, m)?.let { env.log("notify", "${c.name} (${c.kind.label}): $it") } } }
                .onFailure { env.log("notify", "${c.name}: ${it.message}") }
        }
    }

    /** "Send test": the result, said for a person. */
    fun test(c: Channel): String? = send(c, TEST)
}
