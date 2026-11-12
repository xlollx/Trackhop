package com.xlollx.songport.sync

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.sync.ArtistAlerts.FeedRelease
import com.xlollx.songport.sync.ArtistAlerts.Followed
import com.xlollx.songport.sync.ArtistAlerts.Found
import com.xlollx.songport.sync.ArtistAlerts.Kind
import com.xlollx.songport.sync.ArtistAlerts.Kinds
import com.xlollx.songport.sync.ArtistAlerts.News
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * A service's own feed of new releases (Spotify's "What's new") next to each artist's own sources:
 * read once per look, only for the artists followed here, never saying a release twice, and
 * changing nothing when it fails or is not there. Made-up artists and releases.
 */
class ArtistAlertsFeedTest {
    private var clock = 2_000_000_000_000L
    private val today get() = Instant.ofEpochMilli(clock).atZone(ZoneId.systemDefault()).toLocalDate()
    private fun day(daysAgo: Long) = today.minusDays(daysAgo).toString()
    private fun rel(artist: String, title: String, type: String = "SINGLE") =
        News(artist, Kind.RELEASE, "r|$type|${Matcher.normalizeTitle(title)}", title, type)

    private class Fake(var answers: Map<String, Found>) : ArtistAlerts.Lookup {
        override suspend fun look(env: com.xlollx.songport.platform.Env, artist: Followed): Found = answers[artist.name] ?: Found()
    }

    private class Feed(var list: List<FeedRelease>?, var failure: Exception? = null) : ArtistAlerts.Feed {
        var reads = 0
        override suspend fun releases(): List<FeedRelease>? { reads++; failure?.let { throw it }; return list }
    }

    private fun check(env: ContractEnv, followed: List<Followed>, lookup: Fake, feed: Feed?) =
        runBlocking { ArtistAlerts.check(env, followed, 1, lookup, gapMs = 0, now = { clock }, feed = feed) }

    private val followed = listOf(Followed("Neon Harbour"), Followed("Ölfa"))

    /** Both artists looked at once: the next looks say only what is new. */
    private fun baseline(env: ContractEnv, lookup: Fake) {
        assertTrue(check(env, followed, lookup, null).notify.isEmpty())
        clock += ArtistAlerts.DAY_MS
    }

    @Test fun aReleaseOnlyTheFeedKnowsIsSaidOnce() {
        val env = ContractEnv()
        val lookup = Fake(mapOf("Neon Harbour" to Found(releases = listOf(rel("Neon Harbour", "Old Song"))), "Ölfa" to Found(releases = emptyList())))
        baseline(env, lookup)
        val feed = Feed(listOf(
            FeedRelease(listOf("Neon  harbour"), "Paper Lanterns", NewReleases.Type.SINGLE, day(1), "https://open.spotify.com/album/al1"),
            FeedRelease(listOf("Someone Not Followed"), "Elsewhere", NewReleases.Type.ALBUM, day(1)),
        ))
        val out = check(env, followed, lookup, feed)
        assertEquals(1, feed.reads)
        assertEquals(listOf("Neon Harbour" to "Paper Lanterns"), out.notify.map { it.artist to it.title })
        assertEquals("https://open.spotify.com/album/al1", out.notify[0].url)
        // The next day the feed still lists it, and now the artist's own sources do too: not said again.
        clock += ArtistAlerts.DAY_MS
        lookup.answers = lookup.answers + ("Neon Harbour" to Found(releases = listOf(rel("Neon Harbour", "Old Song"), rel("Neon Harbour", "Paper Lanterns", "EP"))))
        assertTrue(check(env, followed, lookup, feed).notify.isEmpty())
    }

    @Test fun aReleaseBothKnowIsSaidOnceAsASingleOrAnEp() {
        val env = ContractEnv()
        val lookup = Fake(mapOf("Neon Harbour" to Found(releases = emptyList()), "Ölfa" to Found(releases = emptyList())))
        baseline(env, lookup)
        lookup.answers = lookup.answers + ("Ölfa" to Found(releases = listOf(rel("Ölfa", "Low Tide", "SINGLE"))))
        val feed = Feed(listOf(FeedRelease(listOf("Ölfa", "Neon Harbour"), "Low Tide", NewReleases.Type.EP, day(2))))
        val out = check(env, followed, lookup, feed)
        // Ölfa's own sources and the feed name the same release: once. Neon Harbour, credited too, hears of it from the feed.
        assertEquals(setOf("Ölfa" to "SINGLE", "Neon Harbour" to "EP"), out.notify.map { it.artist to it.detail }.toSet())
        assertEquals(2, out.notify.size)
    }

    @Test fun anAlbumNamedAfterASingleAlreadySaidIsStillNews() {
        val env = ContractEnv()
        val lookup = Fake(mapOf("Neon Harbour" to Found(releases = listOf(rel("Neon Harbour", "Low Tide", "SINGLE"))), "Ölfa" to Found(releases = emptyList())))
        baseline(env, lookup)
        val feed = Feed(listOf(FeedRelease(listOf("Neon Harbour"), "Low Tide", NewReleases.Type.ALBUM, day(0))))
        assertEquals(listOf("ALBUM"), check(env, followed, lookup, feed).notify.map { it.detail })
    }

    @Test fun anOldReleaseInTheFeedIsNotNews() {
        val env = ContractEnv()
        val lookup = Fake(mapOf("Neon Harbour" to Found(releases = emptyList()), "Ölfa" to Found(releases = emptyList())))
        baseline(env, lookup)
        val feed = Feed(listOf(FeedRelease(listOf("Neon Harbour"), "Years Ago", NewReleases.Type.ALBUM, day(ArtistAlerts.FEED_DAYS + 5))))
        assertTrue(check(env, followed, lookup, feed).notify.isEmpty())
    }

    @Test fun whenTheArtistsOwnSourcesDoNotAnswerTheFeedStillTells() {
        val env = ContractEnv()
        val lookup = Fake(mapOf("Neon Harbour" to Found(releases = emptyList()), "Ölfa" to Found(releases = emptyList())))
        baseline(env, lookup)
        lookup.answers = emptyMap()
        val feed = Feed(listOf(FeedRelease(listOf("Neon Harbour"), "Paper Lanterns", NewReleases.Type.SINGLE, day(0))))
        assertEquals(listOf("Paper Lanterns"), check(env, followed, lookup, feed).notify.map { it.title })
    }

    @Test fun aFeedThatFailsOrIsNotThereChangesNothing() {
        for (feed in listOf(Feed(null), Feed(emptyList(), failure = IllegalStateException("Spotify (web): HTTP 500")), null)) {
            val env = ContractEnv()
            val lookup = Fake(mapOf("Neon Harbour" to Found(releases = listOf(rel("Neon Harbour", "Old Song"))), "Ölfa" to Found(releases = emptyList())))
            baseline(env, lookup)
            lookup.answers = lookup.answers + ("Neon Harbour" to Found(releases = listOf(rel("Neon Harbour", "Old Song"), rel("Neon Harbour", "New One"))))
            assertEquals(listOf("New One"), check(env, followed, lookup, feed).notify.map { it.title })
        }
    }

    @Test fun theFirstLookAtAnArtistOnlyTakesNoteOfTheFeedToo() {
        val env = ContractEnv()
        val feed = Feed(listOf(FeedRelease(listOf("Neon Harbour"), "Paper Lanterns", NewReleases.Type.SINGLE, day(0))))
        assertTrue(check(env, followed, Fake(emptyMap()), feed).notify.isEmpty())
        clock += ArtistAlerts.DAY_MS
        assertTrue(check(env, followed, Fake(emptyMap()), feed).notify.isEmpty())
    }

    @Test fun theFeedIsNotAskedWhenNoArtistWantsReleases() {
        val env = ContractEnv()
        val feed = Feed(emptyList())
        check(env, listOf(Followed("Neon Harbour", Kinds(releases = false))), Fake(emptyMap()), feed)
        assertEquals(0, feed.reads)
    }
}
