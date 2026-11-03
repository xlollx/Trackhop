package com.xlollx.songport.sync

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #27: public Anghami playlists from a link. The page saved in test/resources/anghami. */
class AnghamiPublicTest {
    private val page = parseJson(javaClass.getResource("/anghami/public.json")!!.readText())

    @Test fun aPlaylistAnghamiMakesListsItsSongsAsJson() {
        val songs = AnghamiPublic.songsOf(page)
        assertEquals(3, songs.size)
        assertEquals("1290789601", songs[0].id)
        assertEquals("تامر حسني", songs[0].artists.single())
        assertEquals(179_000L, songs[0].durationMs)
        assertEquals("Top Anghami Arabic", AnghamiPublic.playlistOf(page, "5891475").name)
    }

    @Test fun aUsersPlaylistSendsItsSongsAsBuffers() {
        val t = AnghamiPublic.parseBuffer("ElcKCDgxNjQxNjQ3EksKCDgxNjQxNjQ3EgdBbCBZb3VtGgdBbCBZb3VtIgoxMDEzNzU2NTYxKg5IYWxhIEFsIEthc2VlcjIGMTI0Mzg5QgQyMDIwTbieOUM=")!!
        assertEquals("81641647", t.id)
        assertEquals("Al Youm", t.title)
        assertEquals(listOf("Hala Al Kaseer"), t.artists)
        assertEquals(185, (t.durationMs / 1000).toInt())
        assertEquals(2020, t.year)
    }

    @Test fun linksOfAPlaylist() {
        assertEquals("anghami" to "5891475", PlaylistLinks.parse("https://play.anghami.com/playlist/5891475")?.let { it.providerId to it.playlistId })
    }
}
