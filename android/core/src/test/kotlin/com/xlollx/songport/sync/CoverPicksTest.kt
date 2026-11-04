package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CoverPicksTest {
    private fun t(id: String, album: String, artist: String = "Avenged Sevenfold") =
        Track(id = id, title = "Song $id", artists = listOf(artist), album = album, durationMs = 200_000)

    @Test
    fun editionsOfOneAlbumShareAKey() {
        assertEquals(CoverPicks.albumKey(t("1", "Hail to the King")), CoverPicks.albumKey(t("2", "Hail To The King (Deluxe)")))
    }

    @Test
    fun differentAlbumsAndArtistsDiffer() {
        assertNotEquals(CoverPicks.albumKey(t("1", "Hail to the King")), CoverPicks.albumKey(t("2", "Nightmare")))
        assertNotEquals(CoverPicks.albumKey(t("1", "Greatest Hits", "A")), CoverPicks.albumKey(t("2", "Greatest Hits", "B")))
    }

    @Test
    fun aTrackWithoutAlbumStandsAlone() {
        assertNotEquals(CoverPicks.albumKey(t("1", "")), CoverPicks.albumKey(t("2", "")))
    }

    @Test
    fun aPlaylistOfOneAlbumThenOthersKeepsOrder() {
        val list = listOf(t("1", "Hail to the King"), t("2", "Master of Puppets", "Metallica"), t("3", "Hail to the King"), t("4", "Hail to the King"), t("5", "Nightmare"))
        assertEquals(listOf("1", "2", "5"), list.distinctBy { CoverPicks.albumKey(it) }.map { it.id })
    }

    // ------------------------------------------------------------------ order

    private fun ids(list: List<Track>) = list.map { it.id }

    /** A playlist that opens with three songs of one album by an artist who appears once more, then the artists that fill it. */
    private val playlist = listOf(
        t("a1", "Lonely Album", "Opener"), t("a2", "Lonely Album", "Opener"), t("a3", "Lonely Album", "Opener"),
        t("m1", "Master of Puppets", "Metallica"), t("m2", "Black Album", "Metallica"), t("m3", "Master of Puppets", "Metallica"),
        t("m4", "Ride the Lightning", "Metallica"), t("m5", "Master of Puppets", "metallica"),
        t("d1", "Discovery", "Daft Punk"), t("d2", "Random Access Memories", "Daft Punk"), t("d3", "Discovery", "Daft Punk"), t("d4", "Discovery", "Daft Punk"),
        t("x1", "One Off", "Someone"),
    )

    @Test
    fun theMostPresentArtistsComeFirstOneAlbumEach() {
        val order = CoverPicks.order(playlist)
        // Metallica (5 songs), then Daft Punk and Opener (4 and 3), then Someone; each with its most frequent album.
        assertEquals(listOf("m1", "d1", "a1", "x1"), ids(order).take(4))
        // Then the second album of each, then the third.
        assertEquals(listOf("m2", "d2", "m4"), ids(order).drop(4))
    }

    @Test
    fun oneTrackPerAlbumAndNothingLost() {
        val order = CoverPicks.order(playlist)
        assertEquals(order.size, order.map { CoverPicks.albumKey(it) }.toSet().size)
        assertEquals(playlist.map { CoverPicks.albumKey(it) }.toSet(), order.map { CoverPicks.albumKey(it) }.toSet())
    }

    @Test
    fun theSamePlaylistGivesTheSameOrder() {
        assertEquals(ids(CoverPicks.order(playlist)), ids(CoverPicks.order(playlist.toList())))
        assertEquals(ids(CoverPicks.order(playlist, 2)), ids(CoverPicks.order(playlist, 2)))
    }

    @Test
    fun anotherVariantChangesALittleAndKeepsTheTopArtist() {
        val first = ids(CoverPicks.order(playlist, 0))
        val again = ids(CoverPicks.order(playlist, 1))
        assertNotEquals(first, again)
        assertEquals("m", again[0].take(1))
        assertEquals(first.toSet().size, again.toSet().size)
    }

    @Test
    fun aPlaylistOfDifferentArtistsKeepsItsOrderThenRotatesTheTail() {
        val list = (1..8).map { t("$it", "Album $it", "Artist $it") }
        assertEquals((1..8).map { "$it" }, ids(CoverPicks.order(list)))
        val v1 = ids(CoverPicks.order(list, 1))
        assertEquals(8, v1.toSet().size)
        assertNotEquals((1..8).map { "$it" }, v1)
    }

    @Test
    fun tracksWithoutArtistOrAlbumStillCount() {
        val list = listOf(Track(id = "n1", title = "x"), Track(id = "n2", title = "y"), t("k", "K", "K"))
        assertEquals(listOf("n1", "n2", "k"), ids(CoverPicks.order(list)))
        assertEquals(emptyList<String>(), ids(CoverPicks.order(emptyList())))
    }
}
