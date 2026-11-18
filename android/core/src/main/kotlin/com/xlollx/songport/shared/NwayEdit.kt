package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.Timing
import java.util.UUID

/**
 * A multi sync as its editor shows it (2.1.1): one source and its targets, each target with one of
 * three ways to follow. It is [Nway]'s group read another way, so that every group made before has
 * its place here and nothing is lost:
 *
 *  - the source is the member the group's card stands on ([Nway.source]): a main member;
 *  - any other main member is a target that is [Mode.TWO_WAY];
 *  - a follower is a target that is [Mode.KEEP] (it keeps what the user adds there) or [Mode.EXACT].
 *
 * Saving is one change of the store ([applied], [create]): the whole draft, or nothing. Kotlin puro:
 * coperto dai test.
 */
object NwayEdit {
    /** How a target follows: an exact copy, a copy that keeps the user's own additions, or one whose changes reach the source and the others too. */
    enum class Mode { EXACT, KEEP, TWO_WAY }

    /**
     * A playlist of the sync. [playlistId] null: a new playlist, made on its service when the draft
     * is saved. [downId]: the group's sync that brings the list to it, when it is a member already.
     */
    data class Item(val provider: String, val playlistId: String?, val name: String, val count: Int = -1, val mode: Mode = Mode.KEEP, val downId: String? = null) {
        fun same(o: Item) = provider == o.provider && playlistId != null && playlistId == o.playlistId
        val ref: PlaylistRef get() = PlaylistRef(provider, playlistId, name)
    }

    data class Draft(val name: String, val source: Item?, val targets: List<Item>) {
        val items: List<Item> get() = listOfNotNull(source) + targets

        /** "Make it the source": the target at [index] and the source trade places; the old source goes on sending its changes (two-way) until the user says otherwise. */
        fun withSource(index: Int): Draft {
            val t = targets.getOrNull(index) ?: return this
            val old = source?.copy(mode = Mode.TWO_WAY)
            return copy(source = t, targets = targets.toMutableList().also { if (old != null) it[index] = old else it.removeAt(index) })
        }
    }

    /** What keeps a draft from being saved, the first thing first; null when it can be. */
    enum class Missing { SOURCE, NEW_SOURCE, TARGET, TWICE }

    fun missing(d: Draft): Missing? = when {
        d.source == null -> Missing.SOURCE
        // A new, empty playlist has nothing to give the others.
        d.source.playlistId == null -> Missing.NEW_SOURCE
        d.targets.isEmpty() -> Missing.TARGET
        d.items.any { a -> d.items.count { it.same(a) } > 1 } -> Missing.TWICE
        else -> null
    }

    fun modeOf(m: Nway.Member): Mode = if (m.main) Mode.TWO_WAY else if (m.keepMine) Mode.KEEP else Mode.EXACT

    private fun itemOf(m: Nway.Member) = Item(m.playlist.provider, m.playlist.playlistId, m.playlist.playlistName, mode = modeOf(m), downId = m.down.id)

    /** [group] as the editor shows it: its source, then its other members as targets, in their order. */
    fun draftOf(group: SharedGroup, members: List<Nway.Member>): Draft {
        val source = Nway.source(group, members)
        return Draft(group.name, source?.let { itemOf(it) }, members.filter { it !== source }.map { itemOf(it) })
    }

    /** One line of what saving a draft changes. */
    data class Change(val item: Item, val kind: Kind, val was: Mode? = null) {
        enum class Kind { ADDED, REMOVED, SOURCE, MODE }
    }

    /** What saving [draft] changes in [group], playlist by playlist; empty when nothing does. */
    fun changes(group: SharedGroup, members: List<Nway.Member>, draft: Draft): List<Change> {
        val now = draftOf(group, members)
        val before = now.items.associateBy { it.downId }
        val kept = draft.items.mapNotNullTo(HashSet()) { it.downId }
        return buildList {
            draft.source?.let { s ->
                if (s.downId == null || s.downId !in before) add(Change(s, Change.Kind.ADDED))
                if (s.downId != now.source?.downId) add(Change(s, Change.Kind.SOURCE))
            }
            for (t in draft.targets) {
                val old = before[t.downId]
                when {
                    t.downId == null || old == null -> add(Change(t, Change.Kind.ADDED))
                    // The source that becomes a target is two-way as it was: only another way is a change.
                    old.downId == now.source?.downId -> if (t.mode != Mode.TWO_WAY) add(Change(t, Change.Kind.MODE, Mode.TWO_WAY))
                    old.mode != t.mode -> add(Change(t, Change.Kind.MODE, old.mode))
                }
            }
            now.items.filter { it.downId !in kept }.forEach { add(Change(it, Change.Kind.REMOVED)) }
        }
    }

    private fun display(provider: String) = Providers.byId(provider)?.displayName ?: provider

    /** [d] with the group called [name]: the group, its syncs' own names and the name its list goes by in them. */
    fun renamed(d: StoreData, groupId: String, name: String): StoreData {
        val g = d.groups.firstOrNull { it.id == groupId } ?: return d
        val to = name.trim().take(Invite.MAX_NAME)
        if (to.isEmpty() || to == g.name) return d
        fun ref(r: PlaylistRef) = if (r.provider == SharedProvider.ID && r.playlistId == groupId) r.copy(playlistName = to) else r
        return d.copy(
            groups = d.groups.map { if (it.id == groupId) it.copy(name = to) else it },
            jobs = d.jobs.map { j ->
                if (SharedGroups.sharedSide(j) != groupId) j
                // A name the group gave ("Mix · Spotify") follows it; one the user gave stays.
                else j.copy(name = if (j.name.startsWith(g.name)) to + j.name.removePrefix(g.name) else j.name, source = ref(j.source), target = ref(j.target))
            },
        )
    }

    /**
     * [d] with the group as [draft] says: the name, new members added, the source moved, each
     * target's way set, the members no longer in it taken out. [timed] gives every sync of the group
     * its timing, [styled] gives the card (the source's sync) its look. Every playlist of the draft
     * exists by now ([Item.playlistId]). What the draft leaves as it is stays the very same: a draft
     * read from a group and saved untouched answers [d] itself.
     */
    fun applied(d: StoreData, groupId: String, draft: Draft, timed: (SyncJob) -> SyncJob = { it }, styled: (SyncJob) -> SyncJob = { it }): StoreData {
        require(missing(draft) == null) { "Not ready to save: ${missing(draft)}" }
        require(draft.items.all { !it.playlistId.isNullOrBlank() }) { "A playlist of the draft was not made" }
        var n = renamed(d, groupId, draft.name)
        val group = n.groups.firstOrNull { it.id == groupId && Nway.isGroup(it) } ?: return d
        val existing = Nway.members(n.jobs, groupId).mapTo(HashSet()) { it.down.id }
        val like = Nway.anchor(n, group) ?: Nway.jobsOf(n, groupId).firstOrNull()
        // The syncs of the draft's playlists: a member's own, or a new one's.
        // A playlist taken out and put back (or "changed" to itself) has no sync id in the draft: it keeps its member's.
        val before = Nway.members(n.jobs, groupId)
        val claimed = draft.items.mapNotNullTo(HashSet()) { it.downId?.takeIf { id -> id in existing } }
        val ids = draft.items.associateWith { item ->
            item.downId?.takeIf { id -> id in existing }
                ?: before.firstOrNull { m -> m.down.id !in claimed && m.playlist.provider == item.provider && m.playlist.playlistId == item.playlistId }?.down?.id?.also { claimed += it }
                ?: UUID.randomUUID().toString()
        }
        val source = draft.source!!
        for (item in draft.items) if (ids.getValue(item) !in existing) {
            n = Nway.withMember(n, group, item.ref, main = item === source || item.mode == Mode.TWO_WAY, keepMine = item.mode != Mode.EXACT, like = like, downId = ids.getValue(item))
        }
        // The source first: a main member from then on, so that the one it takes the place of can change its way or go.
        val sourceId = ids.getValue(source)
        if (Nway.anchor(n, group)?.id != sourceId) n = Nway.withSource(n, groupId, sourceId)
        for (t in draft.targets) n = Nway.withRole(n, groupId, ids.getValue(t), main = t.mode == Mode.TWO_WAY, keepMine = t.mode != Mode.EXACT)
        val kept = ids.values.toSet()
        for (m in Nway.members(n.jobs, groupId)) if (m.down.id !in kept) n = Nway.withoutMember(n, groupId, m.down.id)
        n = n.copy(jobs = n.jobs.map { j -> if (SharedGroups.sharedSide(j) == groupId) timed(j).let { if (it.id == sourceId) styled(it) else it } else j })
        return if (n == d) d else n
    }

    /**
     * [d] with every sync of the group [groupId] on one timing, as one change (the group's page,
     * "Automatic schedule"): linked to the saved schedule [scheduleId] while it exists, else [timing]
     * as each sync's own, days and time of day included. On or off (Pause), Wi-Fi only and the rest stay.
     */
    fun scheduled(d: StoreData, groupId: String, scheduleId: String?, timing: Schedules.Spec): StoreData {
        val linked = Schedules.find(d, scheduleId)
        val jobs = d.jobs.map { j ->
            when {
                SharedGroups.sharedSide(j) != groupId -> j
                linked != null -> Schedules.link(j, linked)
                else -> Timing.apply(j, timing.everyMinutes, timing.at, timing.days).copy(scheduleId = null)
            }
        }
        return if (jobs == d.jobs) d else d.copy(jobs = jobs)
    }

    /**
     * A new group from [draft], as one change of the store: the group and every sync of it, or
     * nothing. Nothing is written to any service until its first run.
     */
    fun create(env: Env, draft: Draft, timed: (SyncJob) -> SyncJob = { it }, styled: (SyncJob) -> SyncJob = { it }): SharedGroup {
        // Offered from Trackhop 1.18; a group that exists keeps running without it.
        check(Features.v2) { "Not available in this version" }
        require(missing(draft) == null) { "Not ready to save: ${missing(draft)}" }
        require(draft.items.all { !it.playlistId.isNullOrBlank() }) { "A playlist of the draft was not made" }
        val source = draft.source!!
        // The group's key and its empty list first; it is in the store only with all its syncs.
        val group = SharedGroups.create(env, draft.name, "", "", "", "", null, Invite.LOCAL)
        val saved = runCatching {
            env.store.commit { d ->
                var n = d.copy(groups = d.groups.filter { it.id != group.id } + group)
                n = Nway.withMember(n, group, source.ref, main = true, downId = group.jobId, upId = group.twinId)
                for (t in draft.targets) n = Nway.withMember(n, group, t.ref, main = t.mode == Mode.TWO_WAY, keepMine = t.mode != Mode.EXACT)
                n.copy(jobs = n.jobs.map { j -> if (SharedGroups.sharedSide(j) == group.id) timed(j).let { if (it.id == group.jobId) styled(it) else it } else j })
            }
        }
        if (saved.getOrDefault(false) != true) {
            // Not saved: no key and no list left behind for a group that does not exist.
            runCatching { SharedGroups.forgetDeleted(env, group) }
            throw saved.exceptionOrNull() ?: IllegalStateException(env.text("bulk_not_saved"))
        }
        Nway.log(env, group.id, "created, ${draft.items.size} members: ${source.provider} source, " + draft.targets.joinToString(", ") { "${it.provider} ${it.mode.name.lowercase()}" })
        return group
    }

    /** One line of the log for a draft saved over [group]: what changed, by service and role. */
    fun logLine(changes: List<Change>): String = if (changes.isEmpty()) "saved, members as they were" else "saved: " + changes.joinToString(", ") { c ->
        when (c.kind) {
            Change.Kind.ADDED -> "${c.item.provider} added ${c.item.mode.name.lowercase()}"
            Change.Kind.REMOVED -> "${c.item.provider} removed"
            Change.Kind.SOURCE -> "${c.item.provider} is the source"
            Change.Kind.MODE -> "${c.item.provider} ${c.was?.name?.lowercase()} -> ${c.item.mode.name.lowercase()}"
        }
    }
}
