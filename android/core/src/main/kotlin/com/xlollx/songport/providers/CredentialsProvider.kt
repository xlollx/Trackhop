package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.ServerAddresses

/** Campi richiesti dalla schermata di accesso di un servizio "a credenziali" (server personali, username). */
data class LoginForm(
    val needsUrl: Boolean,
    val needsUser: Boolean,
    val needsSecret: Boolean,
    /** Names of strings in strings.xml. */
    val secretLabelName: String = "login_password",
    val hintName: String,
    /** The secret field is shown but may stay empty (a token that only some features need). */
    val secretOptional: Boolean = false,
    /** The user field is shown but may stay empty (a server that asks for a sign-in only when protected). */
    val userOptional: Boolean = false,
    /**
     * A second way in, chosen on the form: an API key in place of the user and password
     * ([CredentialsProvider.loginWithApiKey]). The form then shows the address and the key only.
     */
    val offersApiKey: Boolean = false,
)

/**
 * Servizi senza OAuth: server personali (Subsonic/Navidrome, Jellyfin, Plex) e sorgenti che si
 * leggono con il solo nome utente (Last.fm, ListenBrainz). Le credenziali restano cifrate sul
 * telefono come i token OAuth e l'app parla soltanto con il server indicato dall'utente.
 */
abstract class CredentialsProvider : MusicProvider {
    abstract val loginForm: LoginForm

    /** Verifica le credenziali contro il servizio e, se valide, le salva. Lancia se non valide. */
    abstract suspend fun login(ctx: Env, url: String, user: String, secret: String)

    /** Signs in with an API key instead of a password, where [LoginForm.offersApiKey] says the service has one. */
    open suspend fun loginWithApiKey(ctx: Env, url: String, apiKey: String): Unit =
        throw ProviderException(ctx.text("error_read_only", displayName))

    override fun isConfigured(ctx: Env) = true
    override fun isConnected(ctx: Env) = ctx.tokens.get(id) != null
    override fun accountName(ctx: Env): String? =
        ctx.tokens.get(id)?.let { t -> t.userName.ifBlank { null } ?: t.extra["url"]?.removePrefix("https://")?.removePrefix("http://") }

    override fun startAuth(ctx: Env) {
        ctx.startAuth(this, AuthMode.CREDENTIALS)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) = Unit
    override fun disconnect(ctx: Env) = ctx.tokens.clear(id)

    /** The account's sign-in; with a second address, the pair is known to [ServerAddresses] before any request. */
    protected fun creds(ctx: Env): Tokens {
        val t = ctx.tokens.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        val home = t.extra["url"]
        val away = t.extra[AWAY_URL]
        if (!home.isNullOrBlank() && !away.isNullOrBlank()) ServerAddresses.register(home, away)
        return t
    }

    /** A new sign-in keeps the second address the account had (the form sets it again, or takes it away, right after). */
    protected fun save(ctx: Env, t: Tokens) {
        val away = ctx.tokens.get(id)?.extra?.get(AWAY_URL)
        ctx.tokens.set(id, if (away != null && AWAY_URL !in t.extra) t.copy(extra = t.extra + (AWAY_URL to away)) else t)
    }

    /** A server reached at an address the user gives: it can have a second one (2.2, home and away). */
    open val offersSecondAddress: Boolean get() = loginForm.needsUrl

    /** The second address of the account, if any. */
    fun awayAddress(ctx: Env): String? = ctx.tokens.get(id)?.extra?.get(AWAY_URL)?.takeIf { it.isNotBlank() }

    /** The address requests go to now: the home one, or the away one when that is where the server answered last. */
    fun addressInUse(ctx: Env): String? {
        val t = ctx.tokens.get(id) ?: return null
        val home = t.extra["url"]?.takeIf { it.isNotBlank() } ?: return null
        val away = t.extra[AWAY_URL]
        if (away.isNullOrBlank()) return home
        ServerAddresses.register(home, away)
        return ServerAddresses.current(home)
    }

    /** Sets (or, blank, takes away) the account's second address. Nothing is checked: from home the away one may not answer. */
    fun setAwayAddress(ctx: Env, url: String?) {
        val t = ctx.tokens.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        val home = t.extra["url"].orEmpty()
        val away = url?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeUrl(it) }
        if (away == null || away == home) {
            if (home.isNotEmpty()) ServerAddresses.unregister(home)
            ctx.tokens.set(id, t.copy(extra = t.extra - AWAY_URL))
        } else {
            ctx.tokens.set(id, t.copy(extra = t.extra + (AWAY_URL to away)))
            if (home.isNotEmpty()) ServerAddresses.register(home, away)
        }
    }

    /**
     * Signs in with a second address (2.2): known before the first request, so a sign-in made away
     * from home reaches the server by the away one. Then kept on the account.
     */
    suspend fun loginWithAddresses(ctx: Env, url: String, away: String?, user: String, secret: String, apiKey: String? = null) {
        val a = away?.trim()?.takeIf { it.isNotEmpty() }?.let { normalizeUrl(it) }
        if (a != null && url.isNotBlank()) ServerAddresses.register(normalizeUrl(url), a)
        if (apiKey != null) loginWithApiKey(ctx, url, apiKey) else login(ctx, url, user, secret)
        if (offersSecondAddress) setAwayAddress(ctx, a)
    }

    /** "navidrome.casa.lan:4533" -> "https://navidrome.casa.lan:4533", senza barra finale. */
    protected fun normalizeUrl(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    protected fun unsupported(ctx: Env): Nothing =
        throw ProviderException(ctx.text("error_read_only", displayName))

    companion object {
        /** Where an account keeps its second address (away from home). */
        const val AWAY_URL = "url2"
    }
}
