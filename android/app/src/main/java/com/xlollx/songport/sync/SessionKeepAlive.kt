package com.xlollx.songport.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xlollx.songport.Notifications
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.env
import com.xlollx.songport.providers.BridgePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a web session alive while it is not used. SoundCloud's web session is a token that lasts an
 * hour, renewed by the site's own page, whose session lapses after a few idle days: once it has,
 * nothing renews and the person has to sign in again. Twice a day, while a SoundCloud connector is
 * there, the connector renews it once in the background (`soundcloud.keepAlive`).
 *
 * The other web connectors keep long-lived sessions (Spotify's and Deezer's cookies, Qobuz's and
 * Apple's tokens, Anghami's and Amazon's pages renewed on use) and need no keep-alive.
 *
 * When the session is found gone (here or by a sync), one notification says to sign in again,
 * once per expiry.
 */
object SessionKeepAlive {
    /** The connector kept alive: SoundCloud has a single account, so one connector id. */
    const val CONNECTOR = SoundCloudPublic.SERVICE
    const val METHOD = "soundcloud.keepAlive"
    const val EVERY_HOURS = 12L

    /** Whether the keep-alive is scheduled: a SoundCloud connector among [connectorIds]. */
    fun wanted(connectorIds: List<String>): Boolean = CONNECTOR in connectorIds

    /** Whether an expiry ([expiredAt], from the connector) is still to be told: once each. */
    fun shouldNotify(expiredAt: String?, notified: String?): Boolean = expiredAt != null && expiredAt != notified

    private const val PREFS = "session_keep_alive"

    /** Tells [expiredAt] once: the notification, then remembered so the next runs stay quiet. */
    fun noticeExpiry(ctx: Context, expiredAt: String?) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "notified.$CONNECTOR"
        if (!shouldNotify(expiredAt, prefs.getString(key, null))) return
        if (Notifications.signInAgain(ctx, "SoundCloud", CONNECTOR)) prefs.edit().putString(key, expiredAt).apply()
    }
}

class KeepAliveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!SessionKeepAlive.wanted(Store.get(ctx).data.settings.connectors)) return Result.success()
        val env = ctx.env
        if (!BridgePlugin.installed(env)) return Result.success()
        // A connector that does not know the method (an older plugin) answers an error: nothing to do.
        val b = withContext(Dispatchers.IO) { runCatching { BridgePlugin.call(env, SessionKeepAlive.METHOD) }.getOrNull() } ?: return Result.success()
        if (b.containsKey("error")) return Result.success()
        SessionKeepAlive.noticeExpiry(ctx, b.getString("expiredAt"))
        return Result.success()
    }
}
