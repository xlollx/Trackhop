package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URLEncoder

/**
 * The commands of the next release's features (Features.v2), as the others ([CliCommands]): calls
 * to the API, plain text for people, the API's JSON with --json (and for the MCP tools).
 * `wrapped`, `poster`, `followed`, `follow`, `unfollow`.
 */
internal class CliV2(private val c: CliCommands) {
    companion object {
        /** Their help lines; none when the features are off. */
        val commands: List<Cli.Cmd>
            get() = if (!Features.v2) emptyList() else listOf(
                Cli.Cmd("wrapped", "Discover", listOf(
                    "wrapped",
                    "wrapped new [--source lastfm] [--period month|half_year|year]",
                    "wrapped list",
                    "wrapped show <wrapped>",
                    "wrapped card <file.png> [<wrapped>]",
                ), "your Wrapped from what you really played: top artists and songs, musical age, as a card",
                    "Made from a listening service connected here: Last.fm, ListenBrainz, your Navidrome, Jellyfin, Emby, Plex or Lyrion server's\n" +
                        "play counts, Spotify (official or web), Deezer, TIDAL or YouTube Music's recent plays. --source picks one (as trackhop wrapped\n" +
                        "lists them; the best one without it), --period the span (the nearest one the service can tell). Each Wrapped is kept:\n" +
                        "list and show read them back (<wrapped> is its id, or latest), card saves its card as a PNG image.",
                    listOf("new", "list", "show", "card")),
                Cli.Cmd("poster", "Discover", listOf("poster <service> <playlist> [--layout classic|minimal|bold]"), "a playlist poster's songs (those you play most first), artists and colours",
                    "The songs you play most come from the playlist's own server, else the first listening service connected. The page draws the\n" +
                        "poster (Tools, Playlist poster); --json gives everything to draw it yourself: palette, lineup, songs, header, the words for an image AI."),
                Cli.Cmd("followed", "Discover", listOf(
                    "followed",
                    "followed check [--wait]",
                    "followed mute|unmute <artist>",
                    "followed on|off|daily|weekly",
                ), "the artists you follow, their latest news, and the look for news",
                    "The artists followed are looked up on a schedule (every day, or every week): new releases, concerts in the country picked\n" +
                        "last in Concerts, new merch and records. The news goes to your notification channels that take New releases\n" +
                        "(trackhop notify). check looks them all up now; mute keeps an artist followed with nothing said; on and off start\n" +
                        "and stop the scheduled look.",
                    listOf("check", "mute", "unmute", "on", "off", "daily", "weekly")),
                Cli.Cmd("follow", "Discover", listOf("follow <artist> [--kinds releases,concerts,merch,records]"), "follow an artist, to be told of their news",
                    "--kinds: what to be told about (your defaults without it)."),
                Cli.Cmd("unfollow", "Discover", listOf("unfollow <artist>"), "stop following an artist"),
            )

        /** Their options that take a value. */
        val valued: Set<String> get() = setOf("source", "period", "layout", "kinds")

        private val pretty = Json { prettyPrint = true }
    }

    private val api get() = c.api
    private val out get() = c.out
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()
    private fun JsonObject.n(k: String) = (this[k] as? JsonPrimitive)?.intOrNull ?: 0
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().map { it.jsonObject }

    /** The exit code, or null for a command that is not one of these. */
    fun run(command: String, p: List<String>, o: Cli.Opts): Int? {
        if (!Features.v2) return null
        return when (command) {
            "wrapped" -> wrapped(p, o)
            "poster" -> {
                if (p.size < 2) throw Cli.Failure(2, "usage: trackhop poster <service> <playlist> [--layout classic|minimal|bold]")
                poster(p[0], p.drop(1).joinToString(" "), o)
            }
            "followed" -> followed(p, o)
            "follow" -> follow(p.joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop follow <artist> [--kinds releases,concerts,merch,records]") }, o)
            "unfollow" -> unfollow(p.joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop unfollow <artist>") })
            else -> null
        }
    }

    // ------------------------------------------------------------------ wrapped

    private fun wrapped(p: List<String>, o: Cli.Opts): Int = when (p.firstOrNull()) {
        null -> {
            val r = api.get("wrapped").jsonObject
            if (c.asJson) print(r).let { 0 } else {
                val sources = r.list("sources")
                if (sources.isEmpty()) out.println("No listening service connected: connect Last.fm, ListenBrainz, your own server, Spotify, Deezer, TIDAL or YouTube Music in Services.")
                else out.println("Listening services: " + sources.joinToString(", ") { "${it.s("service")} (${it.s("source")}: ${(it["periods"] as? JsonArray).orEmpty().joinToString("/") { e -> e.str().orEmpty() }})" })
                val made = r.list("wrapped")
                if (made.isEmpty()) out.println("No Wrapped made yet: trackhop wrapped new")
                else {
                    out.println()
                    summary(made.first())
                    if (made.size > 1) out.println("${made.size - 1} more made before: trackhop wrapped list")
                }
                0
            }
        }
        "new" -> {
            if (!c.asJson) out.println("Reading what you played and looking up the songs' years and covers (this can take a minute)...")
            val w = api.post("wrapped", buildJsonObject { o.value("source")?.let { put("source", it) }; o.value("period")?.let { put("period", it) } }).jsonObject
            if (c.asJson) print(w) else summary(w)
            0
        }
        "list" -> {
            val made = api.get("wrapped").jsonObject.list("wrapped")
            if (c.asJson) print(JsonArray(made)) else {
                if (made.isEmpty()) out.println("No Wrapped made yet: trackhop wrapped new")
                made.forEach { w -> out.println("${w.s("id")}  ${w.s("eyebrow")}  ${w.list("services").firstOrNull()?.s("name") ?: "playlists"}${w.list("topArtists").firstOrNull()?.let { "  top: ${it.s("name")}" } ?: ""}") }
            }
            0
        }
        "show" -> {
            val id = p.getOrNull(1) ?: "latest"
            val w = api.get("wrapped/${enc(id)}").jsonObject
            if (c.asJson) print(w) else summary(w)
            0
        }
        "card" -> {
            val file = p.getOrNull(1) ?: throw Cli.Failure(2, "usage: trackhop wrapped card <file.png> [<wrapped>]")
            val id = p.getOrNull(2) ?: "latest"
            val bytes = api.bytes("GET", "wrapped/${enc(id)}/card")
            File(file).writeBytes(bytes)
            if (c.asJson) print(buildJsonObject { put("file", file); put("bytes", bytes.size) }) else out.println("Saved the card to $file.")
            0
        }
        else -> throw Cli.Failure(2, "usage: trackhop wrapped [new|list|show|card]. See: trackhop help wrapped")
    }

    private fun plays(n: Int) = if (n == 1) "1 play" else "$n plays"

    private fun summary(w: JsonObject) {
        out.println("${w.s("eyebrow")}  (${w.s("from")}, id ${w.s("id")})")
        w["age"].str()?.let { age -> out.println("Musical age $age${w["bornIn"].str()?.let { ": you listen like someone born in $it" } ?: ""}") }
        val artists = w.list("topArtists")
        if (artists.isNotEmpty()) {
            out.println("Top artists:")
            artists.forEachIndexed { i, a -> out.println("  ${i + 1}. ${a.s("name")}${a.n("plays").takeIf { it > 0 }?.let { "  (${plays(it)})" } ?: ""}") }
        }
        val songs = w.list("topTracks")
        if (songs.isNotEmpty()) {
            out.println("Top songs:")
            songs.forEachIndexed { i, s -> out.println("  ${i + 1}. ${s.s("artist")} - ${s.s("title")}${s.n("plays").takeIf { it > 0 }?.let { "  (${plays(it)})" } ?: ""}") }
        }
        val decades = w.list("decades")
        if (decades.isNotEmpty()) out.println("Decades: " + decades.joinToString(", ") { "${it.s("decade")}s ${it.s("percent")}%" })
        listOfNotNull(
            w.n("plays").takeIf { it > 0 }?.let { plays(it) }, w.n("artistCount").takeIf { it > 0 }?.let { "$it artists" }, w["discovery"].str()?.let { "discovery: $it" },
        ).takeIf { it.isNotEmpty() }?.let { out.println(it.joinToString(", ")) }
        out.println("The card: trackhop wrapped card wrapped.png ${w.s("id")}")
    }

    // ------------------------------------------------------------------ poster

    private fun poster(serviceAsk: String, playlistAsk: String, o: Cli.Opts): Int {
        val s = c.service(serviceAsk)
        val pl = c.playlist(s.s("id"), playlistAsk)
        val r = api.get("poster?service=${enc(s.s("id"))}&playlist=${enc(pl.s("id"))}" + (o.value("layout")?.let { "&layout=${enc(it)}" } ?: "")).jsonObject
        if (c.asJson) return print(r).let { 0 }
        out.println("${r.s("title")}  (${r.s("service")}, ${r.n("trackCount")} songs, ${r.s("layout")})")
        val lineup = (r["lineup"] as? JsonArray).orEmpty().mapNotNull { it.str() }
        if (lineup.isNotEmpty()) out.println(lineup.take(6).joinToString("  ·  "))
        r.list("tracks").forEachIndexed { i, t -> out.println("  ${i + 1}. ${t.s("artist")} - ${t.s("title")}${if (t.b("fromPlays")) "  *" else ""}") }
        out.println(r.s("note") + if (r.n("fromPlays") > 0) " (* the songs from your plays)" else "")
        r["palette"]?.jsonObject?.let { pa -> out.println("Colours: ground ${pa.s("ground")} to ${pa.s("groundEnd")}, ink ${pa.s("ink")}, accent ${pa.s("accent")}") }
        out.println("The poster itself: Trackhop's page, Tools, Playlist poster.")
        return 0
    }

    // ------------------------------------------------------------------ followed artists

    private fun kindsLine(k: JsonObject?) = listOf("releases", "concerts", "merch", "records").filter { k?.b(it) == true }.joinToString(", ").ifEmpty { "nothing" }

    private fun followed(p: List<String>, o: Cli.Opts): Int {
        when (p.firstOrNull()) {
            null -> {
                val r = api.get("followed").jsonObject
                if (c.asJson) return print(r).let { 0 }
                val artists = r.list("artists")
                out.println(if (r.b("enabled")) "Looked up every ${r.s("every")}, concerts in ${r.s("country")}; news to ${r.n("channels")} notification channel(s)." else "The scheduled look is off (trackhop followed on).")
                if (artists.isEmpty()) out.println("You follow no artist: trackhop follow <artist>")
                artists.forEach { a -> out.println("  ${a.s("name")}  (${kindsLine(a["kinds"]?.jsonObject)})${if (a.b("muted")) "  muted" else ""}") }
                val latest = r.list("latest")
                if (latest.isNotEmpty()) {
                    out.println("Latest news:")
                    latest.forEach { n -> out.println("  ${n.s("artist")}: ${n.s("kind")}  ${n.s("title")}${n.s("detail").takeIf { it.isNotEmpty() && n.s("kind") == "concerts" }?.let { " ($it)" } ?: ""}  ${n.s("url")}") }
                }
                return 0
            }
            "check" -> {
                val task = api.post("followed/check").jsonObject.s("task")
                return c.followTask(task, o.has("wait"), "Looking up the artists you follow")
            }
            "mute", "unmute" -> {
                val name = p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop followed ${p[0]} <artist>") }
                val a = api.send("PATCH", "followed/${enc(name)}", buildJsonObject { put("muted", p[0] == "mute") }).jsonObject
                if (c.asJson) print(a) else out.println(if (a.b("muted")) "${a.s("name")}: muted (still followed)." else "${a.s("name")}: alerts on.")
                return 0
            }
            "on", "off", "daily", "weekly" -> {
                val body = buildJsonObject {
                    when (p[0]) { "on" -> put("enabled", true); "off" -> put("enabled", false); "daily" -> put("every", "day"); else -> put("every", "week") }
                }
                val r = api.send("PATCH", "followed", body).jsonObject
                if (c.asJson) print(r) else out.println(if (r.b("enabled")) "The look runs every ${r.s("every")}." else "The scheduled look is off.")
                return 0
            }
            else -> throw Cli.Failure(2, "usage: trackhop followed [check|mute|unmute|on|off|daily|weekly]. See: trackhop help followed")
        }
    }

    private fun follow(name: String, o: Cli.Opts): Int {
        val kinds = o.value("kinds")?.let { k -> ServerAlerts.kinds(k) ?: throw Cli.Failure(2, "--kinds is a list of releases, concerts, merch, records") }
        val r = api.post("followed", buildJsonObject {
            put("name", name)
            if (kinds != null) { put("releases", kinds.releases); put("concerts", kinds.concerts); put("merch", kinds.merch); put("records", kinds.records) }
        }).jsonObject
        if (c.asJson) return print(r).let { 0 }
        val a = r["artist"]?.jsonObject
        out.println(if (r.b("followed")) "Following ${a?.s("name")} (${kindsLine(a?.get("kinds")?.jsonObject)})." else "${a?.s("name")} is followed already.")
        return 0
    }

    private fun unfollow(name: String): Int {
        val r = api.send("DELETE", "followed/${enc(name)}").jsonObject
        if (c.asJson) print(r) else out.println("No longer following ${r.s("unfollowed")}.")
        return 0
    }
}
