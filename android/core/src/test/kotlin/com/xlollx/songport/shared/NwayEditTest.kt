package com.xlollx.songport.shared

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SavedSchedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.NwayEdit.Mode
import com.xlollx.songport.sync.MemoryService
import com.xlollx.songport.sync.Schedules
import com.xlollx.songport.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The multi-sync editor's reading of a group ([NwayEdit]): every group made before 2.1.1 has its
 * place in it (source, two-way, keeps its additions, exact copy), and saving a draft is one change
 * of the store that touches only what the draft changed.
 */
class NwayEditTest {
    private lateinit var env: ContractEnv
    private val a = MemoryService("nea")
    private val b = MemoryService("neb")
    private val c = MemoryService("nec")
    private val d = MemoryService("ned")
    private fun ref(s: MemoryService, list: String = "mix") = PlaylistRef(s.id, list, list)
    private fun item(s: MemoryService, mode: Mode = Mode.KEEP, list: String = "mix") = NwayEdit.Item(s.id, list, list, mode = mode)
    private fun now() = env.store.data
    private fun group() = now().groups.single()
    private fun members() = Nway.members(env, group())
    private fun draft() = NwayEdit.draftOf(group(), members())
    private fun songs(s: MemoryService) = s.lists["mix"]!!.map { it.title.removePrefix("Song ").toInt() }.sorted()

    @Before fun setUp() {
        Features.build = true
        SigCache.clear()
        env = ContractEnv()
        for (s in listOf(a, b, c, d)) {
            s.catalogue += (1..20).map { Track("${s.id}$it", "Song $it", listOf("Band"), "Album", 200_000, isrc = "QZABC26" + it.toString().padStart(5, '0')) }
            s.lists["mix"] = ArrayList()
        }
        a.lists["mix"] = (1..5).map { n -> a.catalogue.first { it.title == "Song $n" } }.toMutableList()
        Providers.testDoubles = listOf(a, b, c, d)
    }

    @After fun tearDown() {
        Providers.testDoubles = emptyList()
        Features.build = false
    }

    /** As the screen before 2.1.1 made it: "a" and "c" main, "b" follows keeping its additions, "d" an exact copy. */
    private fun old(): SharedGroup {
        val g = Nway.create(env, "Road trip", listOf(ref(a) to true, ref(b) to false, ref(c) to true, ref(d) to false))
        Nway.setKeepMine(env, Nway.members(env, g).first { it.playlist.provider == d.id }, false)
        return g
    }

    // ------------------------------------------------------------ how a group made before reads

    @Test fun everyGroupMadeBeforeHasItsPlace() {
        old()
        val draft = draft()
        assertEquals("Road trip", draft.name)
        assertEquals("the first main member is the source", a.id, draft.source!!.provider)
        assertEquals(listOf(b.id to Mode.KEEP, c.id to Mode.TWO_WAY, d.id to Mode.EXACT), draft.targets.map { it.provider to it.mode })
        assertTrue("each is the member's own sync", draft.items.all { it.downId != null && it.playlistId == "mix" })
        assertNull(NwayEdit.missing(draft))
    }

    @Test fun theSourceIsTheGroupsOwnSyncWhileItIsMainElseTheFirstMain() {
        val g = old()
        // The card was moved to "c": it is the source, and "a" a two-way target.
        Nway.setSource(env, g, members().first { it.playlist.provider == c.id })
        assertEquals(c.id, draft().source!!.provider)
        assertEquals(listOf(a.id to Mode.TWO_WAY, b.id to Mode.KEEP, d.id to Mode.EXACT), draft().targets.map { it.provider to it.mode })
        // Its own sync no longer main (an older version's doing): the first main member.
        env.store.update { x -> x.copy(groups = x.groups.map { it.copy(jobId = members().first { m -> m.playlist.provider == b.id }.down.id) }) }
        assertEquals(members().first { it.main }.playlist.provider, draft().source!!.provider)
        assertTrue(draft().targets.none { it.provider == draft().source!!.provider })
    }

    // ------------------------------------------------------------ saving

    @Test fun aDraftSavedUntouchedChangesNothing() {
        val g = old()
        val before = now()
        assertSame(before, NwayEdit.applied(before, g.id, draft()))
        assertEquals(emptyList<NwayEdit.Change>(), NwayEdit.changes(group(), members(), draft()))
        assertTrue(env.store.commit { NwayEdit.applied(it, g.id, draft()) })
        assertSame("the store was not written", before, now())
    }

    @Test fun onlyWhatTheDraftChangesIsChanged() {
        val g = old()
        val before = now()
        val was = draft()
        // "b" becomes an exact copy; the rest as it is.
        val next = was.copy(targets = was.targets.map { if (it.provider == b.id) it.copy(mode = Mode.EXACT) else it })
        assertEquals(listOf(NwayEdit.Change.Kind.MODE), NwayEdit.changes(group(), members(), next).map { it.kind })
        val after = NwayEdit.applied(before, g.id, next)
        val changed = after.jobs.filter { j -> before.jobs.none { it == j } }
        assertEquals("one sync changed", listOf(members().first { it.playlist.provider == b.id }.down.id), changed.map { it.id })
        assertEquals(before.jobs.map { it.id }, after.jobs.map { it.id })
        assertEquals(before.groups, after.groups)
    }

    @Test fun targetsComeGoAndChangeTheirWayAsOneChange() {
        val g = old()
        val was = draft()
        val extra = NwayEdit.Item(b.id, "other", "other", mode = Mode.TWO_WAY)
        b.lists["other"] = ArrayList()
        // "d" out, "c" keeps its additions from now on, another playlist of "b" in as two-way.
        val next = was.copy(targets = was.targets.filter { it.provider != d.id }.map { if (it.provider == c.id) it.copy(mode = Mode.KEEP) else it } + extra)
        assertEquals(
            setOf(NwayEdit.Change.Kind.MODE to c.id, NwayEdit.Change.Kind.ADDED to b.id, NwayEdit.Change.Kind.REMOVED to d.id),
            NwayEdit.changes(group(), members(), next).map { it.kind to it.item.provider }.toSet(),
        )
        assertTrue(env.store.commit { NwayEdit.applied(it, g.id, next) })
        assertEquals(listOf(a.id to "mix", b.id to "mix", c.id to "mix", b.id to "other"), members().map { it.playlist.provider to it.playlist.playlistId })
        assertEquals(listOf(Mode.TWO_WAY, Mode.KEEP, Mode.KEEP, Mode.TWO_WAY), members().map { NwayEdit.modeOf(it) })
        assertTrue("no sync of the one taken out is left", now().jobs.none { it.source.provider == d.id || it.target.provider == d.id })
        // Read again, it is what was saved.
        assertEquals(next.targets.map { Triple(it.provider, it.playlistId, it.mode) }, draft().targets.map { Triple(it.provider, it.playlistId, it.mode) })
        assertEquals(group().jobId, members().first().down.id)
    }

    @Test fun aTargetTakenOutAndPutBackKeepsItsSync() {
        val g = old()
        val was = draft()
        val dId = members().first { it.playlist.provider == d.id }.down.id
        // Removed, then the same playlist added again: no sync id in the draft.
        val next = was.copy(targets = was.targets.map { if (it.provider == d.id) it.copy(downId = null) else it })
        assertTrue(env.store.commit { NwayEdit.applied(it, g.id, next) })
        assertEquals(dId, members().first { it.playlist.provider == d.id }.down.id)
        assertEquals(4, members().size)
    }

    @Test fun aGroupFromBeforeNamingAFollowerKeepsTheCardsLook() {
        val g = old()
        val src = members().first { it.playlist.provider == a.id }.down.id
        val follower = members().first { it.playlist.provider == b.id }.down.id
        // 2.1.0 left the group naming a follower; the card stands on "a", which the user styled.
        env.store.update { x -> x.copy(groups = x.groups.map { it.copy(jobId = follower) }, jobs = x.jobs.map { if (it.id == src) it.copy(color = "teal", favorite = true) else it }) }
        Nway.remove(env, group(), members().first { it.playlist.provider == d.id })
        val card = now().jobs.first { it.id == src }
        assertEquals("teal", card.color)
        assertTrue(card.favorite)
        assertEquals(src, group().jobId)
    }

    @Test fun makeItTheSourceSwapsTheTwoAndTheOldSourceGoesOnSendingItsChanges() {
        val g = old()
        env.store.update { x -> x.copy(jobs = x.jobs.map { if (it.id == g.jobId) it.copy(color = "teal", favorite = true) else it }) }
        val next = draft().withSource(0)
        assertEquals(b.id, next.source!!.provider)
        assertEquals(listOf(a.id to Mode.TWO_WAY, c.id to Mode.TWO_WAY, d.id to Mode.EXACT), next.targets.map { it.provider to it.mode })
        assertEquals(listOf(NwayEdit.Change.Kind.SOURCE), NwayEdit.changes(group(), members(), next).map { it.kind })
        assertTrue(env.store.commit { NwayEdit.applied(it, g.id, next) })
        val src = members().first { it.playlist.provider == b.id }
        assertTrue("the new source is a main member", src.main)
        assertEquals(src.down.id, group().jobId)
        assertEquals("the card's look moved with it", "teal" to true, src.down.color to src.down.favorite)
        assertEquals(b.id, draft().source!!.provider)
        // And it still syncs: a song added on the old source reaches everyone.
        val engine = SyncEngine(env)
        runBlocking { Nway.run(env, engine, group()) }.forEach { assertTrue(it.error, it.ok) }
        a.lists["mix"]!! += a.catalogue.first { it.title == "Song 9" }
        runBlocking { Nway.run(env, engine, group()) }.forEach { assertTrue(it.error, it.ok) }
        for (s in listOf(a, b, c, d)) assertEquals(s.id, listOf(1, 2, 3, 4, 5, 9), songs(s))
    }

    @Test fun theSourceReplacedOrDemotedIsOneChangeToo() {
        val g = old()
        val was = draft()
        // The source is now "c" and "a" leaves the sync altogether.
        val next = was.withSource(1).let { it.copy(targets = it.targets.filter { t -> t.provider != a.id }) }
        assertTrue(env.store.commit { NwayEdit.applied(it, g.id, next) })
        assertEquals(listOf(c.id, b.id, d.id), listOf(draft().source!!.provider) + draft().targets.map { it.provider })
        assertEquals(members().first { it.playlist.provider == c.id }.down.id, group().jobId)
        // The only two-way target made the source's exact copy while the source goes: refused whole, nothing written.
        val before = now()
        val bad = draft().copy(source = null)
        assertTrue(runCatching { env.store.commit { NwayEdit.applied(it, g.id, bad) } }.isFailure)
        assertSame(before, now())
    }

    @Test fun theNameTheTimingAndTheLookAreSavedWithIt() {
        val g = old()
        val next = draft().copy(name = "Metal & More")
        assertTrue(env.store.commit { x -> NwayEdit.applied(x, g.id, next, timed = { it.copy(everyMinutes = 180, wifiOnly = true) }, styled = { it.copy(color = "teal") }) })
        assertEquals("Metal & More", group().name)
        val jobs = Nway.jobsOf(now(), g.id)
        assertTrue("every sync of the group runs when it does", jobs.all { it.everyMinutes == 180 && it.wifiOnly })
        assertEquals("the look is the card's alone", listOf(g.jobId), jobs.filter { it.color == "teal" }.map { it.id })
        assertTrue(jobs.all { it.name.startsWith("Metal & More · ") })
        assertTrue(jobs.all { (if (it.source.provider == SharedProvider.ID) it.source else it.target).playlistName == "Metal & More" })
    }

    /**
     * The group's page, "Automatic schedule" (2.1.2): a timing by hand or a saved schedule reaches every
     * sync of the group as one change, with its days, its time and its link; 2.1.1 kept only "Weekly".
     */
    @Test fun aTimedOrLinkedScheduleReachesEverySyncWithItsDayTimeAndLink() {
        val g = old()
        env.store.update { x -> x.copy(jobs = x.jobs + SyncJob("other", "Other", ref(a, "x"), ref(b, "y"))) }
        val other = now().jobs.single { it.id == "other" }
        // Paused, with Wi-Fi only: neither is the schedule's to change.
        env.store.update { x -> x.copy(jobs = x.jobs.map { if (it.id == "other") it else it.copy(enabled = false, wifiOnly = true) }) }
        val members = Nway.jobsOf(now(), g.id).map { it.id }.toSet()
        assertEquals("both halves of each main member and each follower", 6, members.size)

        // By hand: every Saturday at 07:30.
        assertTrue(env.store.commit { NwayEdit.scheduled(it, g.id, null, Schedules.spec(10080, "07:30", listOf(6))) })
        for (j in Nway.jobsOf(now(), g.id)) {
            assertEquals(j.id, listOf(6), j.days); assertEquals(j.id, "07:30", j.at); assertEquals(j.id, 10080, j.everyMinutes)
            assertNull(j.scheduleId); assertFalse(j.enabled); assertTrue(j.wifiOnly)
        }
        assertEquals("Saturday · 07:30", Schedules.spec(10080, "07:30", listOf(6)), Schedules.specOf(Nway.anchor(now(), g)!!))

        // Linked to a saved schedule: Mon and Thu at 06:45, the link kept by the store.
        val early = SavedSchedule("early", "Early", 1440, "06:45", listOf(1, 4))
        assertTrue(env.store.commit { Schedules.save(it, early) })
        assertTrue(env.store.commit { NwayEdit.scheduled(it, g.id, "early", Schedules.spec(60, null, emptyList())) })
        for (j in Nway.jobsOf(now(), g.id)) {
            assertEquals(j.id, "early", j.scheduleId); assertEquals(j.id, listOf(1, 4), j.days); assertEquals(j.id, "06:45", j.at)
        }
        assertEquals(now().jobs.single { it.id == "other" }, other)
        // The schedule edited later: the whole group follows it, still linked.
        assertTrue(env.store.commit { Schedules.save(it, early.copy(at = "05:15", days = listOf(6))) })
        assertTrue(Nway.jobsOf(now(), g.id).all { it.scheduleId == "early" && it.days == listOf(6) && it.at == "05:15" })

        // Set by hand again (Detach): the link goes, the timing stays as given.
        assertTrue(env.store.commit { NwayEdit.scheduled(it, g.id, null, Schedules.spec(360, null, emptyList())) })
        assertTrue(Nway.jobsOf(now(), g.id).all { it.scheduleId == null && it.everyMinutes == 360 && it.days.isEmpty() })
        assertTrue(Schedules.usedBy(now(), "early").isEmpty())
    }

    /** The store refuses the change: not one sync of the group is changed. */
    @Test fun aScheduleThatCannotBeSavedChangesNoSync() {
        val g = old()
        val before = now()
        env.store.failWrites = true
        val saved = env.store.commit { NwayEdit.scheduled(it, g.id, null, Schedules.spec(10080, "07:30", listOf(6))) }
        env.store.failWrites = false
        assertFalse(saved)
        assertSame(before, now())
    }

    @Test fun aDraftThatCannotBeSavedSaysWhy() {
        assertEquals(NwayEdit.Missing.SOURCE, NwayEdit.missing(NwayEdit.Draft("x", null, listOf(item(b)))))
        assertEquals(NwayEdit.Missing.NEW_SOURCE, NwayEdit.missing(NwayEdit.Draft("x", NwayEdit.Item(a.id, null, ""), listOf(item(b)))))
        assertEquals(NwayEdit.Missing.TARGET, NwayEdit.missing(NwayEdit.Draft("x", item(a), emptyList())))
        assertEquals(NwayEdit.Missing.TWICE, NwayEdit.missing(NwayEdit.Draft("x", item(a), listOf(item(b), item(b, Mode.EXACT)))))
        // Two new playlists on one service are two playlists.
        assertNull(NwayEdit.missing(NwayEdit.Draft("x", item(a), listOf(NwayEdit.Item(b.id, null, ""), NwayEdit.Item(b.id, null, "")))))
    }

    // ------------------------------------------------------------ a new one

    @Test fun aNewGroupIsMadeWholeWithEachTargetsWay() {
        val g = NwayEdit.create(env, NwayEdit.Draft("Trip", item(a), listOf(item(b, Mode.EXACT), item(c, Mode.TWO_WAY), item(d))), timed = { it.copy(everyMinutes = 60) }, styled = { it.copy(favorite = true) })
        assertEquals(listOf(g.id), now().groups.map { it.id })
        assertEquals(a.id, draft().source!!.provider)
        assertEquals(listOf(b.id to Mode.EXACT, c.id to Mode.TWO_WAY, d.id to Mode.KEEP), draft().targets.map { it.provider to it.mode })
        assertTrue(Nway.jobsOf(now(), g.id).all { it.everyMinutes == 60 })
        assertEquals(listOf(g.jobId), now().jobs.filter { it.favorite }.map { it.id })
        assertEquals(g.jobId, Nway.anchor(now(), g)!!.id)
        runBlocking { Nway.run(env, SyncEngine(env), g) }.forEach { assertTrue(it.error, it.ok) }
        for (s in listOf(a, b, c, d)) assertEquals(s.id, listOf(1, 2, 3, 4, 5), songs(s))
    }

    @Test fun aNewGroupThatCannotBeWrittenLeavesNothingBehind() {
        env.store.failWrites = true
        val made = runCatching { NwayEdit.create(env, NwayEdit.Draft("Trip", item(a), listOf(item(b)))) }
        env.store.failWrites = false
        assertTrue(made.isFailure)
        assertTrue("no group, no sync", now().groups.isEmpty() && now().jobs.isEmpty())
        assertTrue("nor its key", env.tokens.ids().none { it.startsWith("shared:") })
    }
}
