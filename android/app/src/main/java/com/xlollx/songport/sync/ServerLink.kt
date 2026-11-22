package com.xlollx.songport.sync

import android.content.Context
import android.widget.Toast
import com.xlollx.songport.R
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.platform.env
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * This phone and the user's own Trackhop server, beyond taking turns on playlists (ServerLocks):
 * a sync handed over to the server and taken back, the server's syncs as last read (the cards of
 * syncs handed over show them), a run started there, and the review's choices exchanged for the
 * syncs both know. All of it only while "Coordinate with my server" has an address and a token.
 *
 * The server only reaches syncs the user handed over, or the same two playlists on both: its
 * answers are checked (ServerSyncs) and its review choices touch those syncs' two services only.
 */
object ServerLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _known = MutableStateFlow<Map<String, ServerSyncs.Sync>>(emptyMap())
    /** The server's syncs by id, as last read: empty until read once in this process. */
    val known: StateFlow<Map<String, ServerSyncs.Sync>> get() = _known

    fun api(ctx: Context): ServerSyncs? = ServerSyncs.of(ctx.env)

    fun isOn(ctx: Context): Boolean = ServerLocks.config(ctx.env) != null

    /** The server's id of [jobId] when it was handed over, else null. */
    fun handedOver(ctx: Context, jobId: String): HandedOver? = Store.get(ctx).data.handedOver[jobId]

    /** What happened, in words for the user. */
    class Outcome(val ok: Boolean, val message: String, val canBringBackHere: Boolean = false)

    /** [e] in plain words: nothing of the phone has changed when a call fails. */
    fun say(ctx: Context, e: Throwable): String = when ((e as? ServerSyncs.Failure)?.kind) {
        ServerSyncs.Failure.Kind.UNREACHABLE -> ctx.getString(R.string.server_err_unreachable)
        ServerSyncs.Failure.Kind.TOKEN -> ctx.getString(R.string.server_err_token, ctx.getString(R.string.server_locks_title))
        ServerSyncs.Failure.Kind.OLD -> ctx.getString(R.string.server_locks_old)
        ServerSyncs.Failure.Kind.GONE -> ctx.getString(R.string.server_err_gone)
        ServerSyncs.Failure.Kind.REFUSED -> ctx.getString(R.string.server_locks_refused, (e as ServerSyncs.Failure).detail)
        ServerSyncs.Failure.Kind.ODD, null -> ctx.getString(R.string.server_err_odd)
    }

    private fun log(ctx: Context, message: String) = Diagnostics.log(ctx, "server", message)

    /** The server's syncs, read now; the review's choices exchanged on the way for the syncs both know. */
    suspend fun refresh(ctx: Context): List<ServerSyncs.Sync> {
        val api = api(ctx) ?: return emptyList()
        val list = api.syncs()
        _known.value = list.associateBy { it.id }
        exchangeAll(ctx, api, list)
        return list
    }

    @Volatile private var lastAsked = 0L

    /**
     * [refresh] in the background, for the cards of syncs handed over: at most once a minute
     * unless [force]d (after a review there). A failure leaves what was known.
     */
    fun refreshSoon(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAsked < 60_000) return
        lastAsked = now
        val app = ctx.applicationContext
        if (!isOn(app)) return
        scope.launch { runCatching { refresh(app) } }
    }

    /**
     * For each sync of this phone handed over to the server, or paired by the user with the same
     * one there (and still the same): this phone's review choices sent, the server's taken in. Only
     * those syncs: the server cannot reach any other. A failure here only goes to the log.
     */
    private suspend fun exchangeAll(ctx: Context, api: ServerSyncs, list: List<ServerSyncs.Sync>) {
        val store = Store.get(ctx)
        val byId = list.associateBy { it.id }
        for (job in store.data.syncs) {
            val serverId = store.data.handedOver[job.id]?.serverId?.takeIf { it in byId }
                ?: store.data.serverPairs[job.id]?.takeIf { id -> byId[id]?.let { HandOver.sameAs(job, it) } == true }
                ?: continue
            if (!exchange(ctx, api, job, serverId)) return
        }
    }

    /** [job] paired with the server's same sync [serverId] ([pair]), or no longer: their review choices travel both ways. */
    fun pair(ctx: Context, job: SyncJob, serverId: String?) {
        Store.get(ctx).update { d -> d.copy(serverPairs = if (serverId == null) d.serverPairs - job.id else d.serverPairs + (job.id to serverId)) }
        log(ctx, "${job.name}: review choices ${if (serverId == null) "no longer shared" else "shared"} with the server")
    }

    /** One sync's choices both ways; false when the server cannot take them (an older one): no use asking again. */
    private suspend fun exchange(ctx: Context, api: ServerSyncs, job: SyncJob, serverId: String): Boolean {
        val store = Store.get(ctx)
        return try {
            val theirs = api.exchange(serverId, ReviewDecisions.ofSync(store.data, job))
            val taken = ReviewDecisions.apply(store, job.id, theirs)
            if (taken > 0) log(ctx, "${job.name}: $taken review choices from the server")
            true
        } catch (e: ServerSyncs.Failure) {
            log(ctx, "${job.name}: review choices not exchanged (${e.kind})")
            e.kind != ServerSyncs.Failure.Kind.OLD && e.kind != ServerSyncs.Failure.Kind.TOKEN && e.kind != ServerSyncs.Failure.Kind.UNREACHABLE
        }
    }

    /**
     * [job] made on the server with the server's own accounts, its review choices sent along, then
     * turned off here and marked as handed over. A two-way pair goes as one: the server makes both
     * halves from the original one, and both phone halves are turned off and marked. Nothing changes on
     * the phone unless all of it worked.
     * Not cut short when the dialog goes (a turned screen): once the server has made it, the phone
     * must know. Should the answer be lost all the same, asking again finds the sync it made there
     * ([HandOver.serverId]) and takes it, without making a second one.
     */
    suspend fun handOver(ctx: Context, job: SyncJob): Outcome = withContext(NonCancellable) {
        val api = api(ctx) ?: return@withContext Outcome(false, ctx.getString(R.string.server_err_off, ctx.getString(R.string.server_locks_title)))
        try {
            val data = Store.get(ctx).data
            val jobs = data.jobs
            when (val plan = HandOver.plan(job, api.services(), jobs, data.groups)) {
                // A multi sync: the whole of it, as one.
                is HandOver.Plan.Group -> handOverGroup(ctx, api, job, plan)
                is HandOver.Plan.NotHere -> Outcome(false, ctx.getString(when (plan.why) {
                    HandOver.Why.RADIO -> R.string.server_handover_no_radio
                    HandOver.Why.TWO_WAY -> R.string.server_handover_pair_broken
                    HandOver.Why.SHAZAM -> R.string.server_handover_no_shazam
                    HandOver.Why.FILES -> R.string.server_handover_no_files
                }))
                is HandOver.Plan.Missing -> Outcome(false, ctx.getString(
                    R.string.server_handover_missing,
                    plan.services.joinToString(", ") { com.xlollx.songport.providers.Providers.byId(it)?.displayName ?: it },
                ))
                is HandOver.Plan.Ready -> {
                    val pair = HandOver.pair(job, jobs)
                    // A pair is made from its original half; the server makes the reverse one.
                    val main = pair?.first ?: job
                    val there = api.syncs()
                    // Made there by an earlier try whose answer did not arrive: that one is this sync.
                    val earlier = HandOver.serverId(main).takeIf { id -> there.any { it.id == id } }
                    // The same sync there already: a second one would add what is missing twice.
                    if (earlier == null && there.any { HandOver.sameAs(main, it) }) {
                        return@withContext Outcome(false, ctx.getString(R.string.server_handover_exists, ctx.getString(R.string.server_title)))
                    }
                    // Asked again, the server answers a sync it has as it is, so a pair found there is read, not made twice.
                    val made = if (pair == null && earlier != null) ServerSyncs.Made(earlier, null) else api.made(main, plan, pair != null)
                    val halves = if (pair == null) listOf(job to made.id)
                    else listOf(pair.first to made.id, pair.second to (made.linkedId ?: throw ServerSyncs.Failure(ServerSyncs.Failure.Kind.ODD, "no linked sync")))
                    // The choices made here so far: the server's runs start from them.
                    for ((half, serverId) in halves) {
                        runCatching { ReviewDecisions.apply(Store.get(ctx), half.id, api.exchange(serverId, ReviewDecisions.ofSync(Store.get(ctx).data, half))) }
                    }
                    val store = Store.get(ctx)
                    val now = System.currentTimeMillis()
                    store.update { d ->
                        d.copy(
                            handedOver = d.handedOver + halves.associate { (half, serverId) -> half.id to HandedOver(serverId, now, half.enabled) },
                            jobs = d.jobs.map { j -> if (halves.any { it.first.id == j.id }) j.copy(enabled = false) else j },
                        )
                    }
                    store.flush()
                    for ((half, _) in halves) store.job(half.id)?.let { Scheduler.apply(ctx, it) }
                    log(ctx, "${job.name}: handed over to ${api.base}" + if (pair != null) " (as a pair)" else "")
                    runCatching { refresh(ctx) }
                    Outcome(true, ctx.getString(R.string.server_handover_done))
                }
            }
        } catch (e: ServerSyncs.Failure) {
            log(ctx, "${job.name}: not handed over (${e.kind})")
            Outcome(false, say(ctx, e))
        }
    }

    /** The multi sync (a group of the user's own playlists) [job] belongs to, or null. */
    private fun groupOf(d: com.xlollx.songport.data.StoreData, job: SyncJob): com.xlollx.songport.shared.SharedGroup? =
        com.xlollx.songport.shared.SharedGroups.sharedSide(job)?.let { id -> d.groups.firstOrNull { it.id == id && com.xlollx.songport.shared.Nway.isGroup(it) } }

    /**
     * The multi sync [job] belongs to made on the server, whole: the same source, targets and ways,
     * with the server's accounts ([plan]); then every sync of it here turned off and marked as handed
     * over, each to the server's sync for the same playlist. Nothing changes here unless the server
     * made it all. Asked again, the server gives back the multi sync it made (the same id).
     */
    private suspend fun handOverGroup(ctx: Context, api: ServerSyncs, job: SyncJob, plan: HandOver.Plan.Group): Outcome {
        val store = Store.get(ctx)
        val group = groupOf(store.data, job) ?: return Outcome(false, ctx.getString(R.string.server_handover_pair_broken))
        val members = com.xlollx.songport.shared.Nway.members(store.data.jobs, group.id)
        val made = api.createMulti(HandOver.groupBody(group, members, plan))
        val ids = HandOver.groupIds(members, made)
        val all = members.flatMap { listOfNotNull(it.down, it.up) }
        // Every sync of it must have its counterpart there: a multi sync is never half here, half there.
        if (all.any { it.id !in ids }) throw ServerSyncs.Failure(ServerSyncs.Failure.Kind.ODD, "multi sync not made whole")
        val now = System.currentTimeMillis()
        store.update { d ->
            d.copy(
                handedOver = d.handedOver + all.associate { it.id to HandedOver(ids.getValue(it.id), now, it.enabled) },
                jobs = d.jobs.map { j -> if (all.any { it.id == j.id }) j.copy(enabled = false) else j },
            )
        }
        store.flush()
        for (j in all) store.job(j.id)?.let { Scheduler.apply(ctx, it) }
        log(ctx, "multi sync group=${group.id}: handed over to ${api.base}, ${members.size} playlists")
        runCatching { refresh(ctx) }
        return Outcome(true, ctx.getString(R.string.server_handover_done))
    }

    /** [job] and, when it is half of a pair that was handed over too, its other half, each with its hand-over; for a multi sync, every sync of it. */
    private fun handedHalves(ctx: Context, job: SyncJob): List<Pair<SyncJob, HandedOver>> {
        val d = Store.get(ctx).data
        groupOf(d, job)?.let { g -> return com.xlollx.songport.shared.Nway.jobsOf(d, g.id).mapNotNull { j -> d.handedOver[j.id]?.let { j to it } } }
        val other = HandOver.pair(job, d.jobs)?.let { (a, b) -> if (a.id == job.id) b else a }
        return listOfNotNull(job, other).mapNotNull { j -> d.handedOver[j.id]?.let { j to it } }
    }

    /**
     * [job] back on this phone: the server's review choices taken in, the sync deleted there (its
     * playlists stay), and turned on here again as it was. A server that cannot be reached changes
     * nothing; a sync already deleted there comes back all the same.
     */
    suspend fun bringBack(ctx: Context, job: SyncJob): Outcome = withContext(NonCancellable) {
        val api = api(ctx) ?: return@withContext Outcome(false, ctx.getString(R.string.server_err_off, ctx.getString(R.string.server_locks_title)), canBringBackHere = true)
        val halves = handedHalves(ctx, job)
        if (halves.isEmpty()) return@withContext Outcome(true, ctx.getString(R.string.server_bring_back_done))
        // A multi sync comes back whole: deleted there as one (its playlists stay), every sync of it on again here.
        groupOf(Store.get(ctx).data, job)?.let { g ->
            return@withContext try {
                api.deleteMulti(g.id)
                for ((half, _) in halves) takeBack(ctx, half.id)
                _known.value = _known.value - halves.map { it.second.serverId }.toSet()
                log(ctx, "multi sync group=${g.id}: back from ${api.base}")
                Outcome(true, ctx.getString(R.string.server_bring_back_done))
            } catch (e: ServerSyncs.Failure) {
                log(ctx, "multi sync group=${g.id}: not brought back (${e.kind})")
                val stuck = e.kind == ServerSyncs.Failure.Kind.UNREACHABLE || e.kind == ServerSyncs.Failure.Kind.TOKEN || e.kind == ServerSyncs.Failure.Kind.OLD
                Outcome(false, say(ctx, e), canBringBackHere = stuck)
            }
        }
        try {
            for ((half, h) in halves) {
                try {
                    ReviewDecisions.apply(Store.get(ctx), half.id, api.exchange(h.serverId, ReviewDecisions.ofSync(Store.get(ctx).data, half)))
                } catch (e: ServerSyncs.Failure) {
                    // Gone there, or a server without the call: the sync comes back without them.
                    if (e.kind != ServerSyncs.Failure.Kind.GONE && e.kind != ServerSyncs.Failure.Kind.OLD) throw e
                }
            }
            // The server drops a pair's other half with the one deleted; one already gone is no failure.
            for ((_, h) in halves) api.delete(h.serverId)
            for ((half, _) in halves) takeBack(ctx, half.id)
            _known.value = _known.value - halves.map { it.second.serverId }.toSet()
            log(ctx, "${job.name}: back from ${api.base}")
            Outcome(true, ctx.getString(R.string.server_bring_back_done))
        } catch (e: ServerSyncs.Failure) {
            log(ctx, "${job.name}: not brought back (${e.kind})")
            // A server that cannot be asked (gone, unreachable, its token no longer valid): the phone can still take the sync back alone.
            val stuck = e.kind == ServerSyncs.Failure.Kind.UNREACHABLE || e.kind == ServerSyncs.Failure.Kind.TOKEN || e.kind == ServerSyncs.Failure.Kind.OLD
            Outcome(false, say(ctx, e), canBringBackHere = stuck)
        }
    }

    /**
     * [job] back on this phone without the server (gone for good, or "Coordinate with my server"
     * turned off): only this phone's record of the hand-over is cleared and the sync turned on as it
     * was. The server's copy, if any, is left as it is, and its review choices are not taken in.
     */
    suspend fun bringBackHere(ctx: Context, job: SyncJob): Outcome = withContext(NonCancellable) {
        val halves = handedHalves(ctx, job)
        for (id in halves.map { it.first.id }.ifEmpty { listOf(job.id) }) takeBack(ctx, id)
        _known.value = _known.value - halves.map { it.second.serverId }.toSet()
        log(ctx, "${job.name}: brought back without the server")
        Outcome(true, ctx.getString(R.string.server_bring_back_done))
    }

    private fun takeBack(ctx: Context, jobId: String) {
        val store = Store.get(ctx)
        store.update { d -> HandOver.takenBack(d, jobId) }
        store.flush()
        store.job(jobId)?.let { Scheduler.apply(ctx, it) }
    }

    /** Starts the server's sync [serverId]; the answer in words. */
    suspend fun run(ctx: Context, serverId: String): Outcome {
        val api = api(ctx) ?: return Outcome(false, ctx.getString(R.string.server_err_off, ctx.getString(R.string.server_locks_title)))
        return try {
            api.run(serverId)
            log(ctx, "started $serverId on ${api.base}")
            Outcome(true, ctx.getString(R.string.server_run_started))
        } catch (e: ServerSyncs.Failure) {
            Outcome(false, say(ctx, e))
        }
    }

    /** A sync handed over, run from its card, a widget or an automation: started on the server, said in a toast. */
    fun runHandedOver(ctx: Context, jobId: String) {
        val app = ctx.applicationContext
        val h = handedOver(app, jobId) ?: return
        scope.launch {
            val o = run(app, h.serverId)
            Toast.makeText(app, o.message, Toast.LENGTH_LONG).show()
            runCatching { refresh(app) }
        }
    }
}
