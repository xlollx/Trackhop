package com.xlollx.songport.sync

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JioSaavn public links: the site's webapi.get answers saved (trimmed) in test/resources/jiosaavn. */
class JioSaavnPublicTest {
    private fun fixture(name: String) = parseJson(javaClass.getResource("/jiosaavn/$name.json")!!.readText())
    private fun ref(s: String) = PlaylistLinks.parse(s)

    @Test fun aPlaylistListsItsSongs() {
        val page = fixture("playlist")
        val p = JioSaavnPublic.playlistOf(page, "featured/best-of-ghazals-hindi/Kdb2rW52fiE_")
        assertEquals("Best Of Ghazals - Hindi", p.name)
        assertEquals(3, p.trackCount)
        assertEquals(false, p.ownedByMe)
        assertEquals("https://c.saavncdn.com/editorial/BestOfGhazalsHindi_20260909061521.jpg", p.imageUrl)
        val songs = JioSaavnPublic.tracksOf(page)
        assertEquals(listOf("9vXZ2ueQ", "UmanntR6", "Bx7-n4Qk"), songs.map { it.id })
        val first = songs[0]
        assertEquals("Koi Fariyaad", first.title)
        assertEquals(listOf("Jagjit Singh"), first.artists)
        assertEquals("Tum Bin", first.album)
        assertEquals(539_000L, first.durationMs)
        assertEquals(2001, first.year)
        assertEquals(false, first.explicit)
        assertNull(first.isrc)
        assertEquals("https://www.jiosaavn.com/song/koi-fariyaad/SR4za0ZFUmI", first.uri)
        // The site's HTML entities come out as text.
        assertEquals("Hothon Se Chhu Lo Tum (From \"Prem Geet\")", songs[1].title)
        assertEquals("Vishal & Shekhar", songs[2].artists.first())
    }

    @Test fun anAlbumAndASong() {
        val album = fixture("album")
        assertEquals("Tum Bin", JioSaavnPublic.playlistOf(album, "album/tum-bin/-F0cBDosYnc_").name)
        val songs = JioSaavnPublic.tracksOf(album)
        assertEquals(listOf("Chhoti Chhoti Raatein", "Tumhare Siva"), songs.map { it.title })
        assertEquals(listOf("Sonu Nigam", "Anuradha Paudwal"), songs[0].artists)
        assertEquals(357_000L, songs[0].durationMs)

        val song = fixture("song")
        val p = JioSaavnPublic.playlistOf(song, "song/koi-fariyaad/SR4za0ZFUmI")
        assertEquals("Koi Fariyaad", p.name)
        assertEquals(1, p.trackCount)
        assertEquals(listOf("Koi Fariyaad"), JioSaavnPublic.tracksOf(song).map { it.title })
        assertFalse(JioSaavnPublic.missing(song))
    }

    @Test fun aMissingLinkIsAnswerWithoutAnId() {
        assertTrue(JioSaavnPublic.missing(parseJson("""{"id":"","title":"","type":"playlist","list_count":"0","list":""}""")))
        assertTrue(JioSaavnPublic.missing(parseJson("""{"songs":[]}""")))
        assertFalse(JioSaavnPublic.missing(fixture("playlist")))
        // A song with no performers listed as such keeps its singers; anything not a song is left out.
        val credits = parseJson("""{"id":"a1","title":"X","type":"song","more_info":{"duration":"60","artistMap":{"primary_artists":[],
            "artists":[{"name":"Composer","role":"music"},{"name":" Singer ","role":"singer"}]}}}""")
        assertEquals(listOf("Singer"), JioSaavnPublic.parseTrack(credits)!!.artists)
        assertNull(JioSaavnPublic.parseTrack(parseJson("""{"id":"e1","title":"Episode","type":"episode"}""")))
    }

    @Test fun linksOfPlaylistsAlbumsAndSongs() {
        val featured = PlaylistLinks.Ref("jiosaavn", "featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__")
        assertEquals(featured, ref("https://www.jiosaavn.com/featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__"))
        // As the app shares them, and as short links and saavn.com land: with tracking parameters.
        assertEquals(featured, ref("Listen on JioSaavn https://www.jiosaavn.com/featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__?referrer=svn_source=share&svn_medium=system&utm_source=share"))
        assertEquals(featured, ref("https://saavn.com/featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__"))
        assertEquals(featured, ref("jiosaavn.com/featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__/"))
        assertEquals(PlaylistLinks.Ref("jiosaavn", "s/playlist/2279fbe391defa79/my_mix/kUNKDcX7Cpb54Ip,yLzYJA__"),
            ref("https://www.jiosaavn.com/s/playlist/2279fbe391defa79/my_mix/kUNKDcX7Cpb54Ip,yLzYJA__"))
        assertEquals(PlaylistLinks.Ref("jiosaavn", "album/tum-bin/-F0cBDosYnc_"), ref("https://m.jiosaavn.com/album/tum-bin/-F0cBDosYnc_"))
        assertEquals(PlaylistLinks.Ref("jiosaavn", "song/koi-fariyaad/SR4za0ZFUmI"), ref("https://www.jiosaavn.com/song/koi-fariyaad/SR4za0ZFUmI"))
        // Artists, searches, other sites and short links (followed first) are not lists to read.
        assertNull(ref("https://www.jiosaavn.com/artist/jagjit-singh-songs/EMskk1sVOtM_"))
        assertNull(ref("https://www.jiosaavn.com/search/song/ghazal"))
        assertNull(ref("https://notjiosaavn.com/featured/x/abc_"))
        assertNull(ref("https://jiosaavn.go.link/abc"))
        assertEquals("album", JioSaavnPublic.kindOf("album/tum-bin/-F0cBDosYnc_"))
        assertEquals("song", JioSaavnPublic.kindOf("song/koi-fariyaad/SR4za0ZFUmI"))
        assertEquals("playlist", JioSaavnPublic.kindOf(featured.playlistId))
        assertEquals("https://www.jiosaavn.com/featured/best-of-2000s-hindi/w,zEBfumNkQGSw2I1RxdhQ__", PlaylistLinks.webUrl("jiosaavn", featured.playlistId))
    }
}
