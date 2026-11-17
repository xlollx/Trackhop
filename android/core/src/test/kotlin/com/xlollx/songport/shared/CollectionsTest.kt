package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.MemoryService
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A collection of shared playlists ([Collections]) between two devices meeting in one folder, run
 * with the real SyncEngine. Giulia curates on "cgs" (her playlists Road Trip, Jazz, Indie); Leo
 * follows on "cly" and "cln", where he already has a playlist of his own called Jazz (one song).
 */
class CollectionsTest {
    private lateinit var folder: File
    private val giulia = ContractEnv()
    private val leo = ContractEnv()
    private val gs = MemoryService("cgs")
    private val ly = MemoryService("cly")
    private val ln = MemoryService("cln")
    private lateinit var cid: String

    private fun song(prefix: String, n: Int) = Track("$prefix$n", "Song $n", listOf("Band"), "Album", 200_000, isrc = "QZCOL26" + n.toString().padStart(5, '0'))
    private fun titles(s: MemoryService, list: String) = s.lists[list]!!.map { it.title.removePrefix("Song ").toInt() }.sorted()
    private fun playlists(vararg names: String) = names.map { Playlist(it, it, gs.lists[it]!!.size) }

    @Before fun setUp() {
        Features.build = true
        SigCache.clear()
        folder = Files.createTempDirectory("trackhop-collection").toFile()
        for ((s, p) in listOf(gs to "g", ly to "y", ln to "n")) s.catalogue += (1..40).map { song(p, it) }
        gs.lists["Road Trip"] = (1..3).map { song("g", it) }.toMutableList()
        gs.lists["Jazz"] = (11..12).map { song("g", it) }.toMutableList()
        gs.lists["Indie"] = (21..24).map { song("g", it) }.toMutableList()
        ly.lists["his-jazz"] = mutableListOf(song("y", 30))
        ly.names["his-jazz"] = "Jazz"
        Providers.testDoubles = listOf(gs, ly, ln)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        SharedFolders.opener = { DirFolder(File(it)) }
        Features.build = false
        folder.deleteRecursively()
    }

    private fun create(vararg names: String = arrayOf("Road Trip", "Jazz", "Indie"), autoAdd: Boolean = false): Collections.Settled = runBlocking {
        val (c, settled) = Collections.create(giulia, "Giulia's playlists", "Giulia", "cgs", playlists(*names), folder.path, "Trackhop", autoAdd = autoAdd,
            seen = gs.lists.keys.toSet())
        cid = c.id
        settled
    }

    private fun invite(): Invite = Invite.parse(Collections.invite(giulia, Collections.get(giulia, cid)!!).link())!!

    private fun join(): SharedCollection = Collections.join(leo, invite(), folder.path, "Trackhop", "Leo")

    private fun run(env: ContractEnv) = runBlocking {
        val c = Collections.get(env, cid)!!
        // Twice: what one playlist's syncs sent, the other device's next run reads.
        repeat(2) {
            for (id in Collections.order(env, c)) env.store.job(id)?.let { j -> SyncEngine(env).run(j).let { r -> assertTrue("${r.jobName}: ${r.error}", r.ok) } }
        }
    }

    private fun settle(env: ContractEnv) = runBlocking { Collections.settle(env, cid) }
    private fun names(env: ContractEnv) = Collections.entries(env, Collections.get(env, cid)!!).map { it.name }

    @Test fun aCollectionsInviteIsOneLinkAnOlderTrackhopDoesNotTakeForAPlaylist() {
        create()
        val c = Collections.get(giulia, cid)!!
        val link = Collections.invite(giulia, c).link()
        assertTrue(link, link.startsWith(Invite.BASE + "c=" + cid))
        assertFalse(link.contains("g="))
        val parsed = Invite.parse(link)!!
        assertTrue(parsed.collection)
        assertEquals(cid, parsed.groupId)
        // One code for the whole collection: the creator's key of the list.
        assertEquals(Invite.codeOf(c.list.creator), parsed.code)
        // A single playlist's invite is as it was.
        val one = SharedGroups.group(giulia, Collections.entries(giulia, c).first().groupId)!!
        assertFalse(Invite.parse(SharedTransports.invite(giulia, one).link())!!.collection)
        assertEquals(link, parsed.link())
    }

    @Test fun eachPlaylistIsASharedGroupOfItsOwnAndTheListIsNotOne() {
        val settled = create()
        assertTrue(settled.complete)
        assertEquals(6, settled.started.size)
        val c = Collections.get(giulia, cid)!!
        assertTrue(c.todo.isEmpty())
        assertEquals(listOf("Road Trip", "Jazz", "Indie"), names(giulia))
        assertEquals(3, giulia.store.data.groups.size)
        assertTrue(giulia.store.data.groups.all { it.collectionId == cid && it.createdHere })
        // The list is kept by the collection: no playlist, no syncs, not a sync's side.
        assertNull(SharedGroups.group(giulia, cid))
        assertEquals(6, giulia.store.data.jobs.size)
        assertEquals(listOf(3, 2, 4), Collections.entries(giulia, c).map { it.tracks })
    }

    @Test fun followingAllOnTwoServicesMakesThePlaylistsAndLeavesHisOwnOfTheSameName() {
        create()
        run(giulia)
        val joined = join()
        assertFalse(joined.chosen)
        assertTrue(leo.store.data.groups.isEmpty())
        assertEquals(listOf("Road Trip", "Jazz", "Indie"), names(leo))
        val ids = Collections.entries(leo, joined).map { it.groupId }.toSet()
        val settled = runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cly", "cln"), true, emptySet()) }
        assertTrue(settled.failed.toString(), settled.complete)
        assertEquals(ids, leo.store.data.groups.map { it.id }.toSet())
        assertTrue(leo.store.data.groups.all { it.collectionId == cid && it.keepMine && !it.createdHere })
        // Two syncs per playlist and service.
        assertEquals(12, leo.store.data.jobs.size)
        assertEquals(setOf("his-jazz", "Jazz", "Road Trip", "Indie"), ly.lists.keys)
        assertEquals(setOf("Jazz", "Road Trip", "Indie"), ln.lists.keys)
        run(leo)
        assertEquals(listOf(1, 2, 3), titles(ly, "Road Trip"))
        assertEquals(listOf(1, 2, 3), titles(ln, "Road Trip"))
        assertEquals(listOf(21, 22, 23, 24), titles(ln, "Indie"))
        // His own Jazz, with a song in it, is not taken over: a new one is made beside it.
        assertEquals(listOf(11, 12), titles(ly, "Jazz"))
        assertEquals(listOf(30), titles(ly, "his-jazz"))
        // Nothing of his reaches the curator.
        run(giulia)
        assertEquals(listOf(11, 12), titles(gs, "Jazz"))
        // Settling again makes nothing.
        val again = settle(leo)
        assertTrue(again.started.isEmpty() && again.complete)
        assertEquals(12, leo.store.data.jobs.size)
        assertEquals(1, Collections.followers(Collections.get(giulia, cid)!!, SharedGroups.refresh(giulia, Collections.get(giulia, cid)!!.list)))
    }

    @Test fun aCuratorsChangeReachesEveryServiceOfTheFollower() {
        create("Road Trip")
        run(giulia)
        join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cly", "cln"), true, emptySet()) }
        run(leo)
        gs.lists["Road Trip"]!! += song("g", 4)
        gs.lists["Road Trip"]!!.removeAll { it.title == "Song 1" }
        run(giulia)
        run(leo)
        assertEquals(listOf(2, 3, 4), titles(ly, "Road Trip"))
        assertEquals(listOf(2, 3, 4), titles(ln, "Road Trip"))
    }

    @Test fun pickingTakesOnlyTheChosenAndNewOnesAreProposed() {
        create("Road Trip", "Jazz")
        run(giulia)
        val c = join()
        val road = Collections.entries(leo, c).first { it.name == "Road Trip" }.groupId
        runBlocking { Collections.choose(leo, cid, SharedCollection.PICK, listOf("cln"), true, setOf(road)) }
        assertEquals(setOf("Road Trip"), ln.lists.keys)
        assertTrue(Collections.proposals(Collections.get(leo, cid)!!, Collections.entries(leo, Collections.get(leo, cid)!!)).isEmpty())
        // Giulia adds one later.
        runBlocking { Collections.add(giulia, cid, playlists("Indie")) }
        run(giulia)
        val settled = settle(leo)
        assertTrue(settled.started.isEmpty())
        val now = Collections.get(leo, cid)!!
        val proposed = Collections.proposals(now, Collections.entries(leo, now))
        assertEquals(listOf("Indie"), proposed.map { it.name })
        assertEquals(setOf("Road Trip"), ln.lists.keys)
        // Ignored: not proposed again, and nothing made.
        runBlocking { Collections.answer(leo, cid, proposed[0].groupId, take = false) }
        assertTrue(Collections.proposals(Collections.get(leo, cid)!!, Collections.entries(leo, now)).isEmpty())
        assertEquals(setOf("Road Trip"), ln.lists.keys)
        // Taken after all, from its switch.
        val taken = runBlocking { Collections.setOn(leo, cid, proposed[0].groupId, true) }
        assertEquals(2, taken.started.size)
        run(leo)
        assertEquals(listOf(21, 22, 23, 24), titles(ln, "Indie"))
    }

    @Test fun withAllANewPlaylistArrivesByItself() {
        create("Road Trip")
        run(giulia)
        join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        runBlocking { Collections.add(giulia, cid, playlists("Indie")) }
        run(giulia)
        val settled = settle(leo)
        assertEquals(2, settled.started.size)
        run(leo)
        assertEquals(listOf(21, 22, 23, 24), titles(ln, "Indie"))
    }

    @Test fun aPlaylistSwitchedOffStopsSyncingAndStaysAsItIs() {
        create("Road Trip", "Jazz")
        run(giulia)
        val c = join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        run(leo)
        val road = Collections.entries(leo, c).first { it.name == "Road Trip" }.groupId
        val off = runBlocking { Collections.setOn(leo, cid, road, false) }
        assertEquals(2, off.changed.size)
        assertTrue(leo.store.data.jobs.filter { SharedGroups.sharedSide(it) == road }.none { it.enabled })
        // Switching one off is a selection from then on.
        assertEquals(SharedCollection.PICK, Collections.get(leo, cid)!!.mode)
        gs.lists["Road Trip"]!! += song("g", 5)
        gs.lists["Jazz"]!! += song("g", 13)
        run(giulia)
        run(leo)
        assertEquals(listOf(1, 2, 3), titles(ln, "Road Trip"))
        assertEquals(listOf(11, 12, 13), titles(ln, "Jazz"))
        // "All" again: the one switched off comes back.
        val all = runBlocking { Collections.setMode(leo, cid, SharedCollection.ALL) }
        assertEquals(2, all.changed.size)
        run(leo)
        assertEquals(listOf(1, 2, 3, 5), titles(ln, "Road Trip"))
        assertEquals(setOf("Road Trip", "Jazz"), ln.lists.keys)
    }

    @Test fun aJoinStoppedHalfWayCarriesOnAndMakesNoPlaylistTwice() {
        create()
        run(giulia)
        join()
        // The service refuses the second playlist it is asked to make.
        var made = 0
        ln.onCreate = { if (++made == 2) throw ProviderException("cln: try again later") }
        val first = runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        assertEquals(1, first.failed.size)
        assertEquals(2, first.started.size / 2)
        val c = Collections.get(leo, cid)!!
        // The choice is kept, the one not set up is said, and nothing is half there: a group has its syncs or does not exist.
        assertEquals(1, Collections.rows(leo, c).count { it.pending })
        assertEquals(2, leo.store.data.groups.size)
        assertEquals(4, leo.store.data.jobs.size)
        ln.onCreate = null
        val second = settle(leo)
        assertTrue(second.complete)
        assertEquals(2, second.started.size)
        assertEquals(0, Collections.rows(leo, Collections.get(leo, cid)!!).count { it.pending })
        assertEquals(setOf("Road Trip", "Jazz", "Indie"), ln.lists.keys)
        assertEquals(6, leo.store.data.jobs.size)
    }

    @Test fun anEmptyPlaylistOfTheSameNameIsTakenUnlessASyncUsesIt() {
        create("Road Trip", "Jazz")
        run(giulia)
        join()
        // An empty Road Trip no sync uses is taken; an empty Jazz another sync writes to is not.
        ln.lists["empty-trip"] = ArrayList()
        ln.names["empty-trip"] = "Road Trip"
        ln.lists["busy-jazz"] = ArrayList()
        ln.names["busy-jazz"] = "Jazz"
        leo.store.upsertJob(com.xlollx.songport.model.SyncJob(id = "other", name = "other",
            source = com.xlollx.songport.model.PlaylistRef("cly", "his-jazz", "Jazz"), target = com.xlollx.songport.model.PlaylistRef("cln", "busy-jazz", "Jazz")))
        val settled = runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        assertTrue(settled.failed.toString(), settled.complete)
        assertEquals(setOf("empty-trip", "busy-jazz", "Jazz"), ln.lists.keys)
        val targets = leo.store.data.jobs.filter { it.id != "other" }.flatMap { listOf(it.source.playlistId, it.target.playlistId) }.toSet()
        assertTrue(targets.toString(), "empty-trip" in targets && "Jazz" in targets && "busy-jazz" !in targets)
    }

    @Test fun aPlaylistMadeJustBeforeTheStopIsUsedByTheNextRun() {
        create("Road Trip")
        run(giulia)
        join()
        // The playlist is made, then the folder cannot be reached: the group is not joined.
        var creations = 0
        ln.onCreate = {
            creations++
            SharedFolders.opener = { throw java.io.IOException("offline") }
        }
        val first = runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        assertEquals(1, first.failed.size)
        assertTrue(leo.store.data.groups.isEmpty() && leo.store.data.jobs.isEmpty())
        assertEquals(setOf("Road Trip"), ln.lists.keys)
        // Offline still: the list cannot be read, nothing is touched and the error is said.
        assertNotNull(runCatching { settle(leo) }.exceptionOrNull())
        SharedFolders.opener = { DirFolder(File(it)) }
        val second = settle(leo)
        assertTrue(second.complete)
        assertEquals(1, creations)
        assertEquals(1, leo.store.data.groups.size)
        run(leo)
        assertEquals(listOf(1, 2, 3), titles(ln, "Road Trip"))
    }

    @Test fun aCreationStoppedHalfWayKeepsWhatIsLeftToShareAndSharesNothingTwice() {
        // The folder fails once two files of playlists are in it.
        SharedFolders.opener = { ref ->
            object : GroupFolder by DirFolder(File(ref)) {
                override fun write(name: String, bytes: ByteArray) {
                    if (File(ref).list()!!.size >= 3) throw java.io.IOException("disk full")
                    DirFolder(File(ref)).write(name, bytes)
                }
            }
        }
        val first = create()
        assertFalse(first.complete)
        val c = Collections.get(giulia, cid)!!
        assertTrue(c.todo.isNotEmpty())
        val groupsBefore = giulia.store.data.groups.size
        assertEquals(groupsBefore * 2, giulia.store.data.jobs.size)
        SharedFolders.opener = { DirFolder(File(it)) }
        val second = settle(giulia)
        assertTrue(second.failed.toString(), second.complete)
        assertTrue(Collections.get(giulia, cid)!!.todo.isEmpty())
        assertEquals(3, giulia.store.data.groups.size)
        assertEquals(6, giulia.store.data.jobs.size)
        assertEquals(setOf("Road Trip", "Jazz", "Indie"), names(giulia).toSet())
        assertEquals(3, names(giulia).size)
        // And the follower reads all three.
        assertEquals(3, names(leo.also { join() }).size)
    }

    @Test fun aCollectionThatCannotBeStartedLeavesNothing() {
        SharedFolders.opener = { throw java.io.IOException("offline") }
        assertNotNull(runCatching { create() }.exceptionOrNull())
        assertTrue(giulia.store.data.collections.isEmpty() && giulia.store.data.groups.isEmpty() && giulia.store.data.jobs.isEmpty())
        assertTrue(giulia.tokens.ids().isEmpty())
    }

    @Test fun goingBackBeforeChoosingLeavesNothing() {
        create()
        join()
        val gone = runBlocking { Collections.leave(leo, cid) }
        assertTrue(gone.isEmpty())
        assertTrue(leo.store.data.collections.isEmpty() && leo.store.data.groups.isEmpty())
        assertTrue(leo.tokens.ids().isEmpty())
        assertTrue(ln.lists.isEmpty())
    }

    @Test fun leavingStopsEverythingKeepsThePlaylistsAndIsFinishedAfterAStop() {
        create()
        run(giulia)
        join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        run(leo)
        // The app closed right after the leave was asked: only the mark is there.
        leo.store.update { d -> d.copy(collections = d.collections.map { it.copy(leaving = true) }) }
        val gone = Collections.recover(leo)
        assertEquals(6, gone.size)
        assertTrue(leo.store.data.collections.isEmpty() && leo.store.data.groups.isEmpty() && leo.store.data.jobs.isEmpty())
        assertTrue(leo.tokens.ids().isEmpty())
        assertEquals(listOf(1, 2, 3), titles(ln, "Road Trip"))
        assertTrue(leo.logs.any { it.contains("interrupted") })
        // Joining again later uses the same playlists.
        join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        assertEquals(setOf("Road Trip", "Jazz", "Indie"), ln.lists.keys)
    }

    @Test fun aPlaylistTheCuratorTakesOutStopsForTheFollowerWhoKeepsIt() {
        create("Road Trip", "Jazz")
        run(giulia)
        val c = join()
        runBlocking { Collections.choose(leo, cid, SharedCollection.ALL, listOf("cln"), true, emptySet()) }
        run(leo)
        val jazz = Collections.entries(leo, c).first { it.name == "Jazz" }.groupId
        val out = runBlocking { Collections.remove(giulia, cid, jazz) }
        assertEquals(2, out.gone.size)
        assertEquals(listOf("Road Trip"), names(giulia))
        assertEquals(listOf(11, 12), titles(gs, "Jazz"))
        val settled = settle(leo)
        assertEquals(listOf("Jazz"), settled.removed)
        assertEquals(2, settled.gone.size)
        assertNull(SharedGroups.group(leo, jazz))
        assertEquals(listOf(11, 12), titles(ln, "Jazz"))
        assertEquals(listOf("Road Trip"), names(leo))
        assertTrue(settle(leo).removed.isEmpty())
    }

    @Test fun playlistsMadeLaterJoinWhenTheCuratorAskedForIt() {
        create("Road Trip", autoAdd = true)
        assertEquals(listOf("Road Trip"), names(giulia))
        // Jazz and Indie were there when the collection was made: only one made after joins.
        gs.lists["New finds"] = mutableListOf(song("g", 35))
        val settled = settle(giulia)
        assertEquals(2, settled.started.size)
        assertEquals(listOf("Road Trip", "New finds"), names(giulia))
        assertTrue(settle(giulia).started.isEmpty())
    }

    @Test fun anEntryThatIsNotAPlaylistsInviteIsNotListed() {
        create("Road Trip")
        val c = Collections.get(giulia, cid)!!
        SharedGroups.add(giulia, c.list, listOf(Track("x", "A song", listOf("Band")), Track("t:pl:nothex", "Bad", listOf("k", ""))))
        assertEquals(listOf("Road Trip"), names(giulia))
    }
}
