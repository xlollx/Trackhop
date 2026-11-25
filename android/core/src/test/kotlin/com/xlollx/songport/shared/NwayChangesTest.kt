package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.MemoryService
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A multi sync's added and removed songs, one row per song over its targets ([NwayChanges]). */
class NwayChangesTest {
    private fun t(id: String, artist: String, title: String) = Track(id, title, listOf(artist))
    private fun hubRef() = PlaylistRef(SharedProvider.ID, "g1", "Mix")

    /** A group: the source on Spotify (main), four targets (Deezer, TIDAL, Qobuz, YouTube Music). */
    private val members = listOf("spotify", "deezer", "tidal", "qobuz", "ytm").mapIndexed { i, p ->
        val down = SyncJob("down-$p", "Mix · $p", hubRef(), PlaylistRef(p, "pl-$p", "Mix"))
        Nway.Member(down.target, down, if (i == 0) SyncJob("up-$p", "Mix · $p ↔", PlaylistRef(p, "pl-$p", "Mix"), hubRef()) else null)
    }
    private val targets = members.drop(1).map { it.down.id }
    private fun c(p: String?, track: Track, key: String? = null) = NwayChanges.Change(p?.let { "down-$it" }, track, key)

    @Test fun theSameSongOnEveryTargetIsOneRowWhateverEachServiceCallsIt() {
        val songs = NwayChanges.group(listOf(
            c("deezer", t("d1", "Metallica", "Enter Sandman (Remastered 2021)")),
            c("tidal", t("t1", "Metallica", "Enter Sandman")),
            c("qobuz", t("q1", "Metallica", "Enter Sandman")),
            c("ytm", t("y1", "Metallica feat. Nobody", "Enter Sandman - Remastered")),
        ), targets)
        assertEquals(1, songs.size)
        assertEquals(targets, songs[0].members)
        assertTrue(NwayChanges.everyTarget(songs[0], targets))
        // Named as most of the services name it.
        assertEquals("Enter Sandman", songs[0].name.title)
        // Each service's own name stays with it, for the row's details.
        assertEquals("Enter Sandman (Remastered 2021)", songs[0].changes.first { it.member == "down-deezer" }.track.title)
    }

    @Test fun differentSongsAreNotMerged() {
        val songs = NwayChanges.group(listOf(
            c("deezer", t("d1", "Metallica", "Enter Sandman")),
            c("deezer", t("d2", "Metallica", "Nothing Else Matters")),
            c("tidal", t("t1", "Metallica", "Nothing Else Matters")),
            c("tidal", t("t2", "Iron Maiden", "Enter Sandman")),
        ), targets)
        assertEquals(3, songs.size)
        assertEquals(listOf(listOf("down-deezer", "down-tidal"), listOf("down-tidal"), listOf("down-deezer")), songs.map { it.members })
        assertFalse(songs.any { NwayChanges.everyTarget(it, targets) })
    }

    @Test fun theHubsIdGroupsWhatTheNamesWouldNotAndKeepsApartWhatTheyWould() {
        val songs = NwayChanges.group(listOf(
            // The group's list names the song: its name wins.
            c(null, t("h1", "Metallica", "Enter Sandman"), "h1"),
            c("deezer", t("d1", "Metallica", "Sandman, Enter"), "h1"),
            c("tidal", t("t1", "Metallica", "Enter Sandman (Live)"), "h1"),
            // Same name, another song of the hub: its own row.
            c("qobuz", t("q1", "Metallica", "Enter Sandman"), "h2"),
            // No id known: joins the first song of its name.
            c("ytm", t("y1", "Metallica", "Enter Sandman")),
        ), targets)
        assertEquals(2, songs.size)
        assertEquals("Enter Sandman", songs[0].name.title)
        assertEquals(listOf("down-deezer", "down-tidal", "down-ytm"), songs[0].members)
        assertEquals(listOf("down-qobuz"), songs[1].members)
    }

    @Test fun aChangeOnlyInTheGroupsListIsNoRow() {
        assertTrue(NwayChanges.group(listOf(c(null, t("h1", "A", "B"), "h1"))).isEmpty())
    }

    @Test fun theMostServicesFirstThenByTitle() {
        val songs = NwayChanges.group(listOf(
            c("deezer", t("d1", "Z", "Zebra")),
            c("deezer", t("d2", "A", "Beta")), c("tidal", t("t2", "A", "Beta")),
            c("deezer", t("d3", "A", "Alpha")),
            c("deezer", t("d4", "C", "Gamma")), c("tidal", t("t4", "C", "Gamma")), c("qobuz", t("q4", "C", "Gamma")), c("ytm", t("y4", "C", "Gamma")),
        ), targets)
        assertEquals(listOf("Gamma", "Beta", "Alpha", "Zebra"), songs.map { it.name.title })
        assertEquals(listOf(true, false, false, false), songs.map { NwayChanges.everyTarget(it, targets) })
        // Each song's playlists in the group's order, whatever order the runs listed them.
        assertEquals(targets, NwayChanges.group(targets.reversed().map { NwayChanges.Change(it, t(it, "C", "Gamma")) }, targets).single().members)
    }

    @Test fun aSingleTargetIsNeverAll() {
        val song = NwayChanges.group(listOf(c("deezer", t("d1", "A", "B")))).single()
        assertFalse(NwayChanges.everyTarget(song, listOf("down-deezer")))
    }

    @Test fun theChangesComeWithTheirPlaylistAndTheHubsIdFromTheMatches() {
        val d = StoreData(matchCache = mapOf(
            "shared:h1>deezer" to "d1", "shared:h1>tidal" to "t1",
            // Another source's match to the same service says nothing of the hub.
            "spotify:s9>deezer" to "d2",
        ))
        val gone = t("h1", "Metallica", "Enter Sandman")
        val batch = listOf(
            SyncReport("r0", "up-spotify", "", 0, 0, removed = 1, removedTracks = listOf(gone)),
            SyncReport("r1", "down-deezer", "", 0, 0, removed = 2, removedTracks = listOf(t("d1", "Metallica", "Enter Sandman (Remastered 2021)"), t("d2", "X", "Y"))),
            SyncReport("r2", "down-tidal", "", 0, 0, removed = 1, removedTracks = listOf(t("t1", "Metallica", "Enter Sandman")), addedTracks = listOf(t("t5", "A", "B"))),
            SyncReport("r3", "other-sync", "", 0, 0, removed = 1, removedTracks = listOf(t("o1", "Metallica", "Enter Sandman"))),
        )
        val removed = NwayChanges.changes(d, batch, members, NwayChanges.Kind.REMOVED)
        assertEquals(listOf(null, "down-deezer", "down-deezer", "down-tidal"), removed.map { it.member })
        assertEquals(listOf("h1", "h1", null, "h1"), removed.map { it.key })
        assertEquals(listOf("t5"), NwayChanges.changes(d, batch, members, NwayChanges.Kind.ADDED).map { it.track.id })
        val songs = NwayChanges.group(removed, members.map { it.down.id })
        assertEquals(listOf("Metallica – Enter Sandman", "X – Y"), songs.map { it.name.toString() })
        assertEquals(listOf("down-deezer", "down-tidal"), songs[0].members)
    }

    @Test fun aTargetsSizeIsKnownOnlyWhenItsRunReadIt() {
        assertEquals(1150, NwayChanges.targetSize(SyncReport("r", "j", "", 0, 0, targetCount = 1152, added = 2, removed = 4)))
        assertEquals(30, NwayChanges.targetSize(SyncReport("r", "j", "", 0, 0, added = 30)))
        assertNull(NwayChanges.targetSize(SyncReport("r", "j", "", 0, 0)))
        assertNull(NwayChanges.targetSize(null))
    }

    /** With the real engine: a song the source drops leaves every target, and their tracks lead back to the hub's one song. */
    @Test fun aRealRunsRemovalsAreOneSongOverTheTargets() {
        Features.build = true
        SigCache.clear()
        val env = ContractEnv()
        val engine = SyncEngine(env)
        val a = MemoryService("nca"); val b = MemoryService("ncb"); val cc = MemoryService("ncc")
        fun isrc(n: Int) = "QZABC26" + n.toString().padStart(5, '0')
        a.catalogue += (1..6).map { Track("a$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        b.catalogue += (1..6).map { Track("b$it", "Song $it", listOf("Band"), "Album", 200_000) }
        cc.catalogue += (1..6).map { Track("c$it", if (it == 2) "Song 2 (Remastered 2021)" else "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        a.lists["mix"] = a.catalogue.toMutableList()
        b.lists["mix"] = ArrayList(); cc.lists["mix"] = ArrayList()
        Providers.testDoubles = listOf(a, b, cc)
        try {
            val group = Nway.create(env, "Road trip", listOf(PlaylistRef("nca", "mix", "mix") to true, PlaylistRef("ncb", "mix", "mix") to false, PlaylistRef("ncc", "mix", "mix") to false))
            runBlocking { Nway.run(env, engine, group) }
            a.lists["mix"]!!.removeAll { it.title == "Song 2" }
            val batch = runBlocking { Nway.run(env, engine, group) }
            val members = Nway.members(env, group)
            val removed = NwayChanges.changes(env.store.data, batch, members, NwayChanges.Kind.REMOVED)
            assertTrue("every target's removal knows its song: $removed", removed.all { it.key != null })
            val song = NwayChanges.group(removed, members.map { it.down.id }).single()
            assertEquals("Song 2", song.name.title)
            val targetIds = members.filter { it.playlist.provider != "nca" }.map { it.down.id }
            assertEquals(targetIds, song.members)
            assertTrue(NwayChanges.everyTarget(song, targetIds))
        } finally {
            Providers.testDoubles = emptyList()
            Features.build = false
        }
    }
}
