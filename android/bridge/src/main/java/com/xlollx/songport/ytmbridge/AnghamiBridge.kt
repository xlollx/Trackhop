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

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /**
     * The account's browser engine: a hidden page of play.anghami.com in the sign-in's profile, with
     * the sign-in's desktop identity, whose fetch() reaches the gateway as the player does.
     */
    fun engine(slot: String): WebEngine = WebEngine.of(
        WebEngine.Config(
            service = "anghami", slot = slot,
            startUrl = "${AnghamiWebClient.SITE}/robots.txt", fullUrl = "${AnghamiWebClient.SITE}/",
            hosts = listOf("anghami.com"), desktop = true,
        ),
    )

    /**
     * The client's [AnghamiWebClient.Transport] over [engine]. A 406 from the light page is tried once
     * more after the player's full page has loaded (its bot check may want what its scripts leave).
     */
    fun transport(ctx: Context, engine: WebEngine) = AnghamiWebClient.Transport { method, url, form ->
        fun once(): WebEngine.Response = engine.fetch(
            ctx, method, url, headers = mapOf("Accept" to "application/json, text/plain, */*"),
            body = form, contentType = form?.let { "application/x-www-form-urlencoded" },
        )
        var r = once()
        if (r.code == 406 && !engine.warmed) {
            BridgeNet.log("anghami", "browser engine: refused (406) from the light page, trying from the player's page")
            engine.warm(ctx)
            r = once()
        }
        r.code to r.body
    }

    /** The sid and fingerprint of a player request to [url], when it carries a signed-in session. */
    fun fromRequest(url: String): Pair<String, String?>? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        if (!host.endsWith("anghami.com")) return null
        return AnghamiWebClient.sessionIn(url)?.takeIf { url.contains("sid=") }
    }

    /**
     * Keeps the sid in [pasted] (with the player's fingerprint, when known); what is wrong, or null.
     * [fromPage]: read from the player in the sign-in's page, whose cookies are its site session and
     * stay (a refused sid is renewed from them, see [renewal]); a pasted session clears them, so a
     * renewal never picks up another account the page was signed in with.
     */
    fun keep(ctx: Context, pasted: String, fingerprint: String? = null, slot: String = Slots.PRIMARY, fromPage: Boolean = false): String? {
        val sid = AnghamiWebClient.sidIn(pasted) ?: AnghamiWebClient.sidValue(pasted) ?: return "no Anghami session in it"
        val sess = session(slot)
        if (fromPage) sess.forgetStored(ctx) else sess.clear(ctx)
        renewal.forget(sess.slot)
        sess.put(ctx, AnghamiWebClient.SID, sid)
        fingerprint?.let { sess.put(ctx, AnghamiWebClient.FP, it) }
        sess.save(ctx, MARK, "Anghami")
        return null
    }

    /** The app's side of [AnghamiWebClient]: the session kept at sign-in. */
    /** [renews]: a refused session is renewed in a hidden page; not while the sign-in tries the one it just read. */
    fun host(ctx: Context, slot: String = Slots.PRIMARY, renews: Boolean = true): WebHost = object : WebHost {
        private val sess = session(slot)
        override fun cookies(): String? = sess.cookies(ctx)
        override fun account(): String? = sess.account(ctx)
        override fun get(key: String): String? = sess.get(ctx, key)
        override fun put(key: String, value: String?) = sess.put(ctx, key, value)
        override val userAgent: String get() = BridgeUa.desktop(ctx)
        override val slot: String get() = sess.slot
        override fun renewSession(refused: String?): Boolean = renews && renewal.renew(ctx, sess.slot, refused)
    }

    /**
     * The session renewed without the person when Anghami refuses the sid: the player at
     * play.anghami.com, opened hidden in the account's profile, names its sid in the address of its
     * gateway calls, as at sign-in ([fromRequest]).
     */
    val renewal = SessionRenewal(
        service = "anghami",
        session = { session(it) },
        tokenKey = AnghamiWebClient.SID,
        labels = mapOf(AnghamiWebClient.SID to "sid"),
    ) { slot ->
        HiddenSessionPage.Spec(
            profile = "anghami", slot = slot, url = AnghamiWebClient.SITE + "/", widePage = true,
            fromRequest = { r ->
                fromRequest(r.url.toString())?.let { (sid, fp) ->
                    listOfNotNull(AnghamiWebClient.SID to sid, fp?.let { AnghamiWebClient.FP to it }).toMap()
                }
            },
        )
    }
}

/**
 * The client as the app builds it, from a Context; [slot] names an extra account. Its calls go
 * through the account's browser engine ([AnghamiBridge.engine]): Anghami answers plain HTTP with a
 * 406 (#53). An engine that cannot start in this process leaves the client on plain HTTP.
 */
fun AnghamiWebClient(ctx: Context, slot: String = Slots.PRIMARY, renews: Boolean = true): AnghamiWebClient {
    val engine = AnghamiBridge.engine(slot)
    return AnghamiWebClient(AnghamiBridge.host(ctx, slot, renews), if (engine.usable()) AnghamiBridge.transport(ctx, engine) else null)
}
