package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.JsonElement

/*
 * Where the features of the next release (Features.v2) join the server: their pages under Tools,
 * their API calls and their description. Each hook in Web, Api, Runner, ApiDocs, Cli and Mcp is
 * one line that asks [Features.v2] first, so with it off nothing here is reachable.
 */

/** Tools › Wrapped, Poster and Followed artists; false when [rest] is none of them (or the features are off). */
internal fun Web.v2Tool(r: Web.Req, rest: List<String>): Boolean {
    if (!Features.v2) return false
    when (rest.firstOrNull()) {
        "wrapped" -> wrappedPage(r, rest.drop(1))
        "poster" -> if (rest.size == 1) posterPage(r) else return false
        "alerts" -> if (rest.size == 1) alertsPage(r) else return false
        else -> return false
    }
    return true
}

/** The Tools page's cards for them; empty when off. */
internal fun v2ToolCards(): String = if (!Features.v2) "" else buildString {
    fun tool(href: String, title: String, text: String) = append("""<div class="card"><h3><a href="$href">$title</a></h3><p class="muted">$text</p></div>""")
    append("<h2>Discover</h2>")
    tool("/tools/wrapped", "Wrapped", "Your top artists and songs, your musical age and your decades, from what you really played on Last.fm, ListenBrainz, your own server, Spotify, Deezer, TIDAL or YouTube Music: any time, as a card to share.")
    tool("/tools/poster", "Playlist poster", "A poster of a playlist: its artists and the songs you play most, in its cover's colours, to print or save as PDF.")
    tool("/tools/alerts", "Followed artists", "Follow artists and be told of their new releases, concerts, merch and records, through your notification channels.")
}

/** The API calls; false when [rest] is none of them (or the features are off). */
internal fun Web.apiV2(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (!Features.v2) return false
    val body = { apiJson(ex) }
    return apiWrapped(method, rest, body, json, error) { bytes, type -> sendBytes(ex, 200, bytes, type) } ||
        apiPoster(method, rest, query, json, error) ||
        apiFollowed(method, rest, body, json, error)
}

/** Their lines in the API's description (ApiDocs), and so in openapi.json and the guide's test. */
internal val V2_CALLS: List<ApiDocs.Call>
    get() = if (!Features.v2) emptyList() else listOf(
        ApiDocs.Call("GET", "/wrapped", "Discover", "Wrapped from real listening: the listening services connected (source, service, the periods each can tell, whether it counts plays) and every Wrapped made, newest first (top artists and songs with their plays, musical age, decades, covers, period, from, card)."),
        ApiDocs.Call("POST", "/wrapped", "Discover", "Make a Wrapped from what you played on a listening service (Last.fm, ListenBrainz, a personal server's play counts, Spotify official or web, Deezer, TIDAL, YouTube Music) over a period (month, half_year, year; the nearest one the service can tell); the best service connected when source is left out. Kept in your history; can take a minute (the years and covers are looked up).", body = "{\"source\"?: \"lastfm\", \"period\"?: \"month\" | \"half_year\" | \"year\"}"),
        ApiDocs.Call("GET", "/wrapped/{wrapped}", "Discover", "One Wrapped, by its id or latest."),
        ApiDocs.Call("GET", "/wrapped/{wrapped}/card", "Discover", "A Wrapped's card as a PNG image (a plainer card than the app's, the same figures); a refusal where the system cannot draw it."),
        ApiDocs.Call("GET", "/poster", "Discover", "The playlist poster's data: service= and playlist= (its id or name), layout= classic, minimal or bold. The songs it lists (those you play most first, from the playlist's own server or the first listening service connected; fromPlays marks them), the lineup of its most present artists, the palette from its cover (ground, groundEnd, ink, accent), the header (title, songs, length), a note on where the plays came from and the words for an image AI (coverPrompt). Draw it yourself, or open Tools › Playlist poster.", listOf("service", "playlist", "layout", "fresh")),
        ApiDocs.Call("GET", "/followed", "Discover", "The artists you follow, what each is checked for (releases, concerts, merch, records) and whether muted; whether the look runs on a schedule (enabled, every day or week), the concerts' country, how many notification channels take the news, and the latest news."),
        ApiDocs.Call("PATCH", "/followed", "Discover", "Turn the scheduled look on or off, every day or week.", body = "{\"enabled\"?: true, \"every\"?: \"day\" | \"week\"}"),
        ApiDocs.Call("POST", "/followed", "Discover", "Follow an artist: what it is checked for, your defaults where left out. Answers whether it was new (followed) and the artist.", body = "{\"name\", \"releases\"?, \"concerts\"?, \"merch\"?, \"records\"?}"),
        ApiDocs.Call("PATCH", "/followed/{artist}", "Discover", "Mute an artist (still followed, nothing said) or turn it on again, or change what it is checked for.", body = "{\"muted\"?, \"releases\"?, \"concerts\"?, \"merch\"?, \"records\"?}"),
        ApiDocs.Call("DELETE", "/followed/{artist}", "Discover", "Stop following an artist."),
        ApiDocs.Call("POST", "/followed/check", "Discover", "Look every artist followed up now (a background task): what is new goes to your notification channels that take New releases, and into the latest news."),
    )
