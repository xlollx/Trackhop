package com.xlollx.songport.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The MCP tools of the app's features brought to Desktop and Docker in 2.2, each a command of the
 * command line ([CliParity]) as the other tools are ([Mcp]).
 */
internal object McpParity {
    private fun JsonObject.opt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.need(key: String): String = opt(key) ?: throw Cli.Failure(2, "missing \"$key\"")

    private val SERVICE = Mcp.Param("service", "string", "The service's name or id, as list_services shows them (for example \"Spotify\" or \"spotify\").")
    private val PLAYLIST = Mcp.Param("playlist", "string", "The playlist's name or id, as list_playlists shows them.")

    val tools: List<Mcp.Tool> = listOf(
        Mcp.Tool("list_to_fix", "List the songs to fix", "Every song the last run of every sync could not find or matched loosely, once per pair of services, numbered, with the suggestion when there is one. Nothing is changed.",
            emptyList(), readOnly = true) { Mcp.Call("tofix") },
        Mcp.Tool("fix_songs", "Fix songs of To fix", "Applies a choice to songs of list_to_fix, for every sync between the same two services and in their playlists at once: use (the suggestion), ignore (not on that service, not searched for again) or later (until its sync runs again).",
            listOf(Mcp.Param("action", "string", "use, ignore or later."), Mcp.Param("rows", "string", "The rows' numbers from list_to_fix, split by spaces, or all.")), readOnly = false) { a ->
            Mcp.Call("tofix", listOf(a.need("action")) + a.need("rows").split(' ', ',').filter { it.isNotBlank() })
        },
        Mcp.Tool("list_schedules", "List saved schedules", "The saved schedules (a timing kept once for many syncs): name, timing and how many syncs follow each.",
            emptyList(), readOnly = true) { Mcp.Call("schedules") },
        Mcp.Tool("test_service", "Test a service's connection", "Three calls to a connected service, one after the other: who is signed in, the playlist list, one search; each with what the service answered. Nothing is changed.",
            listOf(SERVICE), readOnly = true) { a -> Mcp.Call("test", listOf(a.need("service"))) },
        Mcp.Tool("scan_library", "Scan a service's library", "The songs in more than one of the user's playlists on a service, and the liked songs in none. Read only; it reads every playlist once.",
            listOf(SERVICE), readOnly = true) { a -> Mcp.Call("scan", listOf(a.need("service"))) },
        Mcp.Tool("vanished_songs", "List vanished songs", "The songs that left the user's playlists, from the backups' dated copies and what mirror syncs removed.",
            emptyList(), readOnly = true) { Mcp.Call("vanished") },
        Mcp.Tool("playlist_link", "A playlist as a universal link", "The playlist as links (trackhop.org/open) that open it on any service; the songs travel in the link itself.",
            listOf(SERVICE, PLAYLIST), readOnly = true) { a -> Mcp.Call("link", listOf(a.need("service"), a.need("playlist"))) },
        Mcp.Tool("add_song_from_link", "Add a song from a link", "A song's link from any music app, found on one of the user's services and added to one of their playlists there (without playlist: only what was found).",
            listOf(Mcp.Param("link", "string", "The song's link."), SERVICE, Mcp.Param("playlist", "string", "The playlist to add it to, as list_playlists shows it.", required = false)), readOnly = false) { a ->
            Mcp.Call("add-link", listOfNotNull(a.need("link"), a.need("service"), a.opt("playlist")))
        },
    )
}
