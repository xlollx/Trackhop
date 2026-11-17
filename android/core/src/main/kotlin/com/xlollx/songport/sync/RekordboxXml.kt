package com.xlollx.songport.sync


/**
 * rekordbox's XML (File › Export Collection in xml format; read back from Preferences › Advanced ›
 * rekordbox xml), as AlphaTheta publishes it for developers ("XML file format for playlists
 * sharing", rekordbox.com/en/support/developer): a `DJ_PLAYLISTS` root with a `COLLECTION` of
 * `TRACK`s (TrackID, Name, Artist, Album, TotalTime in seconds, Location…) and a `PLAYLISTS` tree of
 * `NODE`s, folders (Type 0) and playlists (Type 1) whose `TRACK Key` entries name a track of the
 * collection by its TrackID (KeyType 0) or its Location (KeyType 1).
 *
 * Read a piece at a time through [XmlTokens] (#67) into the same model as an Apple Music library
 * ([ItunesLibrary]): the songs once, the playlists as lists of their ids, so a collection of any
 * size takes the memory of its songs' names and its playlists are offered to pick from. Beat grids
 * and cue points (`TEMPO`, `POSITION_MARK`) go by unread. A file cut short is refused.
 */
object RekordboxXml {
    private const val ROOT = "DJ_PLAYLISTS"

    /** True when the start of a file ([head], a few KB) is a rekordbox XML: told by its root element, whatever its name. */
    fun looksLike(head: String): Boolean {
        val h = head.removePrefix("﻿").trimStart()
        return h.startsWith("<") && h.contains("<$ROOT")
    }

    fun read(text: String): ItunesLibrary = read(CharSource(text.reader()))

    private class Node(val folder: Boolean, val name: String, val byLocation: Boolean, val parent: String?) {
        val ids = ItunesLibrary.IntList()
    }

    internal fun read(source: CharSource): ItunesLibrary {
        val tracks = LinkedHashMap<Int, ItunesLibrary.Info>()
        // KeyType 1 playlists name their tracks by Location: where each file is, to its id.
        val byLocation = HashMap<String, Int>()
        val nodes = ArrayList<Node>() // every folder and playlist but the root, in the file's order
        val open = ArrayList<Node?>() // the NODEs being read; null is the root folder
        var inCollection = false
        var inPlaylists = false
        var made = 0
        val xml = XmlTokens(source)
        xml.run(object : XmlTokens.Handler {
            override fun open(name: String, attrs: String, selfClosing: Boolean) {
                when {
                    name == "COLLECTION" -> inCollection = !selfClosing
                    name == "PLAYLISTS" -> inPlaylists = !selfClosing
                    inCollection && name == "TRACK" -> {
                        val a = XmlTokens.attributes(attrs)
                        val title = a["Name"]?.trim().orEmpty()
                        // A track without an id of its own still counts: it gets one no real track has.
                        val id = a["TrackID"]?.trim()?.toIntOrNull() ?: (--made)
                        a["Location"]?.takeIf { it.isNotBlank() }?.let { byLocation[it] = id }
                        if (title.isEmpty()) return
                        tracks[id] = ItunesLibrary.Info(
                            title = title,
                            artist = a["Artist"]?.trim().orEmpty(),
                            album = a["Album"]?.trim().orEmpty(),
                            // Seconds, written as a whole number or with decimals.
                            durationMs = a["TotalTime"]?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it < 1e7 }?.let { (it * 1000).toLong() } ?: 0,
                        )
                    }
                    inPlaylists && name == "NODE" -> {
                        val a = XmlTokens.attributes(attrs)
                        // The first NODE is the root folder ("ROOT"): a container, not a folder to show.
                        val node = if (open.isEmpty()) null else Node(
                            folder = a["Type"]?.trim() != "1",
                            name = a["Name"]?.trim().orEmpty().ifEmpty { "Playlist" },
                            byLocation = a["KeyType"]?.trim() == "1",
                            parent = open.lastOrNull()?.name,
                        ).also { nodes += it }
                        if (!selfClosing) open += node
                    }
                    inPlaylists && name == "TRACK" -> {
                        val node = open.lastOrNull()?.takeIf { !it.folder } ?: return
                        val key = XmlTokens.attributes(attrs)["Key"] ?: return
                        (if (node.byLocation) byLocation[key] else key.trim().toIntOrNull())?.let(node.ids::add)
                    }
                }
            }

            override fun close(name: String) {
                when (name) {
                    "COLLECTION" -> inCollection = false
                    "PLAYLISTS" -> inPlaylists = false
                    "NODE" -> if (inPlaylists && open.isNotEmpty()) open.removeAt(open.size - 1)
                }
            }
        })
        // Cut short (an upload or a copy that stopped half way): nothing is taken from it.
        if (!xml.whole) throw ItunesLibrary.Incomplete("The file ends before its list does: it is incomplete. Export it again, or upload it again.", apple = false)
        return ItunesLibrary.of(tracks, nodes.mapIndexed { i, n ->
            ItunesLibrary.Playlist("#$i", n.name, if (n.folder) ItunesLibrary.Kind.FOLDER else ItunesLibrary.Kind.USER, n.parent, n.ids.toArray())
        })
    }
}
