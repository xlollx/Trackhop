package com.xlollx.songport.shared

import com.xlollx.songport.platform.Env
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The second way a group's members reach each other: a topic on an ntfy server, ntfy.sh or the
 * group's own, end-to-end encrypted.
 *
 *  - The invite carries a 32-byte group key. The topic's name and the AES key both come from it
 *    (HMAC-SHA256 with two different labels): the topic cannot be guessed, and the server only ever
 *    sees random-looking text. Whoever holds the invite reads the group; writing still takes a
 *    curator's signature (encryption is not authorization: the log's signatures are checked as with
 *    a folder, so a key holder who is not a curator changes nothing).
 *  - What a member publishes is a packet: their [MemberFile] (a full snapshot of every event they
 *    know, or a delta of what the topic does not hold yet), gzipped, cut into pieces of [PIECE]
 *    bytes, each piece sealed on its own with AES-256-GCM (a random 96-bit nonce per message; the
 *    group, the packet and the piece's place are authenticated with it) and sent as one ntfy message
 *    under the server's 4 KB limit. A piece that does not open is dropped; a packet with a piece
 *    missing is skipped; the same piece twice, or pieces in any order, assemble the same packet.
 *  - ntfy keeps messages for a while only (ntfy.sh: about 12 hours). So every member, followers
 *    too, publishes a full snapshot of the whole signed log when the newest one on the topic is
 *    older than [REFRESH_S], and a delta in between. A member back after longer than the server
 *    keeps messages reads everything still there and catches up completely from any other member's
 *    next full snapshot. The merge is idempotent, so reading a message twice changes nothing.
 *  - Limits: a full snapshot is at most [MAX_CHUNKS] messages (under ntfy.sh's burst limit). A log
 *    larger than that (well over a thousand tracks) is cut like a folder file is, the oldest
 *    removals first: a very large group is better on its own ntfy server or a shared folder.
 */

/** The group key's two uses, kept apart: the topic's name and the cipher's key. */
class NtfyKey(groupKey: ByteArray) {
    init { require(groupKey.size == 32) { "a group key is 32 bytes" } }

    /** 32 characters of [A-Za-z0-9_-]: a valid ntfy topic nobody guesses. */
    val topic: String = B64.encode(hmac(groupKey, "trackhop-ntfy-topic-1").copyOf(24))
    private val aes = SecretKeySpec(hmac(groupKey, "trackhop-ntfy-aes-1"), "AES")

    /** [plain] sealed: nonce (12 bytes), then the ciphertext and its 16-byte tag. */
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return nonce + c.doFinal(plain)
    }

    /** What [sealed] holds, or null when it was not sealed with this key and [aad], or was changed since. */
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size < NONCE + TAG_BITS / 8) return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE))
            c.updateAAD(aad)
            c.doFinal(sealed, NONCE, sealed.size - NONCE)
        }.getOrNull()
    }

    companion object {
        const val NONCE = 12
        const val TAG_BITS = 128
        private val random = SecureRandom()

        fun of(text: String): NtfyKey? = B64.decode(text)?.takeIf { it.size == 32 }?.let { NtfyKey(it) }

        private fun hmac(key: ByteArray, label: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(label.toByteArray(Charsets.UTF_8))
    }
}

/** What one member publishes at once: their file, whole ([full]) or only what the topic lacks, and the other members' cards they relay. */
@Serializable
data class NtfyPacket(
    val v: Int = 1,
    val full: Boolean = false,
    /** A full snapshot cut to fit: it does not say the topic lacks what it leaves out. */
    val partial: Boolean = false,
    val file: MemberFile,
    /** Other members' signed cards, so a member who stays quiet is still listed for those who join later. */
    val cards: List<MemberCard> = emptyList(),
)

/** A message as the server lists it. */
data class NtfyMessage(val id: String, val time: Long, val text: String)

/** Packets as ntfy messages and back. */
object NtfyWire {
    /** Plaintext bytes per message: sealed and in base64 it stays near 3 KB, under ntfy's 4 KB message limit. */
    const val PIECE = 2200
    /** At most this many messages in one packet. */
    const val MAX_CHUNKS = 48
    /** A delta larger than this many messages goes as a full snapshot instead. */
    const val DELTA_CHUNKS = 8
    /** A packet larger than this once unzipped is not read. */
    const val MAX_PACKET_BYTES = 8 * 1024 * 1024
    /** A message longer than this is not one of ours. */
    const val MAX_MESSAGE = 4096
    private const val TAG = "th1"
    private val SNAP = Regex("[A-Za-z0-9_-]{12}")

    val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun aad(groupId: String, snap: String, i: Int, n: Int) = canon("trackhop-ntfy-1", groupId, snap, i, n)

    fun newSnap(): String = B64.encode(ByteArray(9).also { SecureRandom().nextBytes(it) })

    fun pack(p: NtfyPacket): ByteArray = gzip(codec.encodeToString(NtfyPacket.serializer(), p).toByteArray(Charsets.UTF_8))

    fun unpack(b: ByteArray): NtfyPacket? = gunzip(b, MAX_PACKET_BYTES)?.let { raw ->
        runCatching { codec.decodeFromString(NtfyPacket.serializer(), raw.toString(Charsets.UTF_8)) }.getOrNull()
    }?.takeIf { it.v >= 1 && it.file.format >= 1 }

    /** [packed] as the texts of its messages, in order. */
    fun messages(key: NtfyKey, groupId: String, snap: String, packed: ByteArray): List<String> {
        val pieces = packed.toList().chunked(PIECE).map { it.toByteArray() }.ifEmpty { listOf(ByteArray(0)) }
        require(pieces.size <= MAX_CHUNKS) { "packet too large" }
        return pieces.mapIndexed { i, piece -> "$TAG.$snap.$i.${pieces.size}." + B64.encode(key.seal(piece, aad(groupId, snap, i, pieces.size))) }
    }

    /** A packet as it was assembled: the server time of its newest piece. */
    data class Assembled(val snap: String, val time: Long, val packet: NtfyPacket)

    /**
     * The packets [messages] hold whole, oldest first. A message that is not ours, does not open
     * with [key], or contradicts another piece's count is ignored; a packet missing a piece too.
     */
    fun assemble(key: NtfyKey, groupId: String, messages: List<NtfyMessage>): List<Assembled> {
        class Parts(val n: Int) { val pieces = arrayOfNulls<ByteArray>(n); var time = 0L }
        val bySnap = LinkedHashMap<String, Parts>()
        for (m in messages) {
            if (m.text.length > MAX_MESSAGE) continue
            val f = m.text.split('.')
            if (f.size != 5 || f[0] != TAG || !SNAP.matches(f[1])) continue
            val n = f[3].toIntOrNull()?.takeIf { it in 1..MAX_CHUNKS } ?: continue
            val i = f[2].toIntOrNull()?.takeIf { it in 0 until n } ?: continue
            val parts = bySnap.getOrPut(f[1]) { Parts(n) }
            if (parts.n != n || parts.pieces[i] != null) continue
            val plain = B64.decode(f[4])?.let { key.open(it, aad(groupId, f[1], i, n)) } ?: continue
            parts.pieces[i] = plain
            parts.time = maxOf(parts.time, m.time)
        }
        return bySnap.mapNotNull { (snap, parts) ->
            if (parts.pieces.any { it == null }) return@mapNotNull null
            val out = ByteArrayOutputStream()
            parts.pieces.forEach { out.write(it!!) }
            unpack(out.toByteArray())?.takeIf { it.file.card.groupId == groupId }?.let { Assembled(snap, parts.time, it) }
        }.sortedWith(compareBy<Assembled> { it.time }.thenBy { it.snap })
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun gunzip(b: ByteArray, max: Int): ByteArray? = runCatching {
        GZIPInputStream(b.inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            out.toByteArray()
        }
    }.getOrNull()
}

/** An ntfy server's two calls this transport makes. */
class NtfyServer(address: String) {
    private val base: HttpUrl = address.trim().trimEnd('/').toHttpUrlOrNull() ?: throw IOException("not an address")

    /** Publishes [text] to [topic]; answers the server's time for it (seconds), when it says. */
    fun publish(topic: String, text: String): Long? {
        val req = Request.Builder().url(base.newBuilder().addPathSegment(topic).build()).header("User-Agent", "Trackhop")
            // Never handed on to a push service: the members poll.
            .header("X-Firebase", "no")
            .post(text.toRequestBody(TEXT)).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val o = runCatching { Json.parseToJsonElement(res.body?.string().orEmpty()).jsonObject }.getOrNull()
            return o?.get("time")?.jsonPrimitive?.longOrNull
        }
    }

    /** The messages [topic] holds since [since] (seconds, or "all"), at most [MAX_POLL_BYTES] of them. */
    fun poll(topic: String, since: String): List<NtfyMessage> {
        val url = base.newBuilder().addPathSegment(topic).addPathSegment("json").addQueryParameter("poll", "1").addQueryParameter("since", since).build()
        client.newCall(Request.Builder().url(url).header("User-Agent", "Trackhop").get().build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val source = res.body?.source() ?: return emptyList()
            val out = ArrayList<NtfyMessage>()
            var read = 0L
            // A line longer than [MAX_LINE] is skipped without being held whole.
            var skipping = false
            while (read < MAX_POLL_BYTES && !source.exhausted()) {
                val nl = source.indexOf('\n'.code.toByte(), 0, MAX_LINE)
                if (nl < 0 && source.request(MAX_LINE)) { source.skip(MAX_LINE); read += MAX_LINE; skipping = true; continue }
                val line = if (nl >= 0) source.readUtf8(nl).also { source.skip(1) } else source.readUtf8()
                read += line.length + 1
                if (skipping) { skipping = false; continue }
                val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (o["event"]?.jsonPrimitive?.contentOrNull != "message") continue
                val text = o["message"]?.jsonPrimitive?.contentOrNull ?: continue
                out += NtfyMessage(o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["time"]?.jsonPrimitive?.longOrNull ?: 0, text)
            }
            return out
        }
    }

    companion object {
        const val MAX_POLL_BYTES = 6L * 1024 * 1024
        /** Longer than this, a line holds no message of ours (they stay near 3 KB). */
        const val MAX_LINE = 16L * 1024
        private val TEXT = "text/plain; charset=utf-8".toMediaType()

        private val client by lazy {
            OkHttpClient.Builder()
                .addInterceptor(com.xlollx.songport.net.Http.hostInterceptor)
                // The topic is the group's secret's offspring: never sent where the user did not point it.
                .followRedirects(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
        }

        /** An address the app accepts for a server: http(s), with a host. */
        fun valid(address: String): Boolean {
            val a = address.trim()
            return a.length <= 200 && (a.startsWith("https://") || a.startsWith("http://")) && a.trimEnd('/').toHttpUrlOrNull() != null
        }

        /** The server's name as members see it ("ntfy.sh"). */
        fun hostOf(address: String): String = address.trim().trimEnd('/').toHttpUrlOrNull()?.host ?: address
    }
}

/**
 * What this device knows of the topic, kept between runs: where the next read starts, the newest
 * full snapshot on the topic, what the topic holds since then (so a delta carries only the rest),
 * and the members' cards to relay.
 */
@Serializable
data class NtfyState(
    /** The server time (s) of the newest message read; 0: read everything the server has. */
    val since: Long = 0,
    /** The server time (s) of the newest full snapshot known on the topic, and its packet. */
    val fullAt: Long = 0,
    val fullSnap: String = "",
    /** Fingerprints of the events, curators' lists and cards the topic holds since that snapshot. */
    val onTopic: Set<String> = emptySet(),
    /** The newest signed card of each member seen. */
    val cards: Map<String, MemberCard> = emptyMap(),
)

/** An encrypted ntfy topic as a group's transport (see the top of this file). */
class NtfyTransport(private val env: Env, private val groupId: String, private val address: String, groupKey: String) : GroupTransport {
    private val key = NtfyKey.of(groupKey) ?: throw IOException("not a group key")
    private val server by lazy { NtfyServer(address) }
    private var state = load(env, groupId)

    override fun readAll(): List<MemberFile> {
        val since = if (state.since <= 0) "all" else (state.since - OVERLAP_S).coerceAtLeast(1).toString()
        val messages = server.poll(key.topic, since)
        val packets = NtfyWire.assemble(key, groupId, messages)
        var st = state.copy(since = maxOf(state.since, messages.maxOfOrNull { it.time } ?: 0))
        val cards = HashMap(st.cards)
        for (a in packets) {
            val fps = fingerprints(a.packet.file, a.packet.cards)
            val newer = a.time > st.fullAt || a.time == st.fullAt && a.snap > st.fullSnap
            st = if (a.packet.full && !a.packet.partial && newer) st.copy(fullAt = a.time, fullSnap = a.snap, onTopic = fps)
            else st.copy(onTopic = st.onTopic + fps, fullAt = if (a.packet.full && newer) a.time else st.fullAt, fullSnap = if (a.packet.full && newer) a.snap else st.fullSnap)
            for (c in a.packet.cards + a.packet.file.card) {
                if (c.groupId != groupId || !SigCache.verified(c)) continue
                val old = cards[c.member]
                if (old == null || c.at > old.at || c.at == old.at && c.sig > old.sig) cards[c.member] = c
            }
        }
        if (st.onTopic.size > MAX_FINGERPRINTS) st = st.copy(onTopic = emptySet(), fullAt = 0)
        // Kept for the publish that follows: a read that is not merged and saved is read again.
        state = st.copy(cards = cards.values.sortedByDescending { it.at }.take(MAX_CARDS).associateBy { it.member })
        return packets.flatMap { a -> listOf(a.packet.file) + a.packet.cards.filter { it.groupId == groupId }.map { MemberFile(card = it) } }
    }

    override fun publish(member: String, file: MemberFile, written: Int): Int {
        val now = clock() / 1000
        val missing = file.events.filter { fp(it.sig) !in state.onTopic }
        val relayed = state.cards.values.filter { it.member != member }.sortedByDescending { it.at }.take(RELAYED_CARDS)
        val missingCards = relayed.filter { fp(it.sig) !in state.onTopic }
        val rest = listOfNotNull(file.card.sig, file.roster?.sig).any { fp(it) !in state.onTopic } || missingCards.isNotEmpty()
        val delta = if (now - state.fullAt >= REFRESH_S || (missing.isEmpty() && !rest)) null
        else NtfyWire.pack(NtfyPacket(file = file.copy(events = missing), cards = missingCards)).takeIf { it.size <= NtfyWire.DELTA_CHUNKS * NtfyWire.PIECE }
        state = when {
            delta != null -> {
                send(delta)
                state.copy(onTopic = state.onTopic + fingerprints(file.copy(events = missing), missingCards))
            }
            now - state.fullAt >= REFRESH_S || missing.isNotEmpty() || rest -> {
                val packed = fit(file, relayed)
                val snap = NtfyWire.newSnap()
                val time = send(packed, snap)
                // Everything this member knows counts as on the topic, what a cut left out too: it is not sent again in deltas.
                // Where the next read starts does not move: what others sent since the last read is still to read.
                state.copy(fullAt = time, fullSnap = snap, onTopic = fingerprints(file, relayed))
            }
            else -> state
        }
        save(env, groupId, state)
        return written
    }

    override fun unreachable(env: Env, group: SharedGroup): String = env.text("shared_error_server", group.folderName.ifBlank { NtfyServer.hostOf(address) })

    /** [packed] as messages on the topic; answers the server time of the last. */
    private fun send(packed: ByteArray, snap: String = NtfyWire.newSnap()): Long {
        var time = clock() / 1000
        for (m in NtfyWire.messages(key, groupId, snap, packed)) server.publish(key.topic, m)?.let { time = it }
        return time
    }

    /** The full snapshot: everything, or as much as [NtfyWire.MAX_CHUNKS] messages hold (the oldest removals left out first). */
    private fun fit(file: MemberFile, cards: List<MemberCard>): ByteArray {
        val budget = NtfyWire.MAX_CHUNKS * NtfyWire.PIECE
        fun pack(events: List<SharedEvent>, partial: Boolean) =
            NtfyWire.pack(NtfyPacket(full = true, partial = partial, file = file.copy(events = events), cards = cards))
        pack(file.events, false).let { if (it.size <= budget) return it }
        val ordered = file.events.sortedWith(compareBy<SharedEvent> { if (it.adds) 0 else 1 }.thenByDescending { it.at }.thenBy { it.id })
        var lo = 0
        var hi = ordered.size
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pack(ordered.take(mid), true).size <= budget) lo = mid else hi = mid - 1
        }
        return pack(ordered.take(lo), true)
    }

    companion object {
        /** A full snapshot older than this is published again: well inside ntfy.sh's 12 hours, with a run every 6. */
        const val REFRESH_S = 5L * 3600
        /** Each read starts this much before the newest message seen: a packet sent while the last read ran is read whole. */
        const val OVERLAP_S = 15L * 60
        const val MAX_FINGERPRINTS = 100_000
        const val MAX_CARDS = 200
        const val RELAYED_CARDS = 50

        /** The clock (ms); the group's, so tests move both. */
        internal val clock: () -> Long get() = SharedGroups.clock

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        private fun fp(sig: String) = sig.take(22)

        private fun fingerprints(file: MemberFile, cards: List<MemberCard>): Set<String> =
            (file.events.map { it.sig } + listOfNotNull(file.card.sig, file.roster?.sig) + cards.map { it.sig }).filter { it.isNotEmpty() }.mapTo(HashSet()) { fp(it) }

        private fun file(env: Env, groupId: String) = File(File(File(env.dataDir, "shared"), "ntfy"), "$groupId.json")

        fun load(env: Env, groupId: String): NtfyState =
            runCatching { codec.decodeFromString(NtfyState.serializer(), file(env, groupId).readText()) }.getOrNull() ?: NtfyState()

        private fun save(env: Env, groupId: String, s: NtfyState) {
            val f = file(env, groupId)
            f.parentFile.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(codec.encodeToString(NtfyState.serializer(), s))
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }

        fun forget(env: Env, groupId: String) { file(env, groupId).delete() }
    }
}
