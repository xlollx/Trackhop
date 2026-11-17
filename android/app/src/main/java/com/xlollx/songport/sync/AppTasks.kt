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

}
