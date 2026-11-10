package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.shared.GroupLog
import com.xlollx.songport.shared.MemberCard
import com.xlollx.songport.shared.MemberFile
import com.xlollx.songport.shared.Roster
import com.xlollx.songport.shared.SharedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared playlists' third way to meet (docs/guide/shared-playlists.md): a mailbox on this server
 * for one group, which the members' Trackhops write their signed items to (events, member cards,
 * the curators' list) and read back from, since a cursor.
 *
 *  - The owner of this server makes a mailbox with their API token (POST /api/v1/groups) and gets
 *    the group's key ("thg_…"), shown once; only its SHA-256 is kept. The invite carries it. That
 *    key opens GET and POST /api/v1/groups/{group}/items of its own group and nothing else: no
 *    syncs, no settings, no other group. A new key (POST …/{group}/key) ends the old one; deleting
 *    the mailbox ends both. With the API turned off, mailboxes are closed too.
 *  - The server does not decide who may write to the group: the members' Trackhops check every
 *    signature and the curators' list, as with a shared folder. It only takes what has the shape
 *    of a group's item (the same checks as the app's) and is signed by whoever it names, each once,
 *    within the caps below.
 *  - Nothing is ever dropped by age. A mailbox reaching its cap is compacted first, with the same
 *    merge the members run (the creator's key, given when it was made, says whose curators' list
 *    counts): what no member would count goes, and so do the actions a later one replaced.
 */
internal object GroupMailboxes {
    /** The start of a group's key, so it is never mistaken for an API token. */
    const val PREFIX = "thg_"
    /** One request's body at most. */
    const val MAX_BODY = 4 * 1024 * 1024
    /** Items in one request at most: the app sends a member's log 5,000 events a call (ServerGroupTransport.MAX_SEND_EVENTS), with their card and the curators' list. */
    const val MAX_POST_ITEMS = 5_000 + 300
    /** One item's JSON at most. */
    const val MAX_ITEM = 8 * 1024
    /**
     * A request's JSON before it is read: nested this deep at most, and with at most this many
     * values an item, so that 4 MB of tiny values or brackets cannot become hundreds of MB in memory.
     */
    const val MAX_DEPTH = 8
    const val MAX_VALUES_PER_ITEM = 32
    /** A mailbox at most, in items and bytes, compacted first when it gets there (a busy group of members with thousands of tracks fits). */
    const val MAX_ITEMS = 100_000
    const val MAX_BYTES = 16L * 1024 * 1024
    /** [MAX_ITEMS], lower in tests. */
    @Volatile internal var maxItems = MAX_ITEMS
    /** All the mailboxes of one person's Trackhop together, on disk at most. */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    @Volatile internal var maxTotalBytes = MAX_TOTAL_BYTES
    /** New items checked per mailbox and minute at most (each a signature to check): a member's first sync of a whole log fits. */
    const val NEW_PER_MINUTE = 20_000
    @Volatile internal var newPerMinute = NEW_PER_MINUTE
    /** Mailboxes whose items stay in memory between calls; the others are read again from disk when asked. */
    const val MAX_LOADED = 4
    /** A full mailbox is compacted at most this often, unless it grew by a tenth since: each compaction reads it all. */
    const val COMPACT_EVERY_MS = 5 * 60_000L
    /** Compactions so far (tests). */
    @Volatile internal var compactions = 0
    /** One answer of GET …/items at most: the rest follows with the cursor it gives. */
    const val PAGE_BYTES = 2 * 1024 * 1024
    /** Mailboxes on one person's Trackhop at most. */
    const val MAX_MAILBOXES = 200
    /** Calls per mailbox and minute: a member syncs every few hours, a few calls each time. */
    const val WRITES_PER_MINUTE = 30
    const val READS_PER_MINUTE = 120
    /** Members' cards a compaction keeps at most (SharedGroups.MAX_MEMBERS). */
    const val MAX_CARDS = 256

    private val GROUP_ID = Regex("[0-9a-f]{16}")
    private val KEY = Regex("[A-Za-z0-9_-]{43}")
    private val CAPABILITY = Regex(Regex.escape(PREFIX) + "[A-Za-z0-9_-]{43}")
    private val codec = MemberFile.codec

    /** One stored item: its number (the cursor), its kind (e, c, r) and its JSON as received. */
    private class Item(val seq: Long, val kind: Char, val text: String, val hash: String)

    private class Box(val dir: File) {
        var id = ""
        var name = ""
        var creator = ""
        /** The SHA-256 of the key: read without the lock by [opens], so a new key counts at once. */
        @Volatile var capability = ""
        /** Deleted: a call that found it before waits for the lock and then finds nothing. */
        @Volatile var deleted = false
        var created = 0L
        var lastWrite = 0L
        var seq = 0L
        val items = ArrayList<Item>()
        val hashes = HashSet<String>()
        var bytes = 0L
        var loaded = false
        /** When its items were last used, for [trim]. */
        @Volatile var used = 0L
        val writes = ArrayDeque<Long>()
        val reads = ArrayDeque<Long>()
        /** New items checked in the minute since [freshSince]. */
        var freshSince = 0L
        var freshCount = 0
        /** The last compaction: when, and how many items it kept. */
        var compactedAt = 0L
        var compactedTo = 0
    }

    private val boxes = ConcurrentHashMap<String, Box>()

    /** Forgets what is in memory (tests: a restart reads the disk again). */
    internal fun unload() = boxes.clear()

    /** Mailboxes whose items are in memory (tests). */
    internal fun loadedCount() = boxes.values.count { it.loaded }

    private fun root(env: ServerEnv) = File(env.dataDir, "groups")

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun newCapability(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    /** The group's key in an Authorization header, or null when the header holds something else. */
    fun capabilityOf(header: String?): String? = header?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim()?.takeIf { CAPABILITY.matches(it) }

    // ------------------------------------------------------------------ storage

    private fun box(env: ServerEnv, id: String): Box? {
        if (!GROUP_ID.matches(id)) return null
        val dir = File(root(env), id)
        boxes[dir.absolutePath]?.let { return if (dir.isDirectory) it else null.also { boxes.remove(dir.absolutePath) } }
        val meta = runCatching { Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject }.getOrNull() ?: return null
        val b = Box(dir)
        b.id = id
        b.name = meta.str("name")
        b.creator = meta.str("creator")
        b.capability = meta.str("capability")
        b.created = meta.num("created")
        b.lastWrite = meta.num("lastWrite")
        b.seq = meta.num("seq")
        return boxes.putIfAbsent(dir.absolutePath, b) ?: b
    }

    /** The items, read from disk the first time they are needed (a key's check needs only the meta). */
    private fun Box.load(): Box {
        if (loaded) return this
        loaded = true
        val file = File(dir, "items.jsonl")
        var bad = false
        if (file.isFile) file.forEachLine { line ->
            fun skip() { bad = true }
            // A last line cut short by a crash is left out.
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine skip()
            val seq = (o["s"] as? JsonPrimitive)?.longOrNull ?: return@forEachLine skip()
            val kind = (o["k"] as? JsonPrimitive)?.contentOrNull?.singleOrNull() ?: return@forEachLine skip()
            val v = o["v"] as? JsonObject ?: return@forEachLine skip()
            add(Item(seq, kind, v.toString(), hashOf(kind, v.toString())))
        }
        // Written again without the broken lines: a line appended after one cut short would be lost with it.
        if (bad) write(file, items.joinToString("") { line(it) + "\n" })
        seq = maxOf(seq, items.lastOrNull()?.seq ?: 0)
        return this
    }

    /** Its items out of memory (they are on disk). */
    private fun Box.unloadItems() {
        items.clear(); items.trimToSize(); hashes.clear(); bytes = 0; loaded = false
    }

    /** Keeps the items of the [MAX_LOADED] mailboxes used last in memory; called holding no mailbox's lock. */
    private fun trim() {
        val loaded = boxes.values.filter { it.loaded }
        if (loaded.size <= MAX_LOADED) return
        // Ordered by a copy of when each was used: another call may change it meanwhile.
        loaded.map { it to it.used }.sortedBy { it.second }.take(loaded.size - MAX_LOADED).forEach { (b, _) -> synchronized(b) { b.unloadItems() } }
    }

    private fun Box.add(item: Item) {
        if (!hashes.add(item.hash)) return
        items += item
        bytes += item.text.length
    }

    private fun hashOf(kind: Char, text: String) = sha256("$kind\u0000$text")

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun saveMeta(b: Box) {
        val o = buildJsonObject {
            put("id", b.id); put("name", b.name); put("creator", b.creator); put("capability", b.capability)
            put("created", b.created); put("lastWrite", b.lastWrite); put("seq", b.seq)
        }
        write(File(b.dir, "meta.json"), o.toString())
    }

    private fun line(i: Item) = """{"s":${i.seq},"k":"${i.kind}","v":${i.text}}"""

    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(text)
        try {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ------------------------------------------------------------------ the owner's calls

    /** Why a call is refused: its HTTP code and words. */
    class Refused(val code: Int, message: String) : Exception(message)

    class Made(val id: String, val capability: String)

    /** A new mailbox for the group [id] of [creator] (its public key), named [name] for the owner's list. */
    fun create(env: ServerEnv, id: String, creator: String, name: String): Made {
        if (!GROUP_ID.matches(id)) throw Refused(400, "\"group\" is the group's id: 16 characters, 0-9 and a-f.")
        if (!KEY.matches(creator)) throw Refused(400, "\"creator\" is the creator's public key: 43 characters of base64url.")
        val shown = name.filter { it >= ' ' && it != '\u007f' }.trim().take(60)
        synchronized(this) {
            if ((root(env).list()?.count { GROUP_ID.matches(it) } ?: 0) >= MAX_MAILBOXES) throw Refused(507, "This Trackhop holds $MAX_MAILBOXES group mailboxes already: delete one first.")
            val dir = File(root(env), id)
            if (dir.exists()) throw Refused(409, "A mailbox for this group is here already.")
            val capability = newCapability()
            val b = Box(dir).apply {
                this.id = id; this.name = shown; this.creator = creator; this.capability = sha256(capability)
                created = System.currentTimeMillis()
            }
            saveMeta(b)
            boxes[dir.absolutePath] = b
            env.log("groups", "mailbox made for the group ${shown.ifEmpty { id }}")
            return Made(id, capability)
        }
    }

    fun list(env: ServerEnv): List<JsonObject> = root(env).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
        ?.mapNotNull { d -> box(env, d.name)?.let { info(it) } }.orEmpty()

    fun info(env: ServerEnv, id: String): JsonObject? = box(env, id)?.let { info(it) }

    private fun info(b: Box): JsonObject? = synchronized(b) {
        if (b.deleted) return null
        b.load()
        b.used = System.nanoTime()
        buildJsonObject {
            put("id", b.id); put("name", b.name); put("creator", b.creator)
            put("items", b.items.size); put("bytes", b.bytes); put("cursor", b.seq)
            put("created", b.created); put("lastWrite", b.lastWrite)
        }
    }.also { trim() }

    /** A new key for the group: the old one stops working at once. Null when there is no such mailbox. */
    fun newKey(env: ServerEnv, id: String): String? {
        val b = box(env, id) ?: return null
        val capability = newCapability()
        synchronized(b) {
            if (b.deleted) return null
            b.capability = sha256(capability); saveMeta(b)
        }
        env.log("groups", "new key for the group mailbox ${b.name.ifEmpty { id }}: the old one no longer works")
        return capability
    }

    /** Deletes the mailbox and everything in it; false when there was none. */
    fun delete(env: ServerEnv, id: String): Boolean {
        val b = box(env, id) ?: return false
        synchronized(b) {
            if (b.deleted) return false
            b.deleted = true
            boxes.remove(b.dir.absolutePath)
            b.dir.deleteRecursively()
        }
        env.log("groups", "mailbox of the group ${b.name.ifEmpty { id }} deleted")
        return true
    }

    // ------------------------------------------------------------------ the members' calls

    /** True when [capability] is the current key of a mailbox here (a shared server asks each person's Trackhop). */
    fun knows(env: ServerEnv, capability: String): Boolean {
        val h = sha256(capability)
        return root(env).list()?.any { id -> box(env, id)?.let { MessageDigest.isEqual(it.capability.toByteArray(), h.toByteArray()) } == true } == true
    }

    /** True when [capability] opens the mailbox [id]. */
    fun opens(env: ServerEnv, id: String, capability: String): Boolean {
        val b = box(env, id) ?: return false
        return MessageDigest.isEqual(b.capability.toByteArray(), sha256(capability).toByteArray())
    }

    private fun ArrayDeque<Long>.allow(max: Int, now: Long): Boolean {
        while (isNotEmpty() && first() <= now - 60_000) removeFirst()
        if (size >= max) return false
        addLast(now)
        return true
    }

    /**
     * The items stored after [since], oldest first, as one answer of at most [PAGE_BYTES]:
     * `{"cursor": n, "more": bool, "cards": […], "rosters": […], "events": […]}`. The next read
     * asks for what follows `cursor`.
     */
    fun read(env: ServerEnv, id: String, since: Long): String {
        val b = box(env, id) ?: throw Refused(404, "No mailbox for this group here.")
        try {
            synchronized(b) {
                if (b.deleted) throw Refused(404, "No mailbox for this group here.")
                if (!b.reads.allow(READS_PER_MINUTE, System.currentTimeMillis())) throw Refused(429, "Too many reads of this group: wait a minute.")
                b.load()
                b.used = System.nanoTime()
                val cards = StringBuilder(); val rosters = StringBuilder(); val events = StringBuilder()
                var cursor = since
                var size = 0L
                var more = false
                // Items are kept in the order of their numbers: the first after [since] is found by halves.
                var i = b.items.binarySearchBy(since + 1) { it.seq }.let { if (it < 0) -it - 1 else it }
                while (i < b.items.size) {
                    val item = b.items[i]
                    if (size > 0 && size + item.text.length > PAGE_BYTES) { more = true; break }
                    val to = when (item.kind) { 'c' -> cards; 'r' -> rosters; else -> events }
                    if (to.isNotEmpty()) to.append(',')
                    to.append(item.text)
                    size += item.text.length + 1
                    cursor = item.seq
                    i++
                }
                return """{"cursor":$cursor,"more":$more,"cards":[$cards],"rosters":[$rosters],"events":[$events]}"""
            }
        } finally {
            trim()
        }
    }

    /**
     * A write to the mailbox [id] counted before its body is read: none of a request's work for a
     * caller past the minute's writes (or for a mailbox that is not here).
     */
    fun admitWrite(env: ServerEnv, id: String) {
        val b = box(env, id) ?: throw Refused(404, "No mailbox for this group here.")
        synchronized(b) {
            if (b.deleted) throw Refused(404, "No mailbox for this group here.")
            if (!b.writes.allow(WRITES_PER_MINUTE, System.currentTimeMillis())) throw Refused(429, "Too many writes to this group: wait a minute.")
        }
    }

    /**
     * True when [bytes] (a request's JSON, not read yet) nests at most [MAX_DEPTH] deep and holds at
     * most [MAX_VALUES_PER_ITEM] values for each item a call may carry: brackets and commas outside
     * strings, counted in one pass.
     */
    fun plainEnough(bytes: ByteArray): Boolean {
        val most = MAX_POST_ITEMS.toLong() * MAX_VALUES_PER_ITEM
        var depth = 0
        var values = 0L
        var inString = false
        var escaped = false
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> { if (++depth > MAX_DEPTH) return false; values++ }
                '}', ']' -> depth--
                ',' -> values++
            }
            if (values > most) return false
        }
        return true
    }

    /**
     * What a member sends, checked: each item of a group's shape, for this group, of a sane size,
     * signed by whoever it names (an event by its author, a card by its member, a curators' list by
     * the group's creator). Whether that author may write is the members' check, not this one: a
     * forgery is refused here so that it cannot crowd out real items in the members' reads. Only
     * what the mailbox lacks is checked, and answered as (kind, JSON, hash).
     */
    private fun items(b: Box, body: JsonObject): List<Triple<Char, String, String>> {
        // What the mailbox lacks, each once: only these are checked (a member sending its whole log again costs no signatures).
        class Fresh(val kind: Char, val o: JsonObject, val text: String, val hash: String)
        val fresh = ArrayList<Fresh>()
        val seen = HashSet<String>()
        var count = 0
        for ((field, kind) in listOf("cards" to 'c', "rosters" to 'r', "events" to 'e')) {
            val arr = body[field] ?: continue
            if (arr !is JsonArray) throw Refused(400, "\"$field\" is a list.")
            count += arr.size
            if (count > MAX_POST_ITEMS) throw Refused(413, "At most $MAX_POST_ITEMS items in one call.")
            for (e in arr) {
                val o = e as? JsonObject ?: throw Refused(400, "Each of \"$field\" is an object.")
                val text = o.toString()
                if (text.length > MAX_ITEM) throw Refused(400, "An item of \"$field\" is too large.")
                val hash = hashOf(kind, text)
                if (hash !in b.hashes && seen.add(hash)) fresh += Fresh(kind, o, text, hash)
            }
        }
        // The signatures checked in a minute are capped: the rest waits for the next call.
        val now = System.currentTimeMillis()
        if (now - b.freshSince >= 60_000) { b.freshSince = now; b.freshCount = 0 }
        if (b.freshCount + fresh.size > newPerMinute) throw Refused(429, "Too many new items for this group in a minute: wait a minute.")
        b.freshCount += fresh.size
        val field = mapOf('c' to "cards", 'r' to "rosters", 'e' to "events")
        return fresh.map { f ->
            val o = f.o
            val ok = runCatching {
                when (f.kind) {
                    'c' -> codec.decodeFromJsonElement(MemberCard.serializer(), o).let { it.groupId == b.id && it.wellFormed() && it.verifies() }
                    'r' -> codec.decodeFromJsonElement(Roster.serializer(), o).let { it.groupId == b.id && it.wellFormed() && it.verifies(b.creator) }
                    else -> codec.decodeFromJsonElement(SharedEvent.serializer(), o).let { it.wellFormed() && it.verifies(b.id) }
                }
            }.getOrDefault(false)
            if (!ok) throw Refused(400, "An item of \"${field[f.kind]}\" is not one of this group's.")
            Triple(f.kind, f.text, f.hash)
        }
    }

    /** Stores what [body] holds that the mailbox lacks; answers how many were new and the newest number. */
    fun append(env: ServerEnv, id: String, body: JsonObject): Pair<Int, Long> {
        val b = box(env, id) ?: throw Refused(404, "No mailbox for this group here.")
        try {
            synchronized(b) {
                if (b.deleted) throw Refused(404, "No mailbox for this group here.")
                b.load()
                b.used = System.nanoTime()
                val fresh = items(b, body)
                if (fresh.isEmpty()) return 0 to b.seq
                val adding = fresh.sumOf { it.second.length.toLong() }
                if (b.items.size + fresh.size > maxItems || b.bytes + adding > MAX_BYTES) {
                    // Compacting reads the whole mailbox: not at every call of a writer who keeps it full.
                    val now = System.currentTimeMillis()
                    if (now - b.compactedAt >= COMPACT_EVERY_MS || b.items.size - b.compactedTo > maxItems / 10) compact(env, b, fresh)
                    if (b.items.size + fresh.size > maxItems || b.bytes + adding > MAX_BYTES) throw Refused(507, "This group's mailbox is full.")
                }
                val total = root(env).listFiles()?.sumOf { File(it, "items.jsonl").length() } ?: 0L
                if (total + adding > maxTotalBytes) throw Refused(507, "The group mailboxes on this Trackhop are full: its owner can delete some.")
                val made = fresh.map { (k, t, h) -> Item(++b.seq, k, t, h) }
                File(b.dir, "items.jsonl").appendText(made.joinToString("") { line(it) + "\n" })
                made.forEach { b.add(it) }
                b.lastWrite = System.currentTimeMillis()
                saveMeta(b)
                return made.size to b.seq
            }
        } finally {
            trim()
        }
    }

    /**
     * Keeps what a member's merge would keep of the mailbox and of [coming] (the curators' list the
     * creator signed last, every member's newest signed card, the events that count, one per track
     * and author), each item with its number: a member's cursor still points at the right place.
     */
    private fun compact(env: ServerEnv, b: Box, coming: List<Triple<Char, String, String>>) {
        /** An item stored ([item]) or about to be (null), read as what it is. */
        class Seen(val kind: Char, val value: Any, val item: Item?)
        val seen = (b.items.map { Triple(it.kind, it.text, it) } + coming.map { Triple(it.first, it.second, null) }).mapNotNull { (kind, text, item) ->
            runCatching {
                val json = Json.parseToJsonElement(text)
                val value: Any = when (kind) {
                    'c' -> codec.decodeFromJsonElement(MemberCard.serializer(), json)
                    'r' -> codec.decodeFromJsonElement(Roster.serializer(), json)
                    else -> codec.decodeFromJsonElement(SharedEvent.serializer(), json)
                }
                Seen(kind, value, item)
            }.getOrNull()
        }
        val log = GroupLog(b.id, b.creator)
        val roster = log.roster(seen.mapNotNull { it.value as? Roster })
        val keep = HashSet<Item>()
        seen.firstOrNull { it.value == roster }?.item?.let { keep += it }
        seen.filter { (it.value as? MemberCard)?.let { c -> c.groupId == b.id && c.verifies() } == true }
            .groupBy { (it.value as MemberCard).member }.values
            .map { same -> same.maxWith(compareBy<Seen> { (it.value as MemberCard).at }.thenBy { (it.value as MemberCard).sig }) }
            .sortedWith(compareBy<Seen> { (it.value as MemberCard).member !in roster.curators }.thenByDescending { (it.value as MemberCard).at })
            .take(MAX_CARDS).mapNotNullTo(keep) { it.item }
        val events = seen.filter { it.value is SharedEvent }
        val byEvent = HashMap<SharedEvent, Seen>()
        events.forEach { byEvent.putIfAbsent(it.value as SharedEvent, it) }
        log.merge(roster, emptyList(), events.asSequence().map { it.value as SharedEvent }).mapNotNullTo(keep) { byEvent[it]?.item }
        val before = b.items.size
        val left = b.items.filter { it in keep }
        b.items.clear(); b.hashes.clear(); b.bytes = 0
        left.forEach { b.add(it) }
        write(File(b.dir, "items.jsonl"), left.joinToString("") { line(it) + "\n" })
        b.compactedAt = System.currentTimeMillis()
        b.compactedTo = left.size
        compactions++
        env.log("groups", "mailbox of ${b.name.ifEmpty { b.id }} compacted: ${before - left.size} items no member counts left out")
    }

    // ------------------------------------------------------------------ JSON for the API

    fun made(m: Made): JsonElement = buildJsonObject { put("group", m.id); put("key", m.capability) }

    fun listJson(env: ServerEnv): JsonElement = buildJsonArray { list(env).forEach { add(it) } }
}

/**
 * /api/v1/groups/{group}/items with a group's key: that group's mailbox and nothing else (the key
 * is never an API token: it opens no other call, and no other group). Answered before the API's
 * own token is looked at.
 */
internal fun Web.groupKeyCall(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, capability: String) {
    fun error(code: Int, message: String) = send(ex, code, buildJsonObject { put("error", message) }.toString(), "application/json")
    if (!env.may(Permissions.API) || !Api.enabled(env)) return error(403, "This server's API is off: ask its owner.")
    if (rest.size != 3 || rest[0] != "groups" || rest[2] != "items") return error(403, "A group's key opens that group's items only.")
    if (!GroupMailboxes.opens(env, rest[1], capability)) return error(401, "This key does not open this group: it was replaced, or the group was deleted here.")
    groupItems(ex, method, rest[1], query, ::error)
}

/** GET and POST of a mailbox's items, for its key or the owner's token. */
internal fun Web.groupItems(ex: HttpExchange, method: String, id: String, query: Map<String, String>, error: (Int, String) -> Unit) {
    try {
        when (method) {
            "GET" -> {
                val since = query["since"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: return error(400, "\"since\" is the cursor of the last read (0 for everything).") } ?: 0L
                send(ex, 200, GroupMailboxes.read(env, id, since), "application/json")
            }
            "POST" -> {
                // Counted before the body is read: a caller past the minute's writes costs no memory or parsing.
                GroupMailboxes.admitWrite(env, id)
                val bytes = ex.requestBody.readNBytes(GroupMailboxes.MAX_BODY + 1)
                if (bytes.size > GroupMailboxes.MAX_BODY) return error(413, "At most ${GroupMailboxes.MAX_BODY / 1024 / 1024} MB in one call.")
                if (!GroupMailboxes.plainEnough(bytes)) return error(400, "Too deep or too many values for the items one call may carry.")
                val body = runCatching { Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject }.getOrNull()
                    ?: return error(400, "Send JSON: {\"cards\"?: […], \"rosters\"?: […], \"events\"?: […]}.")
                val (added, cursor) = GroupMailboxes.append(env, id, body)
                send(ex, 200, buildJsonObject { put("added", added); put("cursor", cursor) }.toString(), "application/json")
            }
            else -> error(405, "GET or POST.")
        }
    } catch (e: GroupMailboxes.Refused) {
        if (e.code == 429) ex.responseHeaders.set("Retry-After", "60")
        error(e.code, e.message.orEmpty())
    }
}

/** The owner's calls on the mailboxes, with the API's token. */
internal fun Web.apiGroups(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.isEmpty() || rest[0] != "groups") return false
    try {
        when {
            rest.size == 1 && method == "GET" -> json(200, GroupMailboxes.listJson(env))
            rest.size == 1 && method == "POST" -> {
                val b = apiJson(ex) ?: return true.also { error(400, "Send JSON: {\"group\": id, \"creator\": key, \"name\"?: text}.") }
                json(201, GroupMailboxes.made(GroupMailboxes.create(env, b.text("group").orEmpty(), b.text("creator").orEmpty(), b.text("name").orEmpty())))
            }
            rest.size == 2 && method == "GET" -> json(200, GroupMailboxes.info(env, rest[1]) ?: return true.also { error(404, "No mailbox for this group here.") })
            rest.size == 2 && method == "DELETE" -> {
                if (!GroupMailboxes.delete(env, rest[1])) return true.also { error(404, "No mailbox for this group here.") }
                json(200, buildJsonObject { put("deleted", true) })
            }
            rest.size == 3 && rest[2] == "key" && method == "POST" -> {
                val key = GroupMailboxes.newKey(env, rest[1]) ?: return true.also { error(404, "No mailbox for this group here.") }
                json(200, buildJsonObject { put("group", rest[1]); put("key", key) })
            }
            rest.size == 3 && rest[2] == "items" -> groupItems(ex, method, rest[1], query, error)
            else -> error(404, "Unknown call.")
        }
    } catch (e: GroupMailboxes.Refused) {
        error(e.code, e.message.orEmpty())
    }
    return true
}
