package com.xlollx.songport.server

import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.bool
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.Properties

/**
 * "A new version is out": at most twice a day, one plain GET to GitHub's public API for the
 * releases of Trackhop (no account, no identifiers, a fixed User-Agent), and the newest
 * `server-v…` one compared with this version. Not done at all where something else updates
 * Trackhop (the Microsoft Store, Flathub, Homebrew, Scoop, Chocolatey), nor when turned off in
 * Settings or with `SONGPORT_UPDATE_CHECK=false`. On a server shared by several people only the
 * admin's engine checks, and only the admin is told.
 */
object Updates {
    private const val API = "https://api.github.com/repos/xlollx/Trackhop/releases?per_page=30"
    const val RELEASES = "https://github.com/xlollx/Trackhop/releases"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L

    @Volatile internal var api: String = API

    /** A newer release: its version and its page. */
    data class Available(val version: String, val url: String)

    @Volatile private var file: File? = null
    @Volatile private var current: String = ""
    @Volatile var edition: Html.Edition = Html.Edition.SERVER
        private set
    /** The store or package manager that updates Trackhop here, by the name the user knows it by. */
    @Volatile var updatedBy: String? = null
        private set
    @Volatile private var turnedOffByEnv = false

    /** True once [configure] ran: in the engine that may check (not another person's, on a shared server). */
    val configured: Boolean get() = file != null

    fun configure(dataDir: File, version: String, edition: Html.Edition, env: Map<String, String>, updatedBy: String? = null) {
        file = File(dataDir, "updates.properties")
        current = version.substringBefore(' ').trim()
        this.edition = edition
        this.updatedBy = updatedBy
        turnedOffByEnv = env["SONGPORT_UPDATE_CHECK"]?.trim()?.lowercase() in setOf("0", "false", "no", "off")
    }

    /** Back to not configured (tests). */
    internal fun reset() {
        file = null; current = ""; updatedBy = null; turnedOffByEnv = false; api = API; edition = Html.Edition.SERVER
    }

    /** This version, as the releases name it ("0.7.0"). */
    val version: String get() = current

    private fun props(): Properties = Properties().apply {
        file?.takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }

    private fun save(p: Properties) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        f.outputStream().use { p.store(it, "Trackhop: the last update check") }
    }

    /** False when the setting, the environment or a store says no. */
    val enabled: Boolean get() = configured && !turnedOffByEnv && updatedBy == null && props().getProperty("enabled") != "false"

    /** Set by the environment: the setting cannot turn it back on. */
    val lockedOff: Boolean get() = turnedOffByEnv

    fun setEnabled(on: Boolean) {
        val p = props()
        p.setProperty("enabled", on.toString())
        save(p)
    }

    /** When GitHub last answered, or 0. */
    val checkedAt: Long get() = props().getProperty("checked")?.toLongOrNull() ?: 0

    /** What the last answer found, if it is newer than this version and checks are on. */
    fun available(): Available? {
        if (!enabled) return null
        val p = props()
        val v = p.getProperty("latest") ?: return null
        return Available(v, p.getProperty("url") ?: RELEASES).takeIf { newer(v, current) }
    }

    /** From the scheduler: asks GitHub when the last answer is older than half a day. */
    suspend fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (enabled && now - checkedAt >= EVERY_MS) check()
    }

    /** Asks GitHub now; false when it did not answer (no network, its rate limit): tried again later. */
    suspend fun check(): Boolean {
        if (!enabled || current.isEmpty() || current == "dev") return false
        val r = runCatching { Http.send("GET", api, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Trackhop")) }.getOrNull()
        if (r == null || r.code != 200) return false
        val newest = runCatching { newest(parseJson(r.body)) }.getOrNull()
        val p = props()
        if (newest != null) { p.setProperty("latest", newest.version); p.setProperty("url", newest.url) }
        p.setProperty("checked", System.currentTimeMillis().toString())
        save(p)
        return true
    }

    /** The newest published `server-v…` release in GitHub's list (the app's own are `v…`). */
    internal fun newest(releases: JsonElement?): Available? =
        releases.arr.mapNotNull { j ->
            val tag = j["tag_name"].str ?: return@mapNotNull null
            if (!tag.startsWith("server-v") || j["draft"].bool == true || j["prerelease"].bool == true) return@mapNotNull null
            Available(tag.removePrefix("server-v"), j["html_url"].str ?: RELEASES)
        }.reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }

    /** True when [candidate] ("0.7.1") comes after [installed] ("0.7.0"): numbers compared part by part. */
    fun newer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Who updates Trackhop Desktop when it was not downloaded by hand, from where it runs: the
     * Microsoft Store's package, Flathub's sandbox, or a package manager's folder.
     */
    fun desktopUpdater(appPath: String, javaHome: String, env: Map<String, String>, store: Boolean): String? {
        val where = (appPath + "|" + javaHome).replace('\\', '/').lowercase()
        return when {
            store -> "the Microsoft Store"
            env["FLATPAK_ID"] != null -> "Flathub (your software centre)"
            "/cellar/" in where || "/homebrew/" in where -> "Homebrew (brew upgrade)"
            "/scoop/apps/" in where -> "Scoop (scoop update trackhop)"
            "/chocolatey/" in where -> "Chocolatey (choco upgrade trackhop)"
            else -> null
        }
    }
}
