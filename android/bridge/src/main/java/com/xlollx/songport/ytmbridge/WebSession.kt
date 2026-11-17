package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * A web login kept for one service (Spotify, Apple Music): the cookie jar snapshot plus a few
 * values (account name, cached tokens). Encrypted with the Android Keystore, never handed to
 * Trackhop as such: Trackhop only receives short-lived API tokens or data.
 *
 * One instance per service and account: the primary account (slot "") keeps the storage it has
 * always had (`<name>_session`, the WebView's own cookie jar); an extra account ([forSlot]) has its
 * own file next to it (`<name>_s2_session`) and its own WebView profile ([Slots]), so neither can
 * overwrite the other.
 */
class WebSession private constructor(private val name: String, private val domains: List<String>, val slot: String, private val primary: WebSession?) {
    /** The service's primary account's session, the one the app has always kept. */
    constructor(name: String, domains: List<String>) : this(name, domains, Slots.PRIMARY, null)

    @Volatile private var cached: SharedPreferences? = null
    private val slots = ConcurrentHashMap<String, WebSession>()

    /** The file this account's session lives in; the primary's is the one it has always had. */
    private val fileName: String get() = if (slot.isEmpty()) "${name}_session" else "${name}_s${slot}_session"

    /** This session for [slot]: itself for its own slot, else the service's session of that account. */
    fun forSlot(slot: String): WebSession {
        val s = Slots.valid(slot)
        if (s == this.slot) return this
        primary?.let { return it.forSlot(s) }
        return slots.getOrPut(s) { WebSession(name, domains, s, this) }
    }

    private fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open(ctx.applicationContext).also { cached = it }
    }

    private fun open(ctx: Context): SharedPreferences = try {
        // One master key per service, whatever the account: the files differ, the key is shared.
        val key = MasterKey.Builder(ctx, "${name}_bridge_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, fileName, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("${fileName}_plain", Context.MODE_PRIVATE)
    }

    fun cookies(ctx: Context): String? = prefs(ctx).getString("cookies", null)
    fun account(ctx: Context): String? = prefs(ctx).getString("account", null)
    fun isConnected(ctx: Context): Boolean = cookies(ctx) != null
    fun get(ctx: Context, key: String): String? = prefs(ctx).getString("x_$key", null)
    fun put(ctx: Context, key: String, value: String?) { prefs(ctx).edit().putString("x_$key", value).apply() }

    fun save(ctx: Context, cookies: String, account: String?) {
        prefs(ctx).edit().putString("cookies", cookies).putString("account", account).apply()
    }

    /** The service this session is for, as its profile and file are named ("anghami", "spotify_web"…). */
    val serviceName: String get() = name

    /** The sites whose cookies are this session's (and are cleared with it). */
    val sites: List<String> get() = domains

    /** The cookie jar this account's pages use: the WebView's own for the primary, the slot's profile otherwise. */
    fun jar(): CookieManager = Slots.cookies(name, slot)

    /** Its storage was opened in this process already (a file written a moment ago may not be on disk yet). */
    internal val opened: Boolean get() = cached != null

    /** Every value kept for this account, for Trackhop's encrypted backup ([SessionTransfer]). */
    internal fun stored(ctx: Context): Map<String, *> = prefs(ctx).all

    /**
     * Puts back what [stored] gave on another device, in place of this account's session: the old
     * one is forgotten first (here and its cookies in the WebView), then the values are kept as the
     * sign-in keeps them; the clients read the cookies from them while the jar has none.
     */
    internal fun restore(ctx: Context, values: Map<String, Any?>) {
        clear(ctx)
        SessionTransfer.write(prefs(ctx), values)
    }

    /** Forgets the session here and the service's cookies in its WebView jar (other services keep theirs). */
    /** Forgets what was kept (the session's values), leaving the cookie jar as it is. */
    fun forgetStored(ctx: Context) { prefs(ctx).edit().clear().apply() }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        clearCookies()
    }

    /**
     * Deletes the service's cookies in this account's jar, what is kept for the session aside (other
     * services keep theirs): after a sign-out, and before a sign-in of an account that is not signed
     * in, so the service's page asks which account instead of opening the old one in silence.
     * @return how many cookie names were there.
     */
    fun clearCookies(): Int = runCatching {
        val cm = jar()
        var n = 0
        domains.forEach { d ->
            cm.getCookie("https://$d")?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.distinct()?.forEach { name ->
                n++
                deletions(d, name).forEach { cm.setCookie("https://$d", it) }
            }
        }
        cm.flush()
        n
    }.getOrDefault(0)

    companion object {
        /**
         * The cookie lines that delete [name] wherever a page of [host] may have set it: for the host
         * alone and for the host and each parent domain down to two labels, each with and without
         * `Secure` (a `__Secure-` or `__Host-` name is refused without it).
         */
        fun deletions(host: String, name: String): List<String> {
            val labels = host.trim('.').split('.')
            val domains = (0..(labels.size - 2).coerceAtLeast(0)).map { labels.drop(it).joinToString(".") }.filter { it.contains('.') }
            val scopes = listOf("") + domains.map { " Domain=.$it;" }
            return scopes.flatMap { scope -> listOf("$name=; Max-Age=0;$scope Path=/", "$name=; Max-Age=0;$scope Path=/; Secure") }
        }

        fun cookieValue(cookies: String?, name: String): String? = WebHost.cookieValue(cookies, name)
    }
}
