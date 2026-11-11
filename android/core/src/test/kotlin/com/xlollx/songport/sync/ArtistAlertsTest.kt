package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.data.Settings
import com.xlollx.songport.sync.ArtistAlerts.Followed
import com.xlollx.songport.sync.ArtistAlerts.Found
import com.xlollx.songport.sync.ArtistAlerts.Kind
import com.xlollx.songport.sync.ArtistAlerts.Kinds
import com.xlollx.songport.sync.ArtistAlerts.News
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistAlertsTest {
    private fun rel(artist: String, id: String, type: String = "SINGLE") = News(artist, Kind.RELEASE, id, id, type)
    private fun gig(artist: String, id: String, city: String) = News(artist, Kind.CONCERT, id, "", city)

    /** A lookup answering from [answers] by artist name, counting the calls. */
    private class Fake(var answers: Map<String, Found>) : ArtistAlerts.Lookup {
        val asked = ArrayList<String>()
        override suspend fun look(env: com.xlollx.songport.platform.Env, artist: Followed): Found {
            asked += artist.name; return answers[artist.name] ?: Found()
        }
    }

    private var clock = 1_000_000_000L
    private fun check(env: ContractEnv, followed: List<Followed>, fake: Fake, budget: Long = ArtistAlerts.BUDGET_MS) =
        runBlocking { ArtistAlerts.check(env, followed, 1, fake, budget, gapMs = 0, now = { clock }) }

    @Test fun diffFindsOnlyWhatWasNeverSeen() {
        val (ids, news) = ArtistAlerts.diff(listOf("a", "b"), listOf(rel("X", "b"), rel("X", "c"), rel("X", "c")))
        assertEquals(listOf("c"), news.map { it.id })
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test fun theFirstLookOnlyTakesNote() {
        val (ids, news) = ArtistAlerts.diff(null, listOf(rel("X", "a"), rel("X", "b")))
        assertTrue(news.isEmpty())
        assertEquals(listOf("a", "b"), ids)
    }

    @Test fun noAnswerChangesNothing() {
        assertEquals(listOf("a") to emptyList<News>(), ArtistAlerts.diff(listOf("a"), null))
        assertEquals(null to emptyList<News>(), ArtistAlerts.diff(null, null))
    }

    @Test fun somethingGoneAndBackIsNotNewAgain() {
        val (ids, _) = ArtistAlerts.diff(listOf("a", "b"), listOf(rel("X", "b")))
        assertEquals(listOf("a", "b"), ids)
        assertTrue(ArtistAlerts.diff(ids, listOf(rel("X", "a"))).second.isEmpty())
    }

    @Test fun theNewestIdsAreKept() {
        val (ids, _) = ArtistAlerts.diff((1..5).map { "$it" }, listOf(rel("X", "6")), cap = 3)
        assertEquals(listOf("4", "5", "6"), ids)
    }

    @Test fun baselineOnFollowThenOnlyNewsAreSaid() {
        val env = ContractEnv()
        val fake = Fake(mapOf("ADTR" to Found(releases = listOf(rel("ADTR", "old")))))
        val followed = listOf(Followed("ADTR"))
        val first = check(env, followed, fake)
        assertTrue("no flood on the first look", first.notify.isEmpty())
        assertEquals(0, first.remaining)

        // The next day, nothing new: nothing said.
        clock += ArtistAlerts.DAY_MS
        assertTrue(check(env, followed, fake).notify.isEmpty())

        clock += ArtistAlerts.DAY_MS
        fake.answers = mapOf("ADTR" to Found(releases = listOf(rel("ADTR", "old"), rel("ADTR", "new"))))
        assertEquals(listOf("new"), check(env, followed, fake).notify.map { it.id })
        assertEquals(listOf("new"), ArtistAlerts.latest(env).map { it.id })
    }

    @Test fun anArtistLookedUpTodayIsNotAskedAgain() {
        val env = ContractEnv()
        val fake = Fake(emptyMap())
        check(env, listOf(Followed("A")), fake)
        clock += 3_600_000
        check(env, listOf(Followed("A")), fake)
        assertEquals(listOf("A"), fake.asked)
    }

    @Test fun aKindTurnedOffAndOnStartsOverWithoutAFlood() {
        val env = ContractEnv()
        val fake = Fake(mapOf("A" to Found(concerts = listOf(gig("A", "1", "Milan")))))
        check(env, listOf(Followed("A")), fake)
        clock += ArtistAlerts.DAY_MS
        check(env, listOf(Followed("A", Kinds(concerts = false))), fake)
        clock += ArtistAlerts.DAY_MS
        fake.answers = mapOf("A" to Found(concerts = listOf(gig("A", "1", "Milan"), gig("A", "2", "Rome"))))
        assertTrue(check(env, listOf(Followed("A")), fake).notify.isEmpty())
    }

    @Test fun overTheBudgetTheRestWaitsAndTheNewsIsSaidOnce() {
        val env = ContractEnv()
        val followed = listOf(Followed("A"), Followed("B"))
        val fake = Fake(mapOf("A" to Found(releases = emptyList()), "B" to Found(releases = emptyList())))
        check(env, followed, fake)
        clock += ArtistAlerts.DAY_MS
        fake.answers = mapOf("A" to Found(releases = listOf(rel("A", "x"))), "B" to Found(releases = listOf(rel("B", "y"))))
        // A budget of nothing: one artist per run.
        val first = check(env, followed, fake, budget = 0)
        assertEquals(1, first.checked); assertEquals(1, first.remaining)
        assertTrue(first.notify.isEmpty())
        val second = check(env, followed, fake, budget = 0)
        assertEquals(0, second.remaining)
        assertEquals(setOf("x", "y"), second.notify.map { it.id }.toSet())
        // Said once: the next run has nothing pending.
        clock += ArtistAlerts.DAY_MS
        assertTrue(check(env, followed, fake).notify.isEmpty())
    }

    @Test fun groupingSaysOneLinePerArtistAndKind() {
        val env = ContractEnv()
        val news = listOf(
            rel("ADTR", "s1", "SINGLE"), gig("Architects", "c1", "Milano"), gig("Architects", "c2", "Roma"),
            News("BMTH", Kind.MERCH, "m1"), News("BMTH", Kind.RECORD, "d1"), gig("Muse", "c3", ""),
        )
        assertEquals(5, ArtistAlerts.group(news).size)
        assertEquals(2, ArtistAlerts.group(news)[1].count)
        assertEquals(
            "alerts_news_single(ADTR), alerts_news_concert(Architects,Milano), alerts_news_merch(BMTH), " +
                "alerts_news_record(BMTH), alerts_news_concert_somewhere(Muse)",
            ArtistAlerts.summary(env, news),
        )
    }

    @Test fun followingIsByNameWhateverTheCase() {
        var list = ArtistAlerts.toggle(emptyList(), "Bring Me The Horizon", Kinds(merch = false))
        assertTrue(ArtistAlerts.isFollowed(list, "bring me the horizon"))
        assertFalse(list.single().kinds.merch)
        list = ArtistAlerts.setKinds(list, "BRING ME THE HORIZON", Kinds())
        assertTrue(list.single().kinds.merch)
        assertTrue(ArtistAlerts.toggle(list, "bring me the horizon", Kinds()).isEmpty())
    }

    @Test fun aMutedArtistIsNotLookedUpAndStartsOverWithoutAFloodWhenTurnedOnAgain() {
        val env = ContractEnv()
        val fake = Fake(mapOf("A" to Found(releases = listOf(rel("A", "1"))), "B" to Found(releases = emptyList())))
        var list = listOf(Followed("A"), Followed("B"))
        check(env, list, fake)
        // Muted: still in the list with its kinds, never asked.
        list = ArtistAlerts.setMuted(list, "a", true)
        assertTrue(list.first().muted); assertTrue(list.first().kinds.any); assertFalse(list.first().active)
        fake.asked.clear()
        clock += ArtistAlerts.DAY_MS
        fake.answers = mapOf("A" to Found(releases = listOf(rel("A", "1"), rel("A", "2"))), "B" to Found(releases = emptyList()))
        assertTrue(check(env, list, fake).notify.isEmpty())
        assertEquals(listOf("B"), fake.asked)
        // On again: what came out meanwhile is only taken note of, the next one is news.
        list = ArtistAlerts.setMuted(list, "A", false)
        clock += ArtistAlerts.DAY_MS
        assertTrue(check(env, list, fake).notify.isEmpty())
        clock += ArtistAlerts.DAY_MS
        fake.answers = mapOf("A" to Found(releases = listOf(rel("A", "1"), rel("A", "2"), rel("A", "3"))), "B" to Found(releases = emptyList()))
        assertEquals(listOf("3"), check(env, list, fake).notify.map { it.id })
    }

    @Test fun withEveryoneMutedNothingRuns() {
        val s = Settings(followedArtists = listOf(Followed("A", muted = true), Followed("B")))
        assertEquals(1L, ArtistAlerts.plan(s)?.periodDays)
        assertNull(ArtistAlerts.plan(s.copy(followedArtists = ArtistAlerts.setMuted(s.followedArtists, "b", true))))
    }

    @Test fun followingTwiceKeepsOneHoweverTheNameIsWritten() {
        val id = "a".repeat(8) + "-" + "b".repeat(4) + "-" + "c".repeat(4) + "-" + "d".repeat(4) + "-" + "e".repeat(12)
        var list = ArtistAlerts.follow(emptyList(), "  Parkway   Drive ", Kinds(records = false), id.uppercase())
        assertEquals(Followed("Parkway Drive", Kinds(records = false), mbid = id), list.single())
        list = ArtistAlerts.follow(list, "parkway drive", Kinds())
        list = ArtistAlerts.follow(list, "PARKWAY DRIVE", Kinds(), "not an id")
        assertEquals(1, list.size)
        assertTrue(ArtistAlerts.follow(emptyList(), "  ", Kinds()).isEmpty())
        assertNull(ArtistAlerts.follow(emptyList(), "X", Kinds(), "not an id").single().mbid)
    }

    @Test fun aNameTypedByHandTakesItsProperSpellingOnlyWhenItIsTheSameName() {
        val id = "1".repeat(8) + "-" + "2".repeat(4) + "-" + "3".repeat(4) + "-" + "4".repeat(4) + "-" + "5".repeat(12)
        val list = listOf(Followed("parkway drive", Kinds(merch = false), muted = true), Followed("Muse"))
        val fixed = ArtistAlerts.canonical(list, "parkway drive", "Parkway Drive", id)
        assertEquals(Followed("Parkway Drive", Kinds(merch = false), muted = true, mbid = id), fixed.first())
        assertEquals(list, ArtistAlerts.canonical(list, "parkway drive", "Parkway Drive Tribute", id))
    }

    @Test fun thePageListsAToZAndAnUnfollowUndoneGoesBackWhereItWas() {
        val list = listOf(Followed("muse"), Followed("Ásgeir"), Followed("Architects"))
        assertEquals(listOf("Architects", "Ásgeir", "muse"), ArtistAlerts.sorted(list).map { it.name })
        val without = list.filter { it.name != "Ásgeir" }
        assertEquals(list, ArtistAlerts.restore(without, list[1], 1))
        assertEquals(list, ArtistAlerts.restore(list, list[1], 1))
        assertEquals(without + list[1], ArtistAlerts.restore(without, list[1], 9))
    }

    @Test fun thePlanFollowsTheSettings() {
        assertNull("nobody followed", ArtistAlerts.plan(Settings()))
        val s = Settings(followedArtists = listOf(Followed("A")))
        assertEquals(ArtistAlerts.Plan(1, unmetered = false, batteryNotLow = true), ArtistAlerts.plan(s))
        assertEquals(ArtistAlerts.Plan(7, unmetered = true), ArtistAlerts.plan(s.copy(artistAlertsWeekly = true, artistAlertsWifiOnly = true)))
        assertNull("alerts off", ArtistAlerts.plan(s.copy(artistAlerts = false)))
        assertNull("nothing wanted", ArtistAlerts.plan(Settings(followedArtists = listOf(Followed("A", Kinds(false, false, false, false))))))
    }
}

class ArtistAlertsBackupTest {
    @Test fun theFollowedArtistsGoWithTheSetUpButNotWhatWasSeen() {
        val env = ContractEnv()
        val id = "0".repeat(8) + "-" + "1".repeat(4) + "-" + "2".repeat(4) + "-" + "3".repeat(4) + "-" + "4".repeat(12)
        val followed = listOf(Followed("Architects", Kinds(merch = false)), Followed("Parkway Drive", muted = true, mbid = id))
        env.store.updateSettings { it.copy(followedArtists = followed, artistAlertsWeekly = true) }
        env.keyValues("artist_alerts")["seen"] = "{}"
        val other = ContractEnv()
        com.xlollx.songport.data.ConfigBackup.import(other, com.xlollx.songport.data.ConfigBackup.read(com.xlollx.songport.data.ConfigBackup.export(env, "test")))
        assertEquals(followed, other.store.data.settings.followedArtists)
        assertTrue(other.store.data.settings.artistAlertsWeekly)
        assertNull(other.keyValues("artist_alerts")["seen"])
    }
}
