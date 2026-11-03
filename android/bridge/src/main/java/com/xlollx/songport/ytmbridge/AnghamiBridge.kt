package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * Anghami through play.anghami.com's web player ([AnghamiWebClient]): the user signs in once on
 * Anghami's own page inside a WebView (email, phone, Google, Apple, Facebook); the player then names
 * its session (`sid`) on every request it makes, which the sign-in screen reads as the requests go by
 * and keeps encrypted in [session]. Where the in-app sign-in cannot finish, the sid copied from a
 * desktop browser (any coussa.anghami.com request's address in the developer tools) can be pasted.
 */
object AnghamiBridge {
    val session = WebSession("anghami", listOf("play.anghami.com", "anghami.com", "coussa.anghami.com"))
    /** What the session's "cookies" hold: the session lives in the sid, not in cookies. */
    private const val MARK = "anghami=1"

    /** The sid and fingerprint of a player request to [url], when it carries a signed-in session. */
    fun fromRequest(url: String): Pair<String, String?>? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (!host.endsWith("anghami.com")) return null
        return AnghamiWebClient.sessionIn(url)?.takeIf { url.contains("sid=") }
    }

    /** Keeps the sid in [pasted] (with the player's fingerprint, when known); what is wrong, or null. */
    fun keep(ctx: Context, pasted: String, fingerprint: String? = null): String? {
        val sid = AnghamiWebClient.sidIn(pasted) ?: return "no Anghami session in it"
        session.clear(ctx)
        session.put(ctx, AnghamiWebClient.SID, sid)
        fingerprint?.let { session.put(ctx, AnghamiWebClient.FP, it) }
        session.save(ctx, MARK, "Anghami")
        return null
    }

    /** The app's side of [AnghamiWebClient]: the session kept at sign-in. */
    fun host(ctx: Context): WebHost = object : WebHost {
        override fun cookies(): String? = session.cookies(ctx)
        override fun account(): String? = session.account(ctx)
        override fun get(key: String): String? = session.get(ctx, key)
        override fun put(key: String, value: String?) = session.put(ctx, key, value)
        override val userAgent: String get() = BridgeUa.desktop(ctx)
    }
}

/** The client as the app builds it, from a Context. */
fun AnghamiWebClient(ctx: Context): AnghamiWebClient = AnghamiWebClient(AnghamiBridge.host(ctx))
