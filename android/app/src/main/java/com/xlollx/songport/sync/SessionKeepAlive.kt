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
import com.xlollx.songport.providers.AmazonBridgeProvider
import com.xlollx.songport.providers.AnghamiBridgeProvider
import com.xlollx.songport.providers.AppleBridgeProvider
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.DeezerBridgeProvider
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.GaanaBridgeProvider
import com.xlollx.songport.providers.KeepAlive
import com.xlollx.songport.providers.LiveOneBridgeProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.QobuzBridgeProvider
import com.xlollx.songport.providers.SpotifyBridgeProvider
import com.xlollx.songport.providers.SpotifyProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.providers.YouTubeBridgeProvider
import com.xlollx.songport.providers.YouTubeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Keeps every session alive while it is not used, and says once when one is gone, so the person is
 * asked to sign in again only when the service really ended the session.
 *
 * - Web connectors (through the connector: built in, or the companion app): each answers a
 *   `<service>.keepAlive` that does what its own renewal does. SoundCloud's web session is a token
 *   renewed by the site's own page, whose session lapses after a few idle days; YouTube Music's
 *   cookies are rotated by the player's page (issue #62); the others make one cheap signed-in call,
 *   which renews a refused session the way their calls do (a hidden page of the site, Amazon's
 *   player page on the account's own regional site, LiveOne's refresh token). Per account where the
 *   service has several. An older connector that does not know a method answers an error: skipped.
 * - Official sign-ins ([MusicProvider.keepAlive]): each connected account's access is renewed (one
 *   renewal at a time per account, see OAuthProvider), so refresh tokens stay in use; Deezer's and
 *   Apple's, which have none, make one cheap signed-in call.
 *
 * Twice a day, one call per account, one after the other with a pause between them; nothing for an
 * account not signed in. When a session is found gone (here, or by any call: a sync, the picker), one
 * notification says to sign in again, once per expiry and per connector ([noticeExpiry], [noticeAnswer]).
 */
object SessionKeepAlive {
    /** A connector kept alive: its service id, the connectors' method, the name the notification gives, and whether it has extra accounts. */
    class Target(val service: String, val method: String, val name: String, val slots: Boolean)

    val TARGETS = listOf(
        Target(SoundCloudPublic.SERVICE, "soundcloud.keepAlive", "SoundCloud", slots = false),
        Target(YouTubeBridgeProvider.SERVICE, "keepAlive", "YouTube Music", slots = true),
        Target(AmazonBridgeProvider.SERVICE, "amazon.keepAlive", "Amazon Music", slots = false),
        Target(SpotifyBridgeProvider.SERVICE, "spotify.keepAlive", "Spotify", slots = true),
        Target(DeezerBridgeProvider.SERVICE, "deezer.keepAlive", "Deezer", slots = true),
        Target(QobuzBridgeProvider.SERVICE, "qobuz.keepAlive", "Qobuz", slots = true),
        Target(AppleBridgeProvider.SERVICE, "apple.keepAlive", "Apple Music", slots = true),
        Target(AnghamiBridgeProvider.SERVICE, "anghami.keepAlive", "Anghami", slots = true),
        Target(GaanaBridgeProvider.SERVICE, "gaana.keepAlive", "Gaana", slots = true),
        Target(LiveOneBridgeProvider.SERVICE, "liveone.keepAlive", "LiveOne", slots = true),
    )

    /**
     * The official sign-ins whose access is renewed ([MusicProvider.keepAlive]): OAuth with refresh
     * tokens (Spotify, TIDAL, YouTube), and Deezer's and Apple Music's lasting tokens.
     */
    val OFFICIAL = setOf(SpotifyProvider.SERVICE, TidalProvider.SERVICE, YouTubeProvider.SERVICE, DeezerProvider.SERVICE, AppleMusicProvider.SERVICE)

    /** The official accounts among [connectorIds] the keep-alive renews, once each, extra accounts too. */
    fun officialPlan(connectorIds: List<String>): List<String> = connectorIds.distinct().filter { it.substringBefore('@') in OFFICIAL }

    /** Between two accounts: one call at a time, never a burst. */
    const val PACE_MS = 3_000L

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
    fun wanted(connectorIds: List<String>): Boolean = plan(connectorIds).isNotEmpty() || officialPlan(connectorIds).isNotEmpty()

    /** A web connector's answer to its keep-alive: an [error] (an older connector: skipped), or what it says of the session. */
    class Answer(
        val error: String?, val expiredAt: String?, val expired: Boolean,
        val renewed: Boolean = false, val connected: Boolean = true, val problem: String? = null, val offline: Boolean = false,
    )

    /**
     * One run: each web account through [bridge] (null when no connector is there), then each official
     * account through [official] (its name and outcome; null when nothing to renew), one at a time,
     * with [pause] between two calls. A session gone goes to [expired] (connector id, the service's
     * name, the expiry, told once by the caller); an official one that works again to [working].
     * Returns one line per account for the log, never a token.
     */
    suspend fun run(
        connectorIds: List<String>,
        bridge: (suspend (Planned) -> Answer?)?,
        official: suspend (String) -> Pair<String, KeepAlive>?,
        expired: (String, String, String) -> Unit,
        working: (String) -> Unit,
        pause: suspend () -> Unit,
    ): List<String> {
        val out = ArrayList<String>()
        var first = true
        suspend fun paced() { if (!first) pause(); first = false }
        if (bridge != null) for (p in plan(connectorIds)) {
            paced()
            // A connector that does not know the method (an older plugin) answers an error: nothing to do.
            val b = runCatching { bridge(p) }.getOrNull()
            if (b == null || b.error != null) { out += "${p.connectorId}: skipped"; continue }
            val expiry = expiryOf(b.expiredAt, b.expired)
            if (expiry != null) { expired(p.connectorId, p.target.name, expiry); out += "${p.connectorId}: session gone" }
            else out += "${p.connectorId}: kept"
        }
        for (id in officialPlan(connectorIds)) {
            val (name, k) = runCatching { official(id) }.getOrNull() ?: continue
            if (k.state == KeepAlive.State.NOT_SIGNED_IN) continue
            paced()
            when (k.state) {
                KeepAlive.State.RENEWED -> { working(id); out += "$id: renewed" }
                KeepAlive.State.EXPIRED -> { expired(id, name, GONE); out += "$id: signed out by the service" }
                else -> out += "$id: not renewed this time"
            }
        }
        return out
    }

    /**
     * The expiry a keep-alive's answer reports, or null while the session lives: SoundCloud's says
     * since when ([expiredAt]), YouTube Music's only that it is gone ([expired]).
     */
    fun expiryOf(expiredAt: String?, expired: Boolean): String? = expiredAt ?: GONE.takeIf { expired }

    /**
     * "Reconnect" on one account ([connectorId]): its session renewed now, the same renewal as the
     * keep-alive's. A web connector answers through [bridge] (null: no connector there, or an older
     * one without the method: not renewed), an official one through [official]. Then
     * [KeepAlive.needsSignIn] says whether the account is signed out for a new sign-in.
     */
    suspend fun renewNow(
        connectorId: String,
        bridge: (suspend (Planned) -> Answer?)?,
        official: suspend () -> KeepAlive,
    ): KeepAlive {
        val planned = plan(listOf(connectorId)).firstOrNull() ?: return official()
        val b = bridge?.let { runCatching { it(planned) }.getOrElse { e -> return KeepAlive.failed(e) } }
            ?: return KeepAlive(KeepAlive.State.FAILED, "no connector")
        return when {
            b.error != null -> KeepAlive(KeepAlive.State.FAILED, b.error.take(200))
            expiryOf(b.expiredAt, b.expired) != null -> KeepAlive.EXPIRED
            b.renewed -> KeepAlive.RENEWED
            !b.connected -> KeepAlive.NOT_SIGNED_IN
            else -> KeepAlive(KeepAlive.State.FAILED, b.problem, offline = b.offline)
        }
    }

    /** The connector's side of a keep-alive call on this phone: null when no connector is there. */
    fun bridgeOf(env: Env): (suspend (Planned) -> Answer?)? = if (!BridgePlugin.installed(env)) null else { p ->
        withContext(Dispatchers.IO) { BridgePlugin.call(env, p.target.method, null, null, p.slot) }?.let { answerOf(it) }
    }

    /**
     * "Reconnect" in an account's menu: [p]'s session renewed now. A renewal ends a "sign in again"
     * told before; the caller then signs the account out and opens its sign-in when
     * [KeepAlive.needsSignIn] says so.
     */
    suspend fun reconnect(ctx: Context, p: com.xlollx.songport.providers.MusicProvider): KeepAlive {
        val env = ctx.env
        val k = renewNow(p.id, bridgeOf(env)) { withContext(Dispatchers.IO) { KeepAlive.renewNow(env, p) } }
        env.log("keepalive", "${p.id}: reconnect: " + when {
            k.renewed -> "renewed"
            KeepAlive.needsSignIn(k) -> "not renewed (${k.state.name.lowercase()}), a new sign-in follows"
            else -> "not renewed, no connection: nothing changed"
        })
        if (k.renewed) noticeSignIn(ctx, p.id)
        return k
    }

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

    /** A connector's keep-alive answer, as [run] and [renewNow] read it. */
    fun answerOf(b: Bundle): Answer = Answer(
        b.getString("error"), b.getString("expiredAt"), b.getBoolean("expired", false),
        renewed = b.getBoolean("renewed", false), connected = b.getBoolean("connected", true),
        problem = b.getString("problem"),
        offline = com.xlollx.songport.net.NetError.messageKind(b.getString("problem")) != com.xlollx.songport.net.NetError.Kind.NONE,
    )

    /** The person signs in again, or signs out: the notification goes, and the next expiry is told. */
    fun noticeSignIn(ctx: Context, connectorId: String) {
        runCatching { if (forgetTold(Prefs(ctx), connectorId)) Notifications.cancelSignInAgain(ctx, connectorId) }
    }
}

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val ids = Store.get(ctx).data.settings.connectors
        if (!SessionKeepAlive.wanted(ids)) return Result.success()
        val env = ctx.env
        val bridge = SessionKeepAlive.bridgeOf(env)
        // The official sign-in itself only: a web connector of the same service runs through the connector above.
        val official: suspend (String) -> Pair<String, KeepAlive>? = { id ->
            Providers.byId(id)?.let { p -> p.keepAlive(env)?.let { k -> p.displayName to k } }
        }
        val lines = SessionKeepAlive.run(
            ids, bridge, official,
            expired = { id, name, expiry -> SessionKeepAlive.noticeExpiry(ctx, id, name, expiry) },
            working = { id -> SessionKeepAlive.noticeSignIn(ctx, id) },
            pause = { delay(SessionKeepAlive.PACE_MS) },
        )
        if (lines.isNotEmpty()) env.log("keepalive", lines.joinToString("; "))
        return Result.success()
    }
}
