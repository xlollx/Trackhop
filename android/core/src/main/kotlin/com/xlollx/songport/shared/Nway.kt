package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Progress
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncEngine
import java.util.UUID

/**
 * One playlist kept the same on three or more of the user's own services. It is a shared playlist
 * with no one else in it: a group on this device only ([Invite.LOCAL]), whose list is the hub, and
 * one member per playlist, each synced with the hub by the ordinary syncs a shared playlist uses.
 *
 *  - A main member is a two-way pair with removals (hub into playlist, playlist into hub): what is
 *    added or removed there reaches the hub, and from the hub every other member.
 *  - A member that follows is one sync, hub into playlist; its own changes go nowhere. It keeps
 *    what the user adds there, and loses only what the hub gave it once the hub drops it ("Keep my
 *    additions", the default), or is an exact copy (mirroring removals).
 *
 * Members never sync with each other, only with the hub, so a change has one way to each playlist
 * and nothing bounces (chained pairs A→B, B→C, C→A echo because they make a circle). The hub lists
 * tracks by name and ISRC, not by a service's id: a song one service does not have stays in the hub
 * and on the others, and waits in that member's review.
 */
object Nway {
    /** A playlist of the group: [down] brings the hub into it, [up] (main members only) sends its changes to the hub. */
    data class Member(val playlist: PlaylistRef, val down: SyncJob, val up: SyncJob?) {
        val main: Boolean get() = up != null
        /** A follower that keeps the user's own additions; false for an exact copy and for a main member. */
        val keepMine: Boolean get() = up == null && down.twoWayRemovals
    }

    /**
     * [job] brings an N-way hub into a follower that keeps its additions: marked by two-way removals
     * with no other half, which no other sync has. The engine then runs it as the half of a pair
     * whose way back never runs: nothing of the playlist reaches the hub.
     */
    fun keepsMine(env: Env, job: SyncJob): Boolean = job.twoWayRemovals && job.linkedJobId == null && isHub(env, job.source)

    fun isGroup(group: SharedGroup): Boolean = group.transport == Invite.LOCAL

    /** [ref] is the hub of an N-way group. */
    fun isHub(env: Env, ref: PlaylistRef): Boolean =
        ref.provider == SharedProvider.ID && ref.playlistId?.let { SharedGroups.group(env, it) }?.let { isGroup(it) } == true

    fun groups(env: Env): List<SharedGroup> = env.store.data.groups.filter { isGroup(it) }

    fun members(env: Env, group: SharedGroup): List<Member> = members(env.store.data.jobs, group.id)

    /** The members of the group [groupId] among [all] the syncs, in the list's order. */
    fun members(all: List<SyncJob>, groupId: String): List<Member> {
        val jobs = all.filter { SharedGroups.sharedSide(it) == groupId }
        return jobs.filter { it.source.provider == SharedProvider.ID }
            .map { down -> Member(down.target, down, jobs.firstOrNull { it.id == down.linkedJobId }) }
    }

    /**
     * The member the group's one card stands for, its source: the one of [SharedGroup.jobId] (the
     * sync that brings the group to it) while it is still a main member, else the first main one.
     */
    fun source(group: SharedGroup, members: List<Member>): Member? =
        members.firstOrNull { it.down.id == group.jobId && it.main } ?: members.firstOrNull { it.main }

    // ------------------------------------------------------------------ the card's sync
    //
    // In the Syncs list a group is one card like any other: the sync that brings the group to its
    // source (the "anchor") stands for it there, with its colour, style, label, tags, star and place
    // in the list. The group's other syncs are behind it.

    /** The sync that stands for [group] in the list: its source's, when the group has one. */
    fun anchor(d: StoreData, group: SharedGroup): SyncJob? = source(group, members(d.jobs, group.id))?.down

    /** Every group of the user's own playlists by the id of the sync that stands for it. */
    fun anchors(d: StoreData): Map<String, SharedGroup> =
        d.groups.filter { isGroup(it) }.mapNotNull { g -> anchor(d, g)?.let { it.id to g } }.toMap()

    /** The group [job] stands for in the list; null for any other sync. */
    fun anchored(d: StoreData, job: SyncJob): SharedGroup? {
        val gid = SharedGroups.sharedSide(job) ?: return null
        val g = d.groups.firstOrNull { it.id == gid && isGroup(it) } ?: return null
        return g.takeIf { anchor(d, it)?.id == job.id }
    }

    /** The syncs of [groupId], the one that stands for it included. */
    fun jobsOf(d: StoreData, groupId: String): List<SyncJob> = d.jobs.filter { SharedGroups.sharedSide(it) == groupId }

    /** What a card looks like and where it is: this is what follows the source when it changes. */
    private fun hasLook(j: SyncJob) = j.color != null || j.label != null || j.tags.isNotEmpty() || j.accentStyle != null || j.routeGlow != null || j.favorite

    private fun styled(j: SyncJob, like: SyncJob?) = j.copy(
        color = like?.color, label = like?.label, tags = like?.tags.orEmpty(), accentStyle = like?.accentStyle, routeGlow = like?.routeGlow, favorite = like?.favorite ?: false,
    )

    /**
     * [d] with the sync [toDownId] standing for the group in place of the one that did: it takes the
     * card's colour, style, label, tags, star and place in the list, and the group points to it.
     */
    fun reanchored(d: StoreData, groupId: String, toDownId: String): StoreData {
        val g = d.groups.firstOrNull { it.id == groupId } ?: return d
        val to = d.jobs.firstOrNull { it.id == toDownId && SharedGroups.sharedSide(it) == groupId } ?: return d
        val twin = to.linkedJobId ?: g.twinId
        val groups = d.groups.map { if (it.id == groupId) it.copy(jobId = to.id, twinId = twin) else it }
        val named = d.jobs.firstOrNull { it.id == g.jobId && SharedGroups.sharedSide(it) == groupId }
        // A group from 2.1.0 may still name a follower while its card stands on another sync: the look is
        // taken from the sync that has one, and a card already on [to] only gets the group pointing to it.
        val from = named?.takeIf { hasLook(it) }
            ?: d.jobs.firstOrNull { it.id != to.id && SharedGroups.sharedSide(it) == groupId && it.target.provider != SharedProvider.ID && hasLook(it) }
            ?: named
        if (from == null || from.id == to.id || (!hasLook(from) && hasLook(to))) return d.copy(groups = groups)
        val moved = d.jobs.filter { it.id != to.id }.flatMap { if (it.id == from.id) listOf(styled(to, from), styled(from, null)) else listOf(it) }
        return d.copy(groups = groups, jobs = moved)
    }

    /** [d] with the group pointing to its source ([source]): after a member left or changed its role. */
    fun settled(d: StoreData, groupId: String): StoreData {
        val g = d.groups.firstOrNull { it.id == groupId } ?: return d
        val src = source(g, members(d.jobs, groupId)) ?: return d
        return if (src.down.id == g.jobId && (src.up?.id ?: g.twinId) == g.twinId) d else reanchored(d, groupId, src.down.id)
    }

    // ------------------------------------------------------------------ changes, each one change of the store
    //
    // Each answers the whole data with the change made, for FileStore.update or commit: a member
    // comes, goes or changes its role with all its syncs or with none.

    /** When a member's syncs run: all the syncs of a group share it. */
    private fun timed(j: SyncJob, like: SyncJob?): SyncJob = if (like == null) j else j.copy(
        schedule = like.schedule, everyMinutes = like.everyMinutes, at = like.at, days = like.days, scheduleId = like.scheduleId,
        wifiOnly = like.wifiOnly, enabled = like.enabled, scheduledOn = like.scheduledOn,
    )

    /**
     * [d] with one more member of [group], [ref]: a main member's pair, or a follower's one sync
     * ([keepMine] or an exact copy). It runs when [like] does (a sync of the group), else by [schedule].
     */
    fun withMember(
        d: StoreData, group: SharedGroup, ref: PlaylistRef, main: Boolean, keepMine: Boolean = true, like: SyncJob? = null, schedule: Schedule = Schedule.EVERY_6H,
        downId: String = UUID.randomUUID().toString(), upId: String = UUID.randomUUID().toString(),
    ): StoreData {
        usable(ref)
        require(members(d.jobs, group.id).none { it.down.id != downId && it.playlist.provider == ref.provider && it.playlist.playlistId == ref.playlistId }) { "Already in the group" }
        val (down, up) = SharedGroups.jobsFor(group.copy(jobId = downId, twinId = upId), ref)
        // Told apart in reports and notifications by the member's service.
        val name = "${group.name} · ${Providers.byId(ref.provider)?.displayName ?: ref.provider}"
        val jobs = if (main) listOf(timed(down.copy(name = name, schedule = schedule), like), timed(up.copy(name = "$name ↔", schedule = schedule), like))
            else listOf(timed(follower(down.copy(name = name, schedule = schedule), keepMine), like))
        return d.copy(jobs = d.jobs.filter { it.id != downId && it.id != upId } + jobs)
    }

    /** [d] without the member whose sync is [downId]; the group then stands on its source ([settled]). A group keeps a main member. */
    fun withoutMember(d: StoreData, groupId: String, downId: String): StoreData {
        val all = members(d.jobs, groupId)
        val m = all.firstOrNull { it.down.id == downId } ?: return d
        require(!m.main || all.count { it.main } > 1) { "A group needs a main member" }
        val g = d.groups.firstOrNull { it.id == groupId }
        // The card's style and place go to the next source before its sync goes.
        val next = if (g != null && g.jobId == downId) all.firstOrNull { it.main && it.down.id != downId }?.let { reanchored(d, groupId, it.down.id) } ?: d else d
        return settled(listOfNotNull(m.down.id, m.up?.id).fold(next) { acc, id -> FileStore.withoutJob(acc, id) }, groupId)
    }

    /**
     * [d] with the member whose sync is [downId] a main member or a follower ([keepMine] or an exact
     * copy). The sync that brings the hub in stays the same one (its review, its history).
     */
    fun withRole(d: StoreData, groupId: String, downId: String, main: Boolean, keepMine: Boolean = true, upId: String = UUID.randomUUID().toString()): StoreData {
        val g = d.groups.firstOrNull { it.id == groupId } ?: return d
        val all = members(d.jobs, groupId)
        val m = all.firstOrNull { it.down.id == downId } ?: return d
        val down = m.down
        // What a follower remembered of the group, under its own id alone, is not the new role's state.
        fun forgotten(x: StoreData) = x.copy(pairStates = x.pairStates - FileStore.pairKey(down.id, down.linkedJobId ?: down.id))
        return when {
            main && !m.main -> {
                val up = SharedGroups.jobsFor(g.copy(jobId = down.id, twinId = upId), down.target).second
                val n = forgotten(d)
                // Its other half right after it, behind the same card.
                n.copy(jobs = n.jobs.flatMap {
                    if (it.id == down.id) listOf(down.copy(linkedJobId = upId, twoWayRemovals = true, mirrorRemovals = false), timed(up.copy(name = "${down.name} ↔"), down)) else listOf(it)
                })
            }
            !main && m.main -> {
                require(all.count { it.main } > 1) { "A group needs a main member" }
                val n = m.up?.let { FileStore.withoutJob(d, it.id) } ?: d
                settled(n.copy(jobs = n.jobs.map { if (it.id == down.id) follower(down, keepMine) else it }), groupId)
            }
            // Kept again later, it starts from what the playlist and the group hold then.
            !main && m.keepMine != keepMine -> forgotten(d).let { n -> n.copy(jobs = n.jobs.map { if (it.id == down.id) follower(down, keepMine) else it }) }
            else -> d
        }
    }

    /**
     * [d] with the member whose sync is [downId] as the group's source: a main member if it was not,
     * and the one the card stands on. The source it takes the place of stays a main member (what
     * changes there still reaches the others) until the user says otherwise.
     */
    fun withSource(d: StoreData, groupId: String, downId: String): StoreData = reanchored(withRole(d, groupId, downId, main = true), groupId, downId)

    /**
     * A new group of [members] (a playlist, and whether it is a main member), each an existing
     * playlist; at least one is main. Nothing is written to any service until the first run: then
     * the main members' tracks are merged into the hub, and every member gets the hub's. Followers
     * keep their additions; [setKeepMine] makes one an exact copy.
     */
    fun create(env: Env, name: String, members: List<Pair<PlaylistRef, Boolean>>, schedule: Schedule = Schedule.EVERY_6H): SharedGroup {
        // Offered from Trackhop 1.18; a group that exists keeps running without it.
        check(Features.v2) { "Not available in this version" }
        val first = requireNotNull(members.firstOrNull { it.second }) { "A group needs a main member" }
        members.forEach { usable(it.first) }
        require(members.map { it.first.provider to it.first.playlistId }.toSet().size == members.size) { "The same playlist twice" }
        val group = SharedGroups.create(env, name, "", "", "", "", first.first, Invite.LOCAL)
        // The first main member's pair and all the others, as one change.
        env.store.update { d ->
            val start = withMember(d, group, first.first, true, schedule = schedule, downId = group.jobId, upId = group.twinId)
            members.filter { it !== first }.fold(start) { acc, (ref, main) -> withMember(acc, group, ref, main, schedule = schedule) }
        }
        log(env, group.id, "created, ${members.size} members: " + members.joinToString(", ") { (ref, main) -> "${ref.provider} ${role(main, true)}" })
        return group
    }

    /**
     * Adds [ref] to [group]. A main member's own tracks join the group on the next run; a follower gets the group's, and keeps its own unless [keepMine] is off.
     * It runs as the group does ([anchor]): its timing, days and time, saved schedule, Wi-Fi only, and paused when the group is; [schedule] only for a group with no sync.
     */
    fun add(env: Env, group: SharedGroup, ref: PlaylistRef, main: Boolean, schedule: Schedule = Schedule.EVERY_6H, keepMine: Boolean = true) {
        check(Features.v2) { "Not available in this version" }
        usable(ref)
        require(members(env, group).none { it.playlist.provider == ref.provider && it.playlist.playlistId == ref.playlistId }) { "Already in the group" }
        env.store.update { d -> withMember(d, group, ref, main, keepMine, like = anchor(d, group) ?: jobsOf(d, group.id).firstOrNull(), schedule = schedule) }
        log(env, group.id, "member added: ${ref.provider} ${role(main, keepMine)}")
    }

    /** Takes [member] out of [group]; its playlist stays as it is. Answers the ids of the syncs removed, for the platform to cancel their schedules. */
    fun remove(env: Env, group: SharedGroup, member: Member): List<String> {
        require(!member.main || members(env, group).count { it.main } > 1) { "A group needs a main member" }
        env.store.update { d -> withoutMember(d, group.id, member.down.id) }
        log(env, group.id, "member removed: ${member.playlist.provider} ${role(member)}")
        return listOfNotNull(member.down.id, member.up?.id)
    }

    /** Makes [member] a main member or a follower ([keepMine] or an exact copy). The sync that brings the hub in stays the same one (its review, its history). */
    fun setMain(env: Env, group: SharedGroup, member: Member, main: Boolean, keepMine: Boolean = true) {
        if (member.main == main) return
        env.store.job(member.down.id) ?: return
        if (!main) require(members(env, group).count { it.main } > 1) { "A group needs a main member" }
        env.store.update { d -> withRole(d, group.id, member.down.id, main, keepMine) }
        log(env, group.id, "role changed: ${member.playlist.provider} ${role(member)} -> ${role(main, keepMine)}")
    }

    /** A follower's choice: keep the user's own additions there ([keep]) or an exact copy of the group. */
    fun setKeepMine(env: Env, member: Member, keep: Boolean) {
        if (member.main || member.keepMine == keep) return
        val down = env.store.job(member.down.id) ?: return
        val gid = SharedGroups.sharedSide(down) ?: return
        env.store.update { d -> withRole(d, gid, down.id, main = false, keepMine = keep) }
        log(env, gid, "role changed: ${member.playlist.provider} ${role(member)} -> ${role(false, keep)}")
    }

    /** Makes [member] the group's source ([withSource]). */
    fun setSource(env: Env, group: SharedGroup, member: Member) {
        env.store.update { d -> withSource(d, group.id, member.down.id) }
        log(env, group.id, "source changed: ${member.playlist.provider}, was ${role(member)}")
    }

    /** A member's role as the log says it: main, or a follower that keeps its additions or is an exact copy. */
    fun role(main: Boolean, keepMine: Boolean): String = if (main) "main" else if (keepMine) "follows-keep" else "follows-exact"
    fun role(member: Member): String = role(member.main, member.keepMine)

    /** One line of the diagnostic log about a group: by its id and its services, never by a playlist's name. */
    fun log(env: Env, groupId: String, message: String) = env.log("shared", "multi sync group=$groupId: $message")

    /** Brings in the local groups of a configuration file ([com.xlollx.songport.data.ConfigBackup]); one this device still holds the key of stays as it is. */
    fun restore(env: Env, groups: List<SharedGroup>) {
        // A file can say anything: the id names the group's file on this device.
        for (g in groups.filter { isGroup(it) && GROUP_ID.matches(it.id) }) {
            if (SharedGroups.group(env, g.id) == null || env.tokens.get(SharedGroups.tokenKey(g.id)) == null) SharedGroups.restoreLocal(env, g)
        }
    }

    private val GROUP_ID = Regex("[0-9a-f]{16}")

    private fun follower(down: SyncJob, keepMine: Boolean) = down.copy(linkedJobId = null, twoWayRemovals = keepMine, mirrorRemovals = !keepMine)

    /** Ends the group: its syncs and its list go, every playlist stays as it is. Answers the ids of the syncs removed. */
    fun delete(env: Env, group: SharedGroup): List<String> = SharedGroups.leave(env, group)

    /**
     * The group's syncs in the order one run takes them: every main member's changes into the hub
     * first, then the hub into every member, so one pass carries a change everywhere. Each is an
     * ordinary sync and may also run alone, on its own schedule: the group then settles a run later.
     * Paused syncs are left out, but for a run asked for by hand ([manual]): "Sync now" runs a paused
     * group whole, as it runs a paused classic sync; only its schedule waits.
     */
    fun order(env: Env, group: SharedGroup, manual: Boolean = false): List<SyncJob> =
        members(env, group).let { all -> all.mapNotNull { it.up } + all.map { it.down } }.filter { manual || it.enabled }

    /** "Sync now" on the group: one report per sync run, in [order]. */
    suspend fun run(
        env: Env, engine: SyncEngine, group: SharedGroup, unattended: Boolean = false, onProgress: (SyncJob, Progress) -> Unit = { _, _ -> },
    ): List<SyncReport> = order(env, group, manual = !unattended).map { j ->
        // Read again: an earlier sync of this run may have written to it (its pair's other half).
        val job = env.store.job(j.id) ?: j
        engine.run(job, unattended) { onProgress(job, it) }
    }.also { reports ->
        log(env, group.id, "${reports.size} syncs run: +${reports.sumOf { it.added }} -${reports.sumOf { it.removed }}, " +
            "${reports.sumOf { it.unmatched.size }} not found, ${reports.sumOf { it.heldBack }} removals held back, ${reports.count { !it.ok }} failed")
    }

    private fun usable(ref: PlaylistRef) {
        require(!ref.playlistId.isNullOrBlank()) { "A member is an existing playlist" }
        require(ref.provider != SharedProvider.ID) { "A group cannot be a member" }
    }
}
