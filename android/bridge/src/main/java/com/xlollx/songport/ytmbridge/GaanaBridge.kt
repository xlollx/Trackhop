package com.xlollx.songport.ytmbridge

import android.content.Context

/**
 * Gaana through gaana.com's web player ([GaanaWebClient]): the user signs in once on Gaana's own
 * page inside a WebView (phone number, Google, email); the page then names its session (`token=`)
 * in the address of every signed call to api.gaana.com and keeps it in its storage (`uToken`),
 * which the sign-in screen reads as the requests go by (or from the storage) and keeps encrypted
 * in [session]. Where the in-app sign-in cannot finish, a request copied from a desktop browser's
 * developer tools (or the token alone) can be pasted.
 */
object GaanaBridge {
    val session = WebSession("gaana", listOf("gaana.com", "www.gaana.com", "api.gaana.com"))
    /** What the session's "cookies" hold: the session lives in the token, not in cookies. */
    private const val MARK = "gaana=1"

    /** Reads the page's token out of its storage: a JSON string literal, or null. */
    const val STORAGE_SCRIPT = "(function(){try{return window.localStorage.getItem('${GaanaWebClient.STORAGE_KEY}')}catch(e){return null}})()"

    /** The session of one account: the primary's, or an extra slot's own (see [Slots]). */
    fun session(slot: String): WebSession = session.forSlot(slot)

    /** The session of a page's request to [url] with [headers], when it carries a signed-in one. */
    fun fromRequest(url: String, headers: Map<String, String>?): GaanaWebClient.Seen? = GaanaWebClient.sessionOf(url, headers)

    /**
     * Keeps the session in [pasted] (with the page's device id and country, when known); what is
     * wrong, or null. [fromPage]: read from the sign-in's page, whose cookies and storage are the
     * site's session and stay (a refused token is renewed from them, see [renewal]); a pasted
     * session clears them, so a renewal never picks up another account the page was signed in with.
     */
    fun keep(ctx: Context, pasted: String, deviceId: String? = null, country: String? = null, account: String? = null, slot: String = Slots.PRIMARY, fromPage: Boolean = false): String? {
        val seen = GaanaWebClient.sessionIn(pasted) ?: return "no Gaana session in it"
        val sess = session(slot)
        if (fromPage) sess.forgetStored(ctx) else sess.clear(ctx)
        renewal.forget(sess.slot)
        sess.put(ctx, GaanaWebClient.TOKEN, seen.token)
        (deviceId ?: seen.deviceId)?.let { sess.put(ctx, GaanaWebClient.DEVICE_ID, it) }
        (country ?: seen.country)?.let { sess.put(ctx, GaanaWebClient.COUNTRY, it) }
        sess.save(ctx, MARK, account ?: "Gaana")
        return null
    }

    /** Names the account once the session has been tried. */
    fun name(ctx: Context, account: String, slot: String = Slots.PRIMARY) { val sess = session(slot); if (sess.isConnected(ctx)) sess.save(ctx, MARK, account) }

    /** The app's side of [GaanaWebClient]: the session kept at sign-in. */
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
     * The session renewed without the person when Gaana refuses the token: gaana.com, opened hidden
     * in the account's profile, names its token in the address of its signed calls and keeps it in
     * its storage (`uToken`), both read as at sign-in.
     */
    val renewal = SessionRenewal(
        service = "gaana",
        session = { session(it) },
        tokenKey = GaanaWebClient.TOKEN,
    ) { slot ->
        HiddenSessionPage.Spec(
            profile = "gaana", slot = slot, url = GaanaWebClient.SITE + "/",
            fromRequest = { r -> fromRequest(r.url.toString(), r.requestHeaders)?.let { values(it) } },
            storageScript = STORAGE_SCRIPT,
            fromStorage = { raw -> GaanaWebClient.sessionIn(raw)?.let { mapOf(GaanaWebClient.TOKEN to it.token) } },
        )
    }

    private fun values(s: GaanaWebClient.Seen): Map<String, String> = listOfNotNull(
        GaanaWebClient.TOKEN to s.token, s.deviceId?.let { GaanaWebClient.DEVICE_ID to it }, s.country?.let { GaanaWebClient.COUNTRY to it },
    ).toMap()
}

/** The client as the app builds it, from a Context; [slot] names an extra account. */
fun GaanaWebClient(ctx: Context, slot: String = Slots.PRIMARY, renews: Boolean = true): GaanaWebClient = GaanaWebClient(GaanaBridge.host(ctx, slot, renews))
