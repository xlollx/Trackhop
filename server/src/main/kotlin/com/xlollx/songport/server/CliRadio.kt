package com.xlollx.songport.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Radio stations and Shazam as connectors on the command line (2.2): `stations` finds a station,
 * `services add radio|shazam` and `services remove` add and take one away, and `new` makes a sync
 * from one (`new radio@2 spotify`). Calls to the API (ApiRadio.kt), as the others.
 */
internal class CliRadio(private val c: CliCommands) {
    companion object {
        val commands: List<Cli.Cmd> = listOf(
            Cli.Cmd("stations", "Services and playlists", listOf("stations <words>"), "find a radio station by name, to add it: trackhop services add radio"),
        )
        val valued: Set<String> = setOf("stream", "station", "homepage", "from", "to")
        private val pretty = Json { prettyPrint = true }

        /** True for the id of a radio connector or Shazam's. */
        fun isRadioOrShazam(serviceId: String) = serviceId == "radio" || serviceId == "shazam"
    }

    private val api get() = c.api
    private val out get() = c.out
    private fun print(e: JsonElement) = out.println(pretty.encodeToString(JsonElement.serializer(), e))
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.s(k: String) = this[k].str().orEmpty()

    fun run(command: String, p: List<String>, o: Cli.Opts): Int? = when (command) {
        "stations" -> stations(p.joinToString(" ").ifBlank { throw Cli.Failure(2, "usage: trackhop stations <words>") })
        else -> null
    }

    private fun stations(words: String): Int {
        val list = api.get("radio/stations?q=${enc(words)}").jsonArray.map { it.jsonObject }
        if (c.asJson) return print(JsonArray(list)).let { 0 }
        if (list.isEmpty()) out.println("No station by that name. Try another spelling, or give its stream: trackhop services add radio --stream <url> --station <name>")
        for (s in list) out.println("${s.s("name")}  ${listOf(s.s("country"), s.s("codec"), s.s("bitrate").takeIf { it != "0" }?.let { "$it kbps" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")}\n  ${s.s("stream")}")
        return 0
    }

    /**
     * `services add radio <words>` (the station the directory ranks first for them), or with
     * `--stream <url> --station <name>`; `services add shazam`; `services remove <radio or Shazam>`.
     */
    fun services(p: List<String>, o: Cli.Opts): Int {
        val usage = "usage: trackhop services add radio <station> | --stream <url> --station <name> [--name NAME] [--color COLOR]\n       trackhop services add shazam\n       trackhop services remove <radio or shazam>"
        val what = p.getOrNull(1) ?: throw Cli.Failure(2, usage)
        if (p[0] == "remove") {
            val s = c.service(p.drop(1).joinToString(" "))
            if (!isRadioOrShazam(s.s("serviceId"))) throw Cli.Failure(2, "only a radio or Shazam is taken away here; other services on Trackhop's page, Services.")
            val r = api.send("DELETE", "services/${enc(s.s("id"))}").jsonObject
            if (c.asJson) print(r) else out.println("Removed ${s.s("name")}.")
            return 0
        }
        val r = when (what) {
            "shazam" -> api.post("services/shazam").jsonObject
            "radio" -> {
                val words = p.drop(2).joinToString(" ")
                val station = o.value("stream")?.let { stream ->
                    Triple(o.value("station") ?: words.ifBlank { throw Cli.Failure(2, "--station <name> is needed with --stream") }, stream, o.value("homepage"))
                } ?: run {
                    if (words.isBlank()) throw Cli.Failure(2, usage)
                    val first = api.get("radio/stations?q=${enc(words)}").jsonArray.firstOrNull()?.jsonObject
                        ?: throw Cli.Failure(1, "no station called \"$words\". See: trackhop stations <words>, or give its stream with --stream")
                    Triple(first.s("name"), first.s("stream"), first.s("homepage").ifBlank { null })
                }
                api.post("services/radio", buildJsonObject {
                    put("station", station.first); put("stream", station.second)
                    station.third?.let { put("homepage", it) }
                    o.value("name")?.let { put("name", it) }
                    o.value("color")?.let { put("color", it) }
                }).jsonObject
            }
            else -> throw Cli.Failure(2, usage)
        }
        if (c.asJson) return print(r).let { 0 }
        out.println("${r.s("name")} (${r.s("id")})${r.s("stream").takeIf { it.isNotEmpty() }?.let { "  $it" }.orEmpty()}")
        out.println("Make a sync from it: trackhop new ${r.s("id")} <service> [<playlist>]")
        return 0
    }

    /**
     * `new <radio or shazam> <service> [<playlist>]`: the station's sync (its hours: --days, --from,
     * --to; every day 08:00 to 10:00 when not given) or Shazam's, as the API makes them.
     */
    fun newSync(src: JsonObject, p: List<String>, o: Cli.Opts, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Int {
        if (p.size < 2) throw Cli.Failure(2, "usage: trackhop new ${src.s("id")} <service> [<playlist>] [--days mon,fri --from 08:00 --to 10:00]")
        val dst = c.service(p[1])
        val radio = src.s("serviceId") == "radio"
        val body = buildJsonObject {
            put("source", buildJsonObject { put("service", src.s("id")) })
            put("target", buildJsonObject {
                put("service", dst.s("id"))
                p.drop(2).joinToString(" ").takeIf { it.isNotEmpty() }?.let { put("playlist", c.playlist(dst.s("id"), it).s("id")) }
            })
            if (radio) {
                if (o.value("days") != null || o.value("from") != null || o.value("to") != null) put("radio", buildJsonObject {
                    o.value("days")?.let { d -> put("days", buildJsonArray { d.split(',').filter { it.isNotBlank() }.forEach { add(JsonPrimitive(c.day(it))) } }) }
                    o.value("from")?.let { put("start", it) }
                    o.value("to")?.let { put("end", it) }
                })
                o.value("name")?.let { put("name", it) }
                o.value("color")?.let { put("color", if (it == "none") "" else it) }
                o.value("label")?.let { put("label", if (it == "none") "" else it) }
                // The card's own marks, as for any new sync (its hours stay the radio's window above).
                if (o.has("favorite")) put("favorite", true)
                if (o.has("unfavorite")) put("favorite", false)
                c.tagsField(o, null)?.let { put("tags", it) }
            } else fields()
        }
        val r = api.send("POST", "syncs", body).jsonObject
        if (c.asJson) return print(r).let { 0 }
        val window = (r["radio"] as? JsonObject)?.s("window")
        out.println("Made \"${r.s("name")}\" (${r.s("id")}): ${src.s("name")} → ${dst.s("name")}${window?.let { ", listens $it" } ?: ", ${r.s("timing")}"}")
        return 0
    }
}
