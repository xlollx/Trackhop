package com.xlollx.songport.shared

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A shared playlist this device takes part in. Each member listens on their own service: the group
 * is synced into their own playlist by a two-way pair of syncs, [jobId] (the group into the
 * playlist) and [twinId] (the playlist into the group), so matching, review and the safety checks
 * are the ones every sync has.
 */
@Serializable
data class SharedGroup(
    val id: String,
    val name: String,
    /** The creator's public key, from the invite: the only key whose curators' list counts. */
    val creator: String,
    /** This device's public key in the group; the private one is in the token vault. */
    val me: String,
    /** The group's folder, as [SharedFolders] opens it (a path, an Android folder address); for [Invite.NTFY], the server's address. */
    val folder: String,
    /** The folder's name as the user saw it when picking it; for [Invite.NTFY], the server's name. */
    val folderName: String = "",
    /** The name the other members see. */
    val myName: String = "",
    /** The service this member listens on, by name, for the members list. */
    val myService: String = "",
    /** As a follower: own additions stay in this member's playlist (true) or are taken out (false: an exact copy). */
    val keepMine: Boolean = false,
    val jobId: String = "",
    val twinId: String = "",
    val joinedAt: Long = 0,
    /** How the members reach each other (see [GroupTransport]). */
    val transport: String = Invite.FOLDER,
) {
    val createdHere: Boolean get() = creator == me
    /** The invite without its secret part: [SharedTransports.invite] adds an encrypted channel's key. */
    fun invite(): Invite =
        if (transport == Invite.NTFY) Invite(id, name, creator, transport = transport, server = folder) else Invite(id, name, creator, folderName)
}

/** What this device knows of a group: the merged log and the curators' list, plus a follower's own changes. */
@Serializable
data class GroupState(
    val roster: Roster? = null,
    /** Every valid event known, compacted (see [GroupLog.merge]). */
    val events: List<SharedEvent> = emptyList(),
    /** The newest signed card of each member seen. */
    val members: List<MemberCard> = emptyList(),
    /** The last number this member gave one of their events. */
    val seq: Long = 0,
    /** Per member, the highest event number seen in any file, signed or not: where a new right to write starts. */
    val highest: Map<String, Long> = emptyMap(),
    /** When this device first saw each event, by id: a track's arrival on this device's own clock. */
    val seen: Map<String, Long> = emptyMap(),
    /** A follower keeping their own additions: those tracks (never sent), by key. */
    val own: List<SharedEvent> = emptyList(),
    /** A follower keeping their own additions: group tracks they took out of their playlist, and when. */
    val hidden: Map<String, Long> = emptyMap(),
    /** The hash of the file last written to the folder: an unchanged file is not written again. */
    val written: Int = 0,
)

/**
 * A group's invite, as a link or a QR code: the group, its name, the creator's public key (the
 * key every member trusts for the curators' list) and how the group talks. For the shared folder
 * nothing secret: being able to open the folder is what lets someone in. For an encrypted ntfy
 * channel, the [server] and the group's [key]: whoever holds the invite reads the group (the key
 * travels after the '#', which a browser never sends to trackhop.org).
 */
data class Invite(
    val groupId: String, val name: String, val creator: String, val folderName: String = "", val transport: String = FOLDER,
    val server: String = "", val key: String = "",
) {
    fun link(): String = buildString {
        append(BASE).append("g=").append(groupId).append("&k=").append(creator).append("&t=").append(transport)
        append("&n=").append(enc(name.take(MAX_NAME)))
        if (folderName.isNotBlank()) append("&f=").append(enc(folderName.take(MAX_FOLDER)))
        if (transport == NTFY) append("&s=").append(enc(server)).append("&x=").append(key)
    }

    companion object {
        const val FOLDER = "folder"
        const val NTFY = "ntfy"
        const val BASE = "https://trackhop.org/join#"
        const val MAX_NAME = 60
        const val MAX_FOLDER = 40
        private val ID = Regex("[0-9a-f]{16}")

        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        private fun dec(s: String) = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** The invite in [text] (a link, alone or inside a message), or null. */
        fun parse(text: String): Invite? {
            val at = Regex("""(?:trackhop\.org/join|songport://join)[#?/]*([^\s]+)""").find(text)?.groupValues?.get(1) ?: return null
            val params = at.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to dec(it[1]) } }.toMap()
            val id = params["g"]?.takeIf { ID.matches(it) } ?: return null
            val key = params["k"]?.takeIf { B64.decode(it)?.size == 32 } ?: return null
            val name = params["n"]?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() } ?: return null
            return when (val transport = params["t"] ?: FOLDER) {
                FOLDER -> Invite(id, name, key, params["f"].orEmpty().take(MAX_FOLDER), transport)
                NTFY -> {
                    val server = params["s"]?.takeIf { NtfyServer.valid(it) } ?: return null
                    val secret = params["x"]?.takeIf { B64.decode(it)?.size == 32 } ?: return null
                    Invite(id, name, key, transport = transport, server = server.trim(), key = secret)
                }
                // A way to talk this version does not know.
                else -> null
            }
        }
    }
}

/** Creating, joining and leaving groups, the curators' list, and the group's side of a sync (see [SharedProvider]). */
object SharedGroups {
    enum class Role { CURATOR, FOLLOWER }

    /** A member as the members screen lists them. */
    data class Member(val id: String, val name: String, val service: String, val curator: Boolean, val creator: Boolean, val me: Boolean, val left: Boolean)

    /** The clock events are dated with; tests set their own. */
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockOf(id: String): Any = locks.getOrPut(id) { Any() }

    fun tokenKey(groupId: String) = "shared:$groupId"

    fun group(env: Env, id: String): SharedGroup? = env.store.data.groups.firstOrNull { it.id == id }

    fun groupOf(env: Env, job: SyncJob): SharedGroup? = sharedSide(job)?.let { group(env, it) }

    /** The group a sync reads or writes, by id, when it is one half of a shared playlist. */
    fun sharedSide(job: SyncJob): String? = when {
        job.source.provider == SharedProvider.ID -> job.source.playlistId
        job.target.provider == SharedProvider.ID -> job.target.playlistId
        else -> null
    }

    // ---------------------------------------------------------------- local state

    private fun stateFile(env: Env, id: String) = File(File(env.dataDir, "shared"), "$id.json")

    fun load(env: Env, id: String): GroupState = synchronized(lockOf(id)) {
        runCatching { codec.decodeFromString(GroupState.serializer(), stateFile(env, id).readText()) }.getOrNull() ?: GroupState()
    }

    private fun save(env: Env, id: String, s: GroupState) {
        val f = stateFile(env, id)
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(codec.encodeToString(GroupState.serializer(), s))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    private fun identity(env: Env, group: SharedGroup): Identity =
        env.tokens.get(tokenKey(group.id))?.accessToken?.let { Identity.of(it) }?.takeIf { it.id == group.me }
            ?: throw ProviderException(env.text("shared_error_key", group.name))

    fun role(group: SharedGroup, s: GroupState): Role =
        if (group.me in (s.roster ?: Roster.first(group.id, group.creator)).curators) Role.CURATOR else Role.FOLLOWER

    // ---------------------------------------------------------------- the folder

    /**
     * Reads every member's file, merges it into what this device knows, and writes this member's
     * file again when it changed (their card, the newest curators' list, every event: a member who
     * leaves does not take the group's history with them).
     */
    fun refresh(env: Env, group: SharedGroup, write: Boolean = true): GroupState = synchronized(lockOf(group.id)) {
        val transport = SharedTransports.open(env, group)
        val files = try {
            transport.readAll()
        } catch (e: Exception) {
            env.log("shared", "${group.name}: not read: ${e.message?.take(160)}")
            throw ProviderException(transport.unreachable(env, group))
        }
        val before = load(env, group.id)
        val log = GroupLog(group.id, group.creator)
        val roster = log.roster(files.map { it.roster } + before.roster)
        val events = log.merge(roster, before.events.filter { roster.allows(it) }, files.asSequence().flatMap { it.events.asSequence() })
        val now = clock()
        val members = (files.map { it.card } + before.members)
            .filter { it.groupId == group.id && SigCache.verified(it) }
            .groupBy { it.member }.values.map { cards -> cards.maxWith(compareBy<MemberCard> { it.at }.thenBy { it.sig }) }
        // Only for members (a file can name any author): bounded by the group's size.
        val known = members.mapTo(HashSet()) { it.member } + roster.grants.map { it.member }
        val highest = HashMap(before.highest.filterKeys { it in known })
        for (e in files.asSequence().flatMap { it.events.asSequence() }) if (e.by in known && e.seq > (highest[e.by] ?: 0)) highest[e.by] = e.seq
        val mine = events.filter { it.by == group.me }.maxOfOrNull { it.seq } ?: 0
        var state = before.copy(
            roster = roster, events = events, members = members, highest = highest,
            seq = maxOf(before.seq, mine, highest[group.me] ?: 0),
            seen = events.associate { it.id to (before.seen[it.id] ?: now) },
        )
        if (write) state = publish(env, group, state, transport)
        save(env, group.id, state)
        state
    }

    /** This member's file, published when the transport needs it (see [GroupTransport.publish]). */
    private fun publish(env: Env, group: SharedGroup, state: GroupState, transport: GroupTransport, left: Boolean = false): GroupState {
        val me = identity(env, group)
        val old = state.members.firstOrNull { it.member == me.id }
        val card = old?.takeIf { it.name == group.myName && it.service == group.myService && it.left == left }
            ?: MemberCard(group.id, me.id, group.myName, group.myService, maxOf(clock(), (old?.at ?: 0) + 1), left).signed(me)
        val file = MemberFile(card = card, roster = state.roster?.takeIf { it.version > 0 }, events = state.events)
        val written = try {
            transport.publish(me.id, file, state.written)
        } catch (e: Exception) {
            env.log("shared", "${group.name}: not written: ${e.message?.take(160)}")
            throw ProviderException(transport.unreachable(env, group))
        }
        return state.copy(written = written, members = state.members.filter { it.member != me.id } + card)
    }

    // ---------------------------------------------------------------- the group as a playlist

    /**
     * The group's tracks as this member's sync sees them: the live tracks, dated by when this device
     * first saw them. A follower who keeps their own additions sees those too, and not the group
     * tracks they took out. (One keeping an exact copy sees the group as it is: the pair of syncs
     * takes their own additions out again and puts back what they took out.)
     */
    fun listing(env: Env, group: SharedGroup): List<Track> {
        val s = refresh(env, group)
        val live = GroupLog(group.id, group.creator).live(s.events)
        val follower = role(group, s) == Role.FOLLOWER
        val tracks = live.mapNotNull { e ->
            val arrived = s.seen[e.id] ?: 0
            when {
                follower && group.keepMine && (s.hidden[e.key] ?: -1) >= arrived -> null
                else -> e.track(arrived)
            }
        }
        if (!follower || !group.keepMine) return tracks
        val keys = live.mapTo(HashSet()) { it.key }
        return tracks + s.own.filter { it.key !in keys }.map { it.track() }
    }

    /** The group's key a track of the group listing carries as its id; any other track gets one from its name. */
    private fun keyFor(t: Track): String = if (t.id.startsWith("isrc:") || t.id.startsWith("t:")) t.id else SharedEvent.keyOf(t)

    /**
     * A track of this member's playlist as a group track: the one a review matched it to, when the
     * group still lists it, else a new one described by the track itself.
     */
    fun asGroupTrack(env: Env, groupId: String, t: Track): Track {
        val group = group(env, groupId) ?: return t.copy(id = SharedEvent.keyOf(t))
        val prefix = FileStore.family(SharedProvider.ID) + ":"
        val matched = env.store.data.matchCache.entries.firstOrNull { (k, v) -> v == t.id && k.startsWith(prefix) }?.key
            ?.removePrefix(prefix)?.substringBeforeLast('>')
        if (matched != null) {
            val s = load(env, group.id)
            GroupLog(group.id, group.creator).live(s.events).firstOrNull { it.key == matched }?.let { return it.track(s.seen[it.id] ?: it.at) }
        }
        return Track(id = SharedEvent.keyOf(t), title = t.title, artists = t.artists, album = t.album, durationMs = t.durationMs, isrc = t.isrcNorm)
    }

    /** Tracks added on this member's playlist reach the group: signed events from a curator, local only from a follower. */
    fun add(env: Env, group: SharedGroup, tracks: List<Track>) = change(env, group, tracks, SharedEvent.ADD)

    fun remove(env: Env, group: SharedGroup, tracks: List<Track>) = change(env, group, tracks, SharedEvent.REMOVE)

    private fun change(env: Env, group: SharedGroup, tracks: List<Track>, op: String) {
        if (tracks.isEmpty()) return
        synchronized(lockOf(group.id)) {
            val s = load(env, group.id)
            val now = clock()
            val keys = tracks.map { keyFor(it) }
            val next = when {
                role(group, s) == Role.CURATOR -> {
                    val me = identity(env, group)
                    var seq = s.seq
                    // An action taken on what the member sees comes after it, even when the clock of
                    // whoever wrote the current state ran ahead of this one.
                    val latest = GroupLog(group.id, group.creator).winners(s.events).associate { it.key to it.at }
                    val fresh = tracks.map { t ->
                        val key = keyFor(t)
                        SharedEvent.of(op, t, me.id, ++seq, maxOf(now, (latest[key] ?: 0) + 1)).copy(key = key).signed(group.id, me)
                    }
                    val st = s.copy(events = GroupLog.compact(s.events + fresh), seq = seq, seen = s.seen + fresh.associate { it.id to now })
                    publish(env, group, st, SharedTransports.open(env, group))
                }
                group.keepMine -> if (op == SharedEvent.ADD) {
                    val own = tracks.map { t -> SharedEvent.of(op, t, group.me, 0, now).copy(key = keyFor(t)) }
                    s.copy(own = s.own.filter { it.key !in keys } + own, hidden = s.hidden - keys.toSet())
                } else {
                    val ownKeys = s.own.mapTo(HashSet()) { it.key }
                    s.copy(own = s.own.filter { it.key !in keys }, hidden = s.hidden + keys.filter { it !in ownKeys }.associateWith { now })
                }
                // An exact copy: nothing leaves this device. The pair of syncs then takes the addition
                // out of the member's playlist again, and puts a removed track back (its link is gone).
                else -> {
                    env.log("shared", "${group.name}: following as an exact copy, ${tracks.size} ${if (op == SharedEvent.ADD) "additions are taken out again" else "removals are put back"}")
                    // A track the last run brought in and the member took out at once would read, to
                    // the group's sync, as one their service hides from its listing: forgotten there, so it comes back.
                    if (op == SharedEvent.REMOVE) {
                        val job = env.store.job(group.jobId)
                        val last = env.store.lastWrites(group.jobId)
                        if (job != null && last != null) {
                            val local = keys.mapNotNull { env.store.cachedMatch(SharedProvider.ID, it, job.target.provider) }.toSet()
                            if (local.any { it in last.added }) env.store.putLastWrites(group.jobId, last.copy(added = last.added - local))
                        }
                    }
                    s
                }
            }
            save(env, group.id, next)
        }
    }

    // ---------------------------------------------------------------- groups

    private fun newGroupId(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** The two halves of a member's sync: the group into [local], and [local] into the group. */
    private fun jobsFor(group: SharedGroup, local: PlaylistRef): Pair<SyncJob, SyncJob> {
        val shared = PlaylistRef(SharedProvider.ID, group.id, group.name)
        val down = SyncJob(id = group.jobId, name = group.name, source = shared, target = local, linkedJobId = group.twinId,
            twoWayRemovals = true, schedule = Schedule.EVERY_6H)
        val up = SyncJob(id = group.twinId, name = "${group.name} ↔", source = local, target = shared, linkedJobId = group.jobId,
            twoWayRemovals = true, twin = true, schedule = Schedule.EVERY_6H)
        return down to up
    }

    /**
     * A new group around [local], this member's playlist, its files in [folder] (for [Invite.NTFY]:
     * on the ntfy server at [folder], encrypted with a new group key): this member is its creator and
     * first curator. The playlist's tracks reach the group on the first sync.
     */
    fun create(
        env: Env, name: String, folder: String, folderName: String, myName: String, myService: String, local: PlaylistRef,
        transport: String = Invite.FOLDER,
    ): SharedGroup {
        val me = Identity.create()
        val id = newGroupId()
        val group = SharedGroup(id, name.trim().take(Invite.MAX_NAME), me.id, me.id, folder.trim(), folderName, myName.trim(), myService,
            jobId = UUID.randomUUID().toString(), twinId = UUID.randomUUID().toString(), joinedAt = clock(), transport = transport)
        val roster = Roster(id, 1, clock(), listOf(Grant(me.id))).signed(me)
        val key = if (transport == Invite.NTFY) SharedTransports.newGroupKey() else null
        return start(env, group, me, GroupState(roster = roster), local, key)
    }

    /**
     * Joins the group of [invite] with [local], this member's playlist (usually a new, empty one),
     * as a follower until the creator makes them a curator. Answers the group and whether the folder
     * held any other member's file (none: probably not the group's folder). An encrypted channel's
     * invite says where the group is: [folder] and [folderName] are not used then.
     */
    fun join(env: Env, invite: Invite, folder: String, folderName: String, myName: String, myService: String, local: PlaylistRef): Pair<SharedGroup, Boolean> {
        if (group(env, invite.groupId) != null) throw ProviderException(env.text("shared_error_already", invite.name))
        val me = Identity.create()
        val ntfy = invite.transport == Invite.NTFY
        val group = SharedGroup(invite.groupId, invite.name, invite.creator, me.id, if (ntfy) invite.server else folder,
            if (ntfy) NtfyServer.hostOf(invite.server) else folderName, myName.trim(), myService,
            jobId = UUID.randomUUID().toString(), twinId = UUID.randomUUID().toString(), joinedAt = clock(), transport = invite.transport)
        val g = start(env, group, me, GroupState(), local, invite.key.takeIf { ntfy })
        val others = load(env, g.id).members.any { it.member != me.id }
        return g to others
    }

    private fun start(env: Env, group: SharedGroup, me: Identity, state: GroupState, local: PlaylistRef, groupKey: String? = null): SharedGroup {
        val extra = groupKey?.let { mapOf(SharedTransports.GROUP_KEY to it) }.orEmpty()
        env.tokens.set(tokenKey(group.id), Tokens(accessToken = B64.encode(me.seed), userId = me.id, extra = extra))
        synchronized(lockOf(group.id)) { save(env, group.id, state) }
        try {
            refresh(env, group)
        } catch (e: Exception) {
            forget(env, group)
            throw e
        }
        val (down, up) = jobsFor(group, local)
        env.store.update { d -> d.copy(groups = d.groups.filter { it.id != group.id } + group, jobs = d.jobs + down + up) }
        env.log("shared", "${group.name}: ${if (group.createdHere) "created" else "joined"}, files in ${group.folderName.ifBlank { "the chosen folder" }}")
        return group
    }

    private fun forget(env: Env, group: SharedGroup) {
        env.tokens.clear(tokenKey(group.id))
        synchronized(lockOf(group.id)) { stateFile(env, group.id).delete(); SharedTransports.forget(env, group) }
    }

    /**
     * Leaves the group: this member's file says so (and keeps relaying the group's history), the
     * syncs and the key go. The playlist on the member's service stays as it is. Answers the ids of
     * the syncs removed, for the platform to cancel their schedules.
     */
    fun leave(env: Env, group: SharedGroup): List<String> {
        runCatching {
            synchronized(lockOf(group.id)) {
                val s = load(env, group.id)
                publish(env, group, s, SharedTransports.open(env, group), left = true)
            }
        }.onFailure { env.log("shared", "${group.name}: leaving without a word in the folder: ${it.message?.take(120)}") }
        val ids = env.store.data.jobs.filter { sharedSide(it) == group.id }.map { it.id }
        ids.forEach { env.store.deleteJob(it) }
        env.store.update { d -> d.copy(groups = d.groups.filter { it.id != group.id }) }
        forget(env, group)
        env.log("shared", "${group.name}: left")
        return ids
    }

    /** A follower's choice: keep their own additions (true) or an exact copy. */
    fun setKeepMine(env: Env, group: SharedGroup, keep: Boolean) {
        if (group.keepMine == keep) return
        synchronized(lockOf(group.id)) { save(env, group.id, load(env, group.id).copy(own = emptyList(), hidden = emptyMap())) }
        env.store.update { d -> d.copy(groups = d.groups.map { if (it.id == group.id) it.copy(keepMine = keep) else it }) }
    }

    /**
     * The creator makes [member] a curator or a follower: a new curators' list, signed. A follower's
     * right starts after the last event number seen from them; a curator's ends at the last event
     * of theirs this device accepted, so what they curated stays. There is always one curator.
     */
    fun setCurator(env: Env, group: SharedGroup, member: String, curator: Boolean) {
        if (!group.createdHere) throw ProviderException(env.text("shared_error_not_creator"))
        val me = identity(env, group)
        refresh(env, group, write = false)
        synchronized(lockOf(group.id)) {
            val s = load(env, group.id)
            val roster = s.roster ?: Roster.first(group.id, group.creator)
            val isCurator = member in roster.curators
            if (isCurator == curator) return
            val grants = if (curator) roster.grants + Grant(member, afterSeq = s.highest[member] ?: 0)
            else {
                if (roster.curators.size <= 1) throw ProviderException(env.text("shared_error_last_curator"))
                val last = s.events.filter { it.by == member }.maxOfOrNull { it.seq } ?: 0
                roster.grants.map { if (it.member == member && it.current) it.copy(untilSeq = maxOf(last, it.afterSeq)) else it }
            }
            val next = Roster(group.id, roster.version + 1, clock(), grants).signed(me)
            val st = s.copy(roster = next, events = s.events.filter { next.allows(it) })
            save(env, group.id, st)
        }
        refresh(env, group)
        env.log("shared", "${group.name}: a member is now ${if (curator) "a curator" else "a follower"}")
    }

    fun members(group: SharedGroup, s: GroupState): List<Member> {
        val curators = (s.roster ?: Roster.first(group.id, group.creator)).curators
        val cards = s.members.associateBy { it.member }
        val ids = LinkedHashSet<String>().apply { add(group.creator); addAll(cards.keys); addAll(curators) }
        return ids.map { id ->
            val c = cards[id]
            Member(id, c?.name.orEmpty(), c?.service.orEmpty(), id in curators, id == group.creator, id == group.me, c?.left == true)
        }.sortedWith(compareBy<Member> { it.left }.thenBy { !it.creator }.thenBy { !it.curator }.thenBy { it.name.lowercase() })
    }

    /** The readable log: every action known, the newest first. */
    fun history(s: GroupState): List<SharedEvent> = s.events.sortedWith(GroupLog.ORDER.reversed())
}
