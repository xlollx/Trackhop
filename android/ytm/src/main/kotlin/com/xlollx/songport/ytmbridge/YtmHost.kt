package com.xlollx.songport.ytmbridge

import okhttp3.Interceptor

/**
 * What [YtmClient] needs from where it runs. In the app (:bridge) the session comes from the
 * Keystore, calls go through a hidden WebView first and Google's block is remembered in the app's
 * preferences; on the server the cookies are the ones the user pasted and every call is plain HTTP.
 */
interface YtmHost {
    /** The session's cookies, as a `Cookie` header; null when not connected. */
    fun cookies(): String?
    /** The desktop Chrome user agent the requests carry. */
    val userAgent: String
    /** Added to the HTTP client (the app's connections log); null for none. */
    val interceptor: Interceptor?
    /** A real browser engine to send the calls through; null where there is none. */
    val web: YtmWebTransport?
    /** Where Google's block outlives the process. */
    val verification: YtmVerificationStore
    /** How an error asks for a new session, e.g. "sign out of YouTube Music and sign in again". */
    val signInAgain: String
    /** Said after Google's block, when this place has a way out of it; null for nothing more. */
    val blockedHint: String?
    /** The diagnostics log, where there is one: what happened, never a cookie or an id. */
    fun log(message: String) {}
    /**
     * What the client's process-wide cache of the signed-in player page (visitor id, client version)
     * is kept under: "" for the one account there has always been; an extra account, or on a server
     * each person's data folder and account, has its own, so no session reads another's page.
     */
    val cacheScope: String get() = ""
    /**
     * The channel the account's calls are made as ([YtmClient.channels]): the page id of a brand
     * account the person chose, sent as `onBehalfOfUser`; null (the default, and every session from
     * before this existed) is the account's own channel, with nothing added to the calls.
     */
    val channel: String? get() = null
    /**
     * Whether this place can renew the session without the person ([renewSession]): the app can,
     * by running the player's page in its browser engine; the server, with pasted cookies, cannot.
     */
    val canRenew: Boolean get() = false
    /**
     * A signed-in call was refused (401) with [refused] (the cookies it carried): the session is
     * renewed the way the player's own page renews it, and the cookies to make the call once more
     * with come back; null when it could not be (the call then fails as it always has). Asked at
     * most once per call.
     */
    fun renewSession(refused: String): String? = null
    /**
     * Why the last [renewSession] failed when it was the connection (its page could not load), in the
     * network's words; null otherwise (the default). The refused call then says that, not "sign in again".
     */
    fun renewalLostConnection(): String? = null
}

/** Calls made by a browser engine: Chrome's fingerprint, the browser's own cookies and headers. */
interface YtmWebTransport {
    class Answer(val code: Int, val body: String)

    /** False when the engine cannot start here: plain HTTP is used instead. */
    fun usable(): Boolean

    /** POSTs [body] to [path] (relative to the origin); with [credentials] the browser sends the session's cookies. */
    fun post(path: String, body: String, headers: Map<String, String>, credentials: Boolean): Answer

    /**
     * Google's resumable upload of [bytes] to [path] with the session's cookies (start, then the bytes
     * with "upload, finalize" at the address the start answered): the final answer. Null where this
     * engine does not upload: plain HTTP is used instead.
     */
    fun upload(path: String, bytes: ByteArray, headers: Map<String, String>, contentType: String): Answer? = null
}

/** Google's "Sorry..." block: when it ends, how often it was seen, and Google's page (nothing secret). */
interface YtmVerificationStore {
    fun until(): Long
    fun seen(): Long
    fun level(): Int
    fun html(): String?
    fun save(until: Long, seen: Long, level: Int, html: String)
    fun clear()
}
