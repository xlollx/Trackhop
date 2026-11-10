package com.xlollx.songport.shared

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.ServerLocks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/*
 * The third way a group's members reach each other: a mailbox on one member's own Trackhop server
 * (Docker), the server of "Coordinate with my server" (server/…/GroupMailboxes.kt).
 *
 *  - The creator's app makes the mailbox with the user's API token and gets the group's key
 *    ("thg_…"), which goes in the invite. Members join with the invite only: no account on that
 *    server, and the key opens that group's mailbox and nothing else there.
 *  - Each member sends the signed items the mailbox does not hold yet (their card, the newest
 *    curators' list, the events they know) and reads what was stored after the cursor of their last
 *    read. The server keeps everything (it compacts a full mailbox with the members' own merge), so a
 *    member back after months reads on from where they were.
 *  - The server is not trusted with who may write: what it answers goes through the same checks as a
 *    folder's files (shape, signatures, the curators' list, the read bounds). Whoever holds the key
 *    reads the group, and the server's owner sees what the group holds: not encrypted.
 */

/** What this device knows of the group's mailbox, kept between runs. */
@Serializable
data class MailboxState(
    /** Where the next read starts: the cursor the server gave with the last one merged. */
    val cursor: Long = 0,
    /** Fingerprints of the items the mailbox holds (read there, or sent), so only the rest is sent. */
    val there: Set<String> = emptySet(),
    /** Events dated too far ahead to count yet: read once, offered again at each read until they do. */
    val pending: List<SharedEvent> = emptyList(),
)

/** A group's mailbox on a Trackhop server as its transport (see the top of this file). */
class ServerGroupTransport(private val env: Env, private val groupId: String, address: String, private val key: String) : GroupTransport {
    private val base: HttpUrl = ServerLocks.normalize(address).toHttpUrlOrNull() ?: throw IOException("not an address")
    private val host = base.host
    private var state = load(env, groupId)
    /** The server refused the key at the last call: replaced or the mailbox deleted, the invite no longer works. */
    private var refused = false

    private fun items(): HttpUrl.Builder = base.newBuilder().addPathSegments("api/v1/groups").addPathSegment(groupId).addPathSegment("items")

    /** [req] answered, its body as text (at most [MAX_ANSWER] bytes); a refused key is noted for [unreachable]. */
    private fun send(req: Request): String = client.newCall(req.newBuilder().header("Authorization", "Bearer $key").header("User-Agent", "Trackhop").build()).execute().use { res ->
        if (res.code == 401 || res.code == 403) refused = true
        if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
        val source = res.body?.source() ?: throw IOException("no answer")
        if (source.request(MAX_ANSWER + 1)) throw IOException("answer too large")
        source.buffer.readUtf8()
    }

    override fun readAll(first: Set<String>): List<MemberFile> {
        val codec = MemberFile.codec
        var cursor = state.cursor
        val cards = ArrayList<MemberCard>()
        val rosters = ArrayList<Roster>()
        val events = LinkedHashSet<SharedEvent>()
        var read = 0L
        // Page after page while the server has more, within the read bounds: the rest is read next time.
        while (true) {
            val text = send(Request.Builder().url(items().addQueryParameter("since", cursor.toString()).build()).get().build())
            val page = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw IOException("not a mailbox's answer")
            val next = (page["cursor"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= cursor } ?: throw IOException("not a mailbox's answer")
            fun <T> each(field: String, decode: (kotlinx.serialization.json.JsonElement) -> T?) =
                (page[field] as? JsonArray).orEmpty().mapNotNull { runCatching { decode(it) }.getOrNull() }
            cards += each("cards") { codec.decodeFromJsonElement(MemberCard.serializer(), it) }.filter { it.groupId == groupId && it.wellFormed() }
            rosters += each("rosters") { codec.decodeFromJsonElement(Roster.serializer(), it) }.filter { it.groupId == groupId && it.wellFormed() }
            events += each("events") { codec.decodeFromJsonElement(SharedEvent.serializer(), it) }.filter { it.wellFormed() }
            read += text.length
            val more = (page["more"] as? JsonPrimitive)?.booleanOrNull == true
            val moved = next > cursor
            cursor = next
            if (!more || !moved || read >= MAX_READ || events.size > FolderTransport.MAX_UNIQUE || cards.size > MAX_CARDS * 4) break
        }
        val latest = SharedGroups.clock() + SharedGroups.MAX_AHEAD_MS
        val all = LinkedHashSet(state.pending).apply { addAll(events) }
        // Who comes first when there is too much: the members known, those the curators' lists name, those with a card.
        val named = first + rosters.flatMap { r -> r.grants.map { it.member } }
        val carded = cards.mapTo(HashSet()) { it.member }
        val byAuthor = all.groupBy { it.by }
        val authors = byAuthor.keys.sortedWith(compareBy<String> { it !in named }.thenBy { it !in carded }.thenBy { it }).take(FolderTransport.MAX_FILES)
        val newestCards = cards.groupBy { it.member }.values.map { same -> same.maxWith(compareBy<MemberCard> { it.at }.thenBy { it.sig }) }
            .sortedWith(compareBy<MemberCard> { it.member !in named }.thenByDescending { it.at }).take(MAX_CARDS)
        val files = newestCards.map { MemberFile(card = it) } +
            rosters.distinct().sortedByDescending { it.version }.take(MAX_ROSTERS).map { MemberFile(card = nobody(""), roster = it) } +
            authors.flatMap { a -> byAuthor.getValue(a).chunked(FolderTransport.MAX_EVENTS).map { MemberFile(card = nobody(a), events = it) } }
        var there = state.there + fingerprints(cards, rosters, events)
        if (there.size > MAX_FINGERPRINTS) there = emptySet()
        // Kept for the publish that follows: a read that is not merged and saved is read again.
        state = state.copy(cursor = cursor, there = there, pending = all.filter { it.at > latest }.take(MAX_PENDING))
        return files
    }

    override fun publish(member: String, file: MemberFile, written: Int): Int {
        val codec = MemberFile.codec
        val cards = listOf(file.card).filter { fp(it.sig) !in state.there }
        val rosters = listOfNotNull(file.roster).filter { fp(it.sig) !in state.there }
        val events = file.events.filter { fp(it.sig) !in state.there }
        // What was read is kept now that it is merged: the next read goes on from its cursor.
        if (cards.isEmpty() && rosters.isEmpty() && events.isEmpty()) return written.also { save(env, groupId, state) }
        // In calls of at most [MAX_SEND] bytes; the card and the curators' list go with the first.
        val texts = events.map { codec.encodeToString(SharedEvent.serializer(), it) }
        var i = 0
        var firstCall = true
        while (firstCall || i < texts.size) {
            val chunk = StringBuilder()
            val sent = ArrayList<SharedEvent>()
            while (i < texts.size && (chunk.isEmpty() || chunk.length + texts[i].length < MAX_SEND) && sent.size < MAX_SEND_EVENTS) {
                if (chunk.isNotEmpty()) chunk.append(',')
                chunk.append(texts[i]); sent += events[i]; i++
            }
            val body = buildString {
                append("{\"events\":[").append(chunk).append(']')
                if (firstCall) {
                    append(",\"cards\":[").append(cards.joinToString(",") { codec.encodeToString(MemberCard.serializer(), it) }).append(']')
                    append(",\"rosters\":[").append(rosters.joinToString(",") { codec.encodeToString(Roster.serializer(), it) }).append(']')
                }
                append('}')
            }
            send(Request.Builder().url(items().build()).post(body.toRequestBody(JSON)).build())
            state = state.copy(there = state.there + fingerprints(if (firstCall) cards else emptyList(), if (firstCall) rosters else emptyList(), sent))
            firstCall = false
        }
        save(env, groupId, state)
        return written
    }

    override fun unreachable(env: Env, group: SharedGroup): String =
        if (refused) env.text("shared_error_server_key", group.folderName.ifBlank { host })
        else env.text("shared_error_server", group.folderName.ifBlank { host })

    companion object {
        /** A whole read at most, page after page; the rest waits for the next. */
        const val MAX_READ = 32L * 1024 * 1024
        /** One answer at most (the server's pages are about 2 MB). */
        const val MAX_ANSWER = 8L * 1024 * 1024
        /** One call's body at most, and its events (under the server's 4 MB and 20,000). */
        const val MAX_SEND = 3 * 1024 * 1024
        const val MAX_SEND_EVENTS = 5000
        const val MAX_FINGERPRINTS = 100_000
        const val MAX_CARDS = 200
        const val MAX_ROSTERS = 16
        const val MAX_PENDING = 1000
        private val JSON = "application/json".toMediaType()
        private val KEY = Regex("thg_[A-Za-z0-9_-]{43}")

        /** A mailbox key as the server makes them. */
        fun validKey(key: String): Boolean = KEY.matches(key)

        private val client by lazy {
            OkHttpClient.Builder()
                .addInterceptor(com.xlollx.songport.net.Http.hostInterceptor)
                // The key is sent only where the invite points.
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        /** Stands for an author in a file made of their events: not a card (unsigned, it lists nobody). */
        private fun nobody(author: String) = MemberCard(groupId = "", member = author, name = "")

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        private fun fp(sig: String) = sig.take(22)

        private fun fingerprints(cards: Collection<MemberCard>, rosters: Collection<Roster>, events: Collection<SharedEvent>): Set<String> =
            (cards.map { it.sig } + rosters.map { it.sig } + events.map { it.sig }).filter { it.isNotEmpty() }.mapTo(HashSet()) { fp(it) }

        private fun file(env: Env, groupId: String) = File(File(File(env.dataDir, "shared"), "server"), "$groupId.json")

        fun load(env: Env, groupId: String): MailboxState =
            runCatching { codec.decodeFromString(MailboxState.serializer(), file(env, groupId).readText()) }.getOrNull() ?: MailboxState()

        private fun save(env: Env, groupId: String, s: MailboxState) {
            val f = file(env, groupId)
            f.parentFile.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(codec.encodeToString(MailboxState.serializer(), s))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }

        fun forget(env: Env, groupId: String) { file(env, groupId).delete() }

        /**
         * The user's own server ("Coordinate with my server") and its API token, or a plain-words
         * refusal. The token goes only to that server: a group made on another one (the option
         * since pointed elsewhere) is refused, not sent this server's token.
         */
        private fun owner(env: Env, address: String): Pair<HttpUrl, String> {
            val c = ServerLocks.config(env) ?: throw ProviderException(env.text("server_err_unreachable"))
            val url = ServerLocks.normalize(address).toHttpUrlOrNull() ?: throw ProviderException(env.text("server_err_unreachable"))
            if (url != ServerLocks.normalize(c.address).toHttpUrlOrNull()) throw ProviderException(env.text("server_err_off", env.text("server_locks_title")))
            return url to c.token
        }

        private fun ownerCall(env: Env, req: Request.Builder, token: String): JsonObject {
            val res = try {
                client.newCall(req.header("Authorization", "Bearer $token").header("Accept", "application/json").build()).execute()
            } catch (e: IOException) {
                throw ProviderException(env.text("server_err_unreachable"))
            }
            res.use {
                val text = it.body?.source()?.let { s -> s.request(64 * 1024); s.buffer.readUtf8(minOf(s.buffer.size, 64L * 1024)) }.orEmpty()
                val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (it.isSuccessful && o != null) return o
                val said = (o?.get("error") as? JsonPrimitive)?.contentOrNull?.take(300).orEmpty()
                throw ProviderException(when {
                    it.code == 401 -> env.text("server_err_token", env.text("server_locks_title"))
                    it.code == 404 && said.startsWith("Unknown call") -> env.text("server_locks_old")
                    said.isNotEmpty() -> env.text("server_locks_refused", said)
                    else -> env.text("server_locks_refused", "HTTP ${it.code}")
                })
            }
        }

        /**
         * Makes the mailbox of the group [groupId] (its creator [creator], named [name]) on the user's
         * own server at [address], with the API token of "Coordinate with my server"; answers the
         * group's key. The token stays on this device: only the key goes in the invite.
         */
        fun makeMailbox(env: Env, address: String, groupId: String, creator: String, name: String): String {
            val (url, token) = owner(env, address)
            val body = buildJsonObject { put("group", groupId); put("creator", creator); put("name", name) }.toString()
            val made = ownerCall(env, Request.Builder().url(url.newBuilder().addPathSegments("api/v1/groups").build()).post(body.toRequestBody(JSON)), token)
            return (made["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { validKey(it) } ?: throw ProviderException(env.text("server_err_odd"))
        }

        /** A new key for the mailbox of [groupId] on the user's own server: the old one stops working there at once. */
        fun newMailboxKey(env: Env, address: String, groupId: String): String {
            val (url, token) = owner(env, address)
            val made = ownerCall(env, Request.Builder().url(url.newBuilder().addPathSegments("api/v1/groups").addPathSegment(groupId).addPathSegment("key").build())
                .post("".toRequestBody(JSON)), token)
            return (made["key"] as? JsonPrimitive)?.contentOrNull?.takeIf { validKey(it) } ?: throw ProviderException(env.text("server_err_odd"))
        }

        /** Deletes the mailbox again (a group that could not start): best effort. */
        fun deleteMailbox(env: Env, address: String, groupId: String) {
            val (url, token) = owner(env, address)
            ownerCall(env, Request.Builder().url(url.newBuilder().addPathSegments("api/v1/groups").addPathSegment(groupId).build()).delete(), token)
        }
    }
}
