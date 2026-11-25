package com.xlollx.songport.notify

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.shared.Invite
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a notice leads, kept as text in an Intent and in the history, and read back the same. */
class NotifTargetTest {
    @Test fun everyTargetReadsBackAsItWasWritten() {
        val all = listOf(
            NotifTarget.Sync("job:1/ä"), NotifTarget.Review("j", "r:2"), NotifTarget.Group("g 1"),
            NotifTarget.Unplayable("spotify"), NotifTarget.Unplayable(null), NotifTarget.Followed,
            NotifTarget.Link("https://example.org/a?b=c:d"), NotifTarget.Account("soundcloud-2"),
            NotifTarget.Settings, NotifTarget.Log, NotifTarget.Tools,
            NotifTarget.Update("https://github.com/xlollx/Trackhop/releases/download/v2.2.1/Trackhop-v2.2.1.apk"), NotifTarget.Update(null),
        )
        for (t in all) assertEquals(t.encode(), t, NotifTarget.parse(t.encode()))
    }

    @Test fun whatIsNotOursOpensNothing() {
        assertNull(NotifTarget.parse(null))
        assertNull(NotifTarget.parse(""))
        assertNull(NotifTarget.parse("teleport:somewhere"))
        assertNull(NotifTarget.parse("sync:"))
        assertNull(NotifTarget.parse("review:only-one"))
        // Only web pages leave the app.
        assertNull(NotifTarget.parse("link:" + java.net.URLEncoder.encode("intent://evil", "UTF-8")))
        assertNull(NotifTarget.parse("link:javascript%3Aalert(1)"))
    }

    private val hub = PlaylistRef(SharedProvider.ID, "local", "g")
    private val plain = SyncJob("a", "Liked", PlaylistRef("spotify", "p", "Liked"), PlaylistRef("deezer", "q", "Liked"))
    private val member = SyncJob("g1", "Road trip · Deezer", hub, PlaylistRef("deezer", "m", "Road trip"))
    private val d = StoreData(jobs = listOf(plain, member), groups = listOf(SharedGroup("local", "Road trip", "k", "k", "", transport = Invite.LOCAL, jobId = "g1")))
    private fun report(job: String, ok: Boolean = true, lost: Int = 0) =
        SyncReport("r-$job", job, job, 0, 0, error = if (ok) null else "Sign-in expired", unmatchedTracks = (1..lost).map { Track("t$it", "Song $it") })

    @Test fun aRunLeadsToItsReviewItsGroupOrItsSync() {
        assertEquals(NotifTarget.Sync("a"), NotifTarget.forRun(d, plain, report("a")))
        assertEquals(NotifTarget.Sync("a"), NotifTarget.forRun(d, plain, report("a", ok = false)))
        assertEquals(NotifTarget.Review("a", "r-a"), NotifTarget.forRun(d, plain, report("a", lost = 2)))
        assertEquals(NotifTarget.Group("local"), NotifTarget.forRun(d, member, report("g1")))
        // A group gone: the sync itself.
        assertEquals(NotifTarget.Sync("g1"), NotifTarget.forRun(d.copy(groups = emptyList()), member, report("g1")))
    }
}
