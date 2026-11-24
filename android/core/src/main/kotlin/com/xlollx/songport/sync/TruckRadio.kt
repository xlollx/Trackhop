package com.xlollx.songport.sync

import java.io.File

/**
 * The internet radio list of Euro Truck Simulator 2 and American Truck Simulator, `live_streams.sii`
 * in the game's documents folder: a plain-text SII unit whose `stream_data[i]` lines read
 * `"address|name|genre|language|bitrate|favourite"`. Read, its stations become stations a radio sync
 * can listen to; written, stations found in the radio directory join the game's list. Writing keeps
 * every station already there, in its place, and adds only addresses the list does not hold.
 * The game reads the file when it starts: it must be closed while the file is written.
 */
object TruckRadio {
    data class Station(val stream: String, val name: String, val genre: String = "", val language: String = "", val bitrate: Int = 0, val favourite: Boolean = false) {
        /** The station as one `stream_data` value, `|` taken out of every field (it separates them). */
        fun line(): String = listOf(stream, name, genre, language, if (bitrate > 0) bitrate.toString() else "128", if (favourite) "1" else "0")
            .joinToString("|") { it.replace("|", " ").replace("\"", "'").trim() }
    }

    /** The file is not a plain-text SII (an encrypted or binary save): it is left alone. */
    class NotText : Exception("live_streams.sii is not a plain-text SII file")

    /** The games and where each keeps its list, under the user's documents (Windows) or data folder (Linux, macOS). */
    val GAMES = listOf("Euro Truck Simulator 2", "American Truck Simulator")

    private val ENTRY = Regex("""^\s*stream_data\s*\[\s*(\d*)\s*\]\s*:\s*"((?:[^"\\]|\\.)*)"\s*$""")
    private val COUNT = Regex("""^\s*stream_data\s*:\s*\d+\s*$""")

    /**
     * The files of the games found on this computer, from the usual places for [home], then in [extra]
     * folders holding a game's folder (Docker: the game's documents folder mounted into the data folder).
     */
    fun findFiles(home: File, os: String = System.getProperty("os.name").orEmpty(), extra: List<File> = emptyList()): List<File> {
        val roots = extra + when {
            os.startsWith("Windows", ignoreCase = true) -> listOf(File(home, "Documents"), File(home, "OneDrive/Documents"))
            os.startsWith("Mac", ignoreCase = true) -> listOf(File(home, "Library/Application Support"))
            else -> listOf(File(home, ".local/share"), File(home, ".steam/steam/steamapps/compatdata/227300/pfx/drive_c/users/steamuser/Documents"),
                File(home, ".steam/steam/steamapps/compatdata/270880/pfx/drive_c/users/steamuser/Documents"))
        }
        return roots.flatMap { r -> GAMES.map { File(File(r, it), "live_streams.sii") } }.filter { it.isFile }.distinctBy { it.canonicalPath }
    }

    /** The game a file belongs to, from its folder's name; null when it cannot be told. */
    fun gameOf(file: File): String? = GAMES.firstOrNull { file.parentFile?.name.equals(it, ignoreCase = true) }

    private fun checkText(text: String) {
        if (text.startsWith("ScsC") || text.startsWith("BSII") || !text.trimStart('﻿').trimStart().startsWith("SiiNunit")) throw NotText()
    }

    /** The stations of the list, in its order; lines that are not stations are skipped. */
    fun parse(text: String): List<Station> {
        checkText(text)
        return text.lineSequence().mapNotNull { ENTRY.find(it)?.groupValues?.get(2) }.mapNotNull { v ->
            val f = v.replace("\\\"", "\"").split('|')
            val stream = f.getOrNull(0)?.trim().orEmpty()
            if (!stream.startsWith("http://") && !stream.startsWith("https://")) return@mapNotNull null
            Station(stream, f.getOrNull(1)?.trim().orEmpty().ifEmpty { stream }, f.getOrNull(2)?.trim().orEmpty(), f.getOrNull(3)?.trim().orEmpty(),
                f.getOrNull(4)?.trim()?.toIntOrNull() ?: 0, f.getOrNull(5)?.trim() == "1")
        }.toList()
    }

    /**
     * [text] with [add] appended to its list: the stations already there stay as they are, in their
     * place; an address the list already holds is not added again. The count line and the indexes
     * are written anew. Answers the new text and how many stations were added.
     */
    fun withStations(text: String, add: List<Station>): Pair<String, Int> {
        checkText(text)
        val have = parse(text).mapTo(HashSet()) { it.stream.trim().lowercase() }
        val fresh = add.filter { it.stream.startsWith("http") && have.add(it.stream.trim().lowercase()) }
        if (fresh.isEmpty()) return text to 0
        val nl = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(Regex("\r?\n")).toMutableList()
        val entries = lines.withIndex().filter { ENTRY.containsMatchIn(it.value) }
        val countAt = lines.indexOfFirst { COUNT.containsMatchIn(it) }
        val indent = (entries.firstOrNull()?.value ?: lines.getOrNull(countAt) ?: " ").takeWhile { it == ' ' || it == '\t' }.ifEmpty { " " }
        val values = entries.map { ENTRY.find(it.value)!!.groupValues[2] } + fresh.map { it.line() }
        val block = listOf("${indent}stream_data: ${values.size}") + values.mapIndexed { i, v -> "${indent}stream_data[$i]: \"$v\"" }
        // Where the list was (or, in a unit without one, before the unit's closing brace).
        val at = when {
            countAt >= 0 -> countAt
            entries.isNotEmpty() -> entries.first().index
            else -> lines.indexOfFirst { it.contains("live_stream_def") }.let { d ->
                if (d < 0) throw NotText()
                (d + 1 until lines.size).firstOrNull { lines[it].trim() == "}" } ?: throw NotText()
            }
        }
        val drop = (entries.map { it.index } + listOfNotNull(countAt.takeIf { it >= 0 })).toSet()
        val out = ArrayList<String>()
        lines.forEachIndexed { i, l ->
            if (i == at) out += block
            if (i !in drop) out += l
        }
        return out.joinToString(nl) to fresh.size
    }

    /**
     * Adds [add] to the list in [file], after keeping a copy of it next to it (`live_streams.sii.bak`,
     * the first time; later copies get the time in their name). Answers how many were added.
     */
    fun addTo(file: File, add: List<Station>, now: Long = System.currentTimeMillis()): Int {
        val text = file.readText(Charsets.UTF_8)
        val (out, added) = withStations(text, add)
        if (added == 0) return 0
        val bak = File(file.parentFile, "live_streams.sii.bak").let { if (it.exists()) File(file.parentFile, "live_streams.sii.$now.bak") else it }
        file.copyTo(bak, overwrite = false)
        val tmp = File(file.parentFile, "live_streams.sii.tmp")
        tmp.writeText(out, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.writeText(out, Charsets.UTF_8); tmp.delete() }
        return added
    }
}
