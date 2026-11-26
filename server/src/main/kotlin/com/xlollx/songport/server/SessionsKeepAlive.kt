package com.xlollx.songport.server

import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.notify.NotifyHistory
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.KeepAlive
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.delay

/**
 * The server's twice-daily keep-alive of its own sessions, as the app's: each signed-in account of
 * the services here (official sign-ins: their access renewed, one renewal at a time per account;
 * web connectors: one signed-in call) once, one after the other with a pause between them. One log
 * line per account, never a token or a cookie. A session the service really ended is told once,
 * "sign in again", on the notification channels that want failures and in the Notifications history;
 * it is told again only after it worked once more.
 */
object SessionsKeepAlive {
    const val EVERY_MS = 12 * 3_600_000L
    const val PACE_MS = 3_000L
    private const val KV = "keepalive"
    private const val LAST = "last"
    private fun told(id: String) = "told.$id"

    /** Whether a run is due at [now]: twelve hours after the last one (the first a while after the start, by the scheduler). */
    fun isDue(env: Env, now: Long): Boolean {
        val last = env.keyValues(KV)[LAST]?.toLongOrNull() ?: return true
        return now - last >= EVERY_MS || now < last
    }

    /** The accounts kept alive: the services added here, each account once. */
    fun accounts(env: Env): List<MusicProvider> =
        env.store.data.settings.connectors.distinct().mapNotNull { Providers.byId(it) }

    /**
     * One run over [accounts]: what each said, for the log and the tests. [tell] posts the "sign in
     * again" notice (default: the channels and the history).
     */
    suspend fun run(
        env: Env,
        accounts: List<MusicProvider> = accounts(env),
        pause: suspend () -> Unit = { delay(PACE_MS) },
        tell: (MusicProvider) -> Unit = { signInAgain(env, it) },
    ): List<String> {
        env.keyValues(KV)[LAST] = System.currentTimeMillis().toString()
        val kv = env.keyValues(KV)
        val out = ArrayList<String>()
        var first = true
        for (p in accounts) {
            if (!runCatching { p.isConnected(env) }.getOrDefault(false)) continue
            if (!first) pause()
            first = false
            val k = try { p.keepAlive(env) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { KeepAlive.failed(e) } ?: continue
            val line = when (k.state) {
                KeepAlive.State.RENEWED -> { kv[told(p.id)] = ""; "renewed" }
                KeepAlive.State.EXPIRED -> {
                    if (kv[told(p.id)].isNullOrEmpty()) { runCatching { tell(p) }; kv[told(p.id)] = "1" }
                    "the service ended the session: sign in again"
                }
                KeepAlive.State.NOT_SIGNED_IN -> continue
                KeepAlive.State.FAILED -> "not renewed this time (${k.problem.orEmpty().take(120)})"
            }
            env.log("keepalive", "${p.id}: $line")
            out += "${p.id}: $line"
        }
        return out
    }

    /** "Sign in again" for [p]: the channels that want failures, and the history (once while unread). */
    fun signInAgain(env: Env, p: MusicProvider) {
        val title = env.text("notif_signin_again_title", p.displayName)
        val text = "The session has expired. Open Services to sign in again."
        val target = NotifTarget.Account(p.id)
        val channels = runCatching { Notify.channels(env) }.getOrDefault(emptyList())
            .filter { it.enabled && it.missing().isEmpty() && Notify.Event.FAILED in it.events }
        if (channels.isNotEmpty()) Notify.sendAll(env, channels, Notify.Message(title, text), "signin", target)
        else runCatching { NotifyHistory.of(env).addOnce("signin", title, text, target) }
    }

    /** What "Reconnect" did: [renewed] (nothing else to do), or [signedOut] (a new sign-in is needed), and what to say. */
    data class Reconnected(val renewed: Boolean, val signedOut: Boolean, val message: String)

    /**
     * "Reconnect" (the Services page, the API, the CLI): the account's session renewed now, as the
     * keep-alive does; when that cannot work the account is signed out, as its "Disconnect" does, so
     * its sign-in comes next. Offline nothing is signed out.
     */
    suspend fun reconnect(env: Env, p: MusicProvider): Reconnected {
        val k = KeepAlive.renewNow(env, p)
        val name = p.label(env)
        env.log("keepalive", "${p.id}: reconnect: " + when {
            k.renewed -> "renewed"
            KeepAlive.needsSignIn(k) -> "not renewed (${k.state.name.lowercase()}), signed out for a new sign-in"
            else -> "not renewed, no connection: nothing changed"
        })
        if (k.renewed) {
            env.keyValues(KV)[told(p.id)] = ""
            return Reconnected(true, false, "$name: session renewed, nothing else to do.")
        }
        if (!KeepAlive.needsSignIn(k)) return Reconnected(false, false, "$name: the service could not be reached, nothing changed. Try again later.")
        runCatching { p.disconnect(env) }
        env.keyValues(KV)[told(p.id)] = ""
        return Reconnected(false, true, "$name: the session could not be renewed. Sign in again.")
    }
}
