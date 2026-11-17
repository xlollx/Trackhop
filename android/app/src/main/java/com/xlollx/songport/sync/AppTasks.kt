package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.Notifications
import com.xlollx.songport.R
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.Store
import com.xlollx.songport.platform.env
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Work that outlives the screen that asked for it, and what the app finishes by itself when it starts. */
object AppTasks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PUT_BACK_NOTICE_ID = 4291

    /**
     * Restoring a configuration file (Settings › Backup): in the app's own scope, so leaving the
     * screen neither loses the outcome nor leaves the imported syncs without their schedules until
     * the next start.
     */
    val restore = AppRun<ConfigBackup.Summary>()

    fun restore(ctx: Context, snapshot: ConfigBackup.Snapshot, channels: List<com.xlollx.songport.notify.Notify.Channel>): Boolean {
        val app = ctx.applicationContext
        return restore.start {
            val r = ConfigBackup.import(app.env, snapshot).also { com.xlollx.songport.notify.Notify.restore(app.env, channels) }
            // The imported syncs' schedules start here as they would have where they came from.
            Store.get(app).data.jobs.forEach { Scheduler.apply(app, it) }
            Scheduler.applyKeepAlive(app)
            r
        }
    }

    /** What [resumePutBacks] put back at this start, for the card in Settings; null once read. */
    val putBack = MutableStateFlow<String?>(null)

    /**
     * At the start of the app: the songs "Remove duplicates" had taken out and not put back when it
     * was cut short (the app killed, the phone off) go back in their playlists, and the person is
     * told which. What cannot be reached now stays for the next start.
     */
    fun resumePutBacks(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            runCatching {
                val r = LibraryTools.resumePutBacks(app.env)
                if (r.back.isEmpty()) return@runCatching
                val text = app.getString(R.string.tools_dedupe_put_back, r.back.joinToString(", ") { LibraryTools.songName(it) })
                putBack.value = text
                Notifications.notice(app, PUT_BACK_NOTICE_ID, app.getString(R.string.tools_dedupe_title), text)
            }.onFailure { com.xlollx.songport.data.Diagnostics.log(app, "tools", "duplicates: what was left to put back could not be looked at: ${it.message}") }
        }
    }
}
