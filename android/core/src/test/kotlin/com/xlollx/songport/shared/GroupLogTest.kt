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

    /**
     * Anyone can change an event and fix another field (here its duration) so the copy has the same
     * hash code as a valid event, then borrow that event's signature. The signature cache must not
     * take one for the other: it keys on the exact bytes checked.
     */
    @Test fun aBorrowedSignatureWithTheSameHashCodeIsRefused() {
        val keep = ev(ana, "+", "2", 10)
        val valid = ev(ben, "+", "1", 11)
        val changed = valid.copy(op = "-", seq = valid.seq + 50, at = 1_000_000, key = keep.key, title = keep.title, durationMs = 0)
        val inv31 = java.math.BigInteger.valueOf(31).modInverse(java.math.BigInteger.ONE.shiftLeft(32)).toInt()
        val d = (valid.hashCode() - changed.hashCode()) * inv31
        val forged = changed.copy(durationMs = d.toLong() and 0xffffffffL)
        assertEquals(valid.hashCode(), forged.hashCode())
        assertEquals(valid.sig, forged.sig)
        // The valid event is checked first (in this run or an earlier one), then its twin comes along.
        assertEquals(listOf("1", "2"), live(listOf(keep, valid, forged)))
        assertFalse(SigCache.verified(gid, forged))
    }

    /**
     * A curator made follower still has their key: they could sign new events under their old
     * numbers (one with a smaller signature wins a tie on the number) and remove anything. The
     * creator's list pins the events that were theirs.
     */
    @Test fun aFormerCuratorCannotSignTheirOldNumbersOver() {
        val anas = ev(ana, "+", "3", 9)
        val e1 = ev(ben, "+", "1", 10)
        val e2 = ev(ben, "+", "2", 11)
        val demoted = Roster(gid, 3, 3, listOf(Grant(ana.id), Grant(ben.id, untilSeq = e2.seq, kept = listOf(e1, e2).map { it.sig.take(Grant.KEPT_CHARS) }))).signed(ana)
        // Ben's number 2 again, now taking Ana's song out, dated later; and his number 1 over, with any signature at all.
        var again = SharedEvent.of("-", track("3"), ben.id, e2.seq, 50).signed(gid, ben)
        var at = 50L
        while (again.sig >= e2.sig) again = SharedEvent.of("-", track("3"), ben.id, e2.seq, ++at).signed(gid, ben)
        val over = SharedEvent.of("-", track("1"), ben.id, e1.seq, 60).signed(gid, ben)
        assertEquals(listOf("1", "2", "3"), live(listOf(anas, e1, e2, again, over), demoted))
        assertEquals(listOf("1", "2", "3"), live(listOf(again, over, anas, e2, e1), demoted))
        // Without the pins (the list as it was), the rewrite went through.
        val unpinned = Roster(gid, 3, 3, listOf(Grant(ana.id), Grant(ben.id, untilSeq = e2.seq))).signed(ana)
        assertFalse("3" in live(listOf(anas, e1, e2, again), unpinned))
        // The pins are signed with the list.
        val tampered = demoted.copy(grants = listOf(Grant(ana.id), Grant(ben.id, untilSeq = e2.seq, kept = listOf(again.sig.take(Grant.KEPT_CHARS)))))
        assertFalse(tampered.verifies(ana.id))
    }

    /** An action dated far ahead would beat every later one for good: it waits until its time comes. */
    @Test fun anActionFarInTheFutureWaits() {
        val add = ev(ana, "+", "1", 10)
        val never = ev(ben, "-", "1", 90_000_000)
        val now = 1_000L
        assertEquals(listOf("1"), log.live(log.merge(roster, emptyList(), sequenceOf(add, never), latest = now)).map { it.title.removePrefix("Song ") })
        assertEquals(listOf(add), log.checked(roster, listOf(add, never), latest = now))
        // Once the clock gets there, it counts like any other; the end of time never does.
        assertEquals(listOf("1"), live(listOf(add, ev(ben, "-", "1", Long.MAX_VALUE))))
        assertTrue(log.live(log.merge(roster, emptyList(), sequenceOf(add, never))).isEmpty())
    }

    /** A file filled with events that claim a curator and fail the check is left out whole; a few bad ones only drop themselves. */
    @Test fun aFileFullOfBadSignaturesIsLeftOut() {
        val good = ev(ana, "+", "1", 10)
        val bad = (1..GroupLog.MAX_BAD + 1).map { n -> good.copy(seq = 1000L + n, key = "t:x|$n") }
        assertEquals(null, log.checked(roster, listOf(good) + bad))
        assertEquals(listOf(good), log.checked(roster, listOf(good) + bad.take(3)))
        // A follower's events are not even checked: they cannot count.
        assertEquals(listOf(good), log.checked(roster, listOf(good) + (1..200).map { ev(cleo, "+", "$it", 20) }))
    }

    /** Fields a crafted file could use: huge text, the separators the signed bytes use, half a surrogate pair. */
    @Test fun craftedFieldsAreRefused() {
        fun signedAs(e: SharedEvent) = e.signed(gid, ana)
        val base = SharedEvent(by = ana.id, seq = 77, at = 10, op = "+", key = "t:band|song", title = "Song", artists = listOf("Band"))
        assertTrue(signedAs(base).wellFormed())
        val crafted = listOf(
            base.copy(title = "x".repeat(SharedEvent.MAX_TITLE + 1)),
            base.copy(artists = listOf("a\u001fb")),
            base.copy(artists = (1..11).map { "a$it" }),
            base.copy(title = "Song \uD83C"),
            base.copy(isrc = "\u0000"),
            base.copy(isrc = ""),
            base.copy(op = "x"),
            base.copy(seq = 0),
            base.copy(key = " "),
        )
        for (c in crafted) assertFalse(c.toString(), signedAs(c).wellFormed())
        // Two artists and one artist with the separator inside sign the same bytes: only the first is ever written.
        assertTrue(base.copy(artists = listOf("a", "b")).signedBytes(gid).contentEquals(base.copy(artists = listOf("a\u001fb")).signedBytes(gid)))
        assertTrue(live(crafted.filter { it.key.isNotBlank() }.map { signedAs(it.copy(key = "t:k|${it.hashCode()}")) }).isEmpty())
        // What Trackhop signs is always plain, whatever the service sent.
        val fromService = SharedEvent.of("+", Track("z", "Line\nbreak \uD83C", listOf("A\u001fB"), album = "x".repeat(900), isrc = "usab12345678"), ana.id, 1, 1).signed(gid, ana)
        assertTrue(fromService.wellFormed())
        assertEquals("Line break ", fromService.title)
        // A list of curators or a card out of shape.
        val many = Roster(gid, 5, 5, (0..Roster.MAX_GRANTS).map { Grant(Identity.create().id) }).signed(ana)
        assertEquals(roster, log.roster(listOf(roster, many)))
        assertFalse(MemberCard(gid, "", "x".repeat(MemberCard.MAX_NAME + 1)).signed(ana).wellFormed())
        assertTrue(MemberCard(gid, "", "Ana 🎧").signed(ana).wellFormed())
        // A file nested a million deep in a field nobody reads: refused, no crash.
        assertEquals(null, MemberFile.parse("{\"x\":" + "[".repeat(1_000_000) + "]".repeat(1_000_000) + "}"))
    }

    /** A live take and the studio recording without ISRCs are two tracks: removing one never removes the other. A remaster is the same song. */
    @Test fun aLiveTakeAndTheStudioOneHaveTheirOwnKeys() {
        val studio = Track("a", "Song", listOf("Band"))
        assertNotEquals(SharedEvent.keyOf(studio), SharedEvent.keyOf(Track("b", "Song (Live at Wembley)", listOf("Band"))))
        assertNotEquals(SharedEvent.keyOf(studio), SharedEvent.keyOf(Track("c", "Song - Acoustic", listOf("Band"))))
        assertEquals(SharedEvent.keyOf(studio), SharedEvent.keyOf(Track("d", "Song (2011 Remaster)", listOf("Band"))))
        assertEquals(SharedEvent.keyOf(Track("e", "Song - Live", listOf("Band"))), SharedEvent.keyOf(Track("f", "Song (Live)", listOf("Band"))))
    }

    /** An invite whose key is no usable point, or for a way of talking this Trackhop does not have, is no invite; the group code is short and stable. */
    @Test fun invitesRefuseUnusableKeysAndCarryAGroupCode() {
        val neutral = B64.encode(ByteArray(32).also { it[0] = 1 })
        assertEquals(null, Invite.parse("https://trackhop.org/join#g=$gid&k=$neutral&n=x"))
        assertEquals(null, Invite.parse("https://trackhop.org/join#g=$gid&k=${ana.id}&n=x&t=relay"))
        val inv = Invite.parse("https://trackhop.org/join#g=$gid&k=${ana.id}&n=Road%0Atrip%07")!!
        assertEquals("Road trip", inv.name)
        assertTrue(inv.code, Regex("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){3}").matches(inv.code))
        // 100 bits of SHA-256("abc") = ba7816bf8f01cfea41..., in base 32 (Crockford) — the same as docs/join.html computes.
        assertEquals("Q9W1D-FWF07-7YMGA-183F5", Invite.codeOf("abc"))
        assertEquals(inv.code, Invite(gid, "Other", ana.id).code)
        assertNotEquals(inv.code, Invite(gid, "Road trip", ben.id).code)
    }

    /** A folder someone filled with files: the known members' files are read first, and no more than the bounds. */
    @Test fun aFloodedFolderIsReadWithinBounds() {
        val dir = java.nio.file.Files.createTempDirectory("trackhop-flood").toFile()
        try {
            val folder = DirFolder(dir)
            val real = MemberFile(card = MemberCard(gid, "", "Ana").signed(ana), events = listOf(ev(ana, "+", "1", 10)))
            FolderTransport.write(folder, gid, ana.id, real)
            val junk = MemberFile.write(MemberFile(card = MemberCard(gid, cleo.id, "x")))
            for (n in 0 until FolderTransport.MAX_FILES + 20) java.io.File(dir, FolderTransport.prefix(gid) + "0000-$n.json").writeText(junk)
            val tooMany = MemberFile(card = MemberCard(gid, cleo.id, "x"),
                events = (1..FolderTransport.MAX_EVENTS + 1).map { SharedEvent(by = cleo.id, seq = it.toLong(), at = 1, op = "+", key = "t:$it") })
            java.io.File(dir, FolderTransport.prefix(gid) + "0000-+big.json").writeText(MemberFile.write(tooMany))
            val read = FolderTransport.readAll(folder, gid, setOf(FolderTransport.fileName(gid, ana.id)))
            assertEquals(FolderTransport.MAX_FILES - 1, read.size)
            assertEquals(real, read.first())
            assertTrue(read.none { it.events.size > FolderTransport.MAX_EVENTS })
        } finally {
            dir.deleteRecursively()
        }
    }
}
