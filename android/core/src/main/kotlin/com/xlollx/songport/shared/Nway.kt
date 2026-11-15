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
 *  - A member that follows is one sync, hub into playlist, mirroring removals: an exact copy, whose
 *    own changes go nowhere.
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
    }

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
     * the main members' tracks are merged into the hub, and every member gets the hub's.
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

    /** Adds [ref] to [group]. A main member's own tracks join the group on the next run; a follower becomes a copy of it. */
    fun add(env: Env, group: SharedGroup, ref: PlaylistRef, main: Boolean, schedule: Schedule = Schedule.EVERY_6H) {
        check(Features.v2) { "Not available in this version" }
        usable(ref)
        require(members(env, group).none { it.playlist.provider == ref.provider && it.playlist.playlistId == ref.playlistId }) { "Already in the group" }
        put(env, group, ref, main, schedule)
    }

    /** Takes [member] out of [group]; its playlist stays as it is. Answers the ids of the syncs removed, for the platform to cancel their schedules. */
    fun remove(env: Env, group: SharedGroup, member: Member): List<String> {
        require(!member.main || members(env, group).count { it.main } > 1) { "A group needs a main member" }
        return listOfNotNull(member.down.id, member.up?.id).onEach { env.store.deleteJob(it) }
    }

    /** Makes [member] a main member or a follower. The sync that brings the hub in stays the same one (its review, its history). */
    fun setMain(env: Env, group: SharedGroup, member: Member, main: Boolean) {
        if (member.main == main) return
        val down = env.store.job(member.down.id) ?: return
        if (main) {
            val upId = UUID.randomUUID().toString()
            val up = SharedGroups.jobsFor(group.copy(jobId = down.id, twinId = upId), down.target).second
            env.store.upsertJob(down.copy(linkedJobId = upId, twoWayRemovals = true, mirrorRemovals = false))
            env.store.upsertJob(up.copy(name = "${down.name} ↔", schedule = down.schedule))
        } else {
            require(members(env, group).count { it.main } > 1) { "A group needs a main member" }
            member.up?.let { env.store.deleteJob(it.id) }
            env.store.upsertJob(down.copy(linkedJobId = null, twoWayRemovals = false, mirrorRemovals = true))
        }
    }

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
    }

    private fun usable(ref: PlaylistRef) {
        require(!ref.playlistId.isNullOrBlank()) { "A member is an existing playlist" }
        require(ref.provider != SharedProvider.ID) { "A group cannot be a member" }
    }

    /** Writes the syncs of one member: a main member's pair, or a follower's one mirror. */
    private fun put(
        env: Env, group: SharedGroup, ref: PlaylistRef, main: Boolean, schedule: Schedule,
        downId: String = UUID.randomUUID().toString(), upId: String = UUID.randomUUID().toString(),
    ) {
        val (down, up) = SharedGroups.jobsFor(group.copy(jobId = downId, twinId = upId), ref)
        // Told apart in reports and notifications by the member's service.
        val name = "${group.name} · ${Providers.byId(ref.provider)?.displayName ?: ref.provider}"
        val jobs = if (main) listOf(down.copy(name = name, schedule = schedule), up.copy(name = "$name ↔", schedule = schedule))
            else listOf(down.copy(name = name, schedule = schedule, linkedJobId = null, twoWayRemovals = false, mirrorRemovals = true))
        env.store.update { d -> d.copy(jobs = d.jobs.filter { it.id != downId && it.id != upId } + jobs) }
    }
}
