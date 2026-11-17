package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Many shared playlists behind one invite. A curator shares several of their playlists at once;
 * whoever follows takes them all (those added later too) or picks some, on one or more of their
 * own services.
 *
 * Inside, nothing new travels: each playlist is a shared playlist as ever (a [SharedGroup] with
 * its own log, roles, syncs and review), and the collection's [list] is one more group, with no
 * playlist and no syncs, whose "tracks" are the playlists: one signed entry each, carrying that
 * playlist's own invite. So the three ways to meet, the signatures and a server from before
 * collections all carry a collection as they are, and only the creator's entries count.
 *
 * What a member chose is kept here ([mode], [services], [on]); what exists is the groups and
 * their syncs. [Collections.settle] brings the second in line with the first, one playlist at a
 * time, and may be run again at any point: stopped half way (no network, the app closed), what
 * was set up stays, the rest is still asked for, and nothing is made twice.
 */
@Serializable
data class SharedCollection(
    val id: String,
    /** The list of playlists, as a group (never among the store's groups: it is no playlist). */
    val list: SharedGroup,
    /** [ALL] (those added later too), [PICK] (the ones in [on]; new ones are proposed), or blank: joined, nothing chosen yet. */
    val mode: String = "",
    /** The member's services (accounts) the playlists are kept on; a creator's one is where the playlists are. */
    val services: List<String> = emptyList(),
    /** As a follower: own additions stay in the member's playlists (true) or it is an exact copy. */
    val keepMine: Boolean = true,
    /** The playlists wanted, by their group's id. */
    val on: Set<String> = emptySet(),
    /** The playlists the member has answered for (taken, left out, ignored): the others are proposals. */
    val known: Set<String> = emptySet(),
    /** The creator's playlists still to be shared: asked for, not all made yet (see [Collections.settle]). */
    val todo: List<Todo> = emptyList(),
    /** The creator's choice: playlists made later on their service join the collection by themselves. */
    val autoAdd: Boolean = false,
    /** With [autoAdd]: the playlists of the service already seen, by id. Only one that is not here is new. */
    val seen: Set<String> = emptySet(),
    /** Being left: every playlist's syncs go first, then the list. Set before the first goes, so a leave stopped half way ends at the next start. */
    val leaving: Boolean = false,
    /** When the list was last read for news (see [Collections.due]). */
    val checkedAt: Long = 0,
) {
    val createdHere: Boolean get() = list.createdHere
    val name: String get() = list.name
    val chosen: Boolean get() = mode.isNotBlank()

    /** A creator's playlist to share, and how many tracks it had when chosen. */
    @Serializable
    data class Todo(val playlist: PlaylistRef, val tracks: Int = 0)

    companion object {
        const val ALL = "all"
        const val PICK = "pick"
    }
}

object Collections {
    /** A playlist of a collection, as its list says: the playlist's group, its name, and what its own invite needs. */
    data class Entry(val groupId: String, val name: String, val creator: String, val key: String, val tracks: Int, val at: Long)

    /** A playlist as a collection's page lists it. */
    data class Row(
        val entry: Entry,
        /** Wanted here (its switch). */
        val on: Boolean,
        /** Its group exists on this device. */
        val joined: Boolean,
        /** Wanted, and not on every chosen service yet: the next [settle] sets it up. */
        val pending: Boolean,
    )

    /** What one [settle] did. */
    data class Settled(
        /** The syncs made, in the order to run them first. */
        val started: List<String> = emptyList(),
        /** The syncs that no longer exist (their schedules are cancelled by the platform). */
        val gone: List<String> = emptyList(),
        /** The syncs whose on/off changed (their schedules follow). */
        val changed: List<String> = emptyList(),
        /** The playlists that could not be set up this time, by name, with why. They are still asked for. */
        val failed: List<Pair<String, String>> = emptyList(),
        /** The playlists the curator took out of the collection since: their syncs went, the member's playlists stay. */
        val removed: List<String> = emptyList(),
    ) {
        val complete: Boolean get() = failed.isEmpty()
    }

    const val MAX_PLAYLISTS = 200
    /** How often a background sync of one of its playlists reads a collection's list for news. */
    const val CHECK_EVERY_MS = 60 * 60 * 1000L
    private const val KEY = "t:pl:"
    private val GROUP_ID = Regex("[0-9a-f]{16}")

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockOf(id: String) = locks.getOrPut(id) { Mutex() }

    fun all(env: Env): List<SharedCollection> = env.store.data.collections
    fun get(env: Env, id: String): SharedCollection? = env.store.data.collections.firstOrNull { it.id == id }
    fun of(env: Env, group: SharedGroup): SharedCollection? = group.collectionId.takeIf { it.isNotBlank() }?.let { get(env, it) }

    /** The playlists of [c] this device takes part in. */
    fun groups(env: Env, c: SharedCollection): List<SharedGroup> = env.store.data.groups.filter { it.collectionId == c.id }

    /** The syncs of [c]'s playlists: behind the collection's one card. */
    fun jobs(env: Env, c: SharedCollection): List<SyncJob> {
        val ids = groups(env, c).mapTo(HashSet()) { it.id }
        return env.store.data.jobs.filter { SharedGroups.sharedSide(it) in ids }
    }

    private fun put(env: Env, id: String, fn: (SharedCollection) -> SharedCollection): SharedCollection? {
        env.store.update { d -> d.copy(collections = d.collections.map { if (it.id == id) fn(it) else it }) }
        return get(env, id)
    }

    // ---------------------------------------------------------------- the list

    private fun entryTrack(group: SharedGroup, key: String, tracks: Int) =
        Track(id = KEY + group.id, title = group.name, artists = listOf(group.creator, key), durationMs = tracks.toLong())

    private fun entryOf(list: SharedGroup, e: SharedEvent): Entry? {
        val id = e.key.removePrefix(KEY).takeIf { e.key.startsWith(KEY) && GROUP_ID.matches(it) && it != list.id } ?: return null
        val creator = e.artists.getOrNull(0)?.takeIf { KEY_TEXT.matches(it) } ?: return null
        val key = e.artists.getOrNull(1).orEmpty()
        val keyOk = when (list.transport) {
            Invite.NTFY -> KEY_TEXT.matches(key) && B64.decode(key)?.size == 32
            Invite.SERVER -> ServerGroupTransport.validKey(key)
            else -> true
        }
        val name = plainOf(e.title, Invite.MAX_NAME).trim()
        if (!keyOk || name.isEmpty()) return null
        return Entry(id, name, creator, key, e.durationMs.coerceIn(0, 1_000_000).toInt(), e.at)
    }

    /** The playlists the collection lists now, the first added first. Only the creator's list of curators writes it, as in any group. */
    fun entries(c: SharedCollection, s: GroupState): List<Entry> =
        GroupLog(c.list.id, c.list.creator).live(s.events).mapNotNull { entryOf(c.list, it) }
            .sortedWith(compareBy<Entry> { it.at }.thenBy { it.groupId }).take(MAX_PLAYLISTS)

    /** The playlists the curator took out, by group id: said, not merely missing from what was read so far. */
    private fun takenOut(c: SharedCollection, s: GroupState): Set<String> =
        GroupLog(c.list.id, c.list.creator).tombstones(s.events).mapNotNull { e -> e.key.removePrefix(KEY).takeIf { e.key.startsWith(KEY) } }.toSet()

    fun entries(env: Env, c: SharedCollection): List<Entry> = entries(c, SharedGroups.load(env, c.id))

    /** The page's rows, from what this device holds (nothing is read from the channel). */
    fun rows(env: Env, c: SharedCollection, entries: List<Entry> = entries(env, c)): List<Row> {
        val jobs = env.store.data.jobs
        return entries.map { e ->
            val joined = SharedGroups.group(env, e.groupId)?.collectionId == c.id
            val on = e.groupId in c.on
            Row(e, on, joined, on && c.chosen && !c.createdHere && c.services.any { s -> !has(jobs, e.groupId, s) })
        }
    }

    /** A follower who picks: the playlists added since they chose, each waiting for a yes or a no. */
    fun proposals(c: SharedCollection, entries: List<Entry>): List<Entry> =
        if (c.mode == SharedCollection.PICK && !c.createdHere) entries.filter { it.groupId !in c.known } else emptyList()

    /** How many others are in the collection (their cards on the list, those who left not counted). */
    fun followers(c: SharedCollection, s: GroupState): Int = SharedGroups.members(c.list, s).count { !it.left && !it.me }

    /** The collection's invite as this device can give it: one link, one code for every playlist. */
    fun invite(env: Env, c: SharedCollection): Invite = SharedTransports.invite(env, c.list).copy(collection = true)

    private fun inviteOf(env: Env, c: SharedCollection, e: Entry): Invite = when (c.list.transport) {
        Invite.NTFY -> Invite(e.groupId, e.name, e.creator, transport = Invite.NTFY, server = c.list.folder, key = e.key, signIn = SharedTransports.ntfyAuth(env, c.list) != null)
        Invite.SERVER -> Invite(e.groupId, e.name, e.creator, transport = Invite.SERVER, server = c.list.folder, key = e.key)
        else -> Invite(e.groupId, e.name, e.creator, c.list.folderName)
    }

    // ---------------------------------------------------------------- creating and joining

    /**
     * A new collection of [playlists], the creator's own on one service ([service], an account's id):
     * its list is made and stored, the playlists are then shared one by one ([settle], run here).
     * Fails with nothing kept when the list itself cannot be made; once it is, what could not be
     * shared yet stays asked for ([SharedCollection.todo]) and the answer says which.
     */
    suspend fun create(
        env: Env, name: String, myName: String, service: String, playlists: List<Playlist>,
        folder: String, folderName: String, transport: String = Invite.FOLDER, ntfyAuth: String? = null, autoAdd: Boolean = false,
        seen: Set<String> = emptySet(),
    ): Pair<SharedCollection, Settled> {
        check(Features.v2) { "Not available in this version" }
        val p = Providers.byId(service) ?: throw ProviderException(env.text("shared_no_services"))
        require(playlists.isNotEmpty() && playlists.size <= MAX_PLAYLISTS) { "A collection holds 1 to $MAX_PLAYLISTS playlists" }
        val list = SharedGroups.create(env, name, folder, folderName, myName, p.displayName, null, transport, ntfyAuth)
        val c = SharedCollection(
            list.id, list, SharedCollection.ALL, listOf(service), keepMine = false,
            todo = playlists.distinctBy { it.id }.map { SharedCollection.Todo(PlaylistRef(service, it.id, it.name), maxOf(it.trackCount, 0)) },
            autoAdd = autoAdd, seen = seen + playlists.map { it.id }, checkedAt = SharedGroups.clock(),
        )
        env.store.update { d -> d.copy(collections = d.collections.filter { it.id != c.id } + c) }
        env.log("shared", "${c.name}: collection created, ${c.todo.size} playlists to share")
        return c to settle(env, c.id, read = false)
    }

    /**
     * Joins the collection of [invite]: its list only, which says what playlists there are. Nothing
     * is made on any service until the member chooses ([choose]); a member who then goes back
     * [leave]s. One the app closed on at that point is found again, still waiting for the choice.
     */
    fun join(env: Env, invite: Invite, folder: String, folderName: String, myName: String, ntfyAuth: String? = null): SharedCollection {
        check(Features.v2) { "Not available in this version" }
        require(invite.collection) { "Not a collection's invite" }
        get(env, invite.groupId)?.let { return it }
        if (SharedGroups.group(env, invite.groupId) != null) throw ProviderException(env.text("shared_error_already", invite.name))
        val (list, _) = SharedGroups.join(env, invite, folder, folderName, myName, "", null, ntfyAuth)
        val c = SharedCollection(list.id, list, checkedAt = SharedGroups.clock())
        env.store.update { d -> d.copy(collections = d.collections.filter { it.id != c.id } + c) }
        env.log("shared", "${c.name}: collection joined, waiting for the member's choice")
        return c
    }

    /**
     * The follower's choice: every playlist ([SharedCollection.ALL]) or those in [picked], on
     * [services] (several: each playlist is kept the same on all of them), keeping their own
     * additions or not. Stored first, then set up ([settle]): what fails is still wanted.
     */
    suspend fun choose(env: Env, id: String, mode: String, services: List<String>, keepMine: Boolean, picked: Set<String>): Settled {
        require(mode == SharedCollection.ALL || mode == SharedCollection.PICK) { "All or a selection" }
        require(services.isNotEmpty() && services.toSet().size == services.size) { "At least one service, each once" }
        val c = get(env, id) ?: throw ProviderException(env.text("shared_error_gone"))
        val all = entries(env, c).mapTo(LinkedHashSet()) { it.groupId }
        put(env, id) { it.copy(mode = mode, services = services, keepMine = keepMine, on = if (mode == SharedCollection.ALL) all else picked intersect all, known = all) }
        return settle(env, id, read = false)
    }

    /** All of them from now on (those switched off and those added later too), or only the ones on now. */
    suspend fun setMode(env: Env, id: String, mode: String): Settled {
        require(mode == SharedCollection.ALL || mode == SharedCollection.PICK)
        put(env, id) { it.copy(mode = mode) }
        return settle(env, id, read = false)
    }

    /**
     * A playlist's switch. Off: its syncs stop and the member's playlist stays as it is (a
     * follower who picks; with "All" the choice becomes a selection). On: it syncs again, or is
     * set up when it never was.
     */
    suspend fun setOn(env: Env, id: String, groupId: String, on: Boolean): Settled {
        put(env, id) { c ->
            c.copy(
                on = if (on) c.on + groupId else c.on - groupId, known = c.known + groupId,
                mode = if (!on && c.mode == SharedCollection.ALL) SharedCollection.PICK else c.mode,
            )
        }
        return settle(env, id, read = false)
    }

    /** A proposal answered: the playlist is taken ([settle] sets it up) or left out, and not proposed again. */
    suspend fun answer(env: Env, id: String, groupId: String, take: Boolean): Settled {
        put(env, id) { c -> c.copy(known = c.known + groupId, on = if (take) c.on + groupId else c.on) }
        return if (take) settle(env, id, read = false) else Settled()
    }

    /** A follower's choice for every playlist of the collection, those taken later too: keep their own additions, or an exact copy. */
    fun setKeepMine(env: Env, id: String, keep: Boolean) {
        val c = put(env, id) { it.copy(keepMine = keep) } ?: return
        groups(env, c).forEach { SharedGroups.setKeepMine(env, it, keep) }
    }

    // ---------------------------------------------------------------- the curator's side

    /** The creator adds [playlists] of their service to the collection: asked for, then shared one by one. */
    suspend fun add(env: Env, id: String, playlists: List<Playlist>): Settled {
        val c = get(env, id)?.takeIf { it.createdHere } ?: throw ProviderException(env.text("shared_error_not_creator"))
        val service = c.services.first()
        val shared = jobs(env, c).mapNotNull { it.target.takeIf { t -> t.provider == service }?.playlistId }.toSet() + c.todo.mapNotNull { it.playlist.playlistId }
        val fresh = playlists.distinctBy { it.id }.filter { it.id !in shared }
        require(entries(env, c).size + c.todo.size + fresh.size <= MAX_PLAYLISTS) { "A collection holds up to $MAX_PLAYLISTS playlists" }
        put(env, id) { it.copy(todo = it.todo + fresh.map { p -> SharedCollection.Todo(PlaylistRef(service, p.id, p.name), maxOf(p.trackCount, 0)) }, seen = it.seen + fresh.map { p -> p.id }) }
        return settle(env, id, read = false)
    }

    /**
     * The creator takes a playlist out: the list says so (those who follow stop syncing it and keep
     * their playlist), and its syncs here go. The playlist on the creator's service stays as it is.
     */
    suspend fun remove(env: Env, id: String, groupId: String): Settled = lockOf(id).withLock {
        val c = get(env, id)?.takeIf { it.createdHere } ?: throw ProviderException(env.text("shared_error_not_creator"))
        val entry = entries(env, c).firstOrNull { it.groupId == groupId } ?: return@withLock Settled()
        SharedGroups.remove(env, c.list, listOf(Track(id = KEY + groupId, title = entry.name)))
        val gone = SharedGroups.group(env, groupId)?.takeIf { it.collectionId == id }?.let { SharedGroups.leave(env, it) }.orEmpty()
        put(env, id) { it.copy(on = it.on - groupId) }
        env.log("shared", "${c.name}: \"${entry.name}\" taken out of the collection")
        Settled(gone = gone, removed = listOf(entry.name))
    }

    fun setAutoAdd(env: Env, id: String, on: Boolean, seen: Set<String>) {
        put(env, id) { it.copy(autoAdd = on, seen = if (on) it.seen + seen else it.seen) }
    }

    // ---------------------------------------------------------------- settling

    private fun has(jobs: List<SyncJob>, groupId: String, service: String) =
        jobs.any { it.source.provider == SharedProvider.ID && it.source.playlistId == groupId && it.target.provider == service }

    /** [c]'s list is due to be read for news by a background sync of one of its playlists. */
    fun due(c: SharedCollection, now: Long = SharedGroups.clock()): Boolean = c.chosen && !c.leaving && now - c.checkedAt >= CHECK_EVERY_MS

    /**
     * Brings what exists in line with what was chosen, one playlist at a time. With [read], the
     * list is read first (and written: a creator's entry that did not go out before goes out now);
     * that failing, nothing is touched and the error is the answer's. Then:
     *
     *  - the creator: each playlist still to share becomes a group of its own and an entry of the list;
     *  - a follower: with "All", playlists added since are taken; each wanted playlist gets, on each
     *    chosen service, the member's playlist of that name (made when there is none) and its syncs;
     *    one switched off has its syncs off; one the curator took out is left, the playlist staying.
     *
     * Every step looks at what is there first, so running it again after any stop (an error, the
     * app closed) carries on where it stopped, and makes no playlist and no sync twice. One run at
     * a time per collection.
     */
    suspend fun settle(env: Env, id: String, read: Boolean = true): Settled = lockOf(id).withLock {
        var c = get(env, id) ?: return@withLock Settled()
        if (c.leaving) return@withLock Settled(gone = finishLeaving(env, c))
        if (!c.chosen) return@withLock Settled()
        val state = if (read) SharedGroups.refresh(env, c.list) else SharedGroups.load(env, id)
        if (read) c = put(env, id) { it.copy(checkedAt = SharedGroups.clock()) } ?: return@withLock Settled()
        if (c.createdHere) share(env, c) else follow(env, c, state)
    }

    /** The creator's playlists still to share. */
    private suspend fun share(env: Env, c0: SharedCollection): Settled {
        var c = c0
        val started = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        if (c.autoAdd) c = withNewPlaylists(env, c)
        for (todo in c.todo) {
            val ref = todo.playlist
            try {
                // Made by an earlier run that stopped before the entry was written: the same group.
                val group = groups(env, c).firstOrNull { g -> env.store.job(g.jobId)?.target?.let { it.provider == ref.provider && it.playlistId == ref.playlistId } == true }
                    ?: SharedGroups.create(
                        env, ref.playlistName.ifBlank { c.name }, c.list.folder, c.list.folderName, c.list.myName, c.list.myService, ref,
                        c.list.transport, SharedTransports.ntfyAuth(env, c.list), collectionId = c.id,
                    ).also { started += listOf(it.twinId, it.jobId) }
                if (entries(env, c).none { it.groupId == group.id }) {
                    val key = when (c.list.transport) {
                        Invite.NTFY -> SharedTransports.groupKey(env, group)
                        Invite.SERVER -> SharedTransports.mailboxKey(env, group)
                        else -> ""
                    } ?: throw ProviderException(env.text("shared_error_key", group.name))
                    SharedGroups.add(env, c.list, listOf(entryTrack(group, key, todo.tracks)))
                }
                c = put(env, c.id) { it.copy(todo = it.todo - todo, on = it.on + group.id, known = it.known + group.id) } ?: break
            } catch (e: Exception) {
                env.log("shared", "${c.name}: \"${ref.playlistName}\" not shared yet: ${e.message?.take(160)}")
                failed += ref.playlistName to (e.message ?: e.javaClass.simpleName)
            }
        }
        refreshCounts(env, c)
        return Settled(started = started, failed = failed)
    }

    /** With "Add the playlists I make later": the creator's playlists not seen before are asked for. A service that cannot be read now changes nothing. */
    private suspend fun withNewPlaylists(env: Env, c: SharedCollection): SharedCollection {
        val service = c.services.firstOrNull() ?: return c
        val p = Providers.byId(service) ?: return c
        val lists = runCatching { p.playlists(env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) } }.getOrNull() ?: return c
        // A playlist another sync fills is that sync's, not something the person made to share.
        val targets = env.store.data.jobs.filter { it.target.provider == service }.mapNotNull { it.target.playlistId }.toSet()
        val room = MAX_PLAYLISTS - entries(env, c).size - c.todo.size
        val fresh = lists.filter { it.id !in c.seen && it.id !in targets }.take(maxOf(room, 0))
        if (fresh.isNotEmpty()) env.log("shared", "${c.name}: ${fresh.size} new playlists join the collection")
        return put(env, c.id) { it.copy(seen = it.seen + lists.map { l -> l.id }, todo = it.todo + fresh.map { l -> SharedCollection.Todo(PlaylistRef(service, l.id, l.name), maxOf(l.trackCount, 0)) }) } ?: c
    }

    /** The list says how many tracks each playlist holds: said again when a playlist's own log now holds another number. Never fails a run. */
    private fun refreshCounts(env: Env, c: SharedCollection) {
        runCatching {
            val changed = entries(env, c).mapNotNull { e ->
                val g = SharedGroups.group(env, e.groupId)?.takeIf { it.collectionId == c.id } ?: return@mapNotNull null
                val n = GroupLog(g.id, g.creator).live(SharedGroups.load(env, g.id).events).size
                if (n == e.tracks || n == 0) null else entryTrack(g.copy(name = e.name), e.key, n)
            }
            if (changed.isNotEmpty()) SharedGroups.add(env, c.list, changed)
        }
    }

    private suspend fun follow(env: Env, c0: SharedCollection, state: GroupState): Settled {
        var c = c0
        val entries = entries(c, state)
        if (c.mode == SharedCollection.ALL) {
            val ids = entries.map { it.groupId }
            if (!c.on.containsAll(ids)) c = put(env, c.id) { it.copy(on = it.on + ids, known = it.known + ids) } ?: return Settled()
        }
        val started = ArrayList<String>()
        val gone = ArrayList<String>()
        val changed = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        val removed = ArrayList<String>()
        // Taken out by the curator: the syncs go, the member's playlists stay as they are.
        val out = takenOut(c, state)
        for (g in groups(env, c).filter { it.id in out }) {
            gone += SharedGroups.leave(env, g)
            removed += g.name
            env.log("shared", "${c.name}: \"${g.name}\" was taken out of the collection by its curator; your playlist stays")
        }
        if (out.any { it in c.on }) c = put(env, c.id) { it.copy(on = it.on - out) } ?: return Settled()
        // The member's playlists on each service, read once; those a playlist of the collection already uses are not free.
        val lists = HashMap<String, MutableList<Playlist>>()
        for (e in entries.filter { it.groupId in c.on }) {
            try {
                // Joined on its own before the collection came: it is the collection's now.
                SharedGroups.group(env, e.groupId)?.takeIf { it.collectionId != c.id }?.let { g ->
                    env.store.update { d -> d.copy(groups = d.groups.map { if (it.id == g.id) it.copy(collectionId = c.id) else it }) }
                }
                for (service in c.services) {
                    if (has(env.store.data.jobs, e.groupId, service)) continue
                    val p = Providers.byId(service) ?: throw ProviderException(env.text("shared_no_services"))
                    val mine = lists.getOrPut(service) { p.playlists(env).filter { it.ownedByMe && !MusicProvider.isLibrary(it.id) }.toMutableList() }
                    val used = jobs(env, c).filter { it.target.provider == service }.mapNotNull { it.target.playlistId }.toSet()
                    // A playlist of that name already there is used, its tracks staying: never a second one
                    // (also the one an earlier run made just before it stopped).
                    val local = mine.firstOrNull { it.id !in used && it.name.trim().equals(e.name.trim(), ignoreCase = true) }
                        ?: run {
                            if (!p.canCreatePlaylists) throw ProviderException(env.text("collection_error_cannot_create", p.displayName))
                            p.createPlaylist(env, e.name, MusicProvider.description("Trackhop")).also { mine += it }
                        }
                    val ref = PlaylistRef(service, local.id, local.name)
                    val group = SharedGroups.group(env, e.groupId)
                    if (group == null) {
                        val (g, _) = SharedGroups.join(
                            env, inviteOf(env, c, e), c.list.folder, c.list.folderName, c.list.myName, p.displayName, ref,
                            SharedTransports.ntfyAuth(env, c.list), collectionId = c.id, keepMine = c.keepMine,
                        )
                        started += listOf(g.jobId, g.twinId)
                    } else {
                        started += also(env, group, ref, p.displayName)
                    }
                }
            } catch (e2: Exception) {
                env.log("shared", "${c.name}: \"${e.name}\" not set up yet: ${e2.message?.take(160)}")
                failed += e.name to (e2.message ?: e2.javaClass.simpleName)
            }
        }
        // A playlist switched off stops syncing; switched on again, it syncs.
        for (g in groups(env, c)) {
            val want = g.id in c.on
            env.store.data.jobs.filter { SharedGroups.sharedSide(it) == g.id && it.enabled != want }.forEach {
                env.store.upsertJob(it.copy(enabled = want))
                changed += it.id
            }
        }
        return Settled(started, gone, changed, failed, removed)
    }

    /** One more of the member's services for [group]: a pair of syncs with the group, as a main member of a group of one's own playlists has ([Nway]). */
    private fun also(env: Env, group: SharedGroup, ref: PlaylistRef, serviceName: String): List<String> {
        val downId = UUID.randomUUID().toString()
        val upId = UUID.randomUUID().toString()
        val (down, up) = SharedGroups.jobsFor(group.copy(jobId = downId, twinId = upId), ref)
        val name = "${group.name} · $serviceName"
        env.store.update { d -> d.copy(jobs = d.jobs + down.copy(name = name) + up.copy(name = "$name ↔")) }
        return listOf(downId, upId)
    }

    /** The syncs of [c] in the order one "Sync now" takes them: each playlist's changes into its group, then the group into each playlist. */
    fun order(env: Env, c: SharedCollection): List<String> = groups(env, c).flatMap { g ->
        val jobs = env.store.data.jobs.filter { SharedGroups.sharedSide(it) == g.id && it.enabled }
        val (down, up) = jobs.partition { it.source.provider == SharedProvider.ID }
        if (g.createdHere) up + down else down + up
    }.map { it.id }

    // ---------------------------------------------------------------- leaving

    /**
     * Leaves the collection: every playlist's syncs go, then the list; the member's playlists stay
     * as they are. Marked first, so one stopped half way is finished by [recover] (or the next
     * [settle]) and never comes back half there. Answers the ids of the syncs removed.
     */
    suspend fun leave(env: Env, id: String): List<String> = lockOf(id).withLock {
        val c = put(env, id) { it.copy(leaving = true) } ?: return@withLock emptyList()
        finishLeaving(env, c)
    }

    private fun finishLeaving(env: Env, c: SharedCollection): List<String> {
        val ids = groups(env, c).flatMap { SharedGroups.leave(env, it) }
        SharedGroups.leave(env, c.list)
        env.store.update { d -> d.copy(collections = d.collections.filter { it.id != c.id }) }
        env.log("shared", "${c.name}: collection left, the playlists stay as they are")
        return ids
    }

    /** At the start of the process: a leave the app closed on is finished. Answers the ids of the syncs removed. */
    fun recover(env: Env): List<String> = all(env).filter { it.leaving }.flatMap { c ->
        env.log("shared", "${c.name}: leaving was interrupted, finished now")
        finishLeaving(env, c)
    }
}
