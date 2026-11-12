package com.xlollx.songport.providers

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.PlaylistLinks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * Lyrion Music Server against a stand-in: a local HTTP server answering /jsonrpc.js as the server's
 * own commands do (Slim/Control/Queries.pm and Commands.pm, 9.1): the names of the loops and fields,
 * "playlist index", a playlist that takes a track once, a name that is taken, HTTP Basic when protected.
 */
class LyrionProviderTest {
    private class Song(val id: Int, val title: String, val artist: String, val album: String, val secs: Double, val year: Int) {
        val url get() = "file:///music/$artist/$album/$title.flac"
        fun json(index: Int? = null) = buildString {
            append("{")
            if (index != null) append("\"playlist index\":$index,")
            append("\"id\":$id,\"title\":\"$title\",\"artist\":\"$artist\",\"album\":\"$album\",\"duration\":$secs,\"year\":\"$year\",\"url\":\"$url\"}")
        }
    }

    private val library = listOf(
        Song(11, "Battery", "Metallica", "Master of Puppets", 312.4, 1986),
        Song(12, "Home", "Måneskin", "Rush!", 180.0, 2023),
        Song(13, "Home", "Beyoncé", "Renaissance", 201.0, 2022),
        Song(14, "One", "Metallica", "...And Justice for All", 446.0, 1988),
    )
    /** The plays the server counted per song (tag O, `playcount`); a song never played has none. */
    private val plays = mapOf(11 to 7, 14 to 31)
    private val albumIds = library.map { it.album }.distinct().withIndex().associate { (i, a) -> a to (500 + i) }
    private val names = linkedMapOf(37 to "Funky Beats", 57 to "SUPER")
    private val entries = hashMapOf(37 to mutableListOf(11, 12, 14), 57 to mutableListOf<Int>())
    private var nextId = 100
    /** Null: no password protection. */
    private var password: Pair<String, String>? = null
    private var fullText = false

    private val commands = mutableListOf<List<String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/jsonrpc.js") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val expected = password?.let { (u, p) -> "Basic " + Base64.getEncoder().encodeToString("$u:$p".toByteArray()) }
            val (code, text) = if (expected != null && ex.requestHeaders.getFirst("Authorization") != expected) 401 to "" else {
                val req = parseJson(body)
                assertEquals("slim.request", req["method"].str)
                assertEquals("0", req["params"][0].str)
                val cmd = req["params"][1].arr.map { it.str ?: "" }
                synchronized(commands) { commands += cmd }
                200 to """{"id":1,"method":"slim.request","params":${req["params"]},"result":${answer(cmd)}}"""
            }
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        createContext("/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val ctx = TestEnv()
    private val p = LyrionProvider()

    @After fun stop() = server.stop(0)

    private fun List<String>.param(name: String) = firstOrNull { it.startsWith("$name:") }?.substringAfter(':')

    private fun answer(cmd: List<String>): String {
        fun slice(all: List<String>, at: Int) = all.drop(cmd[at].toInt()).take(cmd[at + 1].toInt()).joinToString(",")
        // The play count only when asked for with its letter, as the CLI does.
        fun js(song: Song, index: Int? = null) = song.json(index).let { j ->
            if (cmd.param("tags")?.contains('O') == true) "{\"playcount\":${plays[song.id] ?: 0}," + j.removePrefix("{") else j
        }
        return when {
            cmd.take(2) == listOf("version", "?") -> """{"_version":"9.1.0"}"""
            cmd.take(2) == listOf("playlists", "tracks") -> {
                val id = cmd.param("playlist_id")!!.toInt()
                val list = entries[id] ?: return """{"count":0}"""
                val songs = list.mapIndexed { i, s -> js(library.first { it.id == s }, i) }
                """{"__playlistTitle":"${names[id]}","count":${songs.size},"playlisttracks_loop":[${slice(songs, 2)}]}"""
            }
            cmd.take(2) == listOf("playlists", "new") -> {
                val name = cmd.param("name")!!
                val taken = names.entries.firstOrNull { it.value == name }
                if (taken != null) """{"overwritten_playlist_id":${taken.key}}"""
                else { val id = nextId++; names[id] = name; entries[id] = mutableListOf(); """{"playlist_id":$id}""" }
            }
            cmd.take(2) == listOf("playlists", "rename") -> {
                val id = cmd.param("playlist_id")!!.toInt()
                val name = cmd.param("newname")!!
                val taken = names.entries.firstOrNull { it.value == name && it.key != id }
                if (cmd.param("dry_run") == null) {
                    taken?.let { names.remove(it.key); entries.remove(it.key) }
                    names[id] = name
                }
                if (taken != null) """{"overwritten_playlist_id":${taken.key}}""" else "{}"
            }
            cmd.take(2) == listOf("playlists", "delete") -> {
                val id = cmd.param("playlist_id")!!.toInt()
                names.remove(id); entries.remove(id); "{}"
            }
            cmd.take(2) == listOf("playlists", "edit") -> {
                val list = entries.getValue(cmd.param("playlist_id")!!.toInt())
                when (cmd.param("cmd")) {
                    "delete" -> list.removeAt(cmd.param("index")!!.toInt())
                    // "Add entry if not already in list".
                    "add" -> library.first { it.url == cmd.param("url") }.let { if (it.id !in list) list += it.id }
                }
                "{}"
            }
            cmd[0] == "playlists" -> {
                // A folder of playlists comes first, with its path as id.
                val all = listOf("""{"id":"file:///playlists/Old","playlist":"Old"}""") + names.map { (id, n) -> """{"id":$id,"playlist":"$n"}""" }
                """{"count":${all.size},"playlists_loop":[${slice(all, 1)}]}"""
            }
            cmd[0] == "albums" -> {
                // sort:playcount: the albums whose songs were played most first.
                assertEquals("playcount", cmd.param("sort"))
                val ranked = albumIds.entries.sortedByDescending { (a, _) -> library.filter { it.album == a }.sumOf { plays[it.id] ?: 0 } }
                val all = ranked.map { (a, id) -> """{"id":$id,"album":"$a"}""" }
                """{"count":${all.size},"albums_loop":[${slice(all, 1)}]}"""
            }
            cmd[0] == "titles" && cmd.param("album_id") != null -> {
                val album = albumIds.entries.first { it.value == cmd.param("album_id")!!.toInt() }.key
                val found = library.filter { it.album == album }.map { js(it) }
                """{"count":${found.size},"titles_loop":[${slice(found, 1)}]}"""
            }
            cmd[0] == "titles" -> {
                val byId = cmd.param("track_id")
                val words = cmd.param("search")?.lowercase()?.split(' ').orEmpty()
                val found = library.filter { s ->
                    if (byId != null) s.id == byId.toInt()
                    // Without the full-text search every word must be in the title.
                    else words.all { w -> w in s.title.lowercase() || (fullText && w in s.artist.lowercase().replace("é", "e").replace("å", "a")) }
                }.map { js(it) }
                if (found.isEmpty()) """{"count":0}""" else """{"count":${found.size},"titles_loop":[${slice(found, 1)}]}"""
            }
            else -> "{}"
        }
    }

    @Test fun signsInWithoutAPasswordAndListsPlaylists() = runBlocking {
        p.login(ctx, url, "", "")
        assertTrue(p.isConnected(ctx))
        // No user: the account is named after the server.
        assertEquals(url.removePrefix("http://"), p.accountName(ctx))
        val lists = p.playlists(ctx)
        assertEquals(listOf("37" to "Funky Beats", "57" to "SUPER"), lists.map { it.id to it.name })
        assertEquals(url, PlaylistLinks.webUrl("lyrion", "37", base = url))
    }

    @Test fun readsAPlaylistWithPositionsAndWhatIsNeededToMatch() = runBlocking {
        p.login(ctx, url, "", "")
        val tracks = p.tracks(ctx, "37")
        assertEquals(listOf("11", "12", "14"), tracks.map { it.id })
        assertEquals(listOf("0", "1", "2"), tracks.map { it.itemId })
        val battery = tracks.first()
        assertEquals("Battery", battery.title)
        assertEquals(listOf("Metallica"), battery.artists)
        assertEquals("Master of Puppets", battery.album)
        assertEquals(312_400L, battery.durationMs)
        assertEquals(1986, battery.year)
        assertEquals("Funky Beats" to 3, p.playlistInfo(ctx, "37").let { it.name to it.trackCount })
        assertEquals("tags:aldyu", commands.first { it.take(2) == listOf("playlists", "tracks") }.last())
    }

    @Test fun readsALongPlaylistPageByPage() = runBlocking {
        p.login(ctx, url, "", "")
        entries[57] = MutableList(1201) { library[it % library.size].id }
        assertEquals(1201, p.tracks(ctx, "57").size)
        assertEquals(listOf("0", "500", "1000"), commands.filter { it.take(2) == listOf("playlists", "tracks") }.map { it[2] })
    }

    @Test fun searchFallsBackToTheTitleAndPutsTheArtistFirst() = runBlocking {
        p.login(ctx, url, "", "")
        val found = p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé")))
        assertEquals(listOf("13", "12"), found.map { it.id })
        assertEquals(listOf("search:beyonce home", "search:home"), commands.filter { it[0] == "titles" }.map { it[3] })
    }

    @Test fun searchFindsArtistAndTitleAtOnceWithTheFullTextSearch() = runBlocking {
        fullText = true
        p.login(ctx, url, "", "")
        assertEquals(listOf("13"), p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé"))).map { it.id })
        assertEquals(1, commands.count { it[0] == "titles" })
        assertEquals(emptyList<Track>(), p.search(ctx, Track(id = "q", title = "Nothing Like This", artists = listOf("Nobody"))))
        // A saved album or a followed artist has no place in a playlist here.
        assertEquals(emptyList<Track>(), p.search(ctx, Track(id = "q", title = "Rush!", kind = Track.KIND_ALBUM)))
    }

    @Test fun createsAPlaylistAndRefusesATakenName() = runBlocking {
        p.login(ctx, url, "", "")
        val made = p.createPlaylist(ctx, "Road: trip", "ignored")
        assertEquals("100" to "Road: trip", made.id to made.name)
        assertEquals("Road: trip", names[100])
        val e = runCatching { p.createPlaylist(ctx, "SUPER", "") }.exceptionOrNull()
        assertEquals("error_server_name_taken(SUPER)", e?.message)
        assertEquals(3, names.size)
    }

    @Test fun addsFoundTracksByTheirUrlAndCachedOnesAfterAskingForIt() = runBlocking {
        p.login(ctx, url, "", "")
        val found = p.search(ctx, Track(id = "q", title = "Battery", artists = listOf("Metallica"))).first()
        commands.clear()
        // The second is known by id only, as from the cache of matches.
        p.addTracks(ctx, "57", listOf(found, Track(id = "13", title = "Home")))
        assertEquals(listOf(11, 13), entries[57])
        assertEquals(1, commands.count { it[0] == "titles" })
        assertEquals(listOf("titles", "0", "1", "track_id:13", "tags:u"), commands.single { it[0] == "titles" })
        assertEquals("url:file:///music/Metallica/Master of Puppets/Battery.flac", commands.first { it.getOrNull(2) == "cmd:add" }[4])
        // The server keeps one copy of a track in a playlist: said, and not asked for again.
        commands.clear()
        assertEquals(setOf("11"), p.addTracksReportingPresent(ctx, "57", listOf(found, Track(id = "14", title = "One"))))
        assertEquals(listOf(11, 13, 14), entries[57])
        assertEquals(1, commands.count { it.getOrNull(2) == "cmd:add" })
    }

    @Test fun aTrackTheServerNoLongerHasIsSaidPlainly() = runBlocking {
        p.login(ctx, url, "", "")
        val e = runCatching { p.addTracks(ctx, "57", listOf(Track(id = "999", title = "Gone", artists = listOf("X")))) }.exceptionOrNull()
        assertEquals("error_server_track_gone(X – Gone)", e?.message)
        assertEquals(emptyList<Int>(), entries[57])
    }

    @Test fun removesByPositionFromTheLast() = runBlocking {
        p.login(ctx, url, "", "")
        val tracks = p.tracks(ctx, "37")
        p.removeTracks(ctx, "37", listOf(tracks[0], tracks[2]))
        assertEquals(listOf(12), entries[37])
        assertEquals(listOf("index:2", "index:0"), commands.filter { it.getOrNull(2) == "cmd:delete" }.map { it[4] })
    }

    @Test fun renamesOnlyOntoAFreeNameAndDeletes() = runBlocking {
        p.login(ctx, url, "", "")
        assertTrue(p.canRenamePlaylists && p.canDeletePlaylists && p.canCreatePlaylists && p.canRemoveTracks)
        val e = runCatching { p.renamePlaylist(ctx, "37", "SUPER") }.exceptionOrNull()
        assertEquals("error_server_name_taken(SUPER)", e?.message)
        // Asked with dry_run only: the other playlist is still there.
        assertEquals(setOf(37, 57), names.keys)
        p.renamePlaylist(ctx, "37", "Funk")
        assertEquals("Funk", names[37])
        p.deletePlaylist(ctx, "57")
        assertEquals(setOf(37), names.keys)
    }

    @Test fun aProtectedServerTakesUserAndPasswordAndSaysWhenTheyAreWrong() = runBlocking {
        password = "lorenzo" to "s3cr3t"
        val none = runCatching { p.login(ctx, url, "", "") }.exceptionOrNull()
        assertEquals("error_lyrion_password", none?.message)
        val wrong = runCatching { p.login(ctx, url, "lorenzo", "nope") }.exceptionOrNull()
        assertEquals("error_lyrion_password", wrong?.message)
        assertFalse(p.isConnected(ctx))
        p.login(ctx, url, " lorenzo ", "s3cr3t")
        assertEquals("lorenzo", p.accountName(ctx))
        assertEquals(2, p.playlists(ctx).size)
        // The password changed on the server: the next call says so instead of an empty list.
        password = "lorenzo" to "new"
        assertEquals("error_lyrion_password", runCatching { p.playlists(ctx) }.exceptionOrNull()?.message)
    }

    @Test fun anAddressThatIsNotALyrionServerIsRefused() = runBlocking {
        val other = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex -> val b = "<html>router</html>".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            start()
        }
        try {
            val e = runCatching { p.login(ctx, "http://127.0.0.1:${other.address.port}", "", "") }.exceptionOrNull()
            assertEquals("error_lyrion_not_found", e?.message)
            assertNull(ctx.tokens.get(p.id))
        } finally { other.stop(0) }
        val gone = runCatching { p.login(ctx, "$url/nothing-here", "", "") }.exceptionOrNull()
        assertTrue(gone?.message, gone?.message?.contains("404") == true)
    }

    @Test fun declaresWhatItCanDo() {
        assertTrue(p.beta)
        assertFalse(p.supportsLikedSongs || p.supportsAlbums || p.supportsArtists)
        assertTrue(p.loginForm.userOptional && p.loginForm.secretOptional)
        assertTrue(Providers.engineFactories().any { it.service == "lyrion" })
    }

    @Test fun readsThePlaysOfEachSongOfAPlaylist() = runBlocking {
        p.login(ctx, url, "", "")
        val counted = p.playlistPlays(ctx, "37")
        assertEquals(listOf("11" to 7, "12" to 0, "14" to 31), counted.map { it.track.id to it.plays })
        assertEquals("tags:aldyuO", commands.last { it.take(2) == listOf("playlists", "tracks") }.last())
        // A library list is not a Lyrion playlist: nothing asked.
        val before = commands.size
        assertTrue(p.playlistPlays(ctx, MusicProvider.LIKED_ID).isEmpty())
        assertEquals(before, commands.size)
    }

    @Test fun theTopSongsComeFromTheAlbumsPlayedMostByTheirOwnCounts() = runBlocking {
        p.login(ctx, url, "", "")
        val top = p.topPlays(ctx, 10)
        // Never played songs are left out; the most played first, whatever its album's rank.
        assertEquals(listOf("14" to 31, "11" to 7), top.map { it.track.id to it.plays })
    }
}
