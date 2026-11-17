package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import java.io.Reader

/**
 * A library or playlist exported by Apple Music / iTunes (File › Library › Export Library or Export
 * Playlist), read a piece at a time (#67): the file goes through a small buffer, so a library of any
 * size takes the memory of its tracks' names, never of the whole file. The XML is an Apple plist: a
 * "Tracks" dictionary (one entry per song) and a "Playlists" array (each with its "Playlist Items").
 *
 * Kotlin and java.io only: the same reader serves the app, Desktop and Docker.
 */
class ItunesLibrary private constructor(
    private val tracks: LinkedHashMap<Int, Info>,
    /** Every playlist of the file, in its order, folders and the library's own lists included. */
    val playlists: List<Playlist>,
) {
    class Info(val title: String, val artist: String, val album: String, val durationMs: Long)

    enum class Kind {
        /** A playlist made by the person. */
        USER,
        /** A smart playlist (rules in Music/iTunes); its songs are the ones it held when exported. */
        SMART,
        /** A folder of playlists: a heading, never imported. */
        FOLDER,
        /** The library's own lists: Library, Music, Movies, Podcasts, Audiobooks, Downloaded… */
        SYSTEM,
    }

    class Playlist(
        val key: String,
        val name: String,
        val kind: Kind,
        /** The name of the folder it sits in, or null. */
        val folder: String?,
        val trackIds: IntArray,
    ) {
        val size: Int get() = trackIds.size
    }

    /** How many songs the file holds. */
    val trackCount: Int get() = tracks.size

    /** The playlists a person picks from: theirs and smart ones, not folders or the library's own. */
    val choices: List<Playlist> get() = playlists.filter { it.kind == Kind.USER || it.kind == Kind.SMART }

    /** A whole library (it lists the library's own lists), not one exported playlist. */
    val isLibrary: Boolean get() = playlists.any { it.kind == Kind.SYSTEM }

    /**
     * True when there is something to pick: a library with playlists of its own, or a file of several
     * playlists. One exported playlist, or a library without playlists, is imported as it is.
     */
    val worthPicking: Boolean get() = choices.size > 1 || (isLibrary && choices.isNotEmpty())

    /** The songs of [p] in its order; an item whose song is not in the file is skipped. */
    fun tracksOf(p: Playlist): List<Track> = p.trackIds.asList().mapNotNull { id -> tracks[id]?.let(::track) }

    /**
     * What the file gives as one list: the only playlist of an exported playlist, in its order, or
     * every song of the library in the file's order (what Trackhop read before 2.1).
     */
    fun asOneList(): List<Track> =
        choices.singleOrNull()?.takeIf { !isLibrary && it.size > 0 }?.let(::tracksOf) ?: tracks.values.map(::track)

    private fun track(i: Info) = CsvCodec.withStableId(
        Track(id = "", title = i.title, artists = CsvCodec.splitArtists(i.artist), album = i.album, durationMs = i.durationMs),
    )

    companion object {
        /**
         * True when the start of a file ([head], a few KB) looks like a library with playlists to pick
         * from: an Apple plist, or a rekordbox collection ([RekordboxXml]).
         */
        fun looksLike(head: String): Boolean {
            val h = head.removePrefix("﻿").trimStart()
            return (h.startsWith("<") && (h.contains("<plist") || h.contains("Apple//DTD PLIST"))) || RekordboxXml.looksLike(head)
        }

        /** A library read from another program's file ([RekordboxXml]): the same songs and playlists to pick from. */
        internal fun of(tracks: LinkedHashMap<Int, Info>, playlists: List<Playlist>): ItunesLibrary = ItunesLibrary(tracks, playlists)

        /**
         * Reads [reader] to the end. [progress] gets the characters read so far, about every MB;
         * [cancelled] is asked as often and stops the reading with a [java.util.concurrent.CancellationException].
         */
        fun read(reader: Reader, progress: (Long) -> Unit = {}, cancelled: () -> Boolean = { false }): ItunesLibrary =
            read(CharSource(reader, progress, cancelled))

        internal fun read(source: CharSource): ItunesLibrary =
            if (RekordboxXml.looksLike(source.head())) RekordboxXml.read(source)
            else Builder().also { PlistEvents(source, it).run() }.build()

        fun read(text: String): ItunesLibrary = read(text.reader())
    }

    // ---------------------------------------------------------------- building

    private class Builder : PlistEvents.Sink {
        private val tracks = LinkedHashMap<Int, Info>()
        private class Raw(var key: String, val name: String, val kind: Kind, val parent: String?, val ids: IntArray)
        private val raws = ArrayList<Raw>()

        // The open containers: 'd' or 'a', and for a dictionary the key its next value goes under.
        private val kinds = StringBuilder()
        private val keys = ArrayList<String?>()

        private var track: HashMap<String, String>? = null
        private var playlist: HashMap<String, String>? = null
        private var items = IntList()
        private var itemDepth = -1

        private fun parentKey(level: Int): String? = keys.getOrNull(level)

        var sawRoot = false

        override fun startDict() {
            val depth = kinds.length
            if (depth == 0) sawRoot = true
            // root dict = level 0; Tracks dict = level 1 under key "Tracks"; a song = level 2 inside it.
            if (depth == 2 && kinds[0] == 'd' && kinds[1] == 'd' && parentKey(0) == "Tracks") track = HashMap()
            // Playlists array = level 1 under key "Playlists"; a playlist = level 2 inside it.
            if (depth == 2 && kinds[0] == 'd' && kinds[1] == 'a' && parentKey(0) == "Playlists") { playlist = HashMap(); items = IntList() }
            if (playlist != null && depth == 4 && kinds[3] == 'a' && parentKey(2) == "Playlist Items") itemDepth = depth
            kinds.append('d'); keys.add(null)
        }

        override fun endDict() {
            val depth = kinds.length - 1
            if (depth == 2 && track != null) { track?.let(::addTrack); track = null }
            if (depth == 2 && playlist != null) { playlist?.let(::addPlaylist); playlist = null }
            if (depth == itemDepth) itemDepth = -1
            kinds.setLength(depth); keys.removeAt(keys.size - 1)
        }

        override fun startArray() { kinds.append('a'); keys.add(null) }
        override fun endArray() { kinds.setLength(kinds.length - 1); keys.removeAt(keys.size - 1) }

        override fun key(name: String) { keys[keys.size - 1] = name }

        override fun value(type: String, text: String) {
            val depth = kinds.length - 1
            val key = keys.lastOrNull() ?: return
            val v = when (type) { "true" -> "true"; "false" -> "false"; else -> text }
            when {
                depth == 2 && track != null -> track!![key] = v
                depth == 2 && playlist != null -> playlist!![key] = v
                itemDepth >= 0 && depth == itemDepth && key == "Track ID" -> v.trim().toIntOrNull()?.let(items::add)
            }
        }

        private fun addTrack(f: Map<String, String>) {
            val id = f["Track ID"]?.trim()?.toIntOrNull() ?: return
            val name = f["Name"]?.trim().orEmpty()
            if (name.isEmpty()) return
            tracks[id] = Info(
                title = name,
                artist = (f["Artist"] ?: f["Album Artist"]).orEmpty().trim(),
                album = f["Album"].orEmpty().trim(),
                durationMs = f["Total Time"]?.trim()?.toLongOrNull() ?: 0,
            )
        }

        private fun addPlaylist(f: Map<String, String>) {
            val kind = when {
                f["Folder"] == "true" -> Kind.FOLDER
                f["Master"] == "true" || f.containsKey("Distinguished Kind") || f["Visible"] == "false" -> Kind.SYSTEM
                f.containsKey("Smart Info") || f.containsKey("Smart Criteria") -> Kind.SMART
                else -> Kind.USER
            }
            val key = f["Playlist Persistent ID"] ?: f["Playlist ID"] ?: "#${raws.size}"
            raws += Raw(key, f["Name"]?.trim().orEmpty().ifEmpty { "Playlist" }, kind, f["Parent Persistent ID"], items.toArray())
        }

        fun build(): ItunesLibrary {
            // A file cut short (an upload or a copy that stopped half way) leaves containers open:
            // nothing is taken from it, rather than a library that silently lacks its end.
            if (kinds.isNotEmpty() || !sawRoot) throw Incomplete()
            val folders = raws.filter { it.kind == Kind.FOLDER }.associate { it.key to it.name }
            return ItunesLibrary(tracks, raws.map { Playlist(it.key, it.name, it.kind, it.parent?.let(folders::get), it.ids) })
        }
    }

    /** The file ends before its library (or its list) does: it was cut short (an interrupted upload or copy). */
    class Incomplete(
        message: String = "The file ends before the library does: it is incomplete. Export it again, or upload it again.",
        /** True for an Apple Music/iTunes library: what to export again is then known. */
        val apple: Boolean = true,
    ) : IllegalStateException(message)

    internal class IntList {
        private var a = IntArray(16)
        private var n = 0
        fun add(v: Int) { if (n == a.size) a = a.copyOf(n * 2); a[n++] = v }
        fun toArray(): IntArray = a.copyOf(n)
    }
}

/**
 * A plist read as events through [XmlTokens]: dictionaries, arrays, keys and values. Only what a
 * plist holds is understood; <data> (smart playlists' rules, artwork) is skipped without being kept.
 */
internal class PlistEvents(private val source: CharSource, private val sink: Sink) {
    interface Sink {
        fun startDict(); fun endDict(); fun startArray(); fun endArray()
        fun key(name: String)
        /** A scalar: type is string, integer, real, date, true or false. */
        fun value(type: String, text: String)
    }

    fun run() {
        val xml = XmlTokens(source)
        var open: String? = null // the scalar element whose text is being read
        xml.run(object : XmlTokens.Handler {
            override fun open(name: String, attrs: String, selfClosing: Boolean) {
                when {
                    selfClosing -> when (name) {
                        "true", "false" -> sink.value(name, "")
                        "dict" -> { sink.startDict(); sink.endDict() }
                        "array" -> { sink.startArray(); sink.endArray() }
                        "string" -> sink.value("string", "")
                    }
                    name == "dict" -> sink.startDict()
                    name == "array" -> sink.startArray()
                    name == "plist" -> Unit
                    // <data> says only that it is there (a smart playlist's rules): its content is never kept.
                    else -> { open = name; xml.text.setLength(0); xml.keep = name != "data" }
                }
            }

            override fun close(name: String) {
                if (name == open) {
                    val value = XmlTokens.unescape(xml.text.toString())
                    if (name == "key") sink.key(value) else sink.value(name, value)
                    open = null; xml.keep = false
                } else when (name) { "dict" -> sink.endDict(); "array" -> sink.endArray() }
            }
        })
    }
}
