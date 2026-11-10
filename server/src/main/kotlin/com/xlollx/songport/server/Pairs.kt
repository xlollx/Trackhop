package com.xlollx.songport.server

import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.sync.Timing
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Two-way pairs, made and unmade here as the app's editor does: a sync and its "↔" half in the
 * opposite direction ([SyncJob.linkedJobId], the second with [SyncJob.twin]), so what is added on
 * either side reaches the other. The "↔" half follows the first one's timing, colour, label, tags
 * and star; its name, ignore list and reports stay its own. With [SyncJob.twoWayRemovals] on both,
 * removals travel too. The page, the API and (through it) the command line all come through here.
 */

/** The sync [job] is the "↔" half of, or null: pairs saved before the flag are told by the name the editor gave. */
internal fun Web.twinOriginal(job: SyncJob): SyncJob? {
    val other = job.linkedJobId?.let { env.store.job(it) } ?: return null
    if (other.twin) return null
    return other.takeIf { job.twin || job.name == other.name + " ↔" }
}

/**
 * Why [job] cannot be two-way, null when it can: the app's rule (an existing destination, a source
 * of one's own that can be written to). [listed] also asks the service whether the source playlist
 * is the user's; without it only what the sync itself says is looked at (for the form).
 */
internal fun Web.twoWayProblem(job: SyncJob, listed: Boolean = true): String? {
    val unavailable = "Two-way needs an existing destination playlist and a source Trackhop can write to."
    val notOwned = "Two-way is not for a public link or someone else's playlist as the source: it can be read, not written."
    if (job.radio != null || job.shazam) return unavailable
    val src = Providers.byId(job.source.provider) ?: return unavailable
    val dst = Providers.byId(job.target.provider) ?: return unavailable
    val srcId = job.source.playlistId
    if (job.target.playlistId == null || srcId == null) return unavailable
    if (src.linkOnly || job.source.link != null) return notOwned
    if (!src.canWrite || !usable(src) || !src.supportsLibrary(srcId, asTarget = true) || !dst.supportsLibrary(job.target.playlistId, asTarget = false)) return unavailable
    if (!listed) return null
    val mine = runCatching { runBlocking { (src.libraryEntries(env, true) + src.playlists(env).filter { it.ownedByMe }).any { it.id == srcId } } }
        .getOrElse { return "${src.label(env)}: ${it.message ?: it.javaClass.simpleName}" }
    return if (mine) null else notOwned
}

/**
 * [job] paired as asked, before it is saved: [twoWay] makes or unmakes the pair (null leaves it as
 * it is), [removals] says whether removals travel too (null leaves that as it is). Nothing is
 * written: [settlePair] does the other half once [job] is saved. Throws IllegalArgumentException
 * with what to tell the user.
 */
internal fun Web.pairing(job: SyncJob, twoWay: Boolean?, removals: Boolean?): SyncJob {
    val partner = job.linkedJobId?.let { env.store.job(it) }
    val wanted = twoWay ?: (partner != null)
    if (!wanted) {
        twinOriginal(job)?.let { throw IllegalArgumentException("This is the ↔ half of \"${it.name}\": turn two-way off on that sync (${it.id}).") }
        if (removals == true) throw IllegalArgumentException("\"twoWayRemovals\" is for a two-way pair: this sync has no other half (give \"twoWay\": true to make one).")
        return job.copy(linkedJobId = null, twoWayRemovals = false)
    }
    if (partner == null) twoWayProblem(job)?.let { throw IllegalArgumentException(it) }
    val both = removals ?: (partner != null && job.twoWayRemovals && partner.twoWayRemovals)
    // An archive never removes anything, whatever the pair says.
    return job.copy(linkedJobId = partner?.id ?: UUID.randomUUID().toString(), twoWayRemovals = both && !job.archive)
}

/**
 * The other half after [saved] was written ([before]: as it was, null for a new sync): made or
 * brought in step for a pair, deleted when two-way was turned off (its runs stopped, the pair's
 * state forgotten). The playlists are never touched.
 */
internal fun Web.settlePair(before: SyncJob?, saved: SyncJob) {
    val store = env.store
    val linked = saved.linkedJobId
    if (linked == null) {
        val old = before?.linkedJobId?.let { store.job(it) } ?: return
        if (runner.isRunning(old.id)) SyncState.stop(old.id)
        // Deleting a half forgets the pair's state with it.
        store.deleteJob(old.id)
        env.log("sync", "${saved.name}: two-way turned off, ${old.name} deleted")
        return
    }
    val partner = store.job(linked)
    if (partner != null && twinOriginal(saved) != null) {
        // The "↔" half changed on its own page: only the pair's own choice is shared from here.
        if (partner.twoWayRemovals != saved.twoWayRemovals) store.upsertJob(partner.copy(twoWayRemovals = saved.twoWayRemovals))
    } else {
        val twin = (partner?.copy(
            // Renamed with this sync only when it carries the name the editor gave it.
            name = if (before != null && partner.name == "${before.name} ↔") "${saved.name} ↔" else partner.name,
            twin = partner.twin || (before != null && partner.name == "${before.name} ↔"),
        ) ?: SyncJob(id = linked, name = "${saved.name} ↔", source = saved.target, target = saved.source, twin = true)).copy(
            // What the two share; a pair's second half never mirrors removals by itself.
            mirrorRemovals = false, wifiOnly = saved.wifiOnly, enabled = saved.enabled, policy = saved.policy, linkedJobId = saved.id,
            color = saved.color, label = saved.label, tags = saved.tags, favorite = saved.favorite, accentStyle = saved.accentStyle,
            twoWayRemovals = saved.twoWayRemovals,
        ).let { Timing.apply(it, Timing.interval(saved), saved.at, saved.days) }
        store.upsertJob(twin)
        if (partner == null) env.log("sync", "${saved.name}: two-way, ${twin.name} made")
    }
    // Turned off: what the pair had in common is forgotten, so turning it on again starts afresh.
    if (!saved.twoWayRemovals) store.dropPairState(saved)
}
