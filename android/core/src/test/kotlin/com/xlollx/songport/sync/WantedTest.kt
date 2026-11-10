package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The "Missing on my server" list: what it is made of, how it folds doubles, what it remembers, and its formats. */
class WantedTest {
    private val store = FileStore(Files.createTempDirectory("wanted").toFile())
    private val day = 86_400_000L
    private val t0 = 1_780_000_000_000L

    private fun job(id: String, src: String, dst: String, ignored: List<String> = emptyList()) =
        SyncJob(id, "Sync $id", PlaylistRef(src, "p-$id", "From $id"), PlaylistRef(dst, "t-$id", "To $id"), ignoredSourceIds = ignored)

    private fun run(job: String, at: Long, vararg missing: Track, partial: Boolean = false, sourceCount: Int = 10, error: String? = null) =
        SyncReport("r-$job-$at", job, "Sync $job", at, 1000, sourceCount = sourceCount, unmatched = missing.map { it.toString() },
            unmatchedTracks = missing.toList(), partial = partial, error = error)

    private fun track(id: String, title: String, artist: String, isrc: String? = null, album: String = "", ms: Long = 0) =
        Track(id, title, listOf(artist), album = album, durationMs = ms, isrc = isrc)

    private fun put(jobs: List<SyncJob>, reports: List<SyncReport>) = store.update { d -> d.copy(jobs = jobs, reports = reports.sortedByDescending { it.startedEpoch }) }

    private val hey = track("sp1", "Hey Jude", "The Beatles", isrc = "GBAYE0601690", album = "Hey Jude", ms = 431_000)
    private val heyDeezer = track("dz1", "Hey Jude - Remastered 2015", "The Beatles", isrc = "GBAYE0601690")
    private val heyNoIsrc = track("yt1", "Hey Jude (Remastered)", "Beatles")
    private val heyLive = track("sp2", "Hey Jude (Live)", "The Beatles")
    private val other = track("sp3", "Paranoid", "Black Sabbath", album = "Paranoid")

    @Test fun oneEntryPerTrackAcrossSyncsAndServices() {
        put(
            listOf(job("a", "spotify", "jellyfin"), job("b", "deezer", "subsonic@2"), job("c", "ytm", "plex"), job("d", "spotify", "tidal")),
            listOf(
                run("a", t0, hey, heyLive, other), run("b", t0 + day, heyDeezer), run("c", t0, heyNoIsrc),
                // A streaming target is not the user's server: not on the list.
                run("d", t0, track("x", "Elsewhere", "Nobody")),
            ),
        )
        val list = Wanted.list(store.data)
        assertEquals(listOf("The Beatles – Hey Jude", "The Beatles – Hey Jude (Live)", "Black Sabbath – Paranoid"), list.map { it.toString() })
        val jude = list[0]
        // The copy with ISRC, album and length names it; the three syncs that miss it are all named.
        assertEquals("GBAYE0601690", jude.isrc)
        assertEquals("Hey Jude", jude.album)
        assertEquals(431_000L, jude.durationMs)
        assertEquals("isrc:GBAYE0601690", jude.id)
        assertEquals(setOf("a", "b", "c"), jude.syncs.map { it.syncId }.toSet())
        assertEquals(t0, jude.firstSeen)
        // A live take is another recording to get.
        assertEquals(listOf("a"), list[1].syncs.map { it.syncId })
    }

    @Test fun readsTheLastCompleteRunAndLeavesOutIgnoredTracks() {
        put(
            listOf(job("a", "spotify", "subsonic", ignored = listOf("sp3"))),
            listOf(
                run("a", t0, hey, other),
                // A run skipped as unchanged read nothing; one still going, or failed, is not the last word either.
                run("a", t0 + day, sourceCount = 0),
                run("a", t0 + 2 * day, partial = true),
                run("a", t0 + 3 * day, error = "offline"),
            ),
        )
        assertEquals(listOf("The Beatles – Hey Jude"), Wanted.list(store.data).map { it.toString() })
    }

    @Test fun rememberTheFirstSightingAndTellWhatIsNew() {
        put(listOf(job("a", "spotify", "jellyfin")), listOf(run("a", t0, hey)))
        // The first time ever, what is already missing is old news: dated by its run, nothing announced.
        assertTrue(Wanted.refresh(store, now = t0 + 5 * day).isEmpty())
        assertEquals(t0, Wanted.list(store.data).single().firstSeen)
        put(store.data.jobs, store.data.reports + run("a", t0 + 6 * day, hey, other))
        val added = Wanted.refresh(store, now = t0 + 6 * day)
        assertEquals(listOf("Black Sabbath – Paranoid"), added.map { it.toString() })
        assertEquals(t0 + 6 * day, Wanted.list(store.data).first { it.title == "Paranoid" }.firstSeen)
        // Nothing new: nothing to tell.
        assertTrue(Wanted.refresh(store, now = t0 + 7 * day).isEmpty())
        // Found on the server since: forgotten, and news again when it is missing once more.
        put(store.data.jobs, listOf(run("a", t0 + 8 * day, hey)))
        Wanted.refresh(store, now = t0 + 8 * day)
        assertEquals(setOf("isrc:GBAYE0601690"), store.data.wanted.keys)
        put(store.data.jobs, listOf(run("a", t0 + 9 * day, hey, other)))
        assertEquals(1, Wanted.refresh(store, now = t0 + 9 * day).size)
    }

    @Test fun tickedEntriesAreHiddenUntilAskedFor() {
        put(listOf(job("a", "spotify", "jellyfin")), listOf(run("a", t0, hey, other)))
        val id = Wanted.list(store.data).first { it.title == "Paranoid" }.id
        assertEquals(1, Wanted.mark(store, listOf(id), got = true))
        assertEquals(listOf("Hey Jude"), Wanted.list(store.data).map { it.title })
        val all = Wanted.list(store.data, filter = Wanted.Filter(withGot = true))
        assertTrue(all.first { it.id == id }.got)
        // A new run still missing it keeps the tick.
        put(store.data.jobs, listOf(run("a", t0 + day, hey, other)) + store.data.reports)
        Wanted.refresh(store, now = t0 + day)
        assertEquals(1, Wanted.list(store.data).size)
        assertEquals(1, Wanted.mark(store, listOf(id), got = false))
        assertEquals(2, Wanted.list(store.data).size)
    }

    @Test fun filtersBySyncServiceAndDate() {
        put(listOf(job("a", "spotify", "jellyfin"), job("b", "deezer", "subsonic@2")), listOf(run("a", t0, hey), run("b", t0 + 3 * day, other)))
        fun titles(f: Wanted.Filter) = Wanted.list(store.data, filter = f).map { it.title }
        assertEquals(listOf("Hey Jude"), titles(Wanted.Filter(sync = "a")))
        assertEquals(listOf("Paranoid"), titles(Wanted.Filter(sync = "sync B")))
        assertEquals(listOf("Paranoid"), titles(Wanted.Filter(service = "deezer")))
        assertEquals(listOf("Paranoid"), titles(Wanted.Filter(service = "subsonic")))
        assertEquals(listOf("Paranoid"), titles(Wanted.Filter(service = "subsonic@2")))
        assertEquals(emptyList<String>(), titles(Wanted.Filter(service = "subsonic@3")))
        assertEquals(listOf("Paranoid"), titles(Wanted.Filter(since = t0 + day)))
        assertEquals(t0 - 2 * day, Wanted.parseSince("2d", now = t0))
        assertEquals(t0 - 5 * 3_600_000L, Wanted.parseSince("5h", now = t0))
        assertEquals(1_793_491_200_000L, Wanted.parseSince("2026-11-01"))
        assertEquals(42L, Wanted.parseSince("42"))
        assertNull(Wanted.parseSince("yesterday"))
    }

    @Test fun byAlbum() {
        put(
            listOf(job("a", "spotify", "jellyfin")),
            listOf(run("a", t0, hey, other, track("s4", "Iron Man", "Black Sabbath", album = "Paranoid"), track("s5", "Single", "Black Sabbath"))),
        )
        val albums = Wanted.albums(Wanted.list(store.data))
        assertEquals(listOf("The Beatles/Hey Jude" to 1, "Black Sabbath/Paranoid" to 2, "Black Sabbath/" to 1), albums.map { "${it.artist}/${it.album}" to it.wants.size })
    }

    @Test fun formats() {
        put(listOf(job("a", "spotify", "jellyfin")), listOf(run("a", t0, hey, track("s6", "Hello, \"World\"", "Band, The"))))
        val rec = "b2181aae-5cba-496c-bb0c-b4cc0109ebf8"
        val beatles = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"
        val list = Wanted.list(store.data, known = { if (it == "GBAYE0601690") MusicBrainz.Known(listOf(rec), listOf(beatles)) else null })

        val csv = Wanted.Format.CSV.encode(list).lines()
        assertEquals("title,artist,album,duration_ms,isrc,musicbrainz_id,syncs,first_seen", csv[0])
        assertEquals("\"Hello, \"\"World\"\"\",\"Band, The\",,,,,Sync a,2026-05-28", csv[1])
        assertEquals("Hey Jude,The Beatles,Hey Jude,431000,GBAYE0601690,$rec,Sync a,2026-05-28", csv[2])
        // Read back as a playlist file, it is the same tracks.
        assertEquals(listOf("Hello, \"World\"", "Hey Jude"), CsvCodec.parse(Wanted.Format.CSV.encode(list)).map { it.title })

        assertEquals("Band, The - Hello, \"World\"\nThe Beatles - Hey Jude\n", Wanted.Format.TEXT.encode(list))
        assertTrue(Wanted.Format.M3U.encode(list).contains("#EXTINF:431,The Beatles - Hey Jude"))

        val json = Json.parseToJsonElement(Wanted.Format.JSON.encode(list)).jsonArray
        val jude = json[1].jsonObject
        assertEquals(rec, jude["musicBrainzId"]!!.jsonPrimitive.content)
        assertEquals("GBAYE0601690", jude["isrc"]!!.jsonPrimitive.content)
        assertEquals("a", jude["syncs"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("null", json[0].jsonObject["musicBrainzId"].toString())

        // Lidarr's Custom List: an array of objects whose MusicBrainzId is an artist's; only artists known.
        val lidarr = Json.parseToJsonElement(Wanted.Format.LIDARR.encode(list)) as JsonArray
        assertEquals(1, lidarr.size)
        assertEquals(beatles, (lidarr[0] as JsonObject)["MusicBrainzId"]!!.jsonPrimitive.content)
        assertEquals("The Beatles", (lidarr[0] as JsonObject)["ArtistName"]!!.jsonPrimitive.content)

        assertEquals(Wanted.Format.TEXT, Wanted.Format.of("txt"))
        assertEquals(Wanted.Format.JSON, Wanted.Format.of(null))
        assertEquals(Wanted.Format.LIDARR, Wanted.Format.of("Lidarr"))
        assertNull(Wanted.Format.of("xlsx"))
        assertFalse(Wanted.isServer("spotify"))
        assertTrue(Wanted.isServer("jellyfin@2"))
    }
}
