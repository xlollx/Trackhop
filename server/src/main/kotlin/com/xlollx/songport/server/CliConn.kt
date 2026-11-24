package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * 2.2's connector commands: `address` (a personal server's second address), `why` (why a track was
 * not matched) and `plex` (sounds like, sonic adventure, copy to a user). Calls to the API, as the others.
 */
internal class CliConn(private val c: CliCommands) {
    companion object {
        val commands: List<Cli.Cmd> = listOf(
            Cli.Cmd("address", "Services and playlists", listOf("address <service> [<url> | none]"), "a personal server's second address, for away from home",
                "Tried when the first one does not answer; the one that worked last is tried first next time. Without <url> it shows both."),
            Cli.Cmd("why", "Syncs", listOf("why <sync> \"Artist - Title\""), "why a track was not matched: the closest candidates, their score and its parts"),
            Cli.Cmd("plex", "Discover", listOf(
                "plex sounds-like \"<song>\" [--service plex@2] [--limit 25]",
                "plex adventure \"<from song>\" \"<to song>\" [--service plex@2]",
                "plex users [--service plex@2]",
                "plex copy \"<playlist>\" --to <user> [--service plex@2]",
            ), "Plex: songs that sound alike, a sonic adventure, a playlist copied to a user of the server",
                "Sounds like and Sonic adventure need a Plex Pass server that analysed its music.", listOf("sounds-like", "adventure", "users", "copy")),
        )
        val valued: Set<String> = setOf("to")
        private val pretty = Json { prettyPrint = true }
    }

    private val api get() = c.api
    private val out get() = c.out
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun pct(v: Double?) = v?.let { "${Math.round(it * 100)}%" } ?: "-"

    /** The exit code, or null for a command that is not one of these. */
    fun run(command: String, p: List<String>, o: Cli.Opts): Int? = when (command) {
        "address" -> address(p)
        "why" -> why(p)
        "plex" -> plex(p, o)
        else -> null
    }

    private fun address(p: List<String>): Int {
        if (p.isEmpty()) throw Cli.Failure(2, "usage: trackhop address <service> [<url> | none]")
        val s = c.service(p[0])
        val r = if (p.size > 1) api.send("PUT", "services/${enc(s.s("id"))}/address", buildJsonObject { put("away", if (p[1] == "none") "" else p[1]) }).jsonObject
            else api.get("services/${enc(s.s("id"))}").jsonObject
        if (c.asJson) return print(r).let { 0 }
        if (!r.containsKey("addressInUse")) throw Cli.Failure(1, "${s.s("name")} is not a connected personal server reached at an address")
        out.println("Second address: ${r["awayAddress"].str() ?: "none"}")
        out.println("Now via: ${r["addressInUse"].str() ?: "-"}")
        return 0
    }

    private fun why(p: List<String>): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop why <sync> \"Artist - Title\"")
        val job = c.sync(p[0])
        val r = api.get("syncs/${enc(job.s("id"))}/why?q=${enc(p.drop(1).joinToString(" "))}").jsonObject
        if (c.asJson) return print(r).let { 0 }
        val cands = (r["candidates"] as? JsonArray).orEmpty().map { it.jsonObject }
        out.println("A match needs ${pct(r.d("threshold"))}.")
        if (cands.isEmpty()) out.println("The search found nothing to compare.")
        for (e in cands) {
            val t = e["track"]!!.jsonObject
            out.println("${pct(e.d("score"))}  ${t.s("artist")} - ${t.s("title")}${if (e.s("matched") == "true") "  (would match)" else ""}")
            out.println("      title ${pct(e.d("title"))}, artist ${pct(e.d("artist"))}, length x${"%.2f".format(e.d("length") ?: 1.0)}, version x${"%.2f".format(e.d("version") ?: 1.0)}, album x${"%.2f".format(e.d("album") ?: 1.0)}" +
                (e["reasons"] as? JsonArray).orEmpty().mapNotNull { it.str() }.takeIf { it.isNotEmpty() }?.joinToString(", ", "; ") { it.replace('_', ' ') }.orEmpty())
        }
        return 0
    }

    private fun plexAccount(o: Cli.Opts): String {
        o.value("service")?.let { return c.service(it).s("id") }
        val list = api.get("services").jsonArray.map { it.jsonObject }.filter { it.s("serviceId") == "plex" && it.s("connected") == "true" }
        return list.firstOrNull()?.s("id") ?: throw Cli.Failure(1, "no Plex server connected. See: trackhop services")
    }

    private fun song(account: String, ask: String): JsonObject =
        api.get("plex/${enc(account)}/search?q=${enc(ask)}").jsonArray.firstOrNull()?.jsonObject ?: throw Cli.Failure(1, "no song \"$ask\" on Plex")

    private fun list(r: JsonElement): Int {
        if (c.asJson) return print(r).let { 0 }
        val items = r.jsonArray.map { it.jsonObject }
        if (items.isEmpty()) out.println("Plex gave nothing: the server may not have analysed these songs yet.")
        items.forEachIndexed { i, t -> out.println("${i + 1}. ${t.s("artist")} - ${t.s("title")}  (${t.s("id")})") }
        return 0
    }

    private fun plex(p: List<String>, o: Cli.Opts): Int {
        val usage = "usage: trackhop plex sounds-like \"<song>\" | adventure \"<from>\" \"<to>\" | users | copy \"<playlist>\" --to <user>"
        val account = plexAccount(o)
        return when (p.firstOrNull()) {
            "sounds-like" -> {
                val seed = song(account, p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, usage) })
                list(api.get("plex/${enc(account)}/sounds-like?track=${enc(seed.s("id"))}&limit=${o.value("limit") ?: "25"}"))
            }
            "adventure" -> {
                if (p.size < 3) throw Cli.Failure(2, usage)
                val from = song(account, p[1]); val to = song(account, p[2])
                list(api.get("plex/${enc(account)}/sonic-adventure?from=${enc(from.s("id"))}&to=${enc(to.s("id"))}"))
            }
            "users" -> {
                val r = api.get("plex/${enc(account)}/home-users")
                if (c.asJson) print(r) else r.jsonArray.forEach { u -> out.println("${u.jsonObject.s("name")}  (${u.jsonObject.s("id")})") }
                0
            }
            "copy" -> {
                val ask = p.drop(1).joinToString(" ").ifBlank { throw Cli.Failure(2, usage) }
                val toAsk = o.value("to") ?: throw Cli.Failure(2, usage)
                val users = api.get("plex/${enc(account)}/home-users").jsonArray.map { it.jsonObject }
                val user = users.firstOrNull { it.s("id") == toAsk || it.s("name").equals(toAsk, ignoreCase = true) }
                    ?: throw Cli.Failure(1, "no user \"$toAsk\". See: trackhop plex users")
                val pl = c.playlist(account, ask)
                val r = api.post("plex/${enc(account)}/playlists/${enc(pl.s("id"))}/copy", buildJsonObject { put("user", user.s("id")) }).jsonObject
                if (c.asJson) print(r) else out.println("Copied \"${r.s("name")}\" to ${user.s("name")} (${r.s("tracks")} songs).")
                0
            }
            else -> throw Cli.Failure(2, usage)
        }
    }
}
