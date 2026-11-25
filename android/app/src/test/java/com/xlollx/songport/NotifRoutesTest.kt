package com.xlollx.songport

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.notify.NotifTarget
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where each notification leads when tapped (and its entry in the bell): the target Notifications
 * builds for it, carried as text, read back and resolved by the main screen. 2.2: "40 songs no
 * longer playable" opened the home page.
 */
class NotifRoutesTest {
    private val hub = PlaylistRef(SharedProvider.ID, "local", "g")
    private val plain = SyncJob("a", "Liked", PlaylistRef("spotify", "p", "Liked"), PlaylistRef("deezer", "q", "Liked"))
    private val member = SyncJob("g1", "Road trip · Deezer", hub, PlaylistRef("deezer", "m", "Road trip"))
    private val setlist = SyncJob("o", "Setlist", PlaylistRef("file", "f", "Setlist"), PlaylistRef("spotify", "s", "Setlist"), oneOff = true)
    private val releases = SyncJob("nr", "New releases", PlaylistRef(com.xlollx.songport.providers.NewReleasesProvider.SERVICE, "x", "New"), PlaylistRef("spotify", "n", "New"))
    private fun report(job: String, ok: Boolean = true, lost: Int = 0) =
        SyncReport("r-$job", job, job, 0, 0, error = if (ok) null else "Sign-in expired", unmatchedTracks = (1..lost).map { Track("t$it", "Song $it") })
    private val reports = listOf(report("a"), report("a", lost = 2).copy(id = "r-a-review"), report("o", lost = 1), report("g1"))
    private val d = StoreData(
        jobs = listOf(plain, member, setlist, releases), reports = reports,
        groups = listOf(SharedGroup("local", "Road trip", "k", "k", "", transport = Invite.LOCAL, jobId = "g1")),
    )

    /** As the notification carries it: encoded in the Intent, parsed back by MainActivity. */
    private fun tap(t: NotifTarget): NotifRoute = NotifRoutes.resolve(NotifTarget.parse(t.encode())!!, d)

    @Test fun unplayableTracksOpenTheToolOnTheirService() {
        val r = tap(NotifTarget.Unplayable("spotify"))
        assertEquals(MainActivity.TAB_TOOLS, r.tab)
        assertTrue(r.unplayable)
        assertEquals("spotify", r.unplayableProvider)
        // An older notification, still in the shade after the update, asked by the tool's name.
        assertEquals(NotifRoute(MainActivity.TAB_TOOLS, unplayable = true), tap(MainActivity.legacyTarget(MainActivity.TOOL_UNPLAYABLE)!!))
    }

    @Test fun aFinishedOrFailedSyncOpensItsCard() {
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, focusJob = "a"), tap(NotifRoutes.forResult(d, plain, report("a"))))
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, focusJob = "a"), tap(NotifRoutes.forResult(d, plain, report("a", ok = false))))
        // New releases: a sync like any other.
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, focusJob = "nr"), tap(NotifRoutes.forResult(d, releases, report("nr"))))
    }

    @Test fun songsToReviewOpenTheReview() {
        val t = NotifRoutes.forResult(d, plain, reports[1])
        assertEquals(NotifTarget.Review("a", "r-a-review"), t)
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, review = "r-a-review"), tap(t))
        // The run gone from the history since: its sync.
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, focusJob = "a"), tap(NotifTarget.Review("a", "gone")))
    }

    @Test fun aMultiSyncOpensItsGroupsPage() {
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, group = "local"), tap(NotifRoutes.forResult(d, member, report("g1"))))
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, group = "local"), tap(NotifTarget.Sync("g1")))
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS), tap(NotifTarget.Group("deleted")))
    }

    @Test fun aOneOffTransferOpensItsReviewElseTools() {
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS, review = "r-o"), tap(NotifRoutes.forResult(d, setlist, reports[2])))
        assertEquals(NotifRoute(MainActivity.TAB_TOOLS), tap(NotifRoutes.forResult(d, setlist, report("o"))))
    }

    @Test fun aSyncGoneOpensTheList() {
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS), tap(NotifTarget.Sync("deleted")))
        assertEquals(NotifRoute(MainActivity.TAB_SYNCS), tap(NotifRoutes.forResult(d, null, report("deleted")).let { it }))
    }

    @Test fun severalRunsAtOnceOpenTheLog() {
        assertEquals(NotifRoute(MainActivity.TAB_SETTINGS, log = true), tap(NotifTarget.Log))
    }

    @Test fun artistAlertsOpenFollowedArtists() {
        assertEquals(NotifRoute(MainActivity.TAB_DISCOVER, followed = true), tap(NotifTarget.Followed))
        assertEquals(NotifRoute(MainActivity.TAB_DISCOVER, followed = true), tap(MainActivity.legacyTarget(MainActivity.TOOL_FOLLOWED)!!))
    }

    @Test fun concertsAndRecordsOpenTheirPage() {
        assertEquals(NotifRoute(-1, link = "https://www.ticketmaster.it/event/1"), tap(NotifTarget.Link("https://www.ticketmaster.it/event/1")))
        assertEquals(NotifRoute(-1, link = "https://www.discogs.com/sell/release/1"), tap(NotifTarget.Link("https://www.discogs.com/sell/release/1")))
    }

    @Test fun signInAgainOpensTheServicesCard() {
        assertEquals(NotifRoute(MainActivity.TAB_ACCOUNTS, connector = "soundcloud"), tap(NotifTarget.Account("soundcloud")))
    }

    @Test fun noticesOpenSettings() {
        assertEquals(NotifRoute(MainActivity.TAB_SETTINGS), tap(NotifTarget.Settings))
    }

    @Test fun theNewVersionOpensTheUpdatesFlow() {
        val apk = "${MainActivity.RELEASES_PREFIX}/download/v2.2.1/Trackhop-v2.2.1.apk"
        assertEquals(NotifRoute(-1, update = true, link = apk), tap(NotifTarget.Update(apk)))
        assertEquals(NotifRoute(-1, update = true), tap(NotifTarget.Update(null)))
    }

    @Test fun differentPlacesNeverShareARequestCode() {
        val targets = listOf(NotifTarget.Sync("a"), NotifTarget.Sync("b"), NotifTarget.Review("a", "r"), NotifTarget.Group("local"), NotifTarget.Unplayable(null), NotifTarget.Followed, NotifTarget.Settings, NotifTarget.Log)
        assertEquals(targets.size, targets.map { NotifRoutes.requestCode(4243, it) }.toSet().size)
        assertNotEquals(NotifRoutes.requestCode(4245, NotifTarget.Followed), NotifRoutes.requestCode(4246, NotifTarget.Followed))
    }
}
