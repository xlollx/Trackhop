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
    class Outcome(val ok: Boolean, val message: String)

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
        for (job in store.data.jobs) {
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
     * turned off here and marked as handed over. Nothing changes on the phone unless all of it worked.
     * Not cut short when the dialog goes (a turned screen): once the server has made it, the phone
     * must know. Should the answer be lost all the same, asking again finds the sync it made there
     * ([HandOver.serverId]) and takes it, without making a second one.
     */
    suspend fun handOver(ctx: Context, job: SyncJob): Outcome = withContext(NonCancellable) {
        val api = api(ctx) ?: return@withContext Outcome(false, ctx.getString(R.string.server_err_off, ctx.getString(R.string.server_locks_title)))
        try {
            when (val plan = HandOver.plan(job, api.services())) {
                is HandOver.Plan.NotHere -> Outcome(false, ctx.getString(when (plan.why) {
                    HandOver.Why.RADIO -> R.string.server_handover_no_radio
                    HandOver.Why.TWO_WAY -> R.string.server_handover_no_two_way
                    HandOver.Why.SHAZAM -> R.string.server_handover_no_shazam
                    HandOver.Why.FILES -> R.string.server_handover_no_files
                }))
                is HandOver.Plan.Missing -> Outcome(false, ctx.getString(
                    R.string.server_handover_missing,
                    plan.services.joinToString(", ") { com.xlollx.songport.providers.Providers.byId(it)?.displayName ?: it },
                ))
                is HandOver.Plan.Ready -> {
                    val there = api.syncs()
                    // Made there by an earlier try whose answer did not arrive: that one is this sync.
                    val made = HandOver.serverId(job).takeIf { id -> there.any { it.id == id } }
                    // The same sync there already: a second one would add what is missing twice.
                    if (made == null && there.any { HandOver.sameAs(job, it) }) {
                        return@withContext Outcome(false, ctx.getString(R.string.server_handover_exists, ctx.getString(R.string.server_title)))
                    }
                    val serverId = made ?: api.create(job, plan)
                    // The choices made here so far: the server's runs start from them.
                    runCatching { ReviewDecisions.apply(Store.get(ctx), job.id, api.exchange(serverId, ReviewDecisions.ofSync(Store.get(ctx).data, job))) }
                    val store = Store.get(ctx)
                    store.update { d ->
                        d.copy(
                            handedOver = d.handedOver + (job.id to HandedOver(serverId, System.currentTimeMillis(), job.enabled)),
                            jobs = d.jobs.map { if (it.id == job.id) it.copy(enabled = false) else it },
                        )
                    }
                    store.flush()
                    store.job(job.id)?.let { Scheduler.apply(ctx, it) }
                    log(ctx, "${job.name}: handed over to ${api.base}")
                    runCatching { refresh(ctx) }
                    Outcome(true, ctx.getString(R.string.server_handover_done))
                }
            }
        } catch (e: ServerSyncs.Failure) {
            log(ctx, "${job.name}: not handed over (${e.kind})")
            Outcome(false, say(ctx, e))
        }
    }

    /**
     * [job] back on this phone: the server's review choices taken in, the sync deleted there (its
     * playlists stay), and turned on here again as it was. A server that cannot be reached changes
     * nothing; a sync already deleted there comes back all the same.
     */
    suspend fun bringBack(ctx: Context, job: SyncJob): Outcome = withContext(NonCancellable) {
        val api = api(ctx) ?: return@withContext Outcome(false, ctx.getString(R.string.server_err_off, ctx.getString(R.string.server_locks_title)))
        val h = handedOver(ctx, job.id) ?: return@withContext Outcome(true, ctx.getString(R.string.server_bring_back_done))
        try {
            try {
                ReviewDecisions.apply(Store.get(ctx), job.id, api.exchange(h.serverId, ReviewDecisions.ofSync(Store.get(ctx).data, job)))
            } catch (e: ServerSyncs.Failure) {
                // Gone there, or a server without the call: the sync comes back without them.
                if (e.kind != ServerSyncs.Failure.Kind.GONE && e.kind != ServerSyncs.Failure.Kind.OLD) throw e
            }
            api.delete(h.serverId)
            val store = Store.get(ctx)
            store.update { d ->
                d.copy(
                    handedOver = d.handedOver - job.id,
                    jobs = d.jobs.map { if (it.id == job.id) it.copy(enabled = h.wasEnabled) else it },
                )
            }
            store.flush()
            store.job(job.id)?.let { Scheduler.apply(ctx, it) }
            _known.value = _known.value - h.serverId
            log(ctx, "${job.name}: back from ${api.base}")
            Outcome(true, ctx.getString(R.string.server_bring_back_done))
        } catch (e: ServerSyncs.Failure) {
            log(ctx, "${job.name}: not brought back (${e.kind})")
            Outcome(false, say(ctx, e))
        }
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
