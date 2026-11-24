package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import java.io.File

/**
 * The songs of a rhythm game's song folder, read from the files each game keeps next to a chart:
 * `song.ini` (Clone Hero, YARG, Phase Shift and the Rock Band customs), `.sm`/`.ssc` (StepMania,
 * ITGmania, OutFox) and Beat Saber's `Info.dat` (custom levels). Only the text of these files is
 * read: title, artist, album, year, length. Nothing is written, no audio is opened.
 *
 * A song is a folder holding one of these files; the folders right under the one chosen are its
 * packs or setlists, each a playlist, and "All songs" holds them all.
 */
object GameSongFiles {
    /** A song found in [folder], [pack] the top folder it is in ("" at the top). */
    data class Song(val folder: String, val pack: String, val title: String, val artist: String, val album: String = "", val year: Int = 0, val lengthMs: Long = 0) {
        fun track(): Track = Track(
            id = "gf:$folder", title = GameTitles.clean(title), artists = GameTitles.artists(artist),
            album = album, durationMs = lengthMs, year = year,
        )
    }

    private const val MAX_DEPTH = 6
    private const val MAX_SONGS = 30_000
    private const val MAX_FILE = 512 * 1024L

    /** `song.ini`: an INI file whose [song] section names the song (keys in any case, "name" is the title). */
    fun songIni(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var inSong = true
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            if (line.startsWith("[")) { inSong = line.equals("[song]", ignoreCase = true); return@forEach }
            if (!inSong || line.startsWith(";") || line.startsWith("#") || '=' !in line) return@forEach
            out.putIfAbsent(line.substringBefore('=').trim().lowercase(), line.substringAfter('=').trim())
        }
        return out
    }

    /** `.sm`/`.ssc`: the header's `#KEY:value;` tags (keys upper case); the charts after them are not read. */
    fun smTags(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""#([A-Za-z]+):([^;]*);""").findAll(text.substringBefore("#NOTES").substringBefore("#NOTEDATA")).forEach { m ->
            out.putIfAbsent(m.groupValues[1].uppercase(), m.groupValues[2].trim())
        }
        return out
    }

    /** Beat Saber's `Info.dat`: version 2 (`_songName`…) or 4 (`song.title`…). */
    fun beatSaberInfo(text: String): Triple<String, String, String>? {
        val j = runCatching { parseJson(text.removePrefix("﻿")) }.getOrNull() ?: return null
        val name = j["_songName"].str ?: j["song"]["title"].str ?: return null
        val sub = j["_songSubName"].str ?: j["song"]["subTitle"].str ?: ""
        val author = j["_songAuthorName"].str ?: j["song"]["author"].str ?: ""
        return Triple(name, sub, author)
    }

    /** The song a folder's files describe, or null when it holds none of them. [rel] names the folder from the root. */
    fun songIn(dir: File, rel: String, pack: String): Song? {
        val files = dir.listFiles()?.filter { it.isFile && it.length() <= MAX_FILE } ?: return null
        fun read(f: File) = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
        files.firstOrNull { it.name.equals("song.ini", ignoreCase = true) }?.let(::read)?.let { text ->
            val t = songIni(text)
            val title = t["name"]?.takeIf { it.isNotBlank() } ?: return@let
            return Song(rel, pack, title, t["artist"].orEmpty(), t["album"].orEmpty(), t["year"]?.take(4)?.toIntOrNull() ?: 0, t["song_length"]?.toLongOrNull() ?: 0)
        }
        (files.firstOrNull { it.name.endsWith(".ssc", ignoreCase = true) } ?: files.firstOrNull { it.name.endsWith(".sm", ignoreCase = true) })?.let(::read)?.let { text ->
            val t = smTags(text)
            val title = (t["TITLE"]?.takeIf { it.isNotBlank() } ?: t["TITLETRANSLIT"])?.takeIf { it.isNotBlank() } ?: return@let
            val sub = t["SUBTITLE"].orEmpty()
            val artist = t["ARTIST"]?.takeIf { it.isNotBlank() } ?: t["ARTISTTRANSLIT"].orEmpty()
            return Song(rel, pack, listOf(title, sub).filter { it.isNotBlank() }.joinToString(" "), artist)
        }
        files.firstOrNull { it.name.equals("Info.dat", ignoreCase = true) }?.let(::read)?.let { text ->
            val (name, sub, author) = beatSaberInfo(text) ?: return@let
            return Song(rel, pack, listOf(name, sub).filter { it.isNotBlank() }.joinToString(" "), author)
        }
        return null
    }

    /**
     * Every song under [root], in folder order (names sorted, case aside): a song's folder is not
     * looked into further. Hidden folders are skipped; at most [MAX_DEPTH] levels and [MAX_SONGS] songs.
     */
    fun scan(root: File): List<Song> {
        val out = ArrayList<Song>()
        val rootPath = root.canonicalFile
        fun walk(dir: File, rel: String, pack: String, depth: Int) {
            if (out.size >= MAX_SONGS || depth > MAX_DEPTH) return
            // A song right under the root is in no pack: its folder is the song, not a setlist.
            songIn(dir, rel, if (depth <= 1) "" else pack)?.let { out += it; return }
            dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { sub ->
                    // A link out of the chosen folder is not followed.
                    if (!runCatching { sub.canonicalFile.startsWith(rootPath) }.getOrDefault(false)) return@forEach
                    val r = if (rel.isEmpty()) sub.name else "$rel/${sub.name}"
                    walk(sub, r, if (pack.isEmpty() && depth == 0) sub.name else pack, depth + 1)
                }
        }
        walk(rootPath, "", "", 0)
        return out
    }
}
