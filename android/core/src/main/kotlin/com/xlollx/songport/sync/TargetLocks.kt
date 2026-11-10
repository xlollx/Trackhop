package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is writing to a playlist now, by its key ("service|playlist", [key]).
 *
 * In this process, one run at a time per target: a sync waits for another sync of this process
 * writing to the same playlist ([lockRun]). Other devices (a phone that coordinates with this
 * server) reserve a target through the server's API ([take]) for a while they renew as they run,
 * so a reservation left by a device that died ends by itself. A run of this process that finds a
 * target reserved elsewhere does not write ([lockRun] answers the reservation); a reservation asked
 * while a run of this process writes there is refused ([take] answers [Answer.Busy] with no holder).
 */
object TargetLocks {
    /** How long a reservation lasts when the caller does not say, and the bounds of what it may ask. */
    const val DEFAULT_TTL_MS = 10 * 60_000L
    const val MIN_TTL_MS = 30_000L
    const val MAX_TTL_MS = 60 * 60_000L

    /** A reservation from elsewhere: [holder] is how that device calls itself, [until] when it ends unless renewed. */
    data class Lease(val id: String, val target: String, val holder: String, val until: Long)

    sealed class Answer {
        data class Taken(val lease: Lease) : Answer()
        /** [holder] null: a sync of this process writes there now; [until] is then only as far as a reservation would last. */
        data class Busy(val holder: String?, val until: Long) : Answer()
    }

    private class Entry {
        val mutex = Mutex()
        var lease: Lease? = null
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The time leases are measured by; tests move it. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private fun entry(key: String): Entry = entries.computeIfAbsent(key) { Entry() }

    /**
     * The key of a playlist, the same on every device: the service, not the route or the account
     * (Spotify through its API or its web player, a second account), and the playlist's id. Null
     * while it has none (a playlist still to make).
     */
    fun key(ref: PlaylistRef): String? = ref.playlistId?.takeIf { it.isNotBlank() }?.let { "${FileStore.family(ref.provider)}|$it" }

    /**
     * What a run of [job] writes to: its target, and in a two-way pair ([partner]) the other half's
     * target too, which this half reads and the other writes. Sorted, so runs taking several never
     * wait for each other in a circle.
     */
    fun keys(job: SyncJob, partner: SyncJob?): List<String> =
        listOfNotNull(key(job.target), partner?.let { key(it.target) }).distinct().sorted()

    /** A File playlist lives on each device apart: nothing to agree on with another one. */
    fun shared(key: String) = !key.startsWith("${com.xlollx.songport.providers.LocalFilesProvider.serviceId}|")

    /** Reserves [target] for [holder] (another device), for [ttlMs] clamped to the bounds above. */
    fun take(target: String, holder: String, ttlMs: Long = DEFAULT_TTL_MS): Answer {
        val e = entry(target)
        synchronized(e) {
            val now = clock()
            if (e.mutex.isLocked) return Answer.Busy(null, now + DEFAULT_TTL_MS)
            e.lease?.takeIf { it.until > now }?.let { return Answer.Busy(it.holder, it.until) }
            val lease = Lease(UUID.randomUUID().toString(), target, holder, now + ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS))
            e.lease = lease
            return Answer.Taken(lease)
        }
    }

    /** Extends the reservation [id] by [ttlMs] from now; null when it ended (released, or expired: take it again). */
    fun renew(id: String, ttlMs: Long = DEFAULT_TTL_MS): Lease? {
        for (e in entries.values) synchronized(e) {
            val l = e.lease ?: return@synchronized
            if (l.id != id) return@synchronized
            val now = clock()
            if (l.until <= now) { e.lease = null; return null }
            return l.copy(until = now + ttlMs.coerceIn(MIN_TTL_MS, MAX_TTL_MS)).also { e.lease = it }
        }
        return null
    }

    /** Ends the reservation [id]; false when there was none (already ended). */
    fun release(id: String): Boolean {
        for (e in entries.values) synchronized(e) {
            if (e.lease?.id == id) { e.lease = null; return true }
        }
        return false
    }

    /** True while a run of this process writes to one of [keys]: the next one waits. */
    fun busyHere(keys: List<String>) = keys.any { entry(it).mutex.isLocked }

    /**
     * For a run writing to [keys] (sorted, see [keys]): waits for this process's other runs on
     * them, then holds them. Answers the reservation from elsewhere that one of them has, if any:
     * then nothing is held and the run must not write.
     */
    suspend fun lockRun(keys: List<String>): Lease? {
        val held = ArrayList<Entry>()
        for (k in keys) {
            val e = entry(k)
            e.mutex.lock()
            held += e
            val foreign = synchronized(e) { e.lease?.takeIf { it.until > clock() } }
            if (foreign != null) {
                held.forEach { it.mutex.unlock() }
                return foreign
            }
        }
        return null
    }

    /** Lets go of what [lockRun] held. */
    fun unlockRun(keys: List<String>) = keys.forEach { entry(it).mutex.unlock() }
}

/**
 * Keeps targets for a run with somebody outside this process (the user's own Trackhop server, see
 * [ServerLocks]): one device at a time writes to a playlist.
 */
interface TargetCoordinator {
    /**
     * Runs [block] holding [targets] ("service|playlist", [TargetLocks.key]) for its whole length.
     * Throws [TargetBusyException] without running it when another device holds one of them. When
     * the coordinator cannot be asked, [block] runs anyway: syncs never wait on it.
     */
    suspend fun <T> holding(targets: List<String>, block: suspend () -> T): T
}

/** Another device ([holder]) is writing to one of a run's targets, until [until] (epoch ms, 0 = not said). */
class TargetBusyException(val holder: String, val until: Long) : Exception("Running on $holder")
