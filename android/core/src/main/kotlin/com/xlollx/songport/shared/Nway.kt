package com.xlollx.songport.shared

import com.xlollx.songport.Features
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

    fun members(env: Env, group: SharedGroup): List<Member> {
        val jobs = env.store.data.jobs.filter { SharedGroups.sharedSide(it) == group.id }
        return jobs.filter { it.source.provider == SharedProvider.ID }
            .map { down -> Member(down.target, down, jobs.firstOrNull { it.id == down.linkedJobId }) }
    }

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
        put(env, group, first.first, true, schedule, group.jobId, group.twinId)
        members.filter { it !== first }.forEach { (ref, main) -> put(env, group, ref, main, schedule) }
        return group
    }

    /** Adds [ref] to [group]. A main member's own tracks join the group on the next run; a follower gets the group's, and keeps its own unless [keepMine] is off. */
    fun add(env: Env, group: SharedGroup, ref: PlaylistRef, main: Boolean, schedule: Schedule = Schedule.EVERY_6H, keepMine: Boolean = true) {
        check(Features.v2) { "Not available in this version" }
        usable(ref)
        require(members(env, group).none { it.playlist.provider == ref.provider && it.playlist.playlistId == ref.playlistId }) { "Already in the group" }
        put(env, group, ref, main, schedule, keepMine = keepMine)
    }

    /** Takes [member] out of [group]; its playlist stays as it is. Answers the ids of the syncs removed, for the platform to cancel their schedules. */
    fun remove(env: Env, group: SharedGroup, member: Member): List<String> {
        require(!member.main || members(env, group).count { it.main } > 1) { "A group needs a main member" }
        return listOfNotNull(member.down.id, member.up?.id).onEach { env.store.deleteJob(it) }
    }

    /** Makes [member] a main member or a follower ([keepMine] or an exact copy). The sync that brings the hub in stays the same one (its review, its history). */
    fun setMain(env: Env, group: SharedGroup, member: Member, main: Boolean, keepMine: Boolean = true) {
        if (member.main == main) return
        val down = env.store.job(member.down.id) ?: return
        if (main) {
            // What the follower remembered of the group is not the new pair's state.
            env.store.dropPairState(down)
            val upId = UUID.randomUUID().toString()
            val up = SharedGroups.jobsFor(group.copy(jobId = down.id, twinId = upId), down.target).second
            env.store.upsertJob(down.copy(linkedJobId = upId, twoWayRemovals = true, mirrorRemovals = false))
            env.store.upsertJob(up.copy(name = "${down.name} ↔", schedule = down.schedule))
        } else {
            require(members(env, group).count { it.main } > 1) { "A group needs a main member" }
            member.up?.let { env.store.deleteJob(it.id) }
            env.store.upsertJob(follower(down, keepMine))
        }
    }

    /** A follower's choice: keep the user's own additions there ([keep]) or an exact copy of the group. */
    fun setKeepMine(env: Env, member: Member, keep: Boolean) {
        if (member.main || member.keepMine == keep) return
        val down = env.store.job(member.down.id) ?: return
        // Kept again later, it starts from what the playlist and the group hold then.
        env.store.dropPairState(down)
        env.store.upsertJob(follower(down, keep))
    }

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
     */
    fun order(env: Env, group: SharedGroup): List<SyncJob> =
        members(env, group).let { all -> all.mapNotNull { it.up } + all.map { it.down } }.filter { it.enabled }

    /** "Sync now" on the group: one report per sync run, in [order]. */
    suspend fun run(
        env: Env, engine: SyncEngine, group: SharedGroup, unattended: Boolean = false, onProgress: (SyncJob, Progress) -> Unit = { _, _ -> },
    ): List<SyncReport> = order(env, group).map { j ->
        // Read again: an earlier sync of this run may have written to it (its pair's other half).
        val job = env.store.job(j.id) ?: j
        engine.run(job, unattended) { onProgress(job, it) }
    }.also { reports ->
        env.log("shared", "${group.name}: on several services, ${reports.size} syncs run: +${reports.sumOf { it.added }} -${reports.sumOf { it.removed }}, " +
            "${reports.sumOf { it.unmatched.size }} not found, ${reports.sumOf { it.heldBack }} removals held back, ${reports.count { !it.ok }} failed")
    }

    private fun usable(ref: PlaylistRef) {
        require(!ref.playlistId.isNullOrBlank()) { "A member is an existing playlist" }
        require(ref.provider != SharedProvider.ID) { "A group cannot be a member" }
    }

    /** Writes the syncs of one member: a main member's pair, or a follower's one mirror. */
    private fun put(
        env: Env, group: SharedGroup, ref: PlaylistRef, main: Boolean, schedule: Schedule,
        downId: String = UUID.randomUUID().toString(), upId: String = UUID.randomUUID().toString(), keepMine: Boolean = true,
    ) {
        val (down, up) = SharedGroups.jobsFor(group.copy(jobId = downId, twinId = upId), ref)
        // Told apart in reports and notifications by the member's service.
        val name = "${group.name} · ${Providers.byId(ref.provider)?.displayName ?: ref.provider}"
        val jobs = if (main) listOf(down.copy(name = name, schedule = schedule), up.copy(name = "$name ↔", schedule = schedule))
            else listOf(follower(down.copy(name = name, schedule = schedule), keepMine))
        env.store.update { d -> d.copy(jobs = d.jobs.filter { it.id != downId && it.id != upId } + jobs) }
    }
}
