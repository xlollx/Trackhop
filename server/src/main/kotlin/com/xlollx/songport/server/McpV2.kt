package com.xlollx.songport.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The MCP tools of the next release's features (Features.v2), each a command of the command line
 * ([CliV2]) as the other tools are ([Mcp]). None when the features are off.
 */
internal object McpV2 {
    private fun JsonObject.opt(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.need(key: String): String = opt(key) ?: throw Cli.Failure(2, "missing \"$key\"")
    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.values(vararg keys: String): Map<String, String> = keys.mapNotNull { k -> opt(k)?.let { k to it } }.toMap()

    private val SERVICE = Mcp.Param("service", "string", "The service's name or id, as list_services shows them (for example \"Spotify\" or \"spotify\").")
    private val PLAYLIST = Mcp.Param("playlist", "string", "The playlist's name or id, as list_playlists shows them.")
    private val ARTIST = Mcp.Param("artist", "string", "The artist's name, as the user says it or list_followed_artists shows it.")

    val tools: List<Mcp.Tool>
        get() = if (!Features.v2) emptyList() else listOf(
            Mcp.Tool("get_wrapped", "Read the user's Wrapped", "The user's Wrapped made from what they really played: the listening services connected (and the periods each can tell) and the Wrapped made so far, newest first, each with its top artists and songs (with plays where the service counts them), musical age, decades, period and service. With wrapped: only that one (its id, or latest).",
                listOf(Mcp.Param("wrapped", "string", "One Wrapped: its id, or latest.", required = false)), readOnly = true) { a ->
                a.opt("wrapped")?.let { Mcp.Call("wrapped", listOf("show", it)) } ?: Mcp.Call("wrapped")
            },
            Mcp.Tool("make_wrapped", "Make a Wrapped", "Makes a new Wrapped from what the user played on a listening service (Last.fm, ListenBrainz, their own server's play counts, Spotify, Deezer, TIDAL or YouTube Music) over a period, and keeps it in their history. Can take a minute. Its card is a PNG at the API's /wrapped/{id}/card, or Tools › Wrapped on Trackhop's page.",
                listOf(
                    Mcp.Param("source", "string", "The listening service, as get_wrapped lists them (lastfm, listenbrainz, spotify, jellyfin…); left out, the best one connected.", required = false),
                    Mcp.Param("period", "string", "month, half_year or year (the nearest one the service can tell is used).", required = false),
                ), readOnly = false) { a -> Mcp.Call("wrapped", listOf("new"), values = a.values("source", "period")) },
            Mcp.Tool("get_playlist_poster", "A playlist poster's contents", "What a poster of a playlist shows: the songs it lists (those the user plays most first, from the playlist's own server or a listening service, marked fromPlays), the lineup of its most present artists, the colours from its cover (ground, groundEnd, ink, accent), its header, a note on where the plays came from and the words for an image AI. Nothing is changed. Trackhop's page draws the poster (Tools › Playlist poster).",
                listOf(SERVICE, PLAYLIST, Mcp.Param("layout", "string", "classic (default), minimal or bold.", required = false)), readOnly = true) { a ->
                Mcp.Call("poster", listOf(a.need("service"), a.need("playlist")), values = a.values("layout"))
            },
            Mcp.Tool("list_followed_artists", "List followed artists", "The artists the user follows, what each is checked for (releases, concerts, merch, records) and whether muted, whether the look runs every day or week, and the latest news found (new releases, concerts, merch, records, with links).",
                emptyList(), readOnly = true) { Mcp.Call("followed") },
            Mcp.Tool("follow_artist", "Follow an artist", "Follows an artist: the user is told of their new releases, concerts, merch and records through their notification channels.",
                listOf(ARTIST, Mcp.Param("kinds", "string", "What to be told about, split by commas: releases, concerts, merch, records (the user's defaults when left out).", required = false)), readOnly = false) { a ->
                Mcp.Call("follow", listOf(a.need("artist")), values = a.values("kinds"))
            },
            Mcp.Tool("unfollow_artist", "Unfollow an artist", "Stops following an artist: what was remembered of them goes. Ask the user first.",
                listOf(ARTIST), readOnly = false, destructive = true) { a -> Mcp.Call("unfollow", listOf(a.need("artist"))) },
            Mcp.Tool("find_songs_without_lyrics", "Songs without lyrics", "The songs on the user's Navidrome (or other Subsonic server), Jellyfin, Emby, Plex or Lyrion server that have no lyrics, with what the server allows (support: api, files, report). Reads the whole library: it can take a while. Nothing is changed; lyrics are added from Trackhop's page (Tools › Synced lyrics), after the user sees what was found.",
                listOf(Mcp.Param("service", "string", "The server's name or id (list_services); left out when only one such server is connected.", required = false)), readOnly = true) { a ->
                Mcp.Call("nolyrics", listOfNotNull(a.opt("service")))
            },
            Mcp.Tool("check_followed_artists", "Look for news of followed artists", "Looks every followed artist up now (a background task); what is new goes to the user's notification channels and into the latest news.",
                listOf(Mcp.Param("wait", "boolean", "Wait until it ends (it can take minutes). Default false: answer at once with the task.", required = false)), readOnly = false) { a ->
                Mcp.Call("followed", listOf("check"), wait = a.flag("wait"))
            },
        )
}
