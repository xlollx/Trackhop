package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.LibraryTools

/**
 * The weekly look for unplayable tracks (the app's Settings › "Unplayable tracks", its
 * UnplayableWorker): every connected service that can tell them apart is asked which entries of your
 * playlists and liked songs it no longer plays. Nothing is changed: when some are found, your
 * notification channels that take "Tracks to review" are told, with the way to fix them (Tools ›
 * Unplayable tracks). On and off with [com.xlollx.songport.data.Settings.unplayableCheck], as in the app.
 */
internal object UnplayableWeekly {
    const val TITLE = "Weekly unplayable check"
    const val EVERY_MS = 7L * 24 * 3_600_000
    private const val KV = "unplayable_weekly"

    data class Last(val at: Long, val count: Int, val playlists: Int, val services: Int, val failed: Int)

    fun on(env: ServerEnv): Boolean = env.store.data.settings.unplayableCheck

    fun set(env: ServerEnv, on: Boolean) = env.store.updateSettings { it.copy(unplayableCheck = on) }

    fun last(env: ServerEnv): Last? {
        val kv = env.keyValues(KV)
        val at = kv["at"]?.toLongOrNull() ?: return null
        return Last(at, kv["count"]?.toIntOrNull() ?: 0, kv["playlists"]?.toIntOrNull() ?: 0, kv["services"]?.toIntOrNull() ?: 0, kv["failed"]?.toIntOrNull() ?: 0)
    }

    /** Turned on, a week (or more) since the last look, and none running. */
    fun isDue(env: ServerEnv, now: Long): Boolean = on(env) && now - (last(env)?.at ?: 0) >= EVERY_MS && !Tasks.running(TITLE)

    /** The services asked: connected, and able to say which entries they no longer play. */
    fun services(env: ServerEnv) = Providers.connectors().filter { it.detectsUnplayable && it.isConnected(env) }

    /** Looks every service up; tells the channels when something was found. What it found, in words. */
    suspend fun run(env: ServerEnv, progress: (String) -> Unit = {}): String {
        var count = 0
        var playlists = 0
        var failed = 0
        val per = ArrayList<String>()
        val services = services(env)
        for (p in services) {
            progress("Reading ${p.label(env)}…")
            try {
                val scan = LibraryTools.scanUnplayable(env, p, LibraryTools.unplayableTargets(env, p)) { progress("${p.label(env)}: $it") }
                count += scan.count
                playlists += scan.found.size
                failed += scan.failed.size
                if (scan.count > 0) per += "${p.label(env)}: ${scan.count} in ${scan.found.size} ${if (scan.found.size == 1) "playlist" else "playlists"}"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                failed++
                env.log("unplayable", "${p.label(env)}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        val kv = env.keyValues(KV)
        kv["at"] = System.currentTimeMillis().toString()
        kv["count"] = count.toString(); kv["playlists"] = playlists.toString(); kv["services"] = services.size.toString(); kv["failed"] = failed.toString()
        env.log("unplayable", "weekly check: $count unplayable in ${services.size} services" + (if (failed == 0) "" else "; $failed playlists not read"))
        if (count > 0) tell(env, count, per)
        return summary(last(env)!!)
    }

    fun summary(l: Last): String = when {
        l.services == 0 -> "No connected service can tell unplayable tracks apart."
        l.count == 0 -> "Every track plays." + if (l.failed > 0) " ${l.failed} could not be read." else ""
        else -> "${l.count} unplayable ${if (l.count == 1) "track" else "tracks"} in ${l.playlists} ${if (l.playlists == 1) "playlist" else "playlists"}." + if (l.failed > 0) " ${l.failed} could not be read." else ""
    }

    /** The channels that take "Tracks to review", enabled and ready. */
    fun channels(env: ServerEnv): List<Notify.Channel> =
        runCatching { Notify.channels(env) }.getOrDefault(emptyList()).filter { it.enabled && it.missing().isEmpty() && Notify.Event.REVIEW in it.events }

    private fun tell(env: ServerEnv, count: Int, per: List<String>) {
        val m = Notify.Message(
            if (count == 1) "1 track no longer plays" else "$count tracks no longer play",
            (per + "Trackhop can replace them with a playable version: Tools › Unplayable tracks, ${env.baseUrl.trimEnd('/')}/tools/unplayable").joinToString("\n"),
        )
        Notify.sendAll(env, channels(env), m, "unplayable", com.xlollx.songport.notify.NotifTarget.Unplayable(null))
    }
}
