package com.xlollx.songport.server

/**
 * The release switch on Desktop and Docker: the features of Trackhop 2.0, each behind this one
 * switch, as in the app (the engine's own [com.xlollx.songport.Features], which this one drives).
 * Off by default; `TRACKHOP_PREVIEW_2=1` in the environment (or `-Dtrackhop.preview2=true`) turns
 * it on for testing, and the 2.0 release turns it on here, in one line ([DEFAULT]).
 *
 * Behind it: Wrapped from real listening (WrappedWeb.kt), the playlist poster (PosterWeb.kt), the
 * followed-artist alerts (AlertsWeb.kt) and Spotify (web) as a Concerts source
 * (SpotifyConcertsServer); Discover's New releases, Concerts, Setlists and Records and merch
 * (their pages, API calls, commands and MCP tools); new shared-playlist mailboxes (POST /groups);
 * podcasts (YouTube Music's followed podcasts as a source, OPML exports); playlist covers on the
 * services added in 2.0 and the engine's other 2.0 offers (TIDAL's liked tracks, the new lists).
 * Off, they are not offered, and everything else works as before; what a user already has (a
 * mailbox, a sync reading a 2.0 list) keeps working.
 */
internal object Features {
    /** The switch's value when nothing in the environment says otherwise: true from 2.0. */
    const val DEFAULT = false

    /** The 2.0 features are on. Set by tests; the engine's switch follows it. */
    var v2: Boolean
        get() = com.xlollx.songport.Features.build
        set(value) { com.xlollx.songport.Features.build = value }

    init { v2 = fromEnvironment(System.getenv(), System.getProperty("trackhop.preview2")) }

    /** Called at start, so the engine's switch is set before anything asks it. */
    fun init() = Unit

    /** On when TRACKHOP_PREVIEW_2 (or the trackhop.preview2 property) says so: 1, true, yes or on; else [DEFAULT]. */
    fun fromEnvironment(env: Map<String, String>, property: String? = null): Boolean {
        val v = (property ?: env["TRACKHOP_PREVIEW_2"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
        return v in setOf("1", "true", "yes", "on")
    }

    /** Discover's pages of 2.0 under Tools, and their commands: Files.kt, Cli and Mcp leave them out when off. */
    val V2_TOOLS = setOf("releases", "concerts", "setlists", "records")

    /** Their MCP tools. */
    val V2_MCP_TOOLS = setOf("find_new_releases", "find_concerts", "find_setlists", "find_records")

    /** The API's 2.0 calls by their first word ([V2_TOOLS], and the shared playlists' mailboxes). */
    private val V2_API = V2_TOOLS + "groups"

    /** A call of 2.0 that is not offered now: [V2_TOOLS]' calls, and making a new mailbox (the others serve the mailboxes there are). */
    fun hidesCall(method: String, rest: List<String>): Boolean = !v2 && when (rest.firstOrNull()) {
        in V2_TOOLS -> true
        "groups" -> rest.size == 1 && method == "POST"
        else -> false
    }

    /** A call of 2.0 left out of the API's description (openapi.json, the guide) when off. */
    fun hidesDoc(path: String): Boolean = !v2 && path.trimStart('/').substringBefore('/') in V2_API
}
