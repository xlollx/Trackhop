package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistFilesTest {

    @Test fun detectsFormats() {
        assertEquals(PlaylistFiles.Format.M3U, PlaylistFiles.detect("a.m3u8", "#EXTM3U\n"))
        assertEquals(PlaylistFiles.Format.M3U, PlaylistFiles.detect("boh", "#EXTM3U\n#EXTINF:1,a\n"))
        assertEquals(PlaylistFiles.Format.ITUNES_XML, PlaylistFiles.detect("Libreria.xml", "<?xml version=\"1.0\"?>\n<!DOCTYPE plist>\n<plist version=\"1.0\">"))
        assertEquals(PlaylistFiles.Format.JSON, PlaylistFiles.detect("x", "[{\"title\":\"a\"}]"))
        assertEquals(PlaylistFiles.Format.CSV, PlaylistFiles.detect("x.csv", "title,artist\na,b\n"))
        assertEquals(PlaylistFiles.Format.TEXT, PlaylistFiles.detect("x.txt", "Daft Punk - Get Lucky\n"))
    }

    @Test fun parsesM3u() {
        val m3u = """
            #EXTM3U
            #EXTINF:369,Daft Punk - Get Lucky
            /storage/music/get_lucky.mp3
            #EXTINF:-1,Nirvana - Smells Like Teen Spirit
            http://example.com/x.mp3
            /storage/music/Queen - Bohemian Rhapsody.mp3
        """.trimIndent()
        val t = PlaylistFiles.parse("list.m3u", m3u)
        assertEquals(3, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals(369_000L, t[0].durationMs)
        assertEquals(0L, t[1].durationMs)
        // Senza EXTINF si usa il nome del file.
        assertEquals("Bohemian Rhapsody", t[2].title)
        assertEquals(listOf("Queen"), t[2].artists)
    }

    @Test fun m3uRoundTrip() {
        val tracks = listOf(Track("1", "Get Lucky", listOf("Daft Punk"), "RAM", 369_000))
        val back = PlaylistFiles.parse("x.m3u8", PlaylistFiles.toM3u(tracks))
        assertEquals("Get Lucky", back[0].title)
        assertEquals(listOf("Daft Punk"), back[0].artists)
        assertEquals(369_000L, back[0].durationMs)
    }

    @Test fun parsesItunesXml() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>Tracks</key>
            <dict>
              <key>101</key>
              <dict>
                <key>Track ID</key><integer>101</integer>
                <key>Name</key><string>Volare (Nel blu dipinto di blu)</string>
                <key>Artist</key><string>Domenico Modugno</string>
                <key>Album</key><string>Best of</string>
                <key>Total Time</key><integer>211000</integer>
              </dict>
              <key>102</key>
              <dict>
                <key>Track ID</key><integer>102</integer>
                <key>Name</key><string>Rock &amp; Roll</string>
                <key>Artist</key><string>Led Zeppelin</string>
                <key>Total Time</key><integer>220000</integer>
              </dict>
            </dict>
            <key>Playlists</key>
            <array><dict><key>Name</key><string>Preferiti</string></dict></array>
            </dict></plist>
        """.trimIndent()
        val t = PlaylistFiles.parse("Libreria.xml", xml)
        assertEquals(2, t.size)
        assertEquals("Volare (Nel blu dipinto di blu)", t[0].title)
        assertEquals(listOf("Domenico Modugno"), t[0].artists)
        assertEquals(211_000L, t[0].durationMs)
        assertEquals("Rock & Roll", t[1].title)
    }

    @Test fun parsesJsonWithNestedArtists() {
        val json = """
            {"playlistName":"Mix","tracks":[
              {"title":"Get Lucky","artists":[{"name":"Daft Punk"},{"name":"Pharrell Williams"}],
               "album":{"name":"RAM"},"durationMs":369626,"isrc":"GBTDF1300003"},
              {"trackName":"Volare","artistName":"Domenico Modugno","duration":"3:31"}
            ]}
        """.trimIndent()
        val t = PlaylistFiles.parse("export.json", json)
        assertEquals(2, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), t[0].artists)
        assertEquals(369_626L, t[0].durationMs)
        assertEquals("GBTDF1300003", t[0].isrc)
        assertEquals("Volare", t[1].title)
        assertEquals(211_000L, t[1].durationMs)
    }

    @Test fun parsesAppleMusicTsvExport() {
        val tsv = "Name\tArtist\tComposer\tAlbum\tGenre\tSize\tTime\n" +
            "Get Lucky\tDaft Punk\t\tRandom Access Memories\tElectronic\t8880000\t369\n"
        val t = PlaylistFiles.parse("Playlist.txt", tsv)
        assertEquals(1, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals(369_000L, t[0].durationMs)
    }

    @Test fun parsesPlainText() {
        val text = """
            1. Daft Punk - Get Lucky
            Nirvana – Smells Like Teen Spirit
            # commento
            Imagine
        """.trimIndent()
        val t = PlaylistFiles.parse("lista.txt", text)
        assertEquals(3, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)
        assertEquals("Smells Like Teen Spirit", t[1].title)
        assertEquals("Imagine", t[2].title)
        assertTrue(t[2].artists.isEmpty())
    }

    @Test fun stableIdsAcrossFormats() {
        val fromText = PlaylistFiles.parse("a.txt", "Daft Punk - Get Lucky")
        val fromM3u = PlaylistFiles.parse("a.m3u", "#EXTM3U\n#EXTINF:369,Daft Punk - Get Lucky\nx.mp3")
        assertEquals(fromText[0].id, fromM3u[0].id)
    }

    @Test fun writesAndReadsXspf() {
        val tracks = listOf(
            Track("1", "Get Lucky", listOf("Daft Punk", "Pharrell Williams"), "Random Access Memories", 369000, isrc = "USQX91300108"),
            Track("2", "Rock & Roll <live>", listOf("Led Zeppelin")),
        )
        val xml = PlaylistFiles.toXspf("My <list>", tracks)
        assertTrue(xml.contains("<title>My &lt;list&gt;</title>"))
        assertTrue(xml.contains("<identifier>isrc:USQX91300108</identifier>"))
        assertEquals(PlaylistFiles.Format.XSPF, PlaylistFiles.detect("list.xspf", xml))
        assertEquals(PlaylistFiles.Format.XSPF, PlaylistFiles.detect("boh", xml))
        val back = PlaylistFiles.parse("list.xspf", xml)
        assertEquals(2, back.size)
        assertEquals("Get Lucky", back[0].title)
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), back[0].artists)
        assertEquals("Random Access Memories", back[0].album)
        assertEquals(369000L, back[0].durationMs)
        assertEquals("USQX91300108", back[0].isrc)
        assertEquals("Rock & Roll <live>", back[1].title)
    }

    @Test fun writesJspfThatReadsBackAsJson() {
        val tracks = listOf(Track("1", "Get Lucky", listOf("Daft Punk"), "RAM", 369000, isrc = "USQX91300108"))
        val text = PlaylistFiles.toJspf("Mix", tracks)
        assertTrue(text.contains("\"creator\": \"Daft Punk\""))
        assertTrue(text.contains("isrc:USQX91300108"))
        val back = PlaylistFiles.parse("mix.jspf", text)
        assertEquals(1, back.size)
        assertEquals("Get Lucky", back[0].title)
        assertEquals(listOf("Daft Punk"), back[0].artists)
        assertEquals("RAM", back[0].album)
        assertEquals(369000L, back[0].durationMs)
    }

    @Test fun writesPlainText() {
        val tracks = listOf(Track("1", "Get Lucky", listOf("Daft Punk")), Track("2", "Untitled"))
        val text = PlaylistFiles.toText(tracks)
        assertEquals("Daft Punk - Get Lucky\nUntitled\n", text)
        assertEquals(2, PlaylistFiles.parse("x.txt", text).size)
    }

    @Test fun readsShazamExportAndSetlistPage() {
        val shazam = "Shazam Library\nIndex,TagTime,Title,Artist,URL,TrackKey\n1,2024-01-02,Get Lucky,Daft Punk,https://x,123\n"
        val t = PlaylistFiles.parse("shazamlibrary.csv", shazam)
        assertEquals(1, t.size)
        assertEquals("Get Lucky", t[0].title)
        assertEquals(listOf("Daft Punk"), t[0].artists)

        val html = """<html><head><meta property="og:title" content="Daft Punk Setlist at Bercy, Paris, France"></head>
            <body><a class="songLabel" href="#">One More Time</a><a class="songLabel" href="#">Get Lucky</a>
            <a class="songLabel">Rock &amp; Roll (Led Zeppelin cover)</a></body></html>"""
        val s = SetlistImport.fromPage(html, "https://www.setlist.fm/setlist/daft-punk/2007/bercy-paris-france-1bd6c5a8.html")
        assertEquals("Daft Punk", s.artist)
        val tracks = Setlists.tracks(s)
        assertEquals(3, tracks.size)
        assertEquals(listOf("Daft Punk"), tracks[0].artists)
        assertEquals("Rock & Roll", tracks[2].title)
        assertEquals(listOf("Led Zeppelin"), tracks[2].artists)
        assertTrue(Setlists.playlistName(s).startsWith("Daft Punk"))
    }

    @Test fun readsOpmlSubscriptions() {
        val opml = """<?xml version="1.0"?><opml version="2.0"><body>
            <outline text="News"><outline type="rss" text="The Daily" xmlUrl="https://x/daily.rss"/></outline>
            <outline type="rss" title="Radiolab &amp; Co" xmlUrl="https://x/radiolab.rss"/>
            </body></opml>"""
        assertEquals(PlaylistFiles.Format.OPML, PlaylistFiles.detect("subs.opml", opml))
        val t = PlaylistFiles.parse("subs.opml", opml)
        assertEquals(listOf("The Daily", "Radiolab & Co"), t.map { it.title })
        assertTrue(t.all { it.kind == Track.KIND_PODCAST })
    }

    // ---------------------------------------------------------------- files as players write them

    private val names = listOf("Metallica – Battery", "Beyoncé – Café Noir", "Måneskin – Home")
    private fun named(ts: List<Track>) = ts.map { "${it.artists.joinToString()} – ${it.title}" }

    @Test fun m3uWithBomAndCrlf() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:8,Metallica - Battery\r\nmusic/a.mp3\r\n#EXTINF:8,Beyoncé - Café Noir\r\nmusic/b.flac\r\n#EXTINF:8,Måneskin - Home\r\nmusic/c.mp3\r\n"
        val t = PlaylistFiles.parse("list.m3u8", text)
        assertEquals(names, named(t))
        assertEquals(listOf(8_000L, 8_000L, 8_000L), t.map { it.durationMs })
        // Without #EXTM3U the BOM used to become a track of its own, or stick to the first title.
        assertEquals(listOf("Battery", "Home"), PlaylistFiles.parse("list.txt", "\uFEFFMetallica - Battery\nMåneskin - Home\n").map { it.title })
    }

    @Test fun m3uWithPathsOnly() {
        val text = listOf(
            "Metallica/Master of Puppets/01 Battery.mp3",                                  // relative, the library layout
            "/storage/emulated/0/Music/Beyoncé/Renaissance/01 - Café Noir.flac",           // absolute
            "C:\\Users\\me\\Music\\Måneskin\\Teatro d'ira\\03. Home.mp3",                // Windows
            "file:///home/me/Music/Daft%20Punk/Discovery/02%20Digital%20Love.flac",           // a file: URI, percent-encoded
            "/storage/emulated/0/Music/03 Something.mp3",                                    // no artist folder: no artist
            "/storage/emulated/0/Download/1-07 Queen - Bohemian Rhapsody.mp3",               // disc-track, artist in the name
            "Music/50 Cent - In Da Club.mp3",                                                 // a number that is the artist
            "Music/1979.mp3",                                                                 // a number that is the title
        ).joinToString("\n")
        assertEquals(
            listOf(
                "Metallica – Battery", "Beyoncé – Café Noir", "Måneskin – Home", "Daft Punk – Digital Love", " – Something",
                "Queen – Bohemian Rhapsody", "50 Cent – In Da Club", " – 1979",
            ),
            named(PlaylistFiles.parse("list.m3u", text)),
        )
        assertEquals("Master of Puppets", PlaylistFiles.parse("x.m3u", "Metallica/Master of Puppets/01 Battery.mp3")[0].album)
    }

    @Test fun pls() {
        val text = "[playlist]\r\nFile1=Metallica\\Master of Puppets\\01 Battery.mp3\r\nLength1=312\r\n" +
            "File2=http://radio.example/stream\r\nTitle2=Beyoncé - Café Noir\r\nLength2=-1\r\n" +
            "Title3=Måneskin - Home\r\nFile3=c.mp3\r\nNumberOfEntries=3\r\nVersion=2\r\n"
        assertEquals(PlaylistFiles.Format.PLS, PlaylistFiles.detect("x.pls", text))
        assertEquals(PlaylistFiles.Format.PLS, PlaylistFiles.detect("x", text))
        val t = PlaylistFiles.parse("radio.pls", text)
        assertEquals(names, named(t))
        assertEquals(listOf(312_000L, 0L, 0L), t.map { it.durationMs })
    }

    @Test fun decodesThePickedFile() {
        val s = "Beyoncé - Café Noir\n"
        assertEquals(s, PlaylistFiles.decode(s.toByteArray()))
        assertEquals(s, PlaylistFiles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()))
        assertEquals(s, PlaylistFiles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)))
        // A plain .m3u from a Windows player: not UTF-8.
        assertEquals(s, PlaylistFiles.decode(s.toByteArray(charset("windows-1252"))))
    }

    @Test fun m3uExportReadsBack() {
        val tracks = listOf(
            Track("1", "Énergie (feat. Pharrell)", listOf("Beyoncé"), "", 8_000),
            Track("2", "Orion (Remastered 2017)", listOf("Metallica"), "", 8_000),
        )
        val back = PlaylistFiles.parse("x.m3u8", PlaylistFiles.decode(PlaylistFiles.toM3u(tracks).toByteArray()))
        assertEquals(named(tracks), named(back))
        assertEquals(listOf(8_000L, 8_000L), back.map { it.durationMs })
    }

    // #49: names laid out "Artist - Album - Track".
    @Test fun threePartNamesReadAsArtistAlbumTitleWhenMostLinesHaveThem() {
        val m3u = """
            #EXTM3U
            #EXTINF:200,Radiohead - OK Computer - Karma Police
            a.mp3
            #EXTINF:210,Daft Punk - Discovery - 01 - One More Time
            b.mp3
            #EXTINF:180,Muse - Absolution - Hysteria
            c.mp3
        """.trimIndent()
        val t = PlaylistFiles.parse("mix.m3u", m3u)
        assertEquals(listOf("Karma Police", "One More Time", "Hysteria"), t.map { it.title })
        assertEquals(listOf("OK Computer", "Discovery", "Absolution"), t.map { it.album })
        assertEquals(listOf("Radiohead"), t[0].artists)
        assertEquals(200_000L, t[0].durationMs)
        assertEquals(PlaylistFiles.parse("x.txt", "Radiohead - Karma Police")[0].id.length, t[0].id.length)
    }

    @Test fun aFewThreePartNamesStayTitlesInAutomatic() {
        val text = "Daft Punk - One More Time - Radio Edit\nNirvana - Smells Like Teen Spirit\nMuse - Hysteria\nAdele - Hello\n"
        val t = PlaylistFiles.parse("list.txt", text)
        assertEquals("One More Time - Radio Edit", t[0].title)
        assertEquals("", t[0].album)
    }

    @Test fun theLayoutCanBeChosen() {
        val text = "Daft Punk - One More Time - Radio Edit\nNirvana - Smells Like Teen Spirit\n"
        val three = PlaylistFiles.parse("list.txt", text, PlaylistFiles.NameLayout.ARTIST_ALBUM_TITLE)
        assertEquals("Radio Edit", three[0].title)
        assertEquals("One More Time", three[0].album)
        assertEquals("Smells Like Teen Spirit", three[1].title)
        val two = PlaylistFiles.parse("list.txt", "A - B - C\nD - E - F\n", PlaylistFiles.NameLayout.ARTIST_TITLE)
        assertEquals(listOf("B - C", "E - F"), two.map { it.title })
        assertEquals(PlaylistFiles.NameLayout.AUTO, PlaylistFiles.NameLayout.of("nonsense"))
    }

    @Test fun columnsAreNeverSplit() {
        val csv = "title,artist\nOne More Time - Radio Edit,Daft Punk\nSong - Live,Band\n"
        val t = PlaylistFiles.parse("x.csv", csv, PlaylistFiles.NameLayout.ARTIST_ALBUM_TITLE)
        assertEquals(listOf("One More Time - Radio Edit", "Song - Live"), t.map { it.title })
    }

    @Test fun libraryPathsKeepTheirFolders() {
        val m3u = "Radiohead/OK Computer/06 Karma Police.mp3\nMuse/Absolution/Hysteria.mp3\n"
        val t = PlaylistFiles.parse("lib.m3u", m3u)
        assertEquals(listOf("Karma Police", "Hysteria"), t.map { it.title })
        assertEquals(listOf("OK Computer", "Absolution"), t.map { it.album })
    }

    // #49, the reporter's own lines: library paths with discographies, disc folders, years and
    // "Artist - Album - 02 Title" file names.
    @Test fun libraryPathsAsTheyComeFromAPhone() {
        val m3u = listOf(
            "F9FF-3A77/Music/Childish Gambino/2016.12 - Awaken, My Love!/06 Redbone.m4a",
            "#EXT-X-RATING:0",
            "F9FF-3A77/Music/Diamond Jack Soundtrack/Connor Spiotto - The Villain I Appear to Be.mp3",
            "#EXT-X-RATING:0",
            "F9FF-3A77/Music/Sault/SAULT - 'NINE'/SAULT - 'NINE' - 02 London Gangs.mp3",
            "#EXT-X-RATING:0",
            "F9FF-3A77/Music/Sex Pistols/1977 - Never Mind The Bollocks and Spunk (Released 1996)/(Disc 1) Never Mind The Bollocks (Original)/10. Pretty Vacant.mp3",
            "#EXT-X-RATING:0",
            "F9FF-3A77/Music/Deftones/Deftones - Discography/Demo albums/1993 - (Like) Linus/01 - Like (Linus) -A.mp3",
        ).joinToString("\n")
        val t = PlaylistFiles.parse("mine.m3u", m3u)
        assertEquals(listOf("Redbone", "The Villain I Appear to Be", "London Gangs", "Pretty Vacant", "Like (Linus) -A"), t.map { it.title })
        assertEquals(listOf("Childish Gambino", "Connor Spiotto", "SAULT", "Sex Pistols", "Deftones"), t.map { it.artistLine })
        assertEquals(listOf("Awaken, My Love!", "", "'NINE'", "Never Mind The Bollocks and Spunk (Released 1996)", "(Like) Linus"), t.map { it.album })
    }

    @Test fun pathsOutsideAMusicFolderKeepTheOldReading() {
        assertEquals("Artist", PlaylistFiles.fromPath("Artist/Album/01 Title.mp3").artistLine)
        assertEquals("Album", PlaylistFiles.fromPath("Artist/Album/01 Title.mp3").album)
        assertEquals(emptyList<String>(), PlaylistFiles.fromPath("/storage/emulated/0/Download/Title.mp3").artists)
        assertEquals(emptyList<String>(), PlaylistFiles.fromPath("Music/Some Album/Title.mp3").artists)
        assertEquals("1989", PlaylistFiles.fromPath("Music/Taylor Swift/1989/Style.mp3").album)
    }
}
