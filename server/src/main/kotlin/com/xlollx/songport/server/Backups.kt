package com.xlollx.songport.server

import com.xlollx.songport.model.Schedule
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryTools
import java.io.File

/**
 * Full backups, as the app's Tools › Full backup and Settings › Backups: every playlist and the
 * library of each connected service saved as dated File playlists (the last versions kept), by hand
 * or on a schedule, and copied to a folder of the user's choice when one is set (a second volume, a
 * NAS share): only the files the folder does not have yet, by name, so it keeps every version.
 */
object Backups {
    const val TITLE = "Backup"

    /** The services a backup reads: connected, and not File itself. */
    fun services(env: ServerEnv): List<MusicProvider> =
        Providers.connectors().filter { it !is LocalFilesProvider && !it.linkOnly && (!it.requiresAuth || it.isConnected(env)) }

    /** Backs up [providers] (all of [services] by default); the result as HTML for the task's page. */
    suspend fun run(env: ServerEnv, providers: List<MusicProvider> = services(env), progress: (String) -> Unit = {}): String {
        val sb = StringBuilder()
        var failures = 0
        for (p in providers) {
            progress("${p.label(env)}…")
            val r = runCatching {
                LibraryTools.backupAll(env, p) { pr -> progress("${p.label(env)}: ${pr.done}/${pr.total} playlists") }
            }
            r.exceptionOrNull()?.let { failures++; sb.append("""<div class="card err"><b>${h(p.label(env))}</b>: ${h(it.message)}</div>""") }
            r.getOrNull()?.let { res ->
                failures += res.failures.size
                sb.append("""<div class="card ok"><b>${h(p.label(env))}</b>: ${res.playlists} playlists, ${res.tracks} tracks${if (res.unchanged > 0) " (${res.unchanged} unchanged since the last backup)" else ""}""")
                if (res.failures.isNotEmpty()) sb.append("""<br><span class="err">Not read: ${h(res.failed.joinToString(", "))}</span>""")
                sb.append("</div>")
            }
        }
        val copied = runCatching { copyToFolder(env) }
        copied.getOrNull()?.let { if (it > 0) sb.append("""<p class="muted">Copied $it new files to ${h(env.store.data.settings.backupFolder)}.</p>""") }
        copied.exceptionOrNull()?.let { sb.append("""<div class="card err">Copy to the folder: ${h(it.message)}</div>""") }
        env.kv()["last"] = System.currentTimeMillis().toString()
        env.log("backup", "${providers.size} services backed up, $failures failures")
        Api.notifyBackup(env, providers.size, failures)
        return sb.append("""<p><a href="/connectors/csv">See the files</a></p>""").toString()
    }

    /** Copies the File playlists the backup folder does not have yet. Returns how many. */
    fun copyToFolder(env: ServerEnv): Int {
        if (!env.may(Permissions.FOLDERS)) return 0
        val folder = env.store.data.settings.backupFolder.trim().ifEmpty { return 0 }
        val dir = File(folder)
        if (!dir.isDirectory || !dir.canWrite()) throw IllegalStateException("$folder is not a folder Trackhop can write to")
        var n = 0
        LocalFilesProvider.dir(env).listFiles { f -> f.isFile && f.extension == "csv" }.orEmpty().forEach { f ->
            val out = File(dir, f.name)
            if (!out.exists()) { f.copyTo(out); n++ }
        }
        // The set-up of the day: restoring the folder gives the syncs back, not only the playlists.
        val config = File(dir, com.xlollx.songport.data.ConfigBackup.fileName())
        if (!config.exists()) { config.writeText(Notify.intoBackup(env, com.xlollx.songport.data.ConfigBackup.export(env, "Trackhop (scheduled backup)"))); n++ }
        return n
    }

    fun lastRun(env: ServerEnv): Long = env.kv()["last"]?.toLongOrNull() ?: 0

    /** True when the backup schedule says it is time. */
    fun isDue(env: ServerEnv, now: Long): Boolean {
        val s = env.store.data.settings.backupSchedule
        return s != Schedule.MANUAL && now - lastRun(env) >= s.minutes * 60_000 && !Tasks.running(TITLE)
    }

    private fun ServerEnv.kv() = keyValues("backup_state")
}
