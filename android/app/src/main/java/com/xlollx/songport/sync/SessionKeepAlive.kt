package com.xlollx.songport.sync

import android.content.Context
import android.os.Bundle
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xlollx.songport.Notifications
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.AndroidEnv
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.YouTubeBridgeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps web sessions alive while they are not used, and says once when one is gone.
 *
 * - SoundCloud's web session is a token that lasts an hour, renewed by the site's own page, whose
 *   session lapses after a few idle days: once it has, nothing renews and the person has to sign in
 *   again. The connector renews it in the background (`soundcloud.keepAlive`).
 * - YouTube Music's session has short-lived cookies Google rotates from the player's own page; the
 *   connector never runs that page, and Google ends up refusing the session (a 401, issue #62).
 *   The connector runs the page in the background (`keepAlive`), for each account.
 *
 * Twice a day, while such a connector is there. The other web connectors keep long-lived sessions
 * (Spotify's and Deezer's cookies, Qobuz's and Apple's tokens, Anghami's and Amazon's pages renewed
 * on use) and need no keep-alive. An older connector that does not know a method answers an error:
 * nothing is done.
 *
 * When a web session is found gone (here, or by any call: a sync, the picker), one notification
 * says to sign in again, once per expiry and per connector ([noticeExpiry], [noticeAnswer]). The
 * official connectors renew their own tokens and are not told here: a refresh that fails for good
 * fails the sync, which says so, as it always has.
 */
object SessionKeepAlive {
    /** A connector kept alive: its service id, the connectors' method, the name the notification gives, and whether it has extra accounts. */
    class Target(val service: String, val method: String, val name: String, val slots: Boolean)

    val TARGETS = listOf(
        Target(SoundCloudPublic.SERVICE, "soundcloud.keepAlive", "SoundCloud", slots = false),
        Target(YouTubeBridgeProvider.SERVICE, "keepAlive", "YouTube Music", slots = true),
    )

    /** One connector of the person's ([connectorId], "ytm@2" for an extra account) and its [slot]. */
    class Planned(val connectorId: String, val slot: String, val target: Target)

    const val EVERY_HOURS = 12L

    /** What the keep-alive renews among [connectorIds]: each connector of a kept-alive service, once. */
    fun plan(connectorIds: List<String>): List<Planned> = connectorIds.distinct().mapNotNull { id ->
        val service = id.substringBefore('@')
        val slot = id.substringAfter('@', "")
        TARGETS.firstOrNull { it.service == service && (slot.isEmpty() || it.slots) }?.let { Planned(id, slot, it) }
    }

    /** Whether the keep-alive is scheduled: a kept-alive connector among [connectorIds]. */
    fun wanted(connectorIds: List<String>): Boolean = plan(connectorIds).isNotEmpty()

    /**
     * The expiry a keep-alive's answer reports, or null while the session lives: SoundCloud's says
     * since when ([expiredAt]), YouTube Music's only that it is gone ([expired]).
     */
    fun expiryOf(expiredAt: String?, expired: Boolean): String? = expiredAt ?: GONE.takeIf { expired }

    // ------------------------------------------------------------------ "sign in again", once per expiry

    /** The expiry of a session that says only that it is gone: told once until it works again. */
    const val GONE = "gone"

    /** Whether an expiry ([expiredAt], from the connector) is still to be told: once each. */
    fun shouldNotify(expiredAt: String?, notified: String?): Boolean = expiredAt != null && expiredAt != notified

    /**
     * What a web connector says when its session is gone for good, as its error says it (the same
     * words in the built-in connectors and in a plugin, of any version): YouTube Music's 401 after
     * the renewal, Spotify's, Deezer's and SoundCloud's expired web session, Qobuz's and Anghami's
     * ended session, Amazon's player that no longer takes it.
     */
    private val GONE_WORDS = listOf(
        "rejected the session (401)",
        "web session has expired",
        "session has ended: sign in again",
        "no longer accepts the session",
        "no longer opens the player",
        "served the player to a signed-out visitor",
    )

    /** Whether [error] (a connector's) says its session is gone for good. A throttle, an outage or a slow page does not. */
    fun sessionGone(error: String?): Boolean = error != null && GONE_WORDS.any { error.contains(it, ignoreCase = true) }

    /** Where what was told is remembered, per connector: the app's preferences, a map in tests. */
    interface Memory {
        fun get(key: String): String?
        fun put(key: String, value: String?)
    }

    private fun key(connectorId: String) = "notified.$connectorId"

    /**
     * Tells [expiry] of [connectorId] once, with [post] (the notification; false when it could not
     * be posted, and it is tried again next time). True when it was told now.
     */
    fun tell(memory: Memory, connectorId: String, expiry: String?, post: () -> Boolean): Boolean {
        if (!shouldNotify(expiry, memory.get(key(connectorId)))) return false
        if (!post()) return false
        memory.put(key(connectorId), expiry)
        return true
    }

    /** The session of [connectorId] works again (a sign-in, a signed-in answer): its next expiry is told. True when one had been told. */
    fun forgetTold(memory: Memory, connectorId: String): Boolean {
        if (memory.get(key(connectorId)) == null) return false
        memory.put(key(connectorId), null)
        return true
    }

    /**
     * After a call to a web connector: an [error] that says the session is gone, or SoundCloud's
     * [expiredAt], is told once. An answer to a signed-in listing ([method] "playlists" or ending in
     * ".playlists") shows the session works, and a sign-out or a start over ends it: the next expiry is told again.
     * True when what was told is over (its notification can go).
     */
    fun afterCall(memory: Memory, connectorId: String, method: String, error: String?, expiredAt: String?, post: () -> Boolean): Boolean {
        when {
            expiredAt != null -> tell(memory, connectorId, expiredAt, post)
            error != null -> if (sessionGone(error)) tell(memory, connectorId, GONE, post)
            method.substringAfterLast('.') in ENDS_TOLD -> return forgetTold(memory, connectorId)
        }
        return false
    }

    /** The answers that end what was told: a signed-in listing that worked, a sign-out. */
    private val ENDS_TOLD = setOf("playlists", "disconnect", "reset")

    private const val PREFS = "session_keep_alive"

    private class Prefs(ctx: Context) : Memory {
        private val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun get(key: String): String? = prefs.getString(key, null)
        override fun put(key: String, value: String?) {
            if (value == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, value).apply()
        }
    }

    /** Tells [expiry] of [connectorId] ([service] names it) once: the notification, then remembered so the next runs stay quiet. */
    fun noticeExpiry(ctx: Context, connectorId: String, service: String, expiry: String? = GONE) {
        runCatching { tell(Prefs(ctx), connectorId, expiry) { Notifications.signInAgain(ctx, service, connectorId) } }
    }

    /** What a connector's [answer] to [method] says of its session (see [afterCall]). Never fails the call. */
    fun noticeAnswer(env: Env, connectorId: String, service: String, method: String, answer: Bundle) {
        runCatching {
            val ctx = (env as? AndroidEnv)?.context ?: return
            val forgot = afterCall(Prefs(ctx), connectorId, method, answer.getString("error"), answer.getString("expiredAt")) {
                Notifications.signInAgain(ctx, service, connectorId)
            }
            if (forgot) Notifications.cancelSignInAgain(ctx, connectorId)
        }
    }

    /** The person signs in again, or signs out: the notification goes, and the next expiry is told. */
    fun noticeSignIn(ctx: Context, connectorId: String) {
        runCatching { if (forgetTold(Prefs(ctx), connectorId)) Notifications.cancelSignInAgain(ctx, connectorId) }
    }
}

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val plan = SessionKeepAlive.plan(Store.get(ctx).data.settings.connectors)
        if (plan.isEmpty()) return Result.success()
        val env = ctx.env
        if (!BridgePlugin.installed(env)) return Result.success()
        for (p in plan) {
            // A connector that does not know the method (an older plugin) answers an error: nothing to do.
            val b = withContext(Dispatchers.IO) { runCatching { BridgePlugin.call(env, p.target.method, null, null, p.slot) }.getOrNull() } ?: continue
            if (b.containsKey("error")) continue
            SessionKeepAlive.expiryOf(b.getString("expiredAt"), b.getBoolean("expired", false))
                ?.let { SessionKeepAlive.noticeExpiry(ctx, p.connectorId, p.target.name, it) }
        }
        return Result.success()
    }
}
