package com.xlollx.songport.providers

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Mopidy against a stand-in: a local HTTP server answering /mopidy/rpc as Mopidy's core API is
 * documented (JSON-RPC 2.0, models with "__model__"), with playlists kept as its m3u backend keeps
 * them: a track's uri and name and nothing else, a new playlist replacing one of the same name.
 */
class MopidyProviderTest {
    private class Song(val uri: String, val name: String, val artist: String, val album: String, val ms: Long, val date: String) {
        val json get() = """{"__model__":"Track","uri":"$uri","name":"$name","artists":[{"__model__":"Artist","name":"$artist"}],""" +
            """"album":{"__model__":"Album","name":"$album","date":"$date"},"length":$ms,"date":"$date"}"""
    }

    private val library = listOf(
        Song("local:track:Metallica/Battery.flac", "Battery", "Metallica", "Master of Puppets", 312_400, "1986-03-03"),
        Song("local:track:Maneskin/Home.flac", "Home", "Måneskin", "Rush!", 180_000, "2023"),
        Song("local:track:Beyonce/Home.flac", "Home", "Beyoncé", "Renaissance", 201_000, "2022-07-29"),
        Song("local:track:Metallica/One.flac", "One", "Metallica", "...And Justice for All", 446_000, "1988"),
    )
    /** uri -> name and the uris it lists. */
    private val playlists = linkedMapOf(
        "m3u:Funky%20Beats.m3u8" to ("Funky Beats" to mutableListOf(library[0].uri, library[1].uri, library[3].uri, "http://radio.example/stream")),
        "m3u:SUPER.m3u8" to ("SUPER" to mutableListOf<String>()),
    )

    private class Call(val method: String, val params: JsonElement?)
    private val calls = mutableListOf<Call>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mopidy/rpc") { ex ->
            val req = parseJson(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            assertEquals("2.0", req["jsonrpc"].str)
            val call = Call(req["method"].str ?: "", req["params"])
            synchronized(calls) { calls += call }
            val result = answer(call)
            val text = if (result == null) """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found","data":"Could not find object mounted at ${call.method}"}}"""
            else """{"jsonrpc":"2.0","id":1,"result":$result}"""
            val bytes = text.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        createContext("/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}"
    private val ctx = TestEnv()
    private val p = MopidyProvider()

    @After fun stop() = server.stop(0)

    private fun playlistJson(uri: String): String {
        val (name, uris) = playlists[uri] ?: return "null"
        // As the m3u backend reads its file: uri and name, no artist, no length.
        val tracks = uris.joinToString(",") { u -> """{"__model__":"Track","uri":"$u","name":"${library.firstOrNull { it.uri == u }?.name ?: "Radio"}"}""" }
        return """{"__model__":"Playlist","uri":"$uri","name":"$name","tracks":[$tracks],"last_modified":1700000000000}"""
    }

    private fun answer(c: Call): String? = when (c.method) {
        "core.get_version" -> "\"3.4.2\""
        "core.playlists.as_list" -> playlists.entries.joinToString(",", "[", "]") { (uri, v) -> """{"__model__":"Ref","type":"playlist","uri":"$uri","name":"${v.first}"}""" }
        "core.playlists.lookup" -> playlistJson(c.params["uri"].str!!)
        "core.library.lookup" -> c.params["uris"].arr.joinToString(",", "{", "}") { u ->
            "\"${u.str}\":[" + library.filter { it.uri == u.str }.joinToString(",") { it.json } + "]"
        }
        "core.library.search" -> {
            val q = c.params["query"]
            val title = q["track_name"].arr.firstOrNull().str?.lowercase()
            val artist = q["artist"].arr.firstOrNull().str?.lowercase()
            val found = library.filter { s -> (title == null || title in s.name.lowercase()) && (artist == null || artist in s.artist.lowercase()) }
            """[{"__model__":"SearchResult","uri":"local:search","tracks":[${found.joinToString(",") { it.json }}]},{"__model__":"SearchResult","uri":"file:search"}]"""
        }
        "core.playlists.create" -> {
            val name = c.params["name"].str!!
            val uri = "m3u:" + name.replace(" ", "%20") + ".m3u8"
            playlists[uri] = name to mutableListOf() // an existing file of that name is replaced
            playlistJson(uri)
        }
        "core.playlists.save" -> {
            val pl = c.params["playlist"]
            val uri = pl["uri"].str!!
            assertEquals("Playlist", pl["__model__"].str)
            if (uri !in playlists) "null" else {
                playlists[uri] = (pl["name"].str ?: "") to pl["tracks"].arr.map { assertEquals("Track", it["__model__"].str); it["uri"].str!! }.toMutableList()
                playlistJson(uri)
            }
        }
        "core.playlists.delete" -> (playlists.remove(c.params["uri"].str) != null).toString()
        else -> null
    }

    @Test fun connectsWithTheAddressAloneAndListsPlaylists() = runBlocking {
        // The address as copied from the browser, with Mopidy's path, works too.
        p.login(ctx, "$url/mopidy/", "", "")
        assertTrue(p.isConnected(ctx))
        assertEquals(url.removePrefix("http://"), p.accountName(ctx))
        assertEquals(listOf("m3u:Funky%20Beats.m3u8" to "Funky Beats", "m3u:SUPER.m3u8" to "SUPER"), p.playlists(ctx).map { it.id to it.name })
        assertEquals("Funky Beats" to 4, p.playlistInfo(ctx, "m3u:Funky%20Beats.m3u8").let { it.name to it.trackCount })
    }

    @Test fun readsAPlaylistAndAsksTheLibraryForTheRest() = runBlocking {
        p.login(ctx, url, "", "")
        val tracks = p.tracks(ctx, "m3u:Funky%20Beats.m3u8")
        assertEquals(listOf("0", "1", "2", "3"), tracks.map { it.itemId })
        val battery = tracks.first()
        assertEquals("local:track:Metallica/Battery.flac", battery.id)
        assertEquals("Battery", battery.title)
        assertEquals(listOf("Metallica"), battery.artists)
        assertEquals("Master of Puppets", battery.album)
        assertEquals(312_400L, battery.durationMs)
        assertEquals(1986, battery.year)
        // Not in the library (a stream): what the playlist itself says about it.
        assertEquals("http://radio.example/stream" to "Radio", tracks.last().let { it.id to it.title })
        assertEquals(emptyList<String>(), tracks.last().artists)
    }

    @Test fun searchesTitleAndArtistThenTheTitleAlone() = runBlocking {
        p.login(ctx, url, "", "")
        assertEquals(listOf("local:track:Beyonce/Home.flac"), p.search(ctx, Track(id = "q", title = "Home", artists = listOf("Beyoncé"))).map { it.id })
        assertEquals(1, calls.count { it.method == "core.library.search" })
        // The artist written another way: the title alone, the artist's songs first.
        calls.clear()
        val found = p.search(ctx, Track(id = "q", title = "Home (Remastered 2011)", artists = listOf("Beyonce")))
        assertEquals(listOf("local:track:Beyonce/Home.flac", "local:track:Maneskin/Home.flac"), found.map { it.id })
        assertEquals(listOf(true, false), calls.filter { it.method == "core.library.search" }.map { it.params["query"]["artist"] != null })
        assertEquals("Home", calls.last().params["query"]["track_name"][0].str)
        assertEquals(emptyList<Track>(), p.search(ctx, Track(id = "q", title = "Nothing Like This", artists = listOf("Nobody"))))
        assertEquals(emptyList<Track>(), p.search(ctx, Track(id = "q", title = "Rush!", kind = Track.KIND_ALBUM)))
    }

    @Test fun createsAPlaylistAndNeverOverAnExistingOne() = runBlocking {
        p.login(ctx, url, "", "")
        val made = p.createPlaylist(ctx, "Road trip", "ignored")
        assertEquals("m3u:Road%20trip.m3u8" to "Road trip", made.id to made.name)
        val e = runCatching { p.createPlaylist(ctx, "funky beats", "") }.exceptionOrNull()
        assertEquals("error_server_name_taken(funky beats)", e?.message)
        // Not asked at all: the four tracks are still there.
        assertEquals(4, playlists.getValue("m3u:Funky%20Beats.m3u8").second.size)
        assertEquals(1, calls.count { it.method == "core.playlists.create" })
    }

    @Test fun addsAtTheEndKeepingWhatIsThere() = runBlocking {
        p.login(ctx, url, "", "")
        p.addTracks(ctx, "m3u:Funky%20Beats.m3u8", listOf(Track(id = library[2].uri, title = "Home"), Track(id = library[0].uri, title = "Battery")))
        assertEquals(
            listOf(library[0].uri, library[1].uri, library[3].uri, "http://radio.example/stream", library[2].uri, library[0].uri),
            playlists.getValue("m3u:Funky%20Beats.m3u8").second,
        )
        assertEquals("Funky Beats", playlists.getValue("m3u:Funky%20Beats.m3u8").first)
        p.addTracks(ctx, "m3u:SUPER.m3u8", emptyList())
        assertEquals(1, calls.count { it.method == "core.playlists.save" })
    }

    @Test fun removesByPositionOnlyWhereTheTrackIsStillThere() = runBlocking {
        p.login(ctx, url, "", "")
        val id = "m3u:Funky%20Beats.m3u8"
        val tracks = p.tracks(ctx, id)
        p.removeTracks(ctx, id, listOf(tracks[0], tracks[2]))
        assertEquals(listOf(library[1].uri, "http://radio.example/stream"), playlists.getValue(id).second)
        // The positions read before no longer hold those tracks: nothing else is taken away in their place.
        calls.clear()
        p.removeTracks(ctx, id, listOf(tracks[1].copy(itemId = "1"), tracks[3]))
        assertEquals(listOf(library[1].uri, "http://radio.example/stream"), playlists.getValue(id).second)
        assertEquals(0, calls.count { it.method == "core.playlists.save" })
    }

    @Test fun deletesAndDoesNotOfferARename() = runBlocking {
        p.login(ctx, url, "", "")
        assertTrue(p.canDeletePlaylists && p.canCreatePlaylists && p.canRemoveTracks)
        assertFalse(p.canRenamePlaylists)
        p.deletePlaylist(ctx, "m3u:SUPER.m3u8")
        assertEquals(listOf("m3u:Funky%20Beats.m3u8"), playlists.keys.toList())
        assertTrue(runCatching { p.deletePlaylist(ctx, "m3u:SUPER.m3u8") }.exceptionOrNull()?.message?.contains("not deleted") == true)
        // A playlist that is gone is said, not read as empty.
        assertTrue(runCatching { p.tracks(ctx, "m3u:SUPER.m3u8") }.exceptionOrNull()?.message?.contains("not found") == true)
        assertTrue(runCatching { p.addTracks(ctx, "m3u:SUPER.m3u8", listOf(Track(id = "x", title = "X"))) }.exceptionOrNull() != null)
    }

    @Test fun anAddressWithoutMopidyIsRefusedAndErrorsAreSaid() = runBlocking {
        val e = runCatching { p.login(ctx, "$url/elsewhere", "", "") }.exceptionOrNull()
        assertEquals("error_mopidy_not_found", e?.message)
        assertNull(ctx.tokens.get(p.id))
        // Not connected: said before any request.
        assertEquals("error_not_connected(Mopidy)", runCatching { p.playlists(ctx) }.exceptionOrNull()?.message)
        p.login(ctx, url, "", "")
        server.removeContext("/mopidy/rpc")
        assertTrue(runCatching { p.playlists(ctx) }.exceptionOrNull()?.message?.contains("404") == true)
    }

    @Test fun declaresWhatItCanDo() {
        assertTrue(p.beta)
        assertFalse(p.supportsLikedSongs || p.supportsAlbums || p.supportsArtists)
        assertFalse(p.loginForm.needsUser || p.loginForm.needsSecret)
        assertTrue(Providers.engineFactories().any { it.service == "mopidy" })
    }
}
