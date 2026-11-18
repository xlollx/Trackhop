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
        // Main: a two-way pair with removals. Follower: one sync from the hub, keeping its additions unless told otherwise.
        assertTrue(members[0].down.twoWayRemovals && members[0].up!!.twoWayRemovals && members[0].up!!.twin)
        assertTrue(members[2].keepMine && !members[2].down.mirrorRemovals && members[2].down.linkedJobId == null)
        assertFalse(members[0].keepMine)
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

    @Test fun aFollowerKeepsItsAdditionsToItself() {
        create()
        add(c, 12)
        repeat(3) { sync() }
        // Its own song stays there, run after run, and reaches no one.
        assertEquals(listOf(1, 2, 3, 4, 5, 12), songs(c))
        assertEquals(listOf(1, 2, 3, 4, 5), songs(a))
        assertEquals(listOf(1, 2, 3, 4, 5), songs(b))
        assertTrue(GroupLog(group.id, group.creator).live(SharedGroups.load(env, group.id).events).none { it.title == "Song 12" })
        // What the hub gave goes when the hub drops it; the follower's own song does not.
        remove(a, 2)
        add(b, 6)
        sync()
        assertEquals(listOf(1, 3, 4, 5, 6, 12), songs(c))
        // A group song the follower takes out stays out there, and stays everywhere else.
        remove(c, 4)
        repeat(2) { sync() }
        assertEquals(listOf(1, 3, 5, 6, 12), songs(c))
        assertEquals(listOf(1, 3, 4, 5, 6), songs(a))
        assertEquals(listOf(1, 3, 4, 5, 6), songs(b))
    }

    @Test fun aFollowerSwitchesBetweenKeepingItsAdditionsAndAnExactCopy() {
        create()
        add(c, 12)
        sync()
        val down = Nway.members(env, group)[2].down.id
        Nway.setKeepMine(env, Nway.members(env, group)[2], false)
        Nway.members(env, group)[2].let { assertTrue(!it.keepMine && it.down.mirrorRemovals && it.down.id == down) }
        sync()
        // An exact copy: its own song goes.
        everywhere(1, 2, 3, 4, 5)
        Nway.setKeepMine(env, Nway.members(env, group)[2], true)
        sync()
        add(c, 13)
        remove(a, 5)
        repeat(2) { sync() }
        assertEquals(listOf(1, 2, 3, 4, 13), songs(c))
        assertEquals(listOf(1, 2, 3, 4), songs(a))
        // And back: only then does it go.
        Nway.setKeepMine(env, Nway.members(env, group)[2], false)
        sync()
        everywhere(1, 2, 3, 4)
    }

    @Test fun anExactCopysChangesGoNowhere() {
        create()
        Nway.setKeepMine(env, Nway.members(env, group)[2], false)
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
        // A follower again, keeping its additions: the song stays there and goes no further.
        assertEquals(listOf(2, 3, 4, 5, 8, 13), songs(c))
        assertEquals(listOf(2, 3, 4, 5, 8), songs(a))
        assertEquals(listOf(2, 3, 4, 5, 8), songs(b))
    }

    /** A configuration file carries the group and its syncs to another Trackhop; a shared playlist with other people stays behind. */
    @Test fun aGroupTravelsInAConfigurationFile() {
        create()
        add(c, 12)
        sync()
        val folder = java.nio.file.Files.createTempDirectory("trackhop-group").toFile()
        SharedGroups.create(env, "With friends", folder.path, "Trackhop", "Me", "nwa", PlaylistRef("nwa", "mix", "mix"))
        val text = com.xlollx.songport.data.ConfigBackup.export(env, "test")
        val snapshot = com.xlollx.songport.data.ConfigBackup.read(text)
        assertEquals(listOf(group.id), snapshot.groups.map { it.id })
        assertEquals(5, snapshot.jobs.size)

        val other = ContractEnv()
        com.xlollx.songport.data.ConfigBackup.import(other, snapshot, replacesOther = true)
        val there = Nway.groups(other).single()
        assertEquals(group.id to "Road trip", there.id to there.name)
        // A key of its own: the old one stayed on the first device.
        assertFalse(there.me == group.me)
        assertEquals(listOf(true, true, false), Nway.members(other, there).map { it.main })
        assertTrue(Nway.members(other, there)[2].keepMine)
        // The first run there fills the hub again from the main members and changes no playlist.
        val engineThere = SyncEngine(other)
        fun syncThere() = runBlocking { Nway.run(other, engineThere, there) }.onEach { assertTrue("${it.jobName}: ${it.error}", it.ok) }
        val first = syncThere()
        assertEquals(0, first.sumOf { it.removed })
        assertEquals(listOf(1, 2, 3, 4, 5), songs(a))
        assertEquals(listOf(1, 2, 3, 4, 5), songs(b))
        assertEquals(listOf(1, 2, 3, 4, 5, 12), songs(c))
        // And it carries on: an addition and a removal reach everyone, the follower's own song stays.
        add(b, 7); remove(a, 1)
        syncThere()
        assertEquals(listOf(2, 3, 4, 5, 7), songs(a))
        assertEquals(listOf(2, 3, 4, 5, 7), songs(b))
        assertEquals(listOf(2, 3, 4, 5, 7, 12), songs(c))
        // Brought in again where the group already is: it stays the one it was.
        com.xlollx.songport.data.ConfigBackup.import(other, snapshot, replacesOther = true)
        assertEquals(there.me, Nway.groups(other).single().me)
        folder.deleteRecursively()
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

    /**
     * A playlist added to a paused group (2.1.2): it stays paused and runs when the group does, its days,
     * time, saved schedule and Wi-Fi only included; 2.1.1 gave it "Every 6 hours", on.
     */
    @Test fun aMemberAddedToAPausedGroupStaysPausedAndKeepsTheTiming() {
        group = Nway.create(env, "Road trip", listOf(ref(a) to true, ref(b) to false))
        val sat = com.xlollx.songport.model.SavedSchedule("sat", "Saturday", 10080, "07:30", listOf(6))
        env.store.update { d -> com.xlollx.songport.sync.Schedules.save(d, sat) }
        env.store.update { d ->
            d.copy(jobs = d.jobs.map { j -> if (SharedGroups.sharedSide(j) == group.id) com.xlollx.songport.sync.Schedules.link(j, sat).copy(enabled = false, wifiOnly = true) else j })
        }
        Nway.add(env, group, ref(c), main = true)
        val added = Nway.members(env, group).first { it.playlist.provider == c.id }
        for (j in listOfNotNull(added.down, added.up)) {
            assertFalse("${j.name} paused as the group is", j.enabled)
            assertTrue(j.wifiOnly)
            assertEquals("sat", j.scheduleId)
            assertEquals(listOf(6), j.days)
            assertEquals("07:30", j.at)
            assertEquals(10080, j.everyMinutes)
        }
    }

    /** "Sync now" on a paused group runs all of it, in the group's order (2.1.2); its schedule still waits. */
    @Test fun syncNowRunsAPausedGroupWholeAndScheduledRunsSkipIt() {
        group = Nway.create(env, "Road trip", listOf(ref(a) to true, ref(b) to false, ref(c) to true))
        env.store.update { d -> d.copy(jobs = d.jobs.map { if (SharedGroups.sharedSide(it) == group.id) it.copy(enabled = false) else it }) }
        val all = Nway.order(env, group, manual = true).map { it.id }
        assertEquals("every sync: each main member's way up, then the hub into each", 5, all.size)
        assertEquals(Nway.members(env, group).mapNotNull { it.up?.id }, all.take(2))
        assertTrue("left to its schedule, nothing runs", Nway.order(env, group).isEmpty())
        assertTrue(Nway.jobsOf(env.store.data, group.id).none { com.xlollx.songport.sync.Timing.scheduled(it) })
        val reports = runBlocking { Nway.run(env, engine, group) }
        assertEquals(5, reports.size)
        reports.forEach { assertTrue(it.error, it.ok) }
        everywhere(1, 2, 3, 4, 5)
        assertTrue("still paused", Nway.jobsOf(env.store.data, group.id).none { it.enabled })
        // Unattended (a schedule): nothing.
        assertTrue(runBlocking { Nway.run(env, engine, group, unattended = true) }.isEmpty())
    }

    /** A delete the store could not save (2.1.2): the group stays whole, its key and list too, and it says so; tried again, it goes. */
    @Test fun aDeleteThatCannotBeSavedKeepsTheGroupItsKeyAndItsList() {
        create()
        val before = env.store.data
        val key = env.tokens.ids().filter { it.startsWith("shared:") }
        assertTrue("the group has a key", key.isNotEmpty())
        val events = SharedGroups.load(env, group.id).events
        env.store.failWrites = true
        val gone = runCatching { Nway.delete(env, group) }
        env.store.failWrites = false
        assertTrue("the failure is said", gone.isFailure)
        assertEquals(before.groups, env.store.data.groups)
        assertEquals(before.jobs, env.store.data.jobs)
        assertEquals("its key kept", key, env.tokens.ids().filter { it.startsWith("shared:") })
        assertEquals("its list kept", events, SharedGroups.load(env, group.id).events)
        // Still a working group.
        add(a, 7)
        sync()
        everywhere(1, 2, 3, 4, 5, 7)
        assertEquals(5, Nway.delete(env, group).size)
        assertTrue(env.store.data.groups.isEmpty() && env.store.data.jobs.isEmpty())
        assertTrue(env.tokens.ids().none { it.startsWith("shared:") })
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

    // ------------------------------------------------------------ the card's sync (the source's)

    private fun member(s: MemoryService) = Nway.members(env, group).first { it.playlist.provider == s.id }
    private fun plain(id: String) = SyncJob(id, id, PlaylistRef("nwa", "other-$id", id), PlaylistRef("nwb", "other-$id", id))
    private fun look(j: SyncJob) = listOf(j.color, j.label, j.tags, j.accentStyle, j.routeGlow, j.favorite)
    private val bare = look(SyncJob("x", "x", PlaylistRef("nwa", "p"), PlaylistRef("nwb", "p")))
    private fun now() = env.store.data
    private fun theGroup() = now().groups.single()

    /** The group between two other syncs, its card (the sync of its source, "a") coloured, styled, tagged and starred. Answers how the card looks. */
    private fun styled(): List<Any?> {
        env.store.update { d ->
            d.copy(jobs = listOf(plain("before")) + d.jobs.map {
                if (it.id == group.jobId) it.copy(color = "teal", accentStyle = "pill", routeGlow = false, favorite = true, tags = mapOf("genre" to "metal")) else it
            } + plain("after"))
        }
        return look(env.store.job(group.jobId)!!).also { assertTrue("the card has a look of its own", it != bare) }
    }

    @Test fun theSyncOfTheSourceStandsForTheGroup() {
        create(bMain = true)
        assertEquals("nwa", Nway.source(group, Nway.members(env, group))!!.playlist.provider)
        assertEquals(group.jobId, Nway.anchor(now(), group)!!.id)
        assertEquals(mapOf(group.jobId to group.id), Nway.anchors(now()).mapValues { it.value.id })
        assertEquals(group.id, Nway.anchored(now(), env.store.job(group.jobId)!!)!!.id)
        // No other sync of the group does, nor a sync of its own.
        assertEquals(null, Nway.anchored(now(), member(b).down))
        assertEquals(null, Nway.anchored(now(), member(a).up!!))
        assertEquals(null, Nway.anchored(now(), plain("x")))
    }

    @Test fun aTargetMadeTheSourceTakesTheCardItsLookAndItsPlace() {
        create(bMain = false)
        val card = styled()
        val at = now().jobs.indexOfFirst { it.id == group.jobId }
        val old = group.jobId
        Nway.setSource(env, group, member(c))
        val c2 = member(c)
        assertEquals(c2.down.id, theGroup().jobId)
        assertEquals(c2.up!!.id, theGroup().twinId)
        assertEquals("the card's colour, style, tags and star follow it", card, look(c2.down))
        assertEquals("the sync that was the card keeps none of it", bare, look(env.store.job(old)!!))
        assertEquals("in the same place of the list", at, now().jobs.indexOfFirst { it.id == c2.down.id })
        assertEquals(c2.down.id, Nway.anchor(now(), theGroup())!!.id)
        // The source it took the place of still sends its changes: it is a two-way target until the user says otherwise.
        assertTrue(member(a).main && c2.main)
        assertEquals(listOf("before", "after"), now().jobs.filter { SharedGroups.sharedSide(it) == null }.map { it.id })
        assertTrue(now().jobs.first().id == "before" && now().jobs.last().id == "after")
        // And the group still syncs.
        add(c, 6); sync(); everywhere(1, 2, 3, 4, 5, 6)
    }

    @Test fun theSourceTakenOutHandsTheCardToTheNextMainMember() {
        create(bMain = true)
        val card = styled()
        val at = now().jobs.indexOfFirst { it.id == group.jobId }
        val gone = Nway.remove(env, group, member(a))
        assertEquals(2, gone.size)
        assertTrue(gone.none { id -> now().jobs.any { it.id == id } })
        val b2 = member(b)
        assertEquals(b2.down.id, theGroup().jobId)
        assertEquals(b2.up!!.id, theGroup().twinId)
        assertEquals(card, look(b2.down))
        assertEquals(at, now().jobs.indexOfFirst { it.id == b2.down.id })
        assertEquals(listOf("nwb", "nwc"), Nway.members(env, theGroup()).map { it.playlist.provider })
    }

    @Test fun theSourceMadeToFollowHandsTheCardOverToo() {
        create(bMain = true)
        val card = styled()
        Nway.setMain(env, group, member(a), main = false)
        assertEquals(member(b).down.id, theGroup().jobId)
        assertEquals(card, look(member(b).down))
        assertEquals(bare, look(member(a).down))
        assertFalse(member(a).main)
    }

    @Test fun aTargetTakenOutOrChangedLeavesTheCardWhereItIs() {
        create(bMain = true)
        val card = styled()
        Nway.setKeepMine(env, member(c), false)
        Nway.setMain(env, group, member(b), main = false)
        Nway.remove(env, group, member(c))
        assertEquals(group.jobId, theGroup().jobId)
        assertEquals(card, look(env.store.job(group.jobId)!!))
    }

    /** A group left by an older version with its first sync gone or no longer main: the first main member's sync stands for it. */
    @Test fun aGroupWhoseOwnSyncIsNoLongerMainStandsOnItsFirstMainMember() {
        create(bMain = true)
        env.store.update { d -> d.copy(groups = d.groups.map { it.copy(jobId = "gone", twinId = "gone-too") }) }
        assertEquals(member(a).down.id, Nway.anchor(now(), theGroup())!!.id)
        env.store.update { d -> Nway.settled(d, group.id) }
        assertEquals(member(a).down.id, theGroup().jobId)
        assertEquals(member(a).up!!.id, theGroup().twinId)
    }

    @Test fun aMemberComesAndGoesWithAllItsSyncsAsOneChange() {
        create(bMain = false, cMain = false)
        val before = now()
        // Not a change at all when it cannot be made: the only main member stays.
        assertTrue(runCatching { Nway.withoutMember(before, group.id, group.jobId) }.isFailure)
        assertTrue(runCatching { Nway.withRole(before, group.id, group.jobId, main = false) }.isFailure)
        assertTrue(runCatching { Nway.withMember(before, group, ref(b), main = false) }.isFailure)
        assertEquals(before.jobs, now().jobs)
        // A target becomes two-way and back: one sync more, one less, its own sync the same one.
        val down = member(b).down.id
        val two = Nway.withRole(before, group.id, down, main = true)
        assertEquals(before.jobs.size + 1, two.jobs.size)
        assertTrue(Nway.members(two.jobs, group.id).first { it.down.id == down }.main)
        val back = Nway.withRole(two, group.id, down, main = false, keepMine = false)
        assertEquals(before.jobs.map { it.id }, back.jobs.map { it.id })
        assertFalse(Nway.members(back.jobs, group.id).first { it.down.id == down }.keepMine)
        // A new member runs when the group does.
        val timed = before.copy(jobs = before.jobs.map { it.copy(everyMinutes = 180, wifiOnly = true, enabled = false) })
        val more = Nway.withMember(timed, group, PlaylistRef("nwc", "other", "other"), main = true, like = timed.jobs.first { it.id == group.jobId })
        assertTrue(more.jobs.takeLast(2).all { it.everyMinutes == 180 && it.wifiOnly && !it.enabled })
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
