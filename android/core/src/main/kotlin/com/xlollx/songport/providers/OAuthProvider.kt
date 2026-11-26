package com.xlollx.songport.providers

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.HttpResponse
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

/**
 * Base per i servizi con OAuth 2.0 Authorization Code + PKCE (Spotify, Google, TIDAL).
 * Gestisce: URL di autorizzazione, scambio codice, refresh, chiamate API con Bearer e retry su 401.
 */
abstract class OAuthProvider : MusicProvider {
    protected abstract val defaultClientId: String
    protected abstract val authorizeEndpoint: String
    protected abstract val tokenEndpoint: String
    protected abstract val scopes: String
    protected open val extraAuthParams: Map<String, String> = emptyMap()
    /**
     * The parameters that make the service ask which account to use, added to every sign-in, the
     * first account's too: the browser is almost always signed in already, and without them the
     * service takes that account in silence (after a sign-out, or with another account in mind: #64).
     * Empty for a service whose sign-in has no such parameter (TIDAL, Deezer).
     */
    protected open val switchAccountParams: Map<String, String> = emptyMap()
    protected open val apiAccept: String = "application/json"
    protected open val apiContentType: String = "application/json"

    protected open fun redirectUri(ctx: Env): String = ctx.redirectUri

    fun clientId(ctx: Env): String =
        ownClientId(ctx) ?: defaultClientId

    /** Client ID inserito dall'utente, se c'e' (per servizio, vale per tutti gli account). */
    fun ownClientId(ctx: Env): String? =
        ctx.store.data.settings.clientIds[serviceId]?.trim()?.takeIf { it.isNotEmpty() }

    /** Client secret inserito dall'utente (solo flussi installed-app, es. Google). */
    fun ownClientSecret(ctx: Env): String? =
        ctx.store.data.settings.clientSecrets[serviceId]?.trim()?.takeIf { it.isNotEmpty() }

    override fun usesOwnCredentials(ctx: Env): Boolean = ownClientId(ctx) != null

    override fun isConfigured(ctx: Env): Boolean = clientId(ctx).isNotBlank()
    override fun isConnected(ctx: Env): Boolean = ctx.tokens.get(id) != null
    override fun accountName(ctx: Env): String? = ctx.tokens.get(id)?.userName?.takeIf { it.isNotBlank() }

    override fun startAuth(ctx: Env) = ctx.startAuth(this, AuthMode.BROWSER)

    /**
     * True where the service's sign-in page may run inside the app (WebAuthActivity): Spotify,
     * Deezer, TIDAL. False for Google, whose sign-in refuses embedded views.
     */
    open val loginInApp: Boolean get() = false

    override val authDomain: String? get() = runCatching { java.net.URI(authorizeEndpoint).host }.getOrNull()

    open fun authUrl(ctx: Env, state: String, codeChallenge: String): String =
        authUrl(ctx, state, codeChallenge, redirectUri(ctx))

    open fun authUrl(ctx: Env, state: String, codeChallenge: String, redirect: String): String {
        val params = mapOf(
            "response_type" to "code",
            "client_id" to clientId(ctx),
            "redirect_uri" to redirect,
            "scope" to scopes,
            "state" to state,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge,
        ) + extraAuthParams + switchAccountParams
        return authorizeEndpoint + "?" + Http.query(params)
    }

    override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {
        val code = params["code"] ?: throw ProviderException("missing authorization code")
        val resp = Http.send(
            "POST", tokenEndpoint, mapOf("Accept" to "application/json"),
            Http.form(
                buildMap {
                    put("grant_type", "authorization_code")
                    put("code", code)
                    put("redirect_uri", params[PARAM_REDIRECT_URI] ?: redirectUri(ctx))
                    put("client_id", clientId(ctx))
                    put("code_verifier", verifier)
                    ownClientSecret(ctx)?.let { put("client_secret", it) }
                }
            ),
        )
        if (!resp.ok) throw ProviderException("token exchange failed (${resp.code}): ${resp.body.take(200)}")
        val j = parseJson(resp.body)
        var t = Tokens(
            accessToken = j["access_token"].str ?: throw ProviderException("no access_token in response"),
            refreshToken = j["refresh_token"].str,
            expiresAt = expiry(j["expires_in"].long),
        )
        val store = ctx.tokens
        store.set(id, t)
        // A token the service then refuses (Spotify without Premium) must not show as "connected".
        try { t = enrichAccount(ctx, t) } catch (e: Exception) { store.clear(id); throw e }
        store.set(id, t)
    }

    /** Dopo il login: recupera id/nome utente (e altro) da salvare nei token. */
    protected open suspend fun enrichAccount(ctx: Env, t: Tokens): Tokens = t

    override fun disconnect(ctx: Env) = ctx.tokens.clear(id)

    protected open suspend fun tokens(ctx: Env): Tokens {
        val t = ctx.tokens.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        return if (t.isExpiringSoon() && t.refreshToken != null) renew(ctx, t) else t
    }

    /**
     * Renews the access the caller read as [seen] (expiring, or refused with a 401), one renewal at
     * a time per account ([refreshLock]): several syncs at once would otherwise each send the same
     * refresh token, and a service that rotates them refuses all but the first, which used to sign
     * the account out (TIDAL, 25/11). Inside the lock the stored tokens are read again: when another
     * call renewed them meanwhile, they are used as they are, with no refresh of their own.
     */
    protected suspend fun renew(ctx: Env, seen: Tokens): Tokens = refreshLock(ctx).withLock {
        val now = ctx.tokens.get(id) ?: throw ProviderException(ctx.text("error_not_connected", displayName))
        if (now.accessToken != seen.accessToken || now.refreshToken != seen.refreshToken) return@withLock now
        val fresh = refresh(ctx, now)
        ctx.tokens.set(id, fresh)
        fresh
    }

    /** One lock per account and per data folder (a shared server holds several people's tokens). */
    private fun refreshLock(ctx: Env): Mutex = REFRESH_LOCKS.getOrPut(ctx.dataDir.absolutePath + "|" + id) { Mutex() }

    /**
     * Renews this account's access now (the twice-daily keep-alive, "Reconnect"), so a refresh token
     * stays in use: [KeepAlive.RENEWED], [KeepAlive.EXPIRED] when the service ended the sign-in (the
     * tokens are gone), [KeepAlive.NOT_SIGNED_IN], or [KeepAlive.FAILED] (no network, an outage:
     * nothing changed). A connector without a refresh token makes one cheap signed-in call ([probe]).
     */
    override suspend fun keepAlive(ctx: Env): KeepAlive {
        val t = ctx.tokens.get(id) ?: return KeepAlive.NOT_SIGNED_IN
        return try {
            if (t.refreshToken != null) renew(ctx, t) else probe(ctx)
            KeepAlive.RENEWED
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            if (ctx.tokens.get(id) == null) KeepAlive.EXPIRED else KeepAlive.failed(e)
        }
    }

    /** One cheap signed-in call, for a connector whose access has no refresh token; [keepAlive] reads a sign-out from the tokens being gone. */
    protected open suspend fun probe(ctx: Env) { tokens(ctx) }

    protected open suspend fun refresh(ctx: Env, t: Tokens): Tokens {
        val resp = Http.send(
            "POST", tokenEndpoint, mapOf("Accept" to "application/json"),
            Http.form(
                buildMap {
                    put("grant_type", "refresh_token")
                    put("refresh_token", t.refreshToken ?: "")
                    put("client_id", clientId(ctx))
                    ownClientSecret(ctx)?.let { put("client_secret", it) }
                }
            ),
        )
        if (!resp.ok) {
            if (resp.code == 400 || resp.code == 401) {
                // Refused: signed out only when the refresh token that failed is still the stored one.
                // One another call (or a new sign-in) stored meanwhile is kept, and used.
                val stored = ctx.tokens.get(id)
                if (stored != null && stored.refreshToken != t.refreshToken) {
                    ctx.log(id, "access not renewed (HTTP ${resp.code}) with a refresh token already replaced: the stored one is kept")
                    return stored
                }
                ctx.log(id, "access not renewed (HTTP ${resp.code}): signed out, a new sign-in is needed")
                ctx.tokens.clear(id)
            } else {
                // The status alone: the answer's text is not logged, nor anything of the tokens.
                ctx.log(id, "access not renewed (HTTP ${resp.code})")
            }
            throw ProviderException("$displayName: session expired, please reconnect (${resp.code})")
        }
        val j = parseJson(resp.body)
        ctx.log(id, "access renewed" + (j["expires_in"].long?.let { " for ${it / 60} min" } ?: ""))
        return t.copy(
            accessToken = j["access_token"].str ?: t.accessToken,
            refreshToken = j["refresh_token"].str ?: t.refreshToken,
            expiresAt = expiry(j["expires_in"].long),
        )
    }

    /** Chiamata API autenticata; su 401 prova un refresh e ripete una volta. */
    protected suspend fun api(
        ctx: Env,
        method: String,
        url: String,
        body: JsonElement? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        var t = tokens(ctx)
        var resp = raw(t, method, url, body, extraHeaders)
        if (resp.code == 401 && t.refreshToken != null) {
            t = renew(ctx, t)
            resp = raw(t, method, url, body, extraHeaders)
        }
        if (!resp.ok) {
            // A 429 with what the service asks for: a pause in seconds (Retry-After), or a quota used up.
            val limit = if (resp.code != 429) "" else " (too many requests" + (resp.header("Retry-After")?.trim()?.toLongOrNull()?.let { ", retry after ${it}s" } ?: "") +
                (if (resp.body.contains("quota", ignoreCase = true)) ", quota" else "") + ")"
            ctx.log(id, "HTTP ${resp.code}$limit $method ${url.substringBefore('?').take(120)}: ${errorMessage(resp).take(160)}")
            throw ProviderException(friendlyApiError(ctx, resp) ?: "$displayName API ${resp.code}: ${errorMessage(resp)}", code = resp.code)
        }
        return parseJson(resp.body)
    }

    /**
     * One signed-in call whose answer comes back as it is, neither thrown nor logged: for a nicety
     * (a playlist's picture) whose failure is one line of the caller's. An expired token is
     * refreshed once, as [api] does.
     */
    protected suspend fun authorized(ctx: Env, method: String, url: String, body: okhttp3.RequestBody?, extraHeaders: Map<String, String> = emptyMap()): HttpResponse {
        var t = tokens(ctx)
        suspend fun send(t: Tokens) = Http.send(method, url, mapOf("Authorization" to "Bearer ${t.accessToken}", "Accept" to apiAccept) + extraHeaders, body)
        var resp = send(t)
        if (resp.code == 401 && t.refreshToken != null) {
            t = renew(ctx, t)
            resp = send(t)
        }
        return resp
    }

    /** Un servizio puo' spiegare un errore ricorrente con parole sue (es. i 403 di Spotify). */
    protected open fun friendlyApiError(ctx: Env, resp: HttpResponse): String? = null

    private suspend fun raw(t: Tokens, method: String, url: String, body: JsonElement?, extra: Map<String, String>): HttpResponse =
        Http.send(
            method, url,
            mapOf("Authorization" to "Bearer ${t.accessToken}", "Accept" to apiAccept) + extra,
            body?.let { Http.jsonBody(it.toString(), apiContentType) },
        )

    protected open fun errorMessage(resp: HttpResponse): String = resp.body.take(300)

    companion object {
        /** Chiave interna: il redirect effettivo usato, quando non e' quello di default (loopback). */
        const val PARAM_REDIRECT_URI = "__redirect_uri"
        private val REFRESH_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    }

    protected fun expiry(expiresIn: Long?): Long =
        if (expiresIn == null || expiresIn <= 0) 0 else System.currentTimeMillis() + expiresIn * 1000
}
