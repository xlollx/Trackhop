package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.sync.Soundtracks
import com.xlollx.songport.sync.TruckRadio
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File

/**
 * The games' calls of the API: soundtracks found on MusicBrainz (a sync's source, service
 * "musicbrainz", the id as its playlist), and the radio list of Euro Truck Simulator 2 and American
 * Truck Simulator on this computer: its stations, and adding one to it (a copy of the list is kept).
 */
internal object GamesApi {
    /** Where the truck games' radio lists are: the user's usual folders, and the data folder (Docker: a game's folder mounted there). */
    fun truckFiles(env: ServerEnv): List<File> =
        TruckRadio.findFiles(File(System.getProperty("user.home").orEmpty()), extra = listOf(env.dataDir))

    fun handle(env: ServerEnv, ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
        when {
            rest == listOf("soundtracks") && method == "GET" -> {
                val q = query["q"]?.trim().orEmpty().ifEmpty { return error(400, "Say what to look for: q=…") }
                val found = runCatching { runBlocking { Soundtracks.search(q) } }.getOrElse { return error(502, "MusicBrainz is not answering right now: try again in a minute.") }
                json(200, JsonArray(found.map { f ->
                    buildJsonObject {
                        put("service", Soundtracks.SERVICE); put("playlist", f.id); put("title", f.title); put("artist", f.artist); put("year", f.year)
                        putJsonArray("types") { f.types.forEach { add(it) } }
                    }
                }))
            }
            rest == listOf("truck-radio") && method == "GET" -> json(200, JsonArray(truckFiles(env).map { f ->
                buildJsonObject {
                    put("game", TruckRadio.gameOf(f) ?: f.parentFile?.name.orEmpty()); put("file", f.path)
                    val stations = runCatching { TruckRadio.parse(f.readText()) }
                    stations.exceptionOrNull()?.let { put("error", it.message ?: "unreadable") }
                    putJsonArray("stations") {
                        stations.getOrDefault(emptyList()).forEach { s ->
                            add(buildJsonObject { put("stream", s.stream); put("name", s.name); put("genre", s.genre); put("language", s.language); put("bitrate", s.bitrate) })
                        }
                    }
                }
            }))
            rest == listOf("truck-radio") && method == "POST" -> {
                if (!env.may(Permissions.FOLDERS)) return error(403, "Folders on the server are not turned on for you: ask the admin.")
                val b = runCatching { com.xlollx.songport.net.json.parseToJsonElement(ex.requestBody.readNBytes(100_000).decodeToString()).jsonObject }.getOrNull()
                    ?: return error(400, "Send JSON: {\"stream\", \"name\", \"genre\"?, \"language\"?, \"bitrate\"?, \"game\"?}")
                fun s(k: String) = (b[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val stream = s("stream")
                if (!stream.startsWith("http://") && !stream.startsWith("https://")) return error(400, "\"stream\" is the station's http(s) address.")
                val station = TruckRadio.Station(stream, s("name").ifEmpty { stream }, s("genre"), s("language"), (b["bitrate"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull ?: 0)
                val files = truckFiles(env).filter { s("game").isEmpty() || TruckRadio.gameOf(it).equals(s("game"), ignoreCase = true) }
                if (files.isEmpty()) return error(404, "No radio list of Euro Truck Simulator 2 or American Truck Simulator on this computer.")
                val added = files.map { f ->
                    val n = runCatching { TruckRadio.addTo(f, listOf(station)) }.getOrElse { return error(409, "${f.path}: ${it.message}") }
                    env.log("games", "truck radio: ${station.name} ${if (n > 0) "added to" else "already in"} ${f.path}")
                    buildJsonObject { put("game", TruckRadio.gameOf(f) ?: ""); put("added", n > 0) }
                }
                json(200, JsonArray(added))
            }
            else -> error(404, "Unknown call.")
        }
    }
}
