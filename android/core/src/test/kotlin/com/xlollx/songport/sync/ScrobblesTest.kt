package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mark as listened: the songs once each, back to back by their lengths, and Last.fm's two weeks. */
class ScrobblesTest {
    private val second = Track("a", "Second Hand News", listOf("Fleetwood Mac"), durationMs = 163_000)
    private val dreams = Track("b", "Dreams", listOf("Fleetwood Mac"), durationMs = 257_000)
    private val unknown = Track("c", "Songbird", listOf("Fleetwood Mac"))

    @Test fun finishedAtEndsTheLastSongThere() {
        val l = Scrobbles.lay(listOf(second, dreams), time = 1_000_000_000L, finished = true)
        assertEquals(1_000_000_000L - 257_000 - 163_000, l[0].at)
        assertEquals(1_000_000_000L - 257_000, l[1].at)
    }

    @Test fun startedAtStartsTheFirstAndAnUnknownLengthIsGuessed() {
        val l = Scrobbles.lay(listOf(unknown, dreams), time = 0, finished = false)
        assertEquals(listOf(0L, Scrobbles.GUESS_MS), l.map { it.at })
    }

    @Test fun aSongListedTwiceIsOneListen() {
        assertEquals(listOf("a", "b"), Scrobbles.songs(listOf(second, dreams, dreams.copy(id = "b2", title = "Dreams "), Track("x", " "))).map { it.id })
    }

    @Test fun lastFmTakesTwoWeeksAndNothingAhead() {
        val now = 100L * 24 * 3_600_000
        assertTrue(Scrobbles.lastFmTakes(now - 13L * 24 * 3_600_000, now))
        assertFalse(Scrobbles.lastFmTakes(now - 15L * 24 * 3_600_000, now))
        assertFalse(Scrobbles.lastFmTakes(now + 60_000, now))
    }

    @Test fun deezersAlbumsAndTheirSongs() {
        val albums = DeezerCatalog.parseAlbums("""{"data":[{"id":302127,"title":"Rumours","artist":{"name":"Fleetwood Mac"}}]}""")
        assertEquals(DeezerCatalog.Album("302127", "Rumours", "Fleetwood Mac"), albums.single())
        val tracks = DeezerCatalog.parseAlbumTracks("""{"data":[{"id":1,"title":"Dreams","duration":257,"isrc":"USWB10400049","artist":{"name":"Fleetwood Mac"}}]}""", albums.single())
        assertEquals(Track("1", "Dreams", listOf("Fleetwood Mac"), album = "Rumours", durationMs = 257_000, isrc = "USWB10400049"), tracks.single())
    }
}
