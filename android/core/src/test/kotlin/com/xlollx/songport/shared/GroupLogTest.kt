package com.xlollx.songport.shared

import com.xlollx.songport.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The merge on its own: three members (Ana creates the group, Ben curates, Cleo follows). The same
 * events in any order, any number of times, give the same playlist; the most recent action wins;
 * a removal stays removed; and only what the creator's list lets count, counts.
 */
class GroupLogTest {
    private val gid = "0123456789abcdef"
    private val ana = Identity.create()
    private val ben = Identity.create()
    private val cleo = Identity.create()
    private val log = GroupLog(gid, ana.id)
    private val seqs = HashMap<String, Long>()

    private val roster = Roster(gid, 2, 2, listOf(Grant(ana.id), Grant(ben.id))).signed(ana)

    @Before fun fresh() = SigCache.clear()

    private fun track(n: String, isrc: String? = null) = Track(id = "x$n", title = "Song $n", artists = listOf("Band"), durationMs = 200_000, isrc = isrc)

    private fun ev(who: Identity, op: String, n: String, at: Long, group: String = gid): SharedEvent {
        val seq = (seqs[who.id] ?: 0) + 1
        seqs[who.id] = seq
        return SharedEvent.of(op, track(n), who.id, seq, at).signed(group, who)
    }

    private fun live(events: Collection<SharedEvent>, r: Roster = roster) =
        log.live(log.merge(r, emptyList(), events.asSequence())).map { it.title.removePrefix("Song ") }.sorted()

    @Test fun anyOrderAnyNumberOfTimesGivesTheSamePlaylist() {
        val events = listOf(
            ev(ana, "+", "1", 10), ev(ana, "+", "2", 11), ev(ben, "+", "3", 12), ev(ben, "-", "1", 13),
            ev(ana, "+", "4", 14), ev(ben, "-", "4", 15), ev(ana, "-", "2", 15), ev(ben, "+", "2", 16),
        )
        val expected = live(events)
        assertEquals(listOf("2", "3"), expected)
        val random = java.util.Random(3)
        repeat(30) {
            val shuffled = events.shuffled(random)
            assertEquals(expected, live(shuffled))
            // Twice over, and merged into an earlier merge: nothing changes.
            assertEquals(expected, live(shuffled + shuffled.take(3)))
            val first = log.merge(roster, emptyList(), shuffled.take(4).asSequence())
            assertEquals(expected, log.live(log.merge(roster, first, shuffled.asSequence())).map { it.title.removePrefix("Song ") }.sorted())
        }
    }

    @Test fun theMostRecentActionWins() {
        // Ana removes at 20, Ben adds at 21: it stays. The other way round: it goes.
        assertEquals(listOf("5"), live(listOf(ev(ana, "+", "5", 10), ev(ana, "-", "5", 20), ev(ben, "+", "5", 21))))
        assertEquals(emptyList<String>(), live(listOf(ev(ana, "+", "6", 10), ev(ben, "+", "6", 21), ev(ana, "-", "6", 22))))
        // At the same instant, every member decides the same way whatever the order.
        val a = ev(ana, "+", "7", 30)
        val b = ev(ben, "-", "7", 30)
        assertEquals(live(listOf(a, b)), live(listOf(b, a)))
    }

    @Test fun aRemovalStaysRemovedWhateverOldCopiesSay() {
        val add = ev(ana, "+", "8", 10)
        val remove = ev(ana, "-", "8", 20)
        val merged = log.merge(roster, emptyList(), sequenceOf(add, remove))
        assertEquals(listOf(SharedEvent.keyOf(track("8"))), log.tombstones(merged).map { it.key })
        // A stale copy of the log (a sync app's conflict copy) still lists the addition.
        assertTrue(log.live(log.merge(roster, merged, sequenceOf(add))).isEmpty())
        // Compacted: per track and author only the latest action is kept.
        assertEquals(1, merged.size)
        assertEquals("-", merged.single().op)
    }

    @Test fun aFollowersEventsCountForNobody() {
        val events = listOf(ev(ana, "+", "1", 10), ev(cleo, "+", "2", 11), ev(cleo, "-", "1", 12))
        assertEquals(listOf("1"), live(events))
    }

    @Test fun forgedEventsAreIgnored() {
        val good = ev(ana, "+", "1", 10)
        // Cleo writes an event in Ana's name, signed with her own key.
        val forged = SharedEvent.of("-", track("1"), cleo.id, 99, 20).signed(gid, cleo).copy(by = ana.id)
        // An event of Ana's with its title changed after signing.
        val tampered = ev(ana, "+", "2", 11).copy(title = "Something else")
        // Ana's event for another group, replayed here.
        val replayed = ev(ana, "+", "3", 12, group = "fedcba9876543210")
        val unsigned = SharedEvent.of("+", track("4"), ana.id, 50, 13)
        assertEquals(listOf("1"), live(listOf(good, forged, tampered, replayed, unsigned)))
    }

    @Test fun onlyTheCreatorsListCounts() {
        // Cleo signs a list that makes her a curator, with a higher version.
        val forged = Roster(gid, 9, 9, listOf(Grant(ana.id), Grant(cleo.id))).signed(cleo)
        // The real list with a grant slipped in after signing.
        val tampered = roster.copy(grants = roster.grants + Grant(cleo.id), version = 10)
        val otherGroup = Roster("fedcba9876543210", 11, 11, listOf(Grant(cleo.id))).signed(ana)
        val chosen = log.roster(listOf(roster, forged, tampered, otherGroup, null))
        assertEquals(roster, chosen)
        assertFalse(cleo.id in chosen.curators)
        // With nothing valid, the creator alone curates.
        assertEquals(setOf(ana.id), log.roster(listOf(forged)).curators)
        // A newer list from the creator wins.
        val newer = Roster(gid, 3, 3, listOf(Grant(ana.id))).signed(ana)
        assertEquals(newer, log.roster(listOf(roster, newer)))
    }

    @Test fun aCuratorMadeFollowerKeepsWhatTheyCurated() {
        val e1 = ev(ben, "+", "1", 10)
        val e2 = ev(ben, "+", "2", 11)
        val demoted = Roster(gid, 3, 3, listOf(Grant(ana.id), Grant(ben.id, untilSeq = e2.seq))).signed(ana)
        val e3 = ev(ben, "+", "3", 12)
        val e4 = ev(ben, "-", "1", 13)
        assertEquals(listOf("1", "2"), live(listOf(e1, e2, e3, e4), demoted))
        assertEquals(setOf(ana.id), demoted.curators)
    }

    @Test fun aFollowerMadeCuratorStartsFromThen() {
        val early = ev(cleo, "+", "1", 10)
        val promoted = Roster(gid, 3, 3, listOf(Grant(ana.id), Grant(cleo.id, afterSeq = early.seq))).signed(ana)
        val later = ev(cleo, "+", "2", 11)
        assertEquals(listOf("2"), live(listOf(early, later), promoted))
    }

    @Test fun keysPreferTheIsrcAndOtherwiseMeetByName() {
        assertEquals("isrc:USAB12345678", SharedEvent.keyOf(track("1", isrc = "usab12345678")))
        // Two members' copies of one song without an ISRC, ids apart, meet on one key.
        assertEquals(SharedEvent.keyOf(Track("c", "Song", listOf("The Band"))), SharedEvent.keyOf(Track("d", "song ", listOf("The Band"), album = "Other")))
        assertNotEquals(SharedEvent.keyOf(Track("c", "Song", listOf("The Band"))), SharedEvent.keyOf(Track("e", "Other song", listOf("The Band"))))
    }

    @Test fun invitesGoThroughALinkAndBack() {
        val inv = Invite(gid, "Road trip & co. 🚗", ana.id, "Trackhop group")
        val link = inv.link()
        assertTrue(link, link.startsWith("https://trackhop.org/join#"))
        assertEquals(inv, Invite.parse("Join my playlist: $link thanks"))
        assertEquals(inv, Invite.parse(link.replace("https://trackhop.org/join#", "songport://join?")))
        // Fits a QR code with room to spare.
        assertTrue(link.toByteArray().size < com.xlollx.songport.sync.QrCode.capacity(10))
        assertEquals(null, Invite.parse("https://trackhop.org/join#g=zz&k=${ana.id}&n=x"))
        assertEquals(null, Invite.parse("https://trackhop.org/join#g=$gid&k=short&n=x"))
        assertEquals(null, Invite.parse("https://trackhop.org/join#g=$gid&k=${ana.id}"))
    }

    @Test fun aMemberFileReadsBackAndATruncatedOneIsSkipped() {
        val card = MemberCard(gid, "", "Ana", "TIDAL", 5).signed(ana)
        val file = MemberFile(card = card, roster = roster, events = listOf(ev(ana, "+", "1", 10)))
        val text = MemberFile.write(file)
        assertEquals(file, MemberFile.parse(text))
        assertEquals(null, MemberFile.parse(text.take(text.length / 2)))
        assertTrue(card.verifies())
        assertFalse(card.copy(name = "Cleo").verifies())
    }
}
