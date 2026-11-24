package com.xlollx.songport.sync

import com.xlollx.songport.net.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The games' pure parts: titles, Fortnite Festival's page, MusicBrainz soundtracks, song folders, the truck games' radio list. */
class GamesTest {
    @Test fun gameCutsLeaveTheTitle() {
        assertEquals("Blue Bird", GameTitles.clean("Blue Bird (TV Size)"))
        assertEquals("Unravel", GameTitles.clean("Unravel (TV ver.)"))
        assertEquals("Song", GameTitles.clean("Song (Cut Ver.)"))
        assertEquals("Song", GameTitles.clean("Song [Short Ver.]"))
        assertEquals("Song", GameTitles.clean("Song (Nightcore Mix)"))
        assertEquals("Song", GameTitles.clean("Song (Sped Up Ver.)"))
        assertEquals("Song", GameTitles.clean("Song (Extended Cut Ver.)"))
        // Everything else stays: the matcher reads remixes and guests itself.
        assertEquals("Song (Camellia Remix)", GameTitles.clean("Song (Camellia Remix)"))
        assertEquals("Song (feat. X)", GameTitles.clean("Song (feat. X)"))
        assertEquals("(TV Size)", GameTitles.clean("(TV Size)"))
    }

    @Test fun gameCreditsSplitOnFeat() {
        assertEquals(listOf("TAK", "Hatsune Miku and Kasane Teto"), GameTitles.artists("TAK ft. Hatsune Miku and Kasane Teto"))
        assertEquals(listOf("Simon & Garfunkel"), GameTitles.artists("Simon & Garfunkel"))
        assertEquals(listOf("A", "B"), GameTitles.artists("A vs. B"))
        assertEquals(emptyList<String>(), GameTitles.artists(" "))
    }

    private val spark = """{"_title":"spark-tracks","lastModified":"2026-11-24T14:50:02.731Z",
      "wellerman":{"_title":"wellerman","track":{"tt":"Wellerman","an":"Sea Shanty","ry":2021,"dn":149,"sn":"wellerman"},"_activeDate":"2026-11-22T13:30:00.000Z"},
      "badguy":{"_title":"badguy","track":{"tt":"bad guy","an":"Billie Eilish","ry":2019,"dn":194,"sn":"badguy","isrc":"usum71900764"},"_activeDate":"2023-12-09T14:00:00.000Z"},
      "broken":{"_title":"broken","track":{"an":"No Title"}},
      "_locale":"en-US"}"""

    @Test fun fortniteFestivalPage() {
        val list = FortniteFestival.parse(spark)
        assertEquals(listOf("wellerman", "badguy"), list.map { it.id })
        val t = list[1].track()
        assertEquals("fn:badguy", t.id)
        assertEquals("bad guy", t.title)
        assertEquals(listOf("Billie Eilish"), t.artists)
        assertEquals("USUM71900764", t.isrc)
        assertEquals(194_000L, t.durationMs)
        assertEquals(2019, t.year)
        val now = java.time.Instant.parse("2026-11-24T00:00:00Z").toEpochMilli()
        assertEquals(listOf("wellerman"), FortniteFestival.recent(list, now).map { it.id })
        try { FortniteFestival.parse("<html>"); fail() } catch (_: FortniteFestival.Changed) {}
    }

    @Test fun soundtrackSearchReleaseAndTracks() {
        val found = Soundtracks.parseSearch(parseJson("""{"release-groups":[{"id":"58392f1c-ee4d-3feb-866e-b5c0cd2d0270","title":"Grand Theft Auto",
            "primary-type":"Album","secondary-types":["Soundtrack"],"first-release-date":"1997-09-29","artist-credit":[{"name":"Various Artists","joinphrase":""}]}]}"""))
        assertEquals("release-group/58392f1c-ee4d-3feb-866e-b5c0cd2d0270", found.single().id)
        assertEquals(1997, found.single().year)
        assertEquals(listOf("Soundtrack"), found.single().types)
        val pick = Soundtracks.pick(parseJson("""{"releases":[
            {"id":"a","status":"Bootleg","date":"1990","media":[{"track-count":30}]},
            {"id":"b","status":"Official","date":"2001","media":[{"track-count":11}]},
            {"id":"c","status":"Official","date":"1997-09-29","media":[{"track-count":11}]},
            {"id":"d","status":"Official","date":"1997","media":[{"track-count":8}]}]}"""))
        assertEquals("c", pick)
        val tracks = Soundtracks.tracksOf(parseJson("""{"title":"GTA","date":"1997-09-29","media":[{"tracks":[
            {"title":"Track One","length":200000,"artist-credit":[{"name":"Craig Conner"}],"recording":{"id":"AAAAAAAA-0000-0000-0000-000000000001","isrcs":["GBAAA9700001"]}},
            {"title":"Track Two","recording":{"id":"aaaaaaaa-0000-0000-0000-000000000002","length":100000,"artist-credit":[{"name":"Various Artists"}]}},
            {"title":"Track One again","recording":{"id":"aaaaaaaa-0000-0000-0000-000000000001"}}]}]}"""))
        assertEquals(2, tracks.size)
        assertEquals(listOf("Craig Conner"), tracks[0].artists)
        assertEquals("GBAAA9700001", tracks[0].isrc)
        assertEquals(listOf("aaaaaaaa-0000-0000-0000-000000000001"), tracks[0].mbids)
        assertEquals(emptyList<String>(), tracks[1].artists)
        assertEquals(100_000L, tracks[1].durationMs)
        assertEquals("GTA", tracks[1].album)
    }

    @Test fun musicBrainzLinks() {
        assertEquals(PlaylistLinks.Ref("musicbrainz", "release-group/58392f1c-ee4d-3feb-866e-b5c0cd2d0270"),
            PlaylistLinks.parse("https://musicbrainz.org/release-group/58392f1c-ee4d-3feb-866e-b5c0cd2d0270"))
        assertEquals(PlaylistLinks.Ref("musicbrainz", "release/42506b87-54b0-4c27-896e-18c570b7e66d"),
            PlaylistLinks.parse("see https://beta.musicbrainz.org/release/42506b87-54b0-4c27-896e-18c570b7e66d/discids"))
        assertNull(PlaylistLinks.parse("https://musicbrainz.org/artist/42506b87-54b0-4c27-896e-18c570b7e66d"))
        assertEquals("https://musicbrainz.org/release/x", PlaylistLinks.webUrl("musicbrainz", "release/x"))
    }

    private fun dir(): File = Files.createTempDirectory("games").toFile().apply { deleteOnExit() }

    @Test fun songFolders() {
        val root = dir()
        File(root, "Loose Song").apply { mkdirs() }.resolve("song.ini").writeText("[Song]\nname = Through the Fire (TV Size)\nartist = DragonForce\nalbum = Inhuman Rampage\nyear = 2006\nsong_length = 441000\n")
        File(root, "ITG Pack/Song A").apply { mkdirs() }.resolve("song.ssc").writeText("#VERSION:0.83;\n#TITLE:Song A;\n#SUBTITLE:(Extended Mix);\n#ARTIST:Artist A;\n#NOTEDATA:;\n#TITLE:Not this;")
        File(root, "ITG Pack/Song B").apply { mkdirs() }.resolve("Song B.sm").writeText("#TITLE:;\n#TITLETRANSLIT:Song B;\n#ARTIST:Artist B;\n#NOTES:\n")
        File(root, "BS/1a2b (Name - Mapper)").apply { mkdirs() }.resolve("Info.dat").writeText("""{"_version":"2.0.0","_songName":"Name","_songSubName":"","_songAuthorName":"Artist C"}""")
        File(root, "BS/v4").apply { mkdirs() }.resolve("Info.dat").writeText("""{"version":"4.0.0","song":{"title":"Four","subTitle":"","author":"Artist D"}}""")
        File(root, "Empty/Nothing").mkdirs()
        File(root, ".hidden/Song").apply { mkdirs() }.resolve("song.ini").writeText("[song]\nname=Hidden\n")
        val songs = GameSongFiles.scan(root)
        assertEquals(listOf("Name", "Four", "Song A (Extended Mix)", "Song B", "Through the Fire (TV Size)"), songs.map { it.title })
        assertEquals(listOf("BS", "BS", "ITG Pack", "ITG Pack", ""), songs.map { it.pack })
        val t = songs.last().track()
        assertEquals("Through the Fire", t.title)
        assertEquals(listOf("DragonForce"), t.artists)
        assertEquals(441_000L, t.durationMs)
        assertEquals(2006, t.year)
        assertEquals("gf:Loose Song", t.id)
    }

    private val sii = "SiiNunit\n{\nlive_stream_def : _nameless.1d2.5f30 {\n stream_data: 2\n stream_data[0]: \"http://one.example/stream|Radio One|Rock|EN|128|1\"\n stream_data[1]: \"https://two.example/live|Two|Pop|DE|64|0\"\n}\n\n}\n"

    @Test fun truckRadioReads() {
        val list = TruckRadio.parse(sii)
        assertEquals(listOf("Radio One", "Two"), list.map { it.name })
        assertTrue(list[0].favourite)
        assertEquals(64, list[1].bitrate)
        try { TruckRadio.parse("ScsC binary"); fail() } catch (_: TruckRadio.NotText) {}
    }

    @Test fun truckRadioAddsKeepingWhatIsThere() {
        val (out, added) = TruckRadio.withStations(sii, listOf(
            TruckRadio.Station("HTTP://ONE.example/stream", "Dup"),
            TruckRadio.Station("https://three.example/a", "Three | FM", "Jazz", "IT", 192),
        ))
        assertEquals(1, added)
        assertTrue(out, out.contains(" stream_data: 3\n stream_data[0]: \"http://one.example/stream|Radio One|Rock|EN|128|1\"\n stream_data[1]: \"https://two.example/live|Two|Pop|DE|64|0\"\n stream_data[2]: \"https://three.example/a|Three   FM|Jazz|IT|192|0\"\n}"))
        assertEquals(3, TruckRadio.parse(out).size)
        assertEquals(sii to 0, TruckRadio.withStations(sii, listOf(TruckRadio.Station("http://one.example/stream", "x"))))
        // An empty list: the stations go inside the unit.
        val empty = "SiiNunit\r\n{\r\nlive_stream_def : _nameless.1 {\r\n stream_data: 0\r\n}\r\n}\r\n"
        val (o2, n2) = TruckRadio.withStations(empty, listOf(TruckRadio.Station("http://a.example/", "A")))
        assertEquals(1, n2)
        assertEquals("SiiNunit\r\n{\r\nlive_stream_def : _nameless.1 {\r\n stream_data: 1\r\n stream_data[0]: \"http://a.example/|A|||128|0\"\r\n}\r\n}\r\n", o2)
    }

    @Test fun truckRadioFileKeepsACopy() {
        val d = File(dir(), "Euro Truck Simulator 2").apply { mkdirs() }
        val f = File(d, "live_streams.sii").apply { writeText(sii) }
        assertEquals(1, TruckRadio.addTo(f, listOf(TruckRadio.Station("http://new.example/", "New")), now = 5))
        assertEquals(sii, File(d, "live_streams.sii.bak").readText())
        assertEquals(1, TruckRadio.addTo(f, listOf(TruckRadio.Station("http://newer.example/", "Newer")), now = 6))
        assertTrue(File(d, "live_streams.sii.6.bak").isFile)
        assertEquals(4, TruckRadio.parse(f.readText()).size)
        assertEquals("Euro Truck Simulator 2", TruckRadio.gameOf(f))
        val home = dir()
        val docs = File(home, "Documents/American Truck Simulator").apply { mkdirs() }
        val ats = File(docs, "live_streams.sii").apply { writeText(sii) }
        assertEquals(listOf(ats.canonicalPath), TruckRadio.findFiles(home, "Windows 11").map { it.canonicalPath })
        assertEquals(emptyList<File>(), TruckRadio.findFiles(home, "Linux"))
    }
}
