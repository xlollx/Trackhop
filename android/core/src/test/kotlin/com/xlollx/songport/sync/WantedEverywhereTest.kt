package com.xlollx.songport.sync

import com.xlollx.songport.it.TestEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #73: the songs not found of every service, exported or saved as a File playlist; the servers' list stays as it was. */
class WantedEverywhereTest {
    private val env = TestEnv()
    private val store = env.store
    private val t0 = 1_780_000_000_000L

    private fun job(id: String, src: String, dst: String, ignored: List<String> = emptyList()) =
        SyncJob(id, "Sync $id", PlaylistRef(src, "p-$id", "From $id"), PlaylistRef(dst, "t-$id", "To $id"), ignoredSourceIds = ignored)

    private fun run(job: String, at: Long, vararg missing: Track) =
        SyncReport("r-$job-$at", job, "Sync $job", at, 1000, sourceCount = 10, unmatched = missing.map { it.toString() }, unmatchedTracks = missing.toList())

    private fun track(id: String, title: String, artist: String, isrc: String? = null) = Track(id, title, listOf(artist), isrc = isrc)

    private val hey = track("sp1", "Hey Jude", "The Beatles", isrc = "GBAYE0601690")
    private val heyYt = track("yt1", "Hey Jude", "The Beatles")
    private val paranoid = track("sp3", "Paranoid", "Black Sabbath")
    private val rutles = track("sp4", "Hold My Hand", "The Rutles")
    private val ignoredOne = track("sp5", "Ignored", "Someone")

    private fun setUp() = store.update { d ->
        d.copy(
            jobs = listOf(
                job("a", "spotify", "tidal", ignored = listOf("sp5")), job("b", "ytm", "spotify"), job("c", "spotify", "jellyfin@2"), job("d", "deezer", "shared"),
            ),
            reports = listOf(run("a", t0, hey, paranoid, ignoredOne), run("b", t0, heyYt), run("c", t0, rutles)),
        )
    }

    @Test fun theServersListIsUnchangedByDefault() {
        setUp()
        assertEquals(listOf("The Rutles – Hold My Hand"), Wanted.list(store.data).map { it.toString() })
        assertEquals(Wanted.list(store.data), Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.SERVERS)))
    }

    @Test fun everywhereListsEveryServiceEachSongOnceWithoutTheIgnored() {
        setUp()
        val all = Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL))
        // Sorted by artist, "The" left aside.
        assertEquals(listOf("The Beatles – Hey Jude", "Black Sabbath – Paranoid", "The Rutles – Hold My Hand"), all.map { it.toString() })
        // Missing on TIDAL and on Spotify: one entry naming both syncs.
        assertEquals(setOf("a", "b"), all[0].syncs.map { it.syncId }.toSet())
        assertTrue(all.none { it.got })
    }

    @Test fun onNarrowsToOneTargetByServiceOrProviderId() {
        setUp()
        fun on(s: String) = Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL, on = s)).map { it.toString() }
        assertEquals(listOf("The Beatles – Hey Jude"), on("spotify"))
        assertEquals(listOf("The Beatles – Hey Jude", "Black Sabbath – Paranoid"), on("TIDAL"))
        assertEquals(listOf("The Rutles – Hold My Hand"), on("jellyfin"))
        assertEquals(listOf("The Rutles – Hold My Hand"), on("jellyfin@2"))
        assertEquals(emptyList<String>(), on("jellyfin@3"))
        // The entry missing on Spotify names only the sync that misses it there.
        assertEquals(listOf("b"), Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL, on = "spotify")).single().syncs.map { it.syncId })
    }

    @Test fun missingOnCountsPerTargetServiceMostFirstWithoutTheHub() {
        setUp()
        assertEquals(listOf("tidal" to 2, "spotify" to 1, "jellyfin" to 1), Wanted.missingOn(store.data))
    }

    @Test fun aTickOnTheServersListDoesNotHideASongEverywhere() {
        setUp()
        val id = Wanted.list(store.data).single().id
        assertEquals(1, Wanted.mark(store, listOf(id), got = true))
        assertTrue(Wanted.list(store.data).isEmpty())
        assertFalse(Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL, on = "jellyfin")).single().got)
    }

    @Test fun scopeIsReadByName() {
        assertEquals(Wanted.Scope.SERVERS, Wanted.Scope.of(null))
        assertEquals(Wanted.Scope.SERVERS, Wanted.Scope.of("servers"))
        assertEquals(Wanted.Scope.ALL, Wanted.Scope.of("all"))
        assertEquals(Wanted.Scope.ALL, Wanted.Scope.of("Everywhere"))
        assertNull(Wanted.Scope.of("nowhere"))
    }

    @Test fun savedAsAFilePlaylistWithAFreshNameEachTime() = runBlocking {
        setUp()
        val wants = Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL, on = "tidal"))
        val pl = Wanted.saveAsFilePlaylist(env, "Not found on TIDAL", wants)
        assertEquals("Not found on TIDAL", pl.name)
        assertEquals(2, pl.trackCount)
        assertEquals(listOf("Hey Jude", "Paranoid"), LocalFilesProvider.tracks(env, pl.id).map { it.title })
        assertEquals("GBAYE0601690", LocalFilesProvider.tracks(env, pl.id)[0].isrc)
        // The same name again: a second file, the first one untouched.
        val again = Wanted.saveAsFilePlaylist(env, "Not found on TIDAL", wants.take(1))
        assertEquals("Not found on TIDAL (2)", again.name)
        assertEquals(2, LocalFilesProvider.tracks(env, pl.id).size)
    }

    @Test fun aPlaylistNeedsAName() = runBlocking {
        assertTrue(runCatching { Wanted.saveAsFilePlaylist(env, "  ", emptyList()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun theFormatsWorkOnTheListEverywhere() {
        setUp()
        val all = Wanted.list(store.data, filter = Wanted.Filter(scope = Wanted.Scope.ALL))
        val csv = Wanted.Format.CSV.encode(all)
        assertTrue(csv.startsWith("title,artist,album,duration_ms,isrc,musicbrainz_id,syncs,first_seen"))
        assertEquals(4, csv.trim().lines().size)
        assertEquals(3, CsvCodec.parse(csv).size)
    }
}
