package com.xlollx.songport.update

import android.content.Context
import android.os.Build
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.ui.openUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The GitHub build's update check: at most twice a day, one plain GET to GitHub's API for the
 * latest app release (no account, no identifiers, a fixed User-Agent). A newer one puts an arrow in
 * the top bar; a tap downloads its APK. Not done at all when an app store or updater installed
 * Trackhop (F-Droid, Obtainium…): they update it themselves, and F-Droid's own build lags GitHub's.
 * The user can turn it off in Settings › About.
 */
object AppUpdates {
    /** This build has a setting for it (the Play build follows Google Play). */
    const val configurable = true

    /** No store page to rate on (GitHub, F-Droid, Obtainium). */
    const val rateInStore = false

    /** The link "Share Trackhop" sends. */
    fun shareUrl(ctx: Context) = "https://trackhop.org"

    private const val API = "https://api.github.com/repos/xlollx/Trackhop/releases/latest"
    private const val RELEASES = "https://github.com/xlollx/Trackhop/releases/latest"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L

    /** Installers that keep Trackhop up to date on their own, by the name the user knows them by. */
    private val UPDATERS = mapOf(
        "org.fdroid.fdroid" to "F-Droid", "org.fdroid.basic" to "F-Droid", "com.looker.droidify" to "Droid-ify",
        "com.machiav3lli.fdroid" to "Neo Store", "dev.imranr.obtainium" to "Obtainium", "dev.imranr.obtainium.fdroid" to "Obtainium",
        "in.sunilpaulmathew.izzyondroid" to "IzzyOnDroid", "com.android.vending" to "Google Play",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        if (!on) UpdateState.set(null)
    }

    /** The app that installed Trackhop, or null (a browser download, adb). */
    private fun installer(ctx: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
    }.getOrNull()

    /** True when an app store or updater installed Trackhop: no check, that app tells about updates. */
    fun handledElsewhere(ctx: Context): Boolean = updatedBy(ctx) != null

    /** The app store or updater that installed Trackhop and updates it, or null. */
    fun updatedBy(ctx: Context): String? = UPDATERS[installer(ctx)]

    /** Shows what the last check found, then asks GitHub again when the last answer is old (or [force]). */
    suspend fun check(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!enabled(ctx) || handledElsewhere(ctx)) { UpdateState.set(null); return@withContext }
        val p = prefs(ctx)
        fun publish() {
            val name = p.getString("name", null)
            UpdateState.set(name?.takeIf { UpdateState.newer(it, BuildConfig.VERSION_NAME) }?.let { UpdateState.Available(it, p.getString("url", null)) })
        }
        publish()
        if (!force && System.currentTimeMillis() - p.getLong("checked", 0) < EVERY_MS) return@withContext
        val r = runCatching { Http.send("GET", API, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Trackhop")) }.getOrNull()
        // A refusal (403/429 is GitHub's rate limit) or no network: tried again next time.
        if (r == null || !r.ok) return@withContext
        val j = runCatching { parseJson(r.body) }.getOrNull() ?: return@withContext
        val tag = j["tag_name"].str ?: return@withContext
        val name = tag.removePrefix("v")
        val apk = j["assets"].arr.firstOrNull { it["name"].str?.let { n -> n.endsWith(".apk") && n.contains(name) } == true }?.get("browser_download_url").str
        p.edit().putString("name", name).putString("url", apk ?: j["html_url"].str ?: RELEASES).putLong("checked", System.currentTimeMillis()).apply()
        publish()
    }

    /** Opens the new version's APK (the browser downloads it; Android then offers to install it). */
    fun open(ctx: Context) = openUrl(ctx, UpdateState.available.value?.url ?: RELEASES)
}
