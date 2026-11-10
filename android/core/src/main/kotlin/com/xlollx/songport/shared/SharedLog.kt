package com.xlollx.songport.shared

import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Matcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/*
 * A shared playlist is a log of signed events, one "+" or "−" per track and per action, written by
 * the group's curators. Every member keeps the whole log, merged from every copy it reads: the same
 * events in any order give the same playlist (a CRDT: a last-writer-wins set with tombstones).
 *
 *  - A track's state is the most recent action on it: an addition after a removal brings it back,
 *    a removal after an addition takes it out. A removal stays in the log (a tombstone), so an old
 *    copy of the log that still lists the track does not bring it back.
 *  - Only a curator's events count. Each event is signed with its author's key (Ed25519); who the
 *    curators are is a list signed by the group's creator, whose key every member got with the
 *    invite. An event that is not signed, signed by someone else than its author, or signed by a
 *    member the creator's list does not make a curator, is ignored by everyone.
 *  - A member's service that cannot find a track never removes it: removing is an event, and only
 *    a curator's own removal writes one. What a member cannot find goes to that member's review.
 */

/** Keys and signatures in text: base64url without padding (a key is 43 characters). */
object B64 {
    fun encode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun decode(s: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
}

/**
 * The bytes a signature covers: each field with its length, so no two field lists give the same
 * bytes, as long as the fields are [plain] (no control characters, which the lists use as
 * separators, and no lone surrogates, which UTF-8 cannot write and would turn into "?").
 */
internal fun canon(vararg parts: Any?): ByteArray =
    buildString { for (p in parts) { val s = p?.toString() ?: "\u0000"; append(s.length).append(':').append(s).append(';') } }.toByteArray(Charsets.UTF_8)

/** [s] is at most [max] characters, none a control character or half of a surrogate pair: what a signed field may hold. */
internal fun plain(s: String, max: Int): Boolean {
    if (s.length > max) return false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c < ' ' || c == '\u007f' -> return false
            Character.isHighSurrogate(c) -> { if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false; i++ }
            Character.isLowSurrogate(c) -> return false
        }
        i++
    }
    return true
}

/** [s] made [plain]: what a service's text becomes before it is signed. */
internal fun plainOf(s: String, max: Int): String {
    val out = StringBuilder(minOf(s.length, max))
    var i = 0
    while (i < s.length && out.length < max) {
        val c = s[i]
        when {
            c < ' ' || c == '\u007f' -> out.append(' ')
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                if (out.length + 2 > max) break
                out.append(c).append(s[i + 1]); i++
            }
            Character.isSurrogate(c) -> {}
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** A public key as text: 43 base64url characters, a usable Ed25519 point. */
internal val KEY_TEXT = Regex("[A-Za-z0-9_-]{43}")
private val SIG_TEXT = Regex("[A-Za-z0-9_-]{86}")

/** A member's key pair for one group: [seed] is the private key, [id] the public key as text. */
class Identity(val seed: ByteArray) {
    val publicKey: ByteArray = Ed25519.publicKey(seed)
    val id: String = B64.encode(publicKey)
    fun sign(message: ByteArray): String = B64.encode(Ed25519.sign(seed, message))
    companion object {
        fun create(): Identity = Identity(Ed25519.newPrivateKey())
        fun of(seedText: String): Identity? = B64.decode(seedText)?.takeIf { it.size == 32 }?.let { Identity(it) }
    }
}

/** A signature check of [sig] by [author] (a public key as text) over [message]. */
internal fun signedBy(author: String, message: ByteArray, sig: String): Boolean {
    val key = B64.decode(author)?.takeIf { it.size == 32 } ?: return false
    val s = B64.decode(sig)?.takeIf { it.size == 64 } ?: return false
    return Ed25519.verify(key, message, s)
}

/**
 * One action on the shared playlist: [op] "+" adds the track [key], "-" removes it. [by] is the
 * author's public key, [seq] the author's own counter (1, 2, 3…), [at] the author's clock (epoch
 * ms). The track is described by name and ISRC, never by a service's id: every member looks it up
 * on their own service.
 */
@Serializable
data class SharedEvent(
    val by: String,
    val seq: Long,
    val at: Long,
    val op: String,
    val key: String,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val isrc: String? = null,
    val durationMs: Long = 0,
    val sig: String = "",
) {
    /**
     * What any Trackhop writes, and nothing else: a key and a signature of the right shape, a known
     * action, a positive number, and short [plain] text (a crafted file's megabyte titles or
     * separators hidden in a name are refused before anything else looks at the event).
     */
    fun wellFormed(): Boolean =
        (op == ADD || op == REMOVE) && seq > 0 && KEY_TEXT.matches(by) && SIG_TEXT.matches(sig) && key.isNotBlank() && plain(key, MAX_KEY) &&
            plain(title, MAX_TITLE) && artists.size <= MAX_ARTISTS && artists.all { plain(it, MAX_ARTIST) } && plain(album, MAX_TITLE) &&
            (isrc == null || (isrc.isNotEmpty() && plain(isrc, MAX_ISRC)))

    val adds: Boolean get() = op == ADD
    /** The event's identity: one author never writes two events with one number. */
    val id: String get() = "$by#$seq"

    fun signedBytes(groupId: String): ByteArray =
        canon("trackhop-event-1", groupId, by, seq, at, op, key, title, artists.joinToString("\u001f"), album, isrc, durationMs)

    fun signed(groupId: String, me: Identity): SharedEvent = copy(by = me.id).let { it.copy(sig = me.sign(it.signedBytes(groupId))) }

    fun verifies(groupId: String): Boolean = signedBy(by, signedBytes(groupId), sig)

    /** The track as the group lists it: its id is the group's [key]. */
    fun track(addedAt: Long = at): Track = Track(id = key, title = title, artists = artists, album = album, durationMs = durationMs, isrc = isrc, addedAt = addedAt)

    /** "+ Artist – Title" or "− Artist – Title", for the log. */
    fun line(): String = (if (adds) "+ " else "− ") + track()

    companion object {
        const val ADD = "+"
        const val REMOVE = "-"
        const val MAX_TITLE = 300
        const val MAX_ARTIST = 200
        const val MAX_ARTISTS = 10
        const val MAX_ISRC = 40
        const val MAX_KEY = 1000
        /** Markers that do not make another recording: the same song, whichever the service lists. */
        private val SAME_RECORDING = setOf("remaster", "mono", "stereo")

        /**
         * A track's key in the group: its ISRC when the service gave one (the same recording on every
         * service), else its artist and title made plain, so two members adding the same song meet.
         * The title's version markers ("Live", "Acoustic", "Remix"...) stay in the key: plain titles
         * drop them, and a live take removed by a curator must not take the studio one out of
         * everyone else's playlist.
         */
        fun keyOf(t: Track): String {
            t.isrcNorm?.let { return plainOf("isrc:$it", MAX_KEY) }
            val markers = (Matcher.versionMarkers(t.title) - SAME_RECORDING).sorted()
            val key = "t:" + Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()) + "|" + Matcher.normalizeTitle(t.title) +
                (if (markers.isEmpty()) "" else "|" + markers.joinToString("+"))
            return plainOf(key, MAX_KEY)
        }

        fun of(op: String, t: Track, by: String, seq: Long, at: Long): SharedEvent =
            SharedEvent(by = by, seq = seq, at = at, op = op, key = keyOf(t), title = plainOf(t.title, MAX_TITLE),
                artists = t.artists.take(MAX_ARTISTS).map { plainOf(it, MAX_ARTIST) }, album = plainOf(t.album, MAX_TITLE),
                isrc = t.isrcNorm?.let { plainOf(it, MAX_ISRC) }?.takeIf { it.isNotEmpty() }, durationMs = t.durationMs)
    }
}

/**
 * One member's right to write: events of [member] numbered above [afterSeq] and, once the creator
 * took the right back, up to [untilSeq] count. Taking it back keeps what the member wrote until
 * then (the creator saw it), so a curator who becomes a follower does not take their tracks away.
 *
 * [kept] pins those events: the start of each one's signature, as the creator had them. Without
 * it, a former curator could sign new events under their old numbers (the key is still theirs)
 * and rewrite what they did, removals included, after losing the right.
 */
@Serializable
data class Grant(val member: String, val afterSeq: Long = 0, val untilSeq: Long? = null, val kept: List<String>? = null) {
    val current: Boolean get() = untilSeq == null
    private val keptSet: Set<String>? by lazy { kept?.toHashSet() }
    fun covers(e: SharedEvent): Boolean = e.by == member && e.seq > afterSeq && (untilSeq == null || e.seq <= untilSeq) &&
        keptSet.let { it == null || e.sig.take(KEPT_CHARS) in it }

    /** Shape only: a key, numbers in order, the pinned signatures' starts. */
    fun wellFormed(): Boolean = KEY_TEXT.matches(member) && afterSeq >= 0 && (untilSeq == null || untilSeq >= afterSeq) &&
        (kept == null || kept.all { it.length == KEPT_CHARS && SIG_START.matches(it) })

    companion object {
        /** 96 bits of each kept signature: no one finds another signature with the same start. */
        const val KEPT_CHARS = 16
        private val SIG_START = Regex("[A-Za-z0-9_-]{16}")
    }
}

/** Who the curators are, signed by the group's creator; the highest [version] wins. */
@Serializable
data class Roster(val groupId: String, val version: Long, val at: Long, val grants: List<Grant>, val sig: String = "") {
    val curators: Set<String> get() = grants.filter { it.current }.mapTo(LinkedHashSet()) { it.member }
    fun allows(e: SharedEvent): Boolean = grants.any { it.covers(e) }

    fun signedBytes(): ByteArray =
        canon("trackhop-roster-1", groupId, version, at, grants.joinToString("\u001e") { g ->
            "${g.member},${g.afterSeq},${g.untilSeq ?: ""}" + (g.kept?.let { "," + it.joinToString("|") } ?: "")
        })

    fun wellFormed(): Boolean = version > 0 && grants.size <= MAX_GRANTS && grants.all { it.wellFormed() } && SIG_TEXT.matches(sig)

    fun signed(creator: Identity): Roster = copy(sig = creator.sign(signedBytes()))
    fun verifies(creator: String): Boolean = signedBy(creator, signedBytes(), sig)

    companion object {
        /** The list every group starts from, signed or not: the creator alone curates. */
        fun first(groupId: String, creator: String): Roster = Roster(groupId, 0, 0, listOf(Grant(creator)))
        const val MAX_GRANTS = 1000
    }
}

/** What a member says of themselves in their file, signed with their own key. */
@Serializable
data class MemberCard(
    val groupId: String,
    val member: String,
    val name: String,
    /** The service they listen on, as its name (for the members list; never an account). */
    val service: String = "",
    val at: Long = 0,
    val left: Boolean = false,
    val sig: String = "",
) {
    fun signedBytes(): ByteArray = canon("trackhop-member-1", groupId, member, name, service, at, left)
    fun wellFormed(): Boolean = KEY_TEXT.matches(member) && SIG_TEXT.matches(sig) && plain(name, MAX_NAME) && plain(service, MAX_NAME)
    fun signed(me: Identity): MemberCard = copy(member = me.id).let { it.copy(sig = me.sign(it.signedBytes())) }
    fun verifies(): Boolean = signedBy(member, signedBytes(), sig)

    companion object {
        const val MAX_NAME = 80
    }
}

/** One member's file in the shared folder: who they are, the newest curators' list they know, and every event they know. */
@Serializable
data class MemberFile(
    val format: Int = FORMAT,
    val card: MemberCard,
    val roster: Roster? = null,
    val events: List<SharedEvent> = emptyList(),
) {
    companion object {
        const val FORMAT = 1
        /** Small and stable: no defaults written, unknown fields (a newer Trackhop's) ignored. */
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun parse(text: String): MemberFile? = runCatching { codec.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.format >= 1 }
        fun write(f: MemberFile): String = codec.encodeToString(serializer(), f)
    }
}

/**
 * The merge, the same on every member: pure, order-independent and idempotent. [creator] is the
 * key the invite carried; nothing else is trusted.
 */
class GroupLog(val groupId: String, val creator: String) {

    /** The newest curators' list among [candidates] that the creator really signed for this group. */
    fun roster(candidates: Iterable<Roster?>): Roster {
        val valid = candidates.filterNotNull().distinct().filter { it.groupId == groupId && it.wellFormed() && it.verifies(creator) }
        return valid.maxWithOrNull(compareBy<Roster> { it.version }.thenByDescending { it.sig }) ?: Roster.first(groupId, creator)
    }

    /**
     * Every event of [incoming] that is signed by its author, for this group, by a member [roster]
     * lets write, merged with [known] (already checked), compacted: per track and author only the
     * author's latest action is kept, which is all the merge ever looks at. An event dated after
     * [latest] (a clock, or a member, far in the future) waits: it would otherwise win over every
     * later action, for good.
     */
    fun merge(roster: Roster, known: Collection<SharedEvent>, incoming: Sequence<SharedEvent>, latest: Long = Long.MAX_VALUE / 2): List<SharedEvent> {
        val knownIds = known.associateBy { it.id }
        val byId = HashMap<String, SharedEvent>()
        fun offer(e: SharedEvent, checked: Boolean) {
            if (e.at > latest || !e.wellFormed() || !roster.allows(e)) return
            if (!checked && !SigCache.verified(groupId, e)) return
            val other = byId[e.id]
            // One number, two events: only a misbehaving author does that. The same one wins everywhere.
            byId[e.id] = if (other == null || e.sig < other.sig) e else other
        }
        known.forEach { offer(it, true) }
        incoming.forEach { e -> offer(e, knownIds[e.id] == e) }
        return compact(byId.values)
    }

    /**
     * The events of one file that would count, signatures checked, or null when the file holds more
     * than [MAX_BAD] that claim a curator and fail the check: a file someone filled to make every
     * member spend minutes checking signatures is left out whole (every member's file carries the
     * whole log, so nothing real is lost with it).
     */
    fun checked(roster: Roster, events: List<SharedEvent>, latest: Long = Long.MAX_VALUE / 2): List<SharedEvent>? {
        var bad = 0
        val out = ArrayList<SharedEvent>()
        for (e in events) {
            if (e.at > latest || !e.wellFormed() || !roster.allows(e)) continue
            if (SigCache.verified(groupId, e)) out += e else if (++bad > MAX_BAD) return null
        }
        return out
    }

    /** The tracks the playlist holds: the keys whose winning event is an addition, oldest first. */
    fun live(events: Collection<SharedEvent>): List<SharedEvent> =
        winners(events).filter { it.adds }.sortedWith(compareBy<SharedEvent> { it.at }.thenBy { it.key })

    /** Keys removed for good (their winning event is a removal): what nobody's old copy brings back. */
    fun tombstones(events: Collection<SharedEvent>): List<SharedEvent> = winners(events).filter { !it.adds }

    /**
     * Per key, the event that decides it: the most recent by its author's clock; at the same
     * instant the author and the number decide, so every member picks the same one.
     */
    fun winners(events: Collection<SharedEvent>): List<SharedEvent> =
        compact(events).groupBy { it.key }.values.map { it.maxWith(ORDER) }

    companion object {
        const val MAX_BAD = 64
        val ORDER: Comparator<SharedEvent> = compareBy<SharedEvent> { it.at }.thenBy { it.by }.thenBy { it.seq }

        /** Per (key, author) the author's latest action, by their own counter. */
        fun compact(events: Collection<SharedEvent>): List<SharedEvent> =
            events.groupBy { it.key to it.by }.values.map { same -> same.maxBy { it.seq } }
    }
}

/**
 * Signatures already checked in this process: a run reads every member's file, and most events are
 * the same as last time. Bounded. An entry is the signature with a SHA-256 of the exact bytes it was
 * checked over: any change to the event is checked again (a hash code would not do: anyone can make
 * a changed event with the hash code of a valid one, and borrow its signature).
 */
internal object SigCache {
    private const val MAX = 50_000
    private val ok = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX
    })

    fun verified(groupId: String, e: SharedEvent): Boolean {
        val bytes = e.signedBytes(groupId)
        return check(e.sig + "\u0000" + digest(bytes)) { signedBy(e.by, bytes, e.sig) }
    }

    fun verified(card: MemberCard): Boolean {
        val bytes = card.signedBytes()
        return check(card.sig + "\u0000" + digest(bytes)) { signedBy(card.member, bytes, card.sig) }
    }

    private fun digest(b: ByteArray): String = B64.encode(java.security.MessageDigest.getInstance("SHA-256").digest(b))

    private fun check(k: String, verify: () -> Boolean): Boolean {
        ok[k]?.let { return it }
        return verify().also { ok[k] = it }
    }

    fun clear() = ok.clear()
}
