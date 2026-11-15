package com.xlollx.songport.sync

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gaana public links: answers in the shape api.gaana.com's playlist/detail gave on 15/11/2026
 * (test/resources/gaana), with made-up songs.
 */
class GaanaPublicTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/gaana/$name.json")!!.readText())
    private fun ref(s: String) = PlaylistLinks.parse(s)

    @Test fun aPlaylistListsItsSongsWithTheirIsrc() {
        val page = fixture("playlist")
        assertFalse(GaanaPublic.missing(page))
        val p = GaanaPublic.playlistOf(page, "long-drive-test")
        assertEquals("long-drive-test", p.id)
        assertEquals("Long Drive", p.name)
        // The songs the answer gives, not the site's own (higher) count.
        assertEquals(3, p.trackCount)
        assertEquals(false, p.ownedByMe)
        assertEquals("Songs for a long drive.\nMade up for the tests.", p.description)
        assertEquals("https://a10.gaanacdn.com/gn_pl_img/playlists/test/size_m.jpg", p.imageUrl)

        val songs = GaanaPublic.tracksOf(page)
        assertEquals(listOf("9000001", "9000002", "9000003"), songs.map { it.id })
        val first = songs[0]
        assertEquals("First Light (From \"Morning\")", first.title)
        assertEquals(listOf("Asha Test", "Ravi Sample"), first.artists)
        assertEquals("Morning", first.album)
        assertEquals(278_000L, first.durationMs)
        assertEquals("INT002600011", first.isrc)
        assertEquals(2026, first.year)
        assertEquals(false, first.explicit)
        assertEquals("https://gaana.com/song/first-light", first.uri)
        // A length sent as a number, an empty ISRC, an explicit song.
        assertEquals(195_000L, songs[1].durationMs)
        assertNull(songs[1].isrc)
        assertEquals(true, songs[1].explicit)
        // Nothing but an id and a title.
        assertEquals(emptyList<String>(), songs[2].artists)
        assertEquals(0, songs[2].year)
    }

    @Test fun aPlaylistTheSiteDoesNotHaveIsMissing() {
        assertTrue(GaanaPublic.missing(fixture("missing")))
        assertTrue(GaanaPublic.tracksOf(fixture("missing")).isEmpty())
    }

    @Test fun theSiteNamesItselfSoTheApiAnswers() {
        assertEquals("GaanaWapApp", GaanaPublic.HEADERS["deviceType"])
        assertEquals("IN", GaanaPublic.HEADERS["COUNTRY"])
        assertTrue(GaanaPublic.HEADERS.keys.containsAll(listOf("deviceId", "appVersion", "gaanaAppVersion")))
    }

    @Test fun linksAreRecognised() {
        val list = PlaylistLinks.Ref("gaana", "gaana-dj-bollywood-top-50-1")
        assertEquals(list, ref("https://gaana.com/playlist/gaana-dj-bollywood-top-50-1"))
        assertEquals(list, ref("Listen to Hindi Top 50 on Gaana https://gaana.com/playlist/gaana-dj-bollywood-top-50-1?utm_source=share"))
        assertEquals(list, ref("www.gaana.com/playlist/gaana-dj-bollywood-top-50-1/"))
        assertEquals(list, ref("https://m.gaana.com/playlist/gaana-dj-bollywood-top-50-1"))
        // Songs, albums and other sites are not playlists of Gaana.
        assertNull(ref("https://gaana.com/song/first-light"))
        assertNull(ref("https://gaana.com/album/morning"))
        assertNull(ref("https://notgaana.com/playlist/x"))
        assertEquals("https://gaana.com/playlist/long-drive-test", PlaylistLinks.webUrl("gaana", "long-drive-test"))
    }
}
