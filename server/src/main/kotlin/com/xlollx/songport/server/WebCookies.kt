package com.xlollx.songport.server

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.KeyValues
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.ytmbridge.WebHost
import com.xlollx.songport.ytmbridge.YtmCookies

/**
 * A connector signed in with the cookies of a desktop browser, pasted in the web page: the server
 * has no browser engine to run a sign-in page. YouTube Music ([YtmServerProvider]), Spotify
 * ([SpotifyWebServerProvider]) and Deezer ([DeezerWebServerProvider]) through their web players:
 * unofficial and alpha, which the page says wherever it lists them, with the same form for each.
 */
interface CookieConnector : MusicProvider {
    /** The site whose cookies are copied, as the form names it. */
    val cookieSite: String
    /** The cookies that matter, as the form's placeholder shows them. */
    val cookieExample: String
    /** The form's label for what is pasted. */
    val pasteLabel: String get() = "Cookies of $cookieSite"
    /** How to copy it, as HTML, when it is not the site's cookies; null for the cookies' own steps. */
    val pasteHelp: String? get() = null

    /**
     * Stores the pasted cookies (a `Cookie` header, cookies.txt or a JSON export) once one call has
     * shown they are signed in; a [com.xlollx.songport.model.ProviderException] says what is wrong.
     */
    suspend fun connect(ctx: Env, pasted: String, userAgent: String?)
}

/** [pasted] as the `Cookie` header of [domains]; null when it does not hold the [required] cookie. */
internal fun pastedCookies(pasted: String, domains: List<String>, required: String): String? =
    YtmCookies.header(pasted, domains)?.takeIf { WebHost.cookieValue(it, required) != null }

/** The browser's user agent given with the cookies, when it looks like one. */
internal fun pastedUserAgent(userAgent: String?): String? = userAgent?.trim()?.takeIf { it.startsWith("Mozilla/") }

/**
 * The server's side of the web clients compiled from the app's :bridge (SpotifyWebClient,
 * DeezerWebClient): the pasted cookies, the account and the user agent in tokens.json under [id],
 * the clients' cached values (tokens, query hashes, the gateway's sid) in [kv], no browser engine.
 * [fixed] stands in for tokens.json while new cookies are checked, before anything is saved.
 */
internal open class ServerWebHost(
    private val ctx: Env,
    private val id: String,
    private val kv: KeyValues,
    private val fixed: Tokens? = null,
) : WebHost {
    private val tokens: Tokens? get() = fixed ?: ctx.tokens.get(id)
    override fun cookies(): String? = tokens?.accessToken?.takeIf { it.isNotBlank() }
    override fun account(): String? = tokens?.userName?.takeIf { it.isNotBlank() }
    override fun get(key: String): String? = kv[key]?.takeIf { it.isNotEmpty() }
    // KeyValues holds no null: an empty value is a forgotten one.
    override fun put(key: String, value: String?) { kv[key] = value.orEmpty() }
    override val userAgent: String get() = tokens?.extra?.get(UA) ?: USER_AGENT

    companion object {
        /** The browser's user agent, when the user gave it with the cookies. */
        const val UA = "userAgent"
        /** Desktop Chrome, as the app's web connectors say they are: the players turn other browsers away. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    }
}
