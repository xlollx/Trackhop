package com.xlollx.songport.shared

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.MemoryService
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A shared playlist end to end: three people, each with their own Trackhop (their own store, key
 * and syncs) and their own service, meeting in one shared folder. Ana listens on a TIDAL-like
 * service and creates the group, Ben on a Spotify-like one (no ISRCs, and song 9 missing from its
 * catalogue) becomes a curator, Cleo on a Deezer-like one follows. Each member's own playlist is
 * synced with the real SyncEngine, so matching, review and the two-way safety are the real ones.
 */
class SharedPlaylistTest {
    private lateinit var folder: File
    private val tidal = MemoryService("mtidal")
    private val spotify = MemoryService("mspotify")
    private val deezer = MemoryService("mdeezer")

    private inner class Member(val name: String, val service: MemoryService, val playlist: String) {
        val env = ContractEnv()
        val engine = SyncEngine(env)
        lateinit var groupId: String
        val group: SharedGroup get() = SharedGroups.group(env, groupId)!!
        val list: MutableList<Track> get() = service.lists[playlist]!!
        val songs: List<String> get() = list.map { it.title.removePrefix("Song ") }.sorted()
        val local get() = PlaylistRef(service.id, playlist, playlist)

        /** One sync of the pair, the way "Sync now" on the group does it: this member's changes out, then the group in. */
        fun sync(): Pair<SyncReport, SyncReport> = runBlocking {
            val up = engine.run(env.store.job(group.twinId)!!)
            val down = engine.run(env.store.job(group.jobId)!!)
            assertTrue("$name up: ${up.error}", up.ok)
            assertTrue("$name down: ${down.error}", down.ok)
            up to down
        }

        fun add(n: String) { list += service.catalogue.first { it.title == "Song $n" } }
        fun remove(n: String) { list.removeAll { it.title == "Song $n" } }
        fun state() = SharedGroups.load(env, groupId)
    }

    private lateinit var ana: Member
    private lateinit var ben: Member
    private lateinit var cleo: Member

    private fun isrc(n: String) = "QZ" + "ABC" + "26" + n.padStart(5, '0')

    @Before fun setUp() {
        folder = Files.createTempDirectory("trackhop-group").toFile()
        SigCache.clear()
        val songs = (1..14).map { it.toString() }
        tidal.catalogue += songs.map { Track("t$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        spotify.catalogue += songs.filter { it != "9" }.map { Track("s$it", "Song $it", listOf("Band"), "Album", 200_000) }
        deezer.catalogue += songs.map { Track("d$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        Providers.testDoubles = listOf(tidal, spotify, deezer)
        ana = Member("Ana", tidal, "ana")
        ben = Member("Ben", spotify, "ben")
        cleo = Member("Cleo", deezer, "cleo")
        tidal.lists["ana"] = (1..5).map { n -> tidal.catalogue.first { it.title == "Song $n" } }.toMutableList()
        spotify.lists["ben"] = ArrayList()
        deezer.lists["cleo"] = ArrayList()
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        folder.deleteRecursively()
    }

    /** Ana creates the group from her playlist; Ben and Cleo join with new, empty playlists; Ben becomes a curator. */
    private fun group() {
        val g = SharedGroups.create(ana.env, "Road trip", folder.path, "Trackhop", "Ana", "TIDAL", ana.local)
        ana.groupId = g.id
        ana.sync()
        val invite = Invite.parse(g.invite().link())!!
        val (b, others) = SharedGroups.join(ben.env, invite, folder.path, "Trackhop", "Ben", "Spotify", ben.local)
        assertTrue("Ana's file is in the folder", others)
        ben.groupId = b.id
        cleo.groupId = SharedGroups.join(cleo.env, invite, folder.path, "Trackhop", "Cleo", "Deezer", cleo.local).first.id
        SharedGroups.setCurator(ana.env, ana.group, ben.group.me, true)
        ben.sync(); cleo.sync()
    }

    private fun everyone() { ana.sync(); ben.sync(); cleo.sync() }

    @Test fun theGroupReachesEveryMemberOnTheirOwnService() {
        group()
        val first = listOf("1", "2", "3", "4", "5")
        assertEquals(first, ana.songs)
        assertEquals(first, ben.songs)
        assertEquals(first, cleo.songs)
        // Each member has their own service's tracks.
        assertTrue(ben.list.all { it.id.startsWith("s") })
        assertTrue(cleo.list.all { it.id.startsWith("d") })
        // Roles as the creator set them.
        val members = SharedGroups.members(ana.group, ana.state())
        assertEquals(listOf("Ana", "Ben", "Cleo"), members.map { it.name })
        assertEquals(listOf(true, true, false), members.map { it.curator })
        assertEquals(SharedGroups.Role.FOLLOWER, SharedGroups.role(cleo.group, cleo.state()))
    }

    @Test fun curatorsChangesReachEveryoneAndRemovalsStayRemoved() {
        group()
        ben.add("6")
        ben.sync()
        ana.remove("2")
        ana.sync()
        everyone()
        val expected = listOf("1", "3", "4", "5", "6")
        assertEquals(expected, ana.songs)
        assertEquals(expected, ben.songs)
        assertEquals(expected, cleo.songs)
        // Nothing left to do anywhere, and song 2 does not come back.
        everyone()
        assertEquals(expected, cleo.songs)
        // The readable log says who did what.
        val history = SharedGroups.history(cleo.state())
        val names = SharedGroups.members(cleo.group, cleo.state()).associate { it.id to it.name }
        val last = history.first()
        assertEquals("Ana", names[last.by])
        assertEquals("− Band – Song 2", last.line())
        assertTrue(history.any { names[it.by] == "Ben" && it.line() == "+ Band – Song 6" })
    }

    /** Ana takes song 5 out while Ben still has it: it goes from Ben too. Later Ben puts it back: the newer action wins. */
    @Test fun theMostRecentActionWinsBetweenCurators() {
        group()
        ana.remove("5"); ana.sync()
        ben.sync()
        assertFalse("5" in ben.songs)
        Thread.sleep(5)
        ben.add("5"); ben.sync()
        everyone()
        assertTrue("5" in ana.songs)
        assertTrue("5" in cleo.songs)
    }

    @Test fun aTrackOneServiceCannotFindStaysForTheOthersAndGoesToThatMembersReview() {
        group()
        ana.add("9")
        ana.sync()
        val (_, down) = ben.sync()
        assertEquals(listOf("Song 9"), down.unmatchedTracks.map { it.title })
        everyone()
        everyone()
        assertTrue("9" in ana.songs)
        assertTrue("9" in cleo.songs)
        assertFalse("9" in ben.songs)
        assertTrue(GroupLog(ana.groupId, ana.group.creator).live(ana.state().events).any { it.title == "Song 9" })
    }

    @Test fun aFollowerKeepingAnExactCopyChangesNothingForTheOthers() {
        group()
        cleo.add("10")
        cleo.remove("3")
        cleo.sync()
        // Cleo's own addition is taken out again, the song she took out comes back.
        assertEquals(listOf("1", "2", "3", "4", "5"), cleo.songs)
        everyone()
        assertEquals(listOf("1", "2", "3", "4", "5"), ana.songs)
        assertEquals(listOf("1", "2", "3", "4", "5"), ben.songs)
        assertTrue(cleo.state().events.none { it.by == cleo.group.me })
    }

    @Test fun aFollowerKeepingTheirAdditionsKeepsThemToThemselves() {
        group()
        SharedGroups.setKeepMine(cleo.env, cleo.group, true)
        cleo.add("11")
        cleo.remove("4")
        cleo.sync(); cleo.sync()
        assertEquals(listOf("1", "11", "2", "3", "5"), cleo.songs)
        everyone()
        assertEquals(listOf("1", "2", "3", "4", "5"), ana.songs)
        assertEquals(listOf("1", "2", "3", "4", "5"), ben.songs)
        // A curator's new song still reaches Cleo.
        ana.add("12"); ana.sync(); cleo.sync()
        assertEquals(listOf("1", "11", "12", "2", "3", "5"), cleo.songs)
    }

    /** A follower's Trackhop changed to write anyway: signed events and a curators' list of its own. Nobody else moves. */
    @Test fun aFollowerWhoForcesWritesChangesNothingAndAForgedListIsRejected() {
        group()
        val gid = cleo.groupId
        val key = Identity.of(cleo.env.tokens.get(SharedGroups.tokenKey(gid))!!.accessToken)!!
        val name = FolderTransport.fileName(gid, key.id)
        val mine = MemberFile.parse(File(folder, name).readText())!!
        val roster = Roster(gid, 99, 99, listOf(Grant(ana.group.creator), Grant(key.id))).signed(key)
        val forged = listOf(
            SharedEvent.of("+", deezer.catalogue.first { it.title == "Song 13" }, key.id, 1, System.currentTimeMillis()).signed(gid, key),
            SharedEvent.of("-", deezer.catalogue.first { it.title == "Song 1" }, key.id, 2, System.currentTimeMillis()).signed(gid, key),
            // Even in Ana's name: the signature is Cleo's.
            SharedEvent.of("-", deezer.catalogue.first { it.title == "Song 3" }, key.id, 3, System.currentTimeMillis()).signed(gid, key).copy(by = ana.group.me),
        )
        File(folder, name).writeText(MemberFile.write(mine.copy(roster = roster, events = mine.events + forged)))
        everyone()
        assertEquals(listOf("1", "2", "3", "4", "5"), ana.songs)
        assertEquals(listOf("1", "2", "3", "4", "5"), ben.songs)
        assertEquals(2L, ana.state().roster!!.version)
        assertFalse(key.id in ben.state().roster!!.curators)
    }

    @Test fun conflictCopiesAndHalfWrittenFilesChangeNothing() {
        group()
        val anaFile = File(folder, FolderTransport.fileName(ana.groupId, ana.group.me))
        val stale = anaFile.readText()
        ana.remove("2"); ana.sync()
        // Syncthing keeps the older version beside the new one; Drive makes a "(1)"; a copy is cut short.
        File(folder, anaFile.nameWithoutExtension + ".sync-conflict-20261110-101010-ABCDEFG.json").writeText(stale)
        File(folder, anaFile.nameWithoutExtension + " (1).json").writeText(anaFile.readText().take(200))
        File(folder, anaFile.nameWithoutExtension + " (2).json").writeBytes(ByteArray(FolderTransport.MAX_FILE_BYTES + 1) { ' '.code.toByte() })
        everyone()
        assertEquals(listOf("1", "3", "4", "5"), ben.songs)
        assertEquals(listOf("1", "3", "4", "5"), cleo.songs)
    }

    @Test fun aCuratorMadeFollowerKeepsWhatTheyAddedButAddsNoMore() {
        group()
        ben.add("6"); ben.sync()
        SharedGroups.setCurator(ana.env, ana.group, ben.group.me, false)
        ana.sync()
        ben.add("7"); ben.sync()
        everyone()
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), ana.songs)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), cleo.songs)
        // Ben now follows: his addition was taken out of his own playlist (an exact copy).
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), ben.songs)
    }

    @Test fun thereIsAlwaysACuratorAndOnlyTheCreatorChangesRoles() {
        group()
        assertThrowsProvider { SharedGroups.setCurator(ana.env, ana.group, ben.group.me, false).also { SharedGroups.setCurator(ana.env, ana.group, ana.group.me, false) } }
        assertThrowsProvider { SharedGroups.setCurator(ben.env, ben.group, cleo.group.me, true) }
        assertEquals(setOf(ana.group.me), ana.state().roster!!.curators)
    }

    @Test fun aMemberWhoLeavesTakesNothingAway() {
        group()
        ben.add("6"); ben.sync()
        everyone()
        val jobs = SharedGroups.leave(ben.env, ben.group)
        assertEquals(2, jobs.size)
        assertTrue(ben.env.store.data.jobs.isEmpty())
        assertTrue(ben.env.store.data.groups.isEmpty())
        // Even with Ben's file gone, his song stays: every member's file carries the whole log.
        everyone2()
        File(folder, FolderTransport.fileName(ana.groupId, ana.state().members.first { it.name == "Ben" }.member)).delete()
        everyone2()
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), ana.songs)
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), cleo.songs)
        assertTrue(SharedGroups.members(ana.group, ana.state()).first { it.name == "Ben" }.left)
    }

    private fun everyone2() { ana.sync(); cleo.sync() }

    @Test fun aConfigurationFileLeavesTheGroupsSyncsOut() {
        group()
        val text = ConfigBackup.export(ana.env, "test")
        assertFalse(text.contains(ana.groupId))
    }

    @Test fun aMemberWhoMissedTheFolderForAWhileCatchesUp() {
        group()
        // Cleo's copy of the folder lags (a sync app not run for a while): she reads an older folder.
        val lagging = Files.createTempDirectory("trackhop-lag").toFile()
        folder.listFiles()!!.forEach { it.copyTo(File(lagging, it.name)) }
        ana.remove("1"); ana.add("8"); ana.sync()
        ben.add("6"); ben.sync()
        cleo.env.store.update { d -> d.copy(groups = d.groups.map { it.copy(folder = lagging.path) }) }
        cleo.sync()
        assertEquals(listOf("1", "2", "3", "4", "5"), cleo.songs)
        // The folder catches up (both ways): everyone ends the same.
        lagging.listFiles()!!.filter { it.name.startsWith("trackhop-") }.forEach { f -> if (!File(folder, f.name).exists() || f.lastModified() > File(folder, f.name).lastModified()) f.copyTo(File(folder, f.name), overwrite = true) }
        cleo.env.store.update { d -> d.copy(groups = d.groups.map { it.copy(folder = folder.path) }) }
        everyone()
        val expected = listOf("2", "3", "4", "5", "6", "8")
        assertEquals(expected, ana.songs)
        assertEquals(expected, ben.songs)
        assertEquals(expected, cleo.songs)
        lagging.deleteRecursively()
    }

    private fun assertThrowsProvider(block: () -> Unit) {
        val e = runCatching(block).exceptionOrNull()
        assertNotNull("expected a refusal", e)
        assertTrue(e.toString(), e is com.xlollx.songport.model.ProviderException)
    }
}
