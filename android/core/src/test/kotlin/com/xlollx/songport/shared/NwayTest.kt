package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One playlist on three services of one user ([Nway]), run with the real SyncEngine: "a" lists
 * ISRCs, "b" lists none and lacks song 9, "c" lists ISRCs. Each test says who is a main member.
 */
class NwayTest {
    private lateinit var env: ContractEnv
    private lateinit var engine: SyncEngine
    private val a = MemoryService("nwa")
    private val b = MemoryService("nwb")
    private val c = MemoryService("nwc")
    private lateinit var group: SharedGroup

    private fun isrc(n: Int) = "QZABC26" + n.toString().padStart(5, '0')
    private fun ref(s: MemoryService) = PlaylistRef(s.id, "mix", "mix")
    private fun songs(s: MemoryService) = s.lists["mix"]!!.map { it.title.removePrefix("Song ").toInt() }.sorted()
    private fun add(s: MemoryService, n: Int) { s.lists["mix"]!! += s.catalogue.first { it.title == "Song $n" } }
    private fun remove(s: MemoryService, n: Int) { s.lists["mix"]!!.removeAll { it.title == "Song $n" } }

    @Before fun setUp() {
        Features.build = true
        SigCache.clear()
        env = ContractEnv()
        engine = SyncEngine(env)
        a.catalogue += (1..30).map { Track("a$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        b.catalogue += (1..30).filter { it != 9 }.map { Track("b$it", "Song $it", listOf("Band"), "Album", 200_000) }
        c.catalogue += (1..30).map { Track("c$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = isrc(it)) }
        a.lists["mix"] = (1..5).map { n -> a.catalogue.first { it.title == "Song $n" } }.toMutableList()
        b.lists["mix"] = ArrayList()
        c.lists["mix"] = ArrayList()
        Providers.testDoubles = listOf(a, b, c)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        Features.build = false
    }

    private fun create(bMain: Boolean = true, cMain: Boolean = false) {
        group = Nway.create(env, "Road trip", listOf(ref(a) to true, ref(b) to bMain, ref(c) to cMain))
        sync()
    }

    private fun sync(): List<SyncReport> = runBlocking { Nway.run(env, engine, group) }.onEach { assertTrue("${it.jobName}: ${it.error}", it.ok) }

    private fun everywhere(vararg expected: Int) {
        for (s in listOf(a, b, c)) assertEquals(s.id, expected.toList(), songs(s))
    }

    @Test fun theGroupIsOneHubAndOrdinarySyncs() {
        create()
        val members = Nway.members(env, group)
        assertEquals(listOf("nwa", "nwb", "nwc"), members.map { it.playlist.provider })
        assertEquals(listOf(true, true, false), members.map { it.main })
        // Main: a two-way pair with removals. Follower: one mirror of the hub.
        assertTrue(members[0].down.twoWayRemovals && members[0].up!!.twoWayRemovals && members[0].up!!.twin)
        assertTrue(members[2].down.mirrorRemovals && members[2].down.linkedJobId == null)
        assertEquals(5, env.store.data.jobs.size)
        assertTrue(Nway.isHub(env, members[0].down.source))
        everywhere(1, 2, 3, 4, 5)
        // Each member holds its own service's tracks, once.
        assertTrue(b.lists["mix"]!!.all { it.id.startsWith("b") })
        assertTrue(c.lists["mix"]!!.all { it.id.startsWith("c") })
    }

    @Test fun anAdditionOnOneMemberReachesTheOthersOnce() {
        create()
        add(a, 6)
        val first = sync()
        everywhere(1, 2, 3, 4, 5, 6)
        // Once into the hub, once into each of the two other playlists.
        assertEquals(3, first.sumOf { it.added })
        add(b, 7)
        sync()
        everywhere(1, 2, 3, 4, 5, 6, 7)
        for (s in listOf(a, b, c)) assertEquals(s.id, 7, s.lists["mix"]!!.size)
    }

    @Test fun nothingBouncesOnRepeatedRuns() {
        create()
        add(a, 6); add(b, 7)
        sync()
        val adds = listOf(a, b, c).associateWith { it.addCalls }
        repeat(3) {
            val again = sync()
            assertEquals(0, again.sumOf { it.added })
            assertEquals(0, again.sumOf { it.removed })
        }
        everywhere(1, 2, 3, 4, 5, 6, 7)
        for (s in listOf(a, b, c)) assertEquals(s.id, adds[s], s.addCalls)
    }

    @Test fun aRemovalOnAMainMemberRemovesEverywhere() {
        create()
        remove(b, 2)
        sync()
        everywhere(1, 3, 4, 5)
        // And it stays removed.
        sync()
        everywhere(1, 3, 4, 5)
    }

    @Test fun aFollowersChangesGoNowhere() {
        create()
        sync()
        remove(c, 3)
        add(c, 12)
        sync()
        // An exact copy: its own addition goes, the song it took out comes back; the others never notice.
        everywhere(1, 2, 3, 4, 5)
        assertTrue(GroupLog(group.id, group.creator).live(SharedGroups.load(env, group.id).events).none { it.title == "Song 12" })
    }

    @Test fun aSongOneServiceLacksWaitsInThatMembersReviewAndStaysOnTheOthers() {
        create()
        add(a, 9)
        val reports = sync()
        val bDown = Nway.members(env, group)[1].down
        assertEquals(listOf("Song 9"), reports.first { it.jobId == bDown.id }.unmatchedTracks.map { it.title })
        repeat(2) { sync() }
        assertEquals(listOf(1, 2, 3, 4, 5, 9), songs(a))
        assertEquals(listOf(1, 2, 3, 4, 5, 9), songs(c))
        assertEquals(listOf(1, 2, 3, 4, 5), songs(b))
    }

    @Test fun aFollowerMadeMainSendsItsChanges() {
        create()
        Nway.setMain(env, group, Nway.members(env, group)[2], true)
        sync()
        add(c, 8); remove(c, 1)
        sync()
        everywhere(2, 3, 4, 5, 8)
        // Back to a follower: the hub's sync is the same one, the way up is gone.
        val down = Nway.members(env, group)[2].down.id
        Nway.setMain(env, group, Nway.members(env, group)[2], false)
        assertEquals(down, Nway.members(env, group)[2].down.id)
        add(c, 13)
        sync()
        everywhere(2, 3, 4, 5, 8)
    }

    @Test fun aMemberAddedLaterJoinsAndOneTakenOutIsLeftAlone() {
        group = Nway.create(env, "Road trip", listOf(ref(a) to true, ref(b) to true))
        sync()
        c.lists["mix"]!! += c.catalogue.first { it.title == "Song 20" }
        Nway.add(env, group, ref(c), main = true)
        sync()
        // A main member's own tracks join the group.
        everywhere(1, 2, 3, 4, 5, 20)
        val removed = Nway.remove(env, group, Nway.members(env, group)[1])
        assertEquals(2, removed.size)
        remove(a, 20)
        sync()
        assertEquals(listOf(1, 2, 3, 4, 5), songs(c))
        assertEquals(listOf(1, 2, 3, 4, 5, 20), songs(b))
        // The last main member stays.
        Nway.setMain(env, group, Nway.members(env, group)[1], false)
        assertTrue(runCatching { Nway.remove(env, group, Nway.members(env, group)[0]) }.isFailure)
        assertEquals(emptyList<SharedGroup>(), Nway.delete(env, group).let { env.store.data.groups })
        assertTrue(env.store.data.jobs.isEmpty())
    }

    /** A plain sync and a plain two-way pair next to a group, on the same services: as they always were. */
    @Test fun pairSyncsNextToAGroupRunAsBefore() = runBlocking {
        create()
        a.lists["src"] = (10..12).map { n -> a.catalogue.first { it.title == "Song $n" } }.toMutableList()
        b.lists["dst"] = ArrayList()
        b.lists["two"] = ArrayList()
        val one = SyncJob("one", "One way", PlaylistRef("nwa", "src", "src"), PlaylistRef("nwb", "dst", "dst"), mirrorRemovals = true)
        val there = SyncJob("there", "Two way", PlaylistRef("nwa", "src", "src"), PlaylistRef("nwb", "two", "two"), linkedJobId = "back", twoWayRemovals = true)
        val back = SyncJob("back", "Two way ↔", PlaylistRef("nwb", "two", "two"), PlaylistRef("nwa", "src", "src"), linkedJobId = "there", twoWayRemovals = true, twin = true)
        listOf(one, there, back).forEach { env.store.upsertJob(it) }
        suspend fun run(id: String) = engine.run(env.store.job(id)!!).also { assertTrue(it.error, it.ok) }
        assertEquals(3, run("one").added)
        assertEquals(3, run("there").added)
        run("back")
        b.lists["two"]!!.removeAll { it.title == "Song 11" }
        b.lists["two"]!! += b.catalogue.first { it.title == "Song 14" }
        val change = run("back")
        assertEquals(1 to 1, change.added to change.removed)
        run("there")
        assertEquals(listOf("Song 10", "Song 12", "Song 14"), a.lists["src"]!!.map { it.title })
        val mirror = run("one")
        assertEquals(1 to 1, mirror.added to mirror.removed)
        sync()
        // The group never saw any of it, and the pairs never touched the group's playlists.
        everywhere(1, 2, 3, 4, 5)
        assertEquals(listOf("Song 10", "Song 12", "Song 14"), b.lists["dst"]!!.map { it.title }.sorted())
    }

    @Test fun withoutTheReleaseSwitchNoGroupIsMadeAndAnExistingOneKeepsRunning() {
        create()
        Features.build = false
        assertTrue(runCatching { Nway.create(env, "Other", listOf(ref(a) to true, ref(b) to false)) }.isFailure)
        assertTrue(runCatching { Nway.add(env, group, PlaylistRef("nwc", "other", "other"), false) }.isFailure)
        assertEquals(1, env.store.data.groups.size)
        add(a, 6)
        sync()
        everywhere(1, 2, 3, 4, 5, 6)
        assertFalse(Nway.isHub(env, ref(a)))
    }
}
