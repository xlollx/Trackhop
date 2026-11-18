package com.xlollx.songport.ytmbridge

/**
 * What the Spotify and Deezer web clients ([SpotifyWebClient], [DeezerWebClient]) need from where
 * they run. In the app it is the WebView sign-in, kept with the Keystore ([WebSession]); on the
 * server (server/build.gradle compiles this file and those clients from here) it is the cookies the
 * user pasted and a file in its data folder. No Android in this file: the server has none.
 */
interface WebHost {
    /** The session's cookies, as a `Cookie` header; null when not connected. */
    fun cookies(): String?
    /** The account name saved at sign-in. */
    fun account(): String?
    /** A small value kept with the session (cached tokens, query hashes, the account id). */
    fun get(key: String): String?
    /** Keeps [value] under [key]; null forgets it. */
    fun put(key: String, value: String?)
    /** The desktop Chrome user agent the requests carry. */
    val userAgent: String
    /** Which account of the service this is: "" for the primary (and on the server), "2", "3"… for an extra one. */
    val slot: String get() = ""
    /**
     * What the clients' process-wide caches (the player page, the signed-in user) are kept under:
     * the account's slot, or on a server with several people each person's data folder and account,
     * so no session ever reads another's.
     */
    val cacheScope: String get() = slot
    /**
     * Renews the session the service refused ([refused]: the token it was refused with) without the
     * person, the way the connector's site renews its own (a hidden page of it), and keeps the new
     * one. True when there is a new session to make the call again with. Never clears anything.
     * Default: no renewal (the server, a connector without one).
     */
    fun renewSession(refused: String?): Boolean = false

    companion object {
        /** The value of cookie [name] in a `Cookie` header; null when absent or empty. */
        fun cookieValue(cookies: String?, name: String): String? = cookies?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }
}

/**
 * [call], made again once with a renewed session ([WebHost.renewSession]) when the service refused
 * the one it carried: [refused] tells such a failure, [sent] is the session the call went with
 * (null: none, nothing to renew). Not renewed, or refused again: [expired] ("sign in again"), as
 * before. A refusal means the service did nothing, so a write is safe to make again too. [tag]: the log's.
 */
fun <T> WebHost.renewing(tag: String, sent: String?, expired: String, refused: (Exception) -> Boolean, call: () -> T): T {
    try {
        return call()
    } catch (e: Exception) {
        if (!refused(e)) throw e
        if (sent == null || !renewSession(sent)) throw BridgeException(expired)
    }
    return try {
        call().also { BridgeNet.log(tag, "session refused; access renewed and the call made again: ok") }
    } catch (e: Exception) {
        if (!refused(e)) throw e
        BridgeNet.log(tag, "session refused; access renewed and the call made again: refused again")
        throw BridgeException(expired)
    }
}
