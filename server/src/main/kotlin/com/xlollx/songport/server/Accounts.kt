package com.xlollx.songport.server

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.JellyfinProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * More than one account of a service on the web page, as the app's Accounts screen does it
 * (AccountsScreen.addConnector): the first connector of a service takes its main slot (id
 * "jellyfin"), the next ones a numbered slot ("jellyfin@2", "jellyfin@3"…), each with its own
 * sign-in in tokens.json under its id and its own name. The settings are the engine's own
 * ([com.xlollx.songport.data.Settings.extraAccounts], connectorNames, connectors): a data folder
 * without extra accounts reads exactly as before, and a configuration file carries them.
 */
internal object Accounts {
    /** What the select of the Services page sends for "another account of this service". */
    const val ANOTHER = "@new"

    /**
     * True when this server can keep a second account of [p] apart from the first. The web
     * connectors pasted from a browser ([CookieConnector]) are left out: their clients keep
     * session values per service (the kv files "spotify_web", "deezer_web"…) and, for Spotify
     * and Deezer, in the process itself, so two accounts would mix.
     */
    fun allowsAnother(p: MusicProvider): Boolean = p.requiresAuth && p.supportsMultipleAccounts && p !is CookieConnector && !p.linkOnly

    /**
     * Adds a connector of [serviceId] and returns its id; null when there is no such service. With
     * [another], a new numbered account even when the main one is already there (when the service
     * allows it); without, the main one, or nothing new when it is already in the list (as before).
     * A blank [name] leaves the main account unnamed and calls a numbered one "Jellyfin 2".
     */
    fun add(store: FileStore, serviceId: String, another: Boolean, name: String?): String? {
        val service = Providers.services().firstOrNull { it.serviceId == serviceId && it.id == serviceId } ?: return null
        var id: String? = null
        store.updateSettings { s ->
            val primaryTaken = service.id in s.connectors
            if (primaryTaken && !(another && allowsAnother(service))) {
                id = service.id
                return@updateSettings s
            }
            val slot = if (!primaryTaken) "" else nextSlot(s.extraAccounts[serviceId].orEmpty())
            val newId = if (slot.isEmpty()) serviceId else "$serviceId@$slot"
            id = newId
            val given = name?.trim()?.takeIf { it.isNotEmpty() }
                ?: if (slot.isEmpty()) null else "${service.displayName} ${s.connectors.count { c -> c == serviceId || c.startsWith("$serviceId@") } + 1}"
            s.copy(
                connectors = (s.connectors + newId).distinct(),
                connectorsInit = true,
                connectorNames = if (given == null) s.connectorNames else s.connectorNames + (newId to given),
                extraAccounts = if (slot.isEmpty()) s.extraAccounts
                else s.extraAccounts + (serviceId to s.extraAccounts[serviceId].orEmpty() + slot),
            )
        }
        return id
    }

    /** The app's numbering: after the highest numbered slot, starting at 2. */
    fun nextSlot(used: List<String>): String = ((used.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 1) + 1).toString()

    /** The syncs that read from or write to [p]: an extra account in use is not removed. */
    fun inUse(store: FileStore, p: MusicProvider): Boolean =
        store.data.jobs.any { it.source.provider == p.id || it.target.provider == p.id }

    /**
     * Takes [p] off the list. The main account goes as it always did on the server (its syncs keep
     * working, its sign-in stays). An extra account is refused while a sync uses it (the app's
     * account_remove_blocked: false is returned), else signed out and forgotten with its name.
     */
    fun remove(env: Env, p: MusicProvider): Boolean {
        val store = env.store
        if (p.slot.isEmpty()) {
            store.updateSettings { it.copy(connectors = it.connectors - p.id) }
            return true
        }
        if (inUse(store, p)) return false
        runCatching { p.disconnect(env) }
        store.updateSettings { s ->
            s.copy(
                connectors = s.connectors.filter { it != p.id },
                connectorNames = s.connectorNames - p.id,
                extraAccounts = (s.extraAccounts + (p.serviceId to s.extraAccounts[p.serviceId].orEmpty().filter { it != p.slot }))
                    .filterValues { it.isNotEmpty() },
            )
        }
        return true
    }

    /** A connector's own name: blank gives it back the service's (and account's) name. */
    fun rename(store: FileStore, p: MusicProvider, name: String) {
        val n = name.trim().take(80)
        store.updateSettings { s -> s.copy(connectorNames = if (n.isEmpty()) s.connectorNames - p.id else s.connectorNames + (p.id to n)) }
    }
}

/**
 * Jellyfin's Quick Connect on the web page: the code the server gave, kept here (in memory, a few
 * minutes) while the page asks every few seconds whether it was approved.
 */
internal class QuickConnects {
    class Pending(val providerId: String, val qc: JellyfinProvider.QuickConnect, val at: Long) {
        val lock = Any()
        /** How it ended (signed in, or the error), once it has: later questions get the same answer. */
        @Volatile var outcome: Result<Boolean>? = null
        val logged = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val waiting = ConcurrentHashMap<String, Pending>()

    fun put(providerId: String, qc: JellyfinProvider.QuickConnect, now: Long = System.currentTimeMillis()): String {
        waiting.entries.removeIf { now - it.value.at > TTL_MS }
        return UUID.randomUUID().toString().also { waiting[it] = Pending(providerId, qc, now) }
    }

    /** The code waiting under [token] for [providerId]; null when unknown, another connector's, or too old. */
    fun get(token: String, providerId: String, now: Long = System.currentTimeMillis()): Pending? {
        val p = waiting[token] ?: return null
        if (p.providerId != providerId) return null
        if (now - p.at > TTL_MS) { waiting.remove(token); return null }
        return p
    }

    companion object {
        /** Jellyfin forgets a code after a few minutes; the app gives up after five. */
        const val TTL_MS = 10 * 60_000L
    }
}
