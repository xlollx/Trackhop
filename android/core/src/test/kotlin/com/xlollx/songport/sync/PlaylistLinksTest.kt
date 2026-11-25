package com.xlollx.songport.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistLinksTest {
    private fun ref(url: String) = PlaylistLinks.parse(url)

    @Test fun spotify() {
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("https://open.spotify.com/intl-it/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"),
            ref("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test fun appleMusic() {
        assertEquals(PlaylistLinks.Ref("apple", "pl.u-8aAVZjvtLLlN2o"),
            ref("https://music.apple.com/it/playlist/le-mie-canzoni/pl.u-8aAVZjvtLLlN2o"))
        assertEquals(PlaylistLinks.Ref("apple", "pl.f4d106fed2bd41149aaacabb233eb5eb"),
            ref("https://music.apple.com/us/playlist/pl.f4d106fed2bd41149aaacabb233eb5eb"))
    }

    @Test fun youtube() {
        assertEquals(PlaylistLinks.Ref("youtube", "PLabc-123_x"),
            ref("https://www.youtube.com/playlist?list=PLabc-123_x"))
        assertEquals(PlaylistLinks.Ref("youtube", "PLabc-123_x"),
            ref("https://music.youtube.com/playlist?list=PLabc-123_x&si=zz"))
    }

    @Test fun deezerAndTidal() {
        assertEquals(PlaylistLinks.Ref("deezer", "1234567"), ref("https://www.deezer.com/it/playlist/1234567"))
        assertEquals(PlaylistLinks.Ref("deezer", "1234567"), ref("https://deezer.com/playlist/1234567"))
        assertEquals(PlaylistLinks.Ref("tidal", "12345678-1234-1234-1234-123456789abc"),
            ref("https://tidal.com/browse/playlist/12345678-1234-1234-1234-123456789abc"))
    }

    @Test fun rejectsJunk() {
        assertNull(ref(""))
        assertNull(ref("ciao"))
        assertNull(ref("https://example.com/playlist/1"))
        assertNull(ref("https://open.spotify.com/album/1A2B3C"))
    }

    @Test fun parseAllFindsEveryLinkOnce() {
        val text = "mine https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M yours https://www.deezer.com/it/playlist/1234567 " +
            "again https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"
        assertEquals(listOf(PlaylistLinks.Ref("spotify", "37i9dQZF1DXcBWIGoYBM5M"), PlaylistLinks.Ref("deezer", "1234567")), PlaylistLinks.parseAll(text))
        assertEquals(emptyList<PlaylistLinks.Ref>(), PlaylistLinks.parseAll("no links here"))
    }

    @Test fun webUrls() {
        assertEquals("https://open.spotify.com/playlist/abc", PlaylistLinks.webUrl("spotify_bridge", "abc"))
        assertEquals("https://www.deezer.com/playlist/111", PlaylistLinks.webUrl("deezer_bridge", "111"))
        assertEquals("https://music.apple.com/it/playlist/pl.u-1", PlaylistLinks.webUrl("apple", "pl.u-1", region = "it"))
        assertEquals("https://music.youtube.com/playlist?list=PL1", PlaylistLinks.webUrl("ytm", "PL1"))
        assertEquals("https://music.amazon.it/user-playlists/u1", PlaylistLinks.webUrl("amazon", "u1", region = "it"))
        assertEquals("https://jf.home/web/#/details?id=p1", PlaylistLinks.webUrl("jellyfin", "p1", base = "https://jf.home/"))
        assertEquals("https://nav.home", PlaylistLinks.webUrl("subsonic@2", "p1", base = "https://nav.home"))
        assertNull(PlaylistLinks.webUrl("spotify", "__liked__"))
        assertNull(PlaylistLinks.webUrl("csv", "file"))
    }

    @Test fun soundcloud() {
        val set = PlaylistLinks.Ref("soundcloud", "artist-name/sets/my_set-2")
        assertEquals(set, ref("https://soundcloud.com/artist-name/sets/my_set-2"))
        assertEquals(set, ref("https://m.soundcloud.com/artist-name/sets/my_set-2?si=abc&utm_source=clipboard"))
        assertEquals(set, ref("Listen: https://www.soundcloud.com/artist-name/sets/my_set-2/ now"))
        assertEquals(PlaylistLinks.Ref("soundcloud", "dj/sets/promo/s-AbC12"), ref("https://soundcloud.com/dj/sets/promo/s-AbC12?si=1"))
        assertEquals(PlaylistLinks.Ref("soundcloud", "someone/likes"), ref("soundcloud.com/someone/likes"))
        // A single track, a profile, the signed-in user's own pages and short links are not sets.
        assertNull(ref("https://soundcloud.com/artist-name/a-track"))
        assertNull(ref("https://soundcloud.com/artist-name"))
        assertNull(ref("https://soundcloud.com/you/likes"))
        assertNull(ref("https://on.soundcloud.com/AbCdEf"))
        assertEquals(listOf(set, PlaylistLinks.Ref("soundcloud", "someone/likes")),
            PlaylistLinks.parseAll("https://soundcloud.com/artist-name/sets/my_set-2 https://soundcloud.com/someone/likes"))
        assertEquals("https://soundcloud.com/dj/sets/promo/s-AbC12", PlaylistLinks.webUrl("soundcloud", "dj/sets/promo/s-AbC12"))
    }

    @Test fun netease() {
        val ref1 = PlaylistLinks.Ref("netease", "19723756")
        assertEquals(ref1, ref("https://music.163.com/#/playlist?id=19723756"))
        assertEquals(ref1, ref("https://music.163.com/playlist?id=19723756&userid=1"))
        assertEquals(ref1, ref("https://y.music.163.com/m/playlist?id=19723756&creatorId=1"))
        assertEquals(ref1, ref("分享歌单 https://music.163.com/playlist/19723756/1/?userid=2 (来自@网易云音乐)"))
        assertEquals(ref1, ref("https://music.163.com/#/my/m/music/playlist?id=19723756"))
        assertEquals("https://music.163.com/#/playlist?id=19723756", PlaylistLinks.webUrl("netease", "19723756"))
    }

    @Test fun qqMusic() {
        val ref1 = PlaylistLinks.Ref("qqmusic", "7256912512")
        assertEquals(ref1, ref("https://y.qq.com/n/ryqq/playlist/7256912512"))
        assertEquals(ref1, ref("https://y.qq.com/n/yqq/playlist/7256912512.html"))
        assertEquals(ref1, ref("https://i.y.qq.com/n2/m/share/details/taoge.html?hosteuin=x&id=7256912512&ADTAG=share"))
        assertEquals("https://y.qq.com/n/ryqq/playlist/7256912512", PlaylistLinks.webUrl("qqmusic", "7256912512"))
    }

    /** 2.2: a playlist file kept online is a source of its own, the whole address its id. */
    @Test fun playlistFileLinks() {
        assertEquals(PlaylistLinks.Ref("playlisturl", "https://example.org/lists/road%20trip.xspf"), ref("https://example.org/lists/road%20trip.xspf"))
        assertEquals(PlaylistLinks.Ref("playlisturl", "http://nas.lan:8080/x.M3U8?v=2"), ref("see http://nas.lan:8080/x.M3U8?v=2 here"))
        assertEquals("playlisturl", ref("https://a.b/c.jspf")?.providerId)
        assertEquals("playlisturl", ref("https://a.b/c.pls")?.providerId)
        assertNull(ref("https://a.b/c.xspf.html"))
        assertNull(ref("file:///sdcard/c.m3u"))
        assertEquals("https://a.b/c.m3u", PlaylistLinks.webUrl("playlisturl", "https://a.b/c.m3u"))
        assertEquals("road trip.xspf", com.xlollx.songport.providers.PlaylistUrlProvider.fileName("https://example.org/lists/road%20trip.xspf?x=1"))
    }
}
