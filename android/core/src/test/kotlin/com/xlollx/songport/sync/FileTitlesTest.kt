package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.model.MatchPolicy
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * A personal server whose files carry no tags names its songs after the files: "06 In the End",
 * "13 - In the End", iTunes' "04 - Ide Were Were 1" (2.2.7). The number goes only where the artist,
 * the album and the length agree, so "7 Rings", "22", "1999" and "99 Problems" stay what they are.
 */
class FileTitlesTest {
    private val server = MatchPolicy(fileNames = true)
    private fun song(title: String, artist: String = "Linkin Park", album: String = "Hybrid Theory", ms: Long = 216_000) =
        Track(id = title, title = title, artists = listOfNotNull(artist.takeIf { it.isNotEmpty() }), album = album, durationMs = ms)

    @Test fun theTrackNumberGoesWhereEverythingElseAgrees() {
        val src = song("In the End")
        for (t in listOf("06 In the End", "13 - In the End", "13. In the End", "1-06 In the End", "06_In the End", "6) In the End")) {
            assertEquals(t, 1.0, Matcher.score(src, song(t, ms = 216_500), server), 0.001)
        }
        // A title a dash would otherwise cut away: "13 - Live Forever" read as "13", a subtitle "Live Forever".
        val oasis = song("Live Forever", "Oasis", "Definitely Maybe", 276_000)
        val numbered = song("13 - Live Forever", "Oasis", "Definitely Maybe", 277_000)
        assertTrue(Matcher.score(oasis, numbered) < Matcher.DEFAULT_THRESHOLD)
        assertTrue(Matcher.score(oasis, numbered, server) >= Matcher.REVIEW_THRESHOLD)
        val spanish = song("De Música Ligera", "Soda Stereo", "Canción Animal", 210_000)
        assertTrue(Matcher.score(spanish, song("05 - De Música Ligera", "Soda Stereo", "Canción Animal", 210_000), server) >= Matcher.REVIEW_THRESHOLD)
    }

    @Test fun itunesSecondCopyLosesItsOne() {
        val src = song("Ide Were Were", "Ayo", "Gravity at Last", 250_000)
        assertEquals(1.0, Matcher.score(src, song("04 - Ide Were Were 1", "Ayo", "Gravity at Last", 250_000), server), 0.001)
        assertEquals(1.0, Matcher.score(src, song("Ide Were Were 1", "Ayo", "Gravity at Last", 250_000), server), 0.001)
        // A title that ends in 1 itself keeps it.
        assertEquals(listOf("Symphony No. 1"), Matcher.fileTitles("05 Symphony No. 1", "Symphony No. 1"))
    }

    @Test fun onlyWhereArtistAlbumAndLengthAgree() {
        val src = song("Live Forever", "Oasis", "Definitely Maybe", 276_000)
        fun scored(c: Track) = Matcher.score(src, c, server)
        // Another artist, another album, another length: the number stays and so does the doubt.
        assertTrue(scored(song("13 - Live Forever", "Liam Gallagher", "Definitely Maybe", 276_000)) < Matcher.REVIEW_THRESHOLD)
        assertTrue(scored(song("13 - Live Forever", "Oasis", "Familiar to Millions", 276_000)) < Matcher.REVIEW_THRESHOLD)
        assertTrue(scored(song("13 - Live Forever", "Oasis", "Definitely Maybe", 296_000)) < Matcher.REVIEW_THRESHOLD)
        // Nothing known but the title: never taken from a file name.
        assertTrue(Matcher.score(Track("x", "Live Forever"), Track("y", "13 - Live Forever"), server) < Matcher.DEFAULT_THRESHOLD)
        // Not on a server's songs: as before.
        assertTrue(Matcher.score(src, song("13 - Live Forever", "Oasis", "Definitely Maybe", 276_000)) < Matcher.DEFAULT_THRESHOLD)
    }

    @Test fun titlesThatStartWithANumberStayWhole() {
        assertEquals(emptyList<String>(), Matcher.fileTitles("22", "22"))
        assertEquals(emptyList<String>(), Matcher.fileTitles("1999", "1999"))
        for ((title, artist) in listOf("7 Rings" to "Ariana Grande", "22" to "Taylor Swift", "1999" to "Prince", "99 Problems" to "Jay-Z")) {
            val t = song(title, artist, "", 200_000)
            assertEquals(title, 1.0, Matcher.score(t, t.copy(id = "lib"), server), 0.001)
            // Numbered as a file: found the same.
            assertEquals(title, 1.0, Matcher.score(t, t.copy(id = "lib", title = "03 $title"), server), 0.001)
        }
        // "99 Problems" is not "Problems" by another artist, nor "1999" another song of the same length.
        assertTrue(Matcher.score(song("Problems", "Petey Pablo", "", 200_000), song("99 Problems", "Jay-Z", "", 200_000), server) < Matcher.DEFAULT_THRESHOLD)
        assertTrue(Matcher.score(song("Little Red Corvette", "Prince", "", 200_000), song("1999", "Prince", "", 200_000), server) < Matcher.DEFAULT_THRESHOLD)
    }

    /** A sync to a server whose untagged files are named "NN - Title": found, and nothing searched for long. */
    @Test fun aSyncToAServerFindsTheNumberedFiles() = runBlocking {
        val env = ContractEnv()
        val src = MemoryService("msrc")
        val dst = object : MemoryService("jellyfin") {
            override suspend fun search(ctx: Env, track: Track): List<Track> = catalogue.filter { it.title.contains(track.title, ignoreCase = true) }
        }
        Providers.testDoubles = listOf(src, dst)
        try {
            src.lists["p"] = mutableListOf(song("In the End").copy(id = "s1"), song("Live Forever", "Oasis", "Definitely Maybe", 276_000).copy(id = "s2"))
            dst.catalogue += listOf(song("06 In the End").copy(id = "j1"), song("13 - Live Forever", "Oasis", "Definitely Maybe", 277_000).copy(id = "j2"))
            dst.lists["t"] = mutableListOf()
            val job = SyncJob(id = "files", name = "Metal & More", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("jellyfin", "t", "t"))
                .also { env.store.upsertJob(it) }
            val plan = SyncEngine(env).plan(job, createTarget = false, interactive = true)
            assertEquals(listOf("j1", "j2"), plan.toAdd.map { it.id })
            assertEquals(emptyList<Track>(), plan.unmatched)
            assertEquals(emptyList<Any>(), plan.uncertain)
        } finally {
            Providers.testDoubles = emptyList()
        }
    }

    // ------------------------------------------------------------------ the misses remembered on a server

    @Test fun missesOnAServerAreSearchedAgainUnderANewServerRevision() {
        val store = FileStore(Files.createTempDirectory("misses").toFile())
        val now = System.currentTimeMillis()
        val onServer = FileStore.cacheKey("spotify", "a", "jellyfin@2")
        val elsewhere = FileStore.cacheKey("spotify", "a", "tidal")
        // Misses remembered by 2.2.6: no server revision yet.
        store.update { it.copy(missRevision = Matcher.REVISION, missCache = mapOf(onServer to now, elsewhere to now)) }
        assertFalse(store.cachedMiss("spotify", "a", "jellyfin@2"))
        assertTrue("other services keep theirs", store.cachedMiss("spotify", "a", "tidal"))
        // The next misses: the server's old ones are gone, the new one is remembered.
        store.putMisses(listOf(FileStore.cacheKey("spotify", "b", "plex"), FileStore.cacheKey("spotify", "c", "subsonic")))
        assertEquals(Matcher.SERVER_REVISION, store.data.serverMissRevision)
        assertFalse(onServer in store.data.missCache)
        assertTrue(elsewhere in store.data.missCache)
        assertTrue(store.cachedMiss("spotify", "c", "subsonic"))
        // Shown as not found on the Saved matches page by the same rule.
        assertEquals(setOf("a>tidal", "b>plex", "c>subsonic"), SavedMatches.entries(store.data).map { it.sourceId + ">" + it.target }.toSet())
    }

    @Test fun aMissOlderThanTheServersLastScanIsSearchedAgain() {
        val store = FileStore(Files.createTempDirectory("misses").toFile())
        val now = System.currentTimeMillis()
        store.putMisses(listOf(FileStore.cacheKey("spotify", "a", "subsonic")))
        assertTrue(store.cachedMiss("spotify", "a", "subsonic", scannedAt = now - 60_000))
        assertFalse(store.cachedMiss("spotify", "a", "subsonic", scannedAt = now + 60_000))
    }

    /** #103: Navidrome scanned after the miss: the song is searched and found; a server that cannot tell keeps its misses. */
    @Test fun aSyncSearchesAgainWhatWasMissedBeforeTheLastScan() = runBlocking {
        val env = ContractEnv()
        val src = MemoryService("msrc")
        var scan: Long? = null
        var scanFails = false
        val dst = object : MemoryService("subsonic") {
            override suspend fun lastScan(ctx: Env): Long? { if (scanFails) throw com.xlollx.songport.model.ProviderException("HTTP 500"); return scan }
        }
        Providers.testDoubles = listOf(src, dst)
        try {
            src.lists["p"] = mutableListOf(song("Numb").copy(id = "s1"))
            dst.lists["t"] = mutableListOf()
            val job = SyncJob(id = "scan", name = "test", source = PlaylistRef("msrc", "p", "p"), target = PlaylistRef("subsonic", "t", "t"))
                .also { env.store.upsertJob(it) }
            val engine = SyncEngine(env)
            assertEquals(1, engine.run(job).unmatchedTracks.size)
            // The file arrives; the server cannot tell when it scanned, or fails to: still the miss.
            dst.catalogue += song("Numb").copy(id = "n1")
            assertEquals(1, engine.run(job).unmatchedTracks.size)
            scanFails = true
            assertEquals(1, engine.run(job).unmatchedTracks.size)
            // It scanned before the miss: still the miss.
            scanFails = false
            scan = System.currentTimeMillis() - 3_600_000
            assertEquals(1, engine.run(job).unmatchedTracks.size)
            // It scanned after: searched, found, added.
            scan = System.currentTimeMillis() + 1_000
            val r = engine.run(job)
            assertEquals(0, r.unmatchedTracks.size)
            assertEquals(listOf("n1"), dst.lists["t"]!!.map { it.id })
        } finally {
            Providers.testDoubles = emptyList()
        }
    }
}
