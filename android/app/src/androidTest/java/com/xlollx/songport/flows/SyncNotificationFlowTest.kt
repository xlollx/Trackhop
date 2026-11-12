package com.xlollx.songport.flows

import android.app.Notification
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.MainActivity
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.sync.Scheduler
import com.xlollx.songport.sync.SyncState
import com.xlollx.songport.ui.PAUSED_BAR_TAG
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Several syncs started at once share one ongoing notification: "3 syncs running", a line per
 * sync with its step, Pause all and Stop all. No second entry next to it (the bare "Syncing…" bar
 * WorkManager used to leave), and nothing ongoing once they are done.
 *
 * Pause is cooperative (the engine parks at its next step), and every surface tells the two
 * moments apart: "Pausing" while a step finishes, "Paused" once the engine waits, never "Syncing".
 */
@RunWith(AndroidJUnit4::class)
class SyncNotificationFlowTest : FlowTest() {

    private val nm: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    /** The app's ongoing entries in the shade: progress, foreground service. */
    private fun ongoing(): List<StatusBarNotification> =
        nm.activeNotifications.filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 || it.notification.channelId == Notifications.PROGRESS_CHANNEL_ID }

    private fun StatusBarNotification.title() = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun StatusBarNotification.actionTitles() = notification.actions.orEmpty().map { it.title.toString() }
    private fun StatusBarNotification.action(title: String) = notification.actions.orEmpty().first { it.title.toString() == title }
    private fun StatusBarNotification.lines() = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
    private fun StatusBarNotification.text() = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    /** Nodes showing exactly [text]: the cards' status pills ("Syncing", "Pausing", "Paused"). */
    private fun count(text: String) = compose.onAllNodes(hasText(text, substring = false), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Anything on screen still spinning or sweeping: an indeterminate progress indicator. */
    private fun indeterminate() = compose.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate), useUnmergedTree = true,
    ).fetchSemanticsNodes().size

    @Test
    fun threeSyncsAtOnceShowOneOngoingNotification() {
        // Each target already exists, so each sync reads it too: a second place to hold them.
        synchronized(FakeWorld) {
            (1..3).forEach { i -> FakeWorld.playlists.getValue("deezer").add(FakeWorld.FakePlaylist("road-$i", "Road Trip $i", mutableListOf())) }
        }
        val jobs = (1..3).map { i ->
            SyncJob("job-$i", "Sync number $i", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", "road-$i", "Road Trip $i"))
        }
        val ids = jobs.map { it.id }.toSet()
        store.update { it.copy(jobs = jobs) }
        store.flush()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText("Sync number 1")

        // Each sync waits at its first read until the test lets it go: all three run together.
        val hold = CompletableDeferred<Unit>()
        FakeWorld.hold = hold
        try {
            jobs.forEach { Scheduler.runNow(app, it.id) }
            waitFor("three syncs running", 30_000) { SyncState.running.value.keys == jobs.map { it.id }.toSet() }

            val title = plural(R.plurals.syncs_running, 3, 3)
            waitFor("one notification for the three syncs", 20_000) { ongoing().singleOrNull()?.title() == title }
            // The status line at the head of the list says it too, in its shorter words.
            waitText(plural(R.plurals.status_running, 3, 3))
            // It stays one: no late placeholder from another worker turns up beside it.
            val end = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < end) {
                val now = ongoing()
                assertEquals("ongoing notifications: ${now.map { "${it.id} ${it.title()}" }}", 1, now.size)
                Thread.sleep(200)
            }
            val n = ongoing().single()
            assertEquals(Notifications.PROGRESS_ID, n.id)
            assertEquals(Notifications.PROGRESS_CHANNEL_ID, n.notification.channelId)
            val lines = n.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty().map { it.toString() }
            assertEquals(lines.toString(), 3, lines.size)
            jobs.forEach { j -> assertTrue("${j.name} in $lines", lines.any { it.startsWith(j.name) && it.contains(s(R.string.progress_fetch_source)) }) }
            assertEquals(listOf(s(R.string.sync_pause_all), s(R.string.sync_stop_all)), n.actionTitles())

            // Pause all, from the notification, while each sync is still reading its source: asked
            // for, not reached. "Pausing" everywhere, nothing says "Paused" yet, Resume all offered.
            n.action(s(R.string.sync_pause_all)).actionIntent.send()
            waitFor("all three pausing") { SyncState.paused.value == ids && jobs.all { SyncState.run(it.id) == SyncState.Run.PAUSING } }
            waitText(s(R.string.pill_pausing))
            assertEquals("no card says Syncing while pausing", 0, count(s(R.string.pill_running)))
            assertEquals("no card says Paused before the engine waits", 0, count(s(R.string.sync_paused)))
            waitFor("notification lines say Pausing…") {
                val now = ongoing().singleOrNull()
                now != null && now.title() == title && now.lines().size == 3 &&
                    now.lines().all { it.contains(s(R.string.sync_pausing_short)) && !it.contains(s(R.string.sync_paused)) } &&
                    now.actionTitles().contains(s(R.string.sync_resume_all))
            }

            // The reads end: each engine reaches its next step and waits there. Now they are paused.
            val second = CompletableDeferred<Unit>()
            FakeWorld.hold = second
            hold.complete(Unit)
            waitFor("all three parked", 20_000) { SyncState.parked.value == ids && jobs.all { SyncState.run(it.id) == SyncState.Run.PAUSED } }
            val pausedTitle = plural(R.plurals.syncs_paused, 3, 3)
            waitText(pausedTitle)
            waitText(plural(R.plurals.status_paused, 3, 3))
            waitText(s(R.string.sync_paused))
            waitGone(s(R.string.pill_pausing))
            assertEquals("no card says Syncing while paused", 0, count(s(R.string.pill_running)))
            assertFalse("the top pill no longer says running", exists(title))
            waitNode("a frozen bar", hasTestTag(PAUSED_BAR_TAG))
            // Nothing spins: no top-pill spinner, no sweeping card bar.
            waitFor("no moving progress indicator") { indeterminate() == 0 }
            waitFor("the notification says paused") {
                val now = ongoing().singleOrNull()
                now != null && now.title() == pausedTitle && now.lines().size == 3 &&
                    now.lines().all { it.contains(s(R.string.sync_paused) + " · ") && !it.contains(s(R.string.sync_pausing_short)) } &&
                    now.actionTitles() == listOf(s(R.string.sync_resume_all), s(R.string.sync_stop_all)) &&
                    !now.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            }
            assertEquals(1, ongoing().size)

            // A tap on the top pill resumes them all: back to running, held at the target's read.
            click(pausedTitle)
            waitFor("all three resumed") { SyncState.paused.value.isEmpty() && SyncState.parked.value.isEmpty() && SyncState.running.value.keys == ids }
            waitText(title)
            waitText(s(R.string.pill_running))
            assertEquals(0, count(s(R.string.sync_paused)))
            assertEquals(0, count(s(R.string.pill_pausing)))
            waitFor("the notification says running again") {
                val now = ongoing().singleOrNull()
                now != null && now.title() == title && now.actionTitles() == listOf(s(R.string.sync_pause_all), s(R.string.sync_stop_all)) &&
                    now.lines().none { it.contains(s(R.string.sync_paused)) || it.contains(s(R.string.sync_pausing_short)) }
            }
            assertEquals(1, ongoing().size)

            second.complete(Unit)
            waitFor("the three syncs done", 60_000) { SyncState.running.value.isEmpty() && store.data.reports.count { !it.partial } >= 3 }
            waitFor("no ongoing notification left", 20_000) { ongoing().isEmpty() }
        } finally {
            hold.complete(Unit)
            FakeWorld.hold?.complete(Unit)
            FakeWorld.hold = null
            // Only what still runs: a stop asked for a sync that is not running would wait for its next run.
            SyncState.running.value.keys.forEach { SyncState.stop(it) }
            runCatching { waitFor("syncs finished", 60_000) { SyncState.running.value.isEmpty() } }
        }
        jobs.forEach { j -> assertTrue("${j.name} ran", store.data.reports.any { it.jobId == j.id && it.ok }) }
    }

    /**
     * Stop while paused: one sync, paused from its card while it reads (Pausing), parked at its next
     * step (Paused, a still bar, Resume on the card), then stopped from the card. It ends.
     */
    @Test
    fun stopWhilePausedEndsTheSync() {
        val job = SyncJob("job-stop", "Paused then stopped", PlaylistRef("spotify", FakeWorld.SPOTIFY_ROAD, "Road Trip"), PlaylistRef("deezer", null, "Road Trip stop"))
        store.update { it.copy(jobs = listOf(job)) }
        store.flush()
        launch(tabIntent(MainActivity.TAB_SYNCS))
        waitText(job.name)
        val hold = CompletableDeferred<Unit>()
        FakeWorld.hold = hold
        try {
            Scheduler.runNow(app, job.id)
            waitFor("the sync running", 30_000) { job.id in SyncState.running.value }
            waitText(s(R.string.pill_running))
            clickDesc(s(R.string.sync_pause))
            waitFor("pausing") { SyncState.run(job.id) == SyncState.Run.PAUSING }
            waitText(s(R.string.pill_pausing))
            waitText(s(R.string.sync_pausing), substring = true)
            assertEquals(0, count(s(R.string.pill_running)))

            FakeWorld.hold = null
            hold.complete(Unit)
            waitFor("paused for real", 20_000) { SyncState.run(job.id) == SyncState.Run.PAUSED }
            waitText(s(R.string.sync_paused))
            waitText(s(R.string.sync_paused) + " · ", substring = true)
            waitNode("a frozen bar", hasTestTag(PAUSED_BAR_TAG))
            waitFor("no moving progress indicator") { indeterminate() == 0 }
            assertEquals(0, count(s(R.string.pill_running)))
            // The single-sync notification: "Paused · …", a still bar, Resume and Stop.
            waitFor("the notification says paused", 20_000) {
                val now = ongoing().singleOrNull()
                now != null && now.text().startsWith(s(R.string.sync_paused) + " · ") &&
                    now.actionTitles() == listOf(s(R.string.sync_resume), s(R.string.sync_stop)) &&
                    !now.notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            }

            clickDesc(s(R.string.sync_stop))
            waitFor("the paused sync stopped", 30_000) { SyncState.running.value.isEmpty() }
            waitGone(s(R.string.sync_paused))
            waitGone(s(R.string.pill_running))
            waitFor("no ongoing notification left", 20_000) { ongoing().isEmpty() }
            assertFalse(SyncState.isPaused(job.id))
            assertTrue(SyncState.parked.value.isEmpty())
        } finally {
            hold.complete(Unit)
            FakeWorld.hold = null
            if (job.id in SyncState.running.value) SyncState.stop(job.id)
            runCatching { waitFor("sync finished", 60_000) { SyncState.running.value.isEmpty() } }
        }
    }
}
