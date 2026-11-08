package com.xlollx.songport

import android.app.LocaleManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.LocaleList
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.xlollx.songport.data.Store
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.providers.AppleBridgeProvider
import com.xlollx.songport.providers.AppleMusicProvider
import com.xlollx.songport.providers.DeezerBridgeProvider
import com.xlollx.songport.providers.LastFmProvider
import com.xlollx.songport.providers.ListenBrainzProvider
import com.xlollx.songport.providers.PlexProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.Providers.Factory
import com.xlollx.songport.providers.SoundCloudBridgeProvider
import com.xlollx.songport.providers.SpotifyBridgeProvider
import com.xlollx.songport.providers.SubsonicProvider
import com.xlollx.songport.providers.TidalProvider
import com.xlollx.songport.sync.SoundCloudPublic
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * The store screenshots, taken on an emulator by .github/workflows/screenshots.yml: a demo library
 * (ShotProvider, no network, no account), the app in dark mode with the brand palette, every
 * language of the store listing. Each picture is written to the app's external files dir as
 * shots/<fastlane locale>/<n>.png and pulled by the workflow.
 *
 * Run from the Play build (flavor "play") it takes the Play listing's pictures: only the services
 * that build reaches on its own (TIDAL where the full build shows Amazon Music, the official YouTube
 * connector, no web or plugin connector), and no service picker (it lists what Play cannot reach).
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    @get:Rule val compose = createEmptyComposeRule()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app: Context = instr.targetContext.applicationContext
    private lateinit var loc: Context

    /** App language tag -> fastlane folder. The language list can be narrowed with -e locales en,it. */
    private val locales = linkedMapOf(
        "en" to "en-US", "it" to "it-IT", "fr" to "fr-FR", "de" to "de-DE", "es" to "es-ES", "pt-BR" to "pt-BR",
        "pl" to "pl-PL", "ru" to "ru-RU", "tr" to "tr-TR", "zh" to "zh-CN", "ja" to "ja-JP", "ko" to "ko-KR",
    )

    private val spotify = ShotProvider("spotify", "Spotify", 0xFF1DB954, ShotProvider.IdStyle.SPOTIFY)
    private val ytm = ShotProvider("ytm", "YouTube Music", 0xFFFF0000, ShotProvider.IdStyle.YOUTUBE)
    private val youtube = ShotProvider("youtube", "YouTube Music", 0xFFFF0000, ShotProvider.IdStyle.YOUTUBE, familyName = "YouTube Music")
    private val amazon = ShotProvider("amazon", "Amazon Music", 0xFF25D1DA, ShotProvider.IdStyle.ASIN)
    private val deezer = ShotProvider("deezer", "Deezer", 0xFFA238FF, ShotProvider.IdStyle.NUMERIC)
    private val jellyfin = ShotProvider("jellyfin", "Jellyfin", 0xFF00A4DC, ShotProvider.IdStyle.UUID)
    private val tidal = ShotProvider("tidal", "TIDAL", 0xFF111111, ShotProvider.IdStyle.NUMERIC)

    /** The Play build: no web connector, no plugin. */
    private val play = BuildConfig.STORE_BUILD
    /** YouTube Music of the demo: the web connector in the full build, the official one in the Play build. */
    private val yt = if (play) "youtube" else "ytm"
    /** The fourth service: Amazon Music in the full build; TIDAL in the Play build, which reaches Amazon only through a plugin. */
    private val fourth = if (play) "tidal" else "amazon"
    private val fourthName = if (play) "TIDAL" else "Amazon Music"

    @Test
    fun takeAll() {
        installDemo()
        // -e review true: only the pictures for checking the Settings and Tools layout (English,
        // dark and light, a large font, Tools with no service), kept apart from the store's.
        if (InstrumentationRegistry.getArguments().getString("review") == "true") {
            setLanguage("en")
            shootReview()
            return
        }
        val only = InstrumentationRegistry.getArguments().getString("locales")?.split(',')?.map { it.trim() }?.toSet()
        for ((tag, folder) in locales) {
            if (only != null && tag !in only) continue
            setLanguage(tag)
            shootLanguage(folder)
        }
    }

    // ------------------------------------------------------------------ the demo

    private fun installDemo() {
        Providers.install(
            if (play) listOf(
                Factory("spotify") { spotify },
                Factory(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) },
                Factory("youtube") { youtube },
                Factory("tidal") { tidal },
                Factory("deezer") { deezer },
                Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
                Factory("jellyfin") { jellyfin },
                Factory(PlexProvider.SERVICE) { PlexProvider(it) },
                Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
                Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
            ) else listOf(
                Factory("spotify") { spotify },
                Factory(SpotifyBridgeProvider.SERVICE) { SpotifyBridgeProvider(it) },
                Factory(AppleMusicProvider.SERVICE) { AppleMusicProvider(it) },
                Factory(AppleBridgeProvider.SERVICE) { AppleBridgeProvider(it) },
                Factory("ytm") { ytm },
                Factory("youtube") { youtube },
                Factory("amazon") { amazon },
                Factory(TidalProvider.SERVICE) { TidalProvider(it) },
                Factory("deezer") { deezer },
                // The web connectors of 1.3.0, for the service picker: nothing signed in, nothing fetched.
                Factory(DeezerBridgeProvider.SERVICE) { DeezerBridgeProvider(it) },
                Factory(SoundCloudPublic.SERVICE) { SoundCloudBridgeProvider(it) },
                Factory(SubsonicProvider.SERVICE) { SubsonicProvider(it) },
                Factory("jellyfin") { jellyfin },
                Factory(PlexProvider.SERVICE) { PlexProvider(it) },
                Factory(LastFmProvider.SERVICE) { LastFmProvider(it) },
                Factory(ListenBrainzProvider.SERVICE) { ListenBrainzProvider(it) },
            ),
        )
        val store = Store.get(app)
        val now = System.currentTimeMillis()
        val pl = Demo.PLAYLISTS
        val jobs = listOf(
            SyncJob("job-metal", pl[0].second, PlaylistRef("spotify", pl[0].first, pl[0].second), PlaylistRef(yt, "PLmetal", pl[0].second), Schedule.WEEKLY, mirrorRemovals = true),
            SyncJob("job-road", pl[1].second, PlaylistRef("spotify", pl[1].first, pl[1].second), PlaylistRef(fourth, if (play) "5843001" else "B0road", pl[1].second), Schedule.DAILY),
            SyncJob("job-chill", pl[2].second, PlaylistRef(yt, "PLchill", pl[2].second), PlaylistRef("jellyfin", "jf-chill", pl[2].second), Schedule.WEEKLY, mirrorRemovals = true),
            SyncJob("job-workout", pl[3].second, PlaylistRef("deezer", "908622995", pl[3].second), PlaylistRef("spotify", pl[3].first, pl[3].second), Schedule.EVERY_6H),
        )
        // Ten runs each, the newest last: (added, removed, not found) of the newest, and a failure in the history of one.
        val newest = mapOf("job-metal" to Triple(12, 3, 2), "job-road" to Triple(5, 0, 1), "job-chill" to Triple(3, 1, 0), "job-workout" to Triple(4, 0, 0))
        val reports = ArrayList<SyncReport>()
        val finalJobs = jobs.mapIndexed { j, job ->
            val count = pl[j].third
            var last: SyncReport? = null
            for (r in 0 until 10) {
                val at = now - (10 - r) * job.schedule.minutes.coerceAtLeast(60) * 60_000L - j * 7 * 60_000L
                val isLast = r == 9
                val (a, d, nf) = if (isLast) newest.getValue(job.id) else Triple(r % 4, if (job.mirrorRemovals) r % 2 else 0, r % 3)
                val failed = job.id == "job-road" && r == 6
                val rep = SyncReport(
                    id = "${job.id}-$r", jobId = job.id, jobName = job.name, startedEpoch = at, durationMs = 18_000L + r * 900,
                    sourceCount = count, targetCount = count - a + d, added = if (failed) 0 else a, removed = if (failed) 0 else d,
                    unmatched = if (failed) emptyList() else (0 until nf).map { "Unreleased demo $it" },
                    error = if (failed) "Network unreachable" else null,
                )
                reports += rep
                last = rep
            }
            job.copy(lastRunEpoch = last!!.startedEpoch, lastReportId = last.id)
        }
        store.update { d -> d.copy(jobs = finalJobs, reports = reports) }
        store.updateSettings {
            it.copy(
                connectors = listOf("spotify", yt, fourth, "deezer", "jellyfin"), connectorsInit = true,
                onboardingDone = true, tourDone = true, themeMode = "DARK", dynamicColor = false, newLook = true,
                supportPromptDone = true, appOpens = 1, supportNudgeSeen = 0, shareTarget = yt,
                whatsNewSeen = com.xlollx.songport.ui.WhatsNew.newestVersion, tipsPillDismissed = true,
            )
        }
        store.flush()
        preloadCovers()
    }

    /** Album art for the demo YouTube ids, put where the cards look first: no cover is fetched. */
    @Suppress("UNCHECKED_CAST")
    private fun preloadCovers() {
        val field = com.xlollx.songport.ui.Cards::class.java.getDeclaredField("coverCache").apply { isAccessible = true }
        val cache = field.get(com.xlollx.songport.ui.Cards) as HashMap<String, Bitmap?>
        val palettes = listOf(
            intArrayOf(0xFF1B1B3A.toInt(), 0xFFE63946.toInt()), intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
            intArrayOf(0xFF3A1C71.toInt(), 0xFFFFAF7B.toInt()), intArrayOf(0xFF000000.toInt(), 0xFF8E9EAB.toInt()),
            intArrayOf(0xFF42275A.toInt(), 0xFF734B6D.toInt()), intArrayOf(0xFF141E30.toInt(), 0xFFF7971E.toInt()),
            intArrayOf(0xFF232526.toInt(), 0xFFB31217.toInt()), intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt()),
        )
        val albums = (Demo.SONGS + Demo.EXTRA).map { it.album }.distinct()
        val art = albums.mapIndexed { i, album -> album to cover(palettes[i % palettes.size], i) }.toMap()
        for (n in 0 until 1300) {
            val id = ytm.id(n)
            val song = Demo.SONGS[n % Demo.SONGS.size]
            val bmp = art.getValue(song.album)
            synchronized(cache) {
                cache["https://i.ytimg.com/vi/$id/hq720.jpg"] = bmp
                cache["https://i.ytimg.com/vi/$id/mqdefault.jpg"] = bmp
            }
        }
    }

    private fun cover(colors: IntArray, seed: Int): Bitmap {
        val side = 480
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, side.toFloat(), side.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (seed % 4) {
            0 -> { p.color = 0x55FFFFFF; c.drawCircle(side * 0.62f, side * 0.4f, side * 0.26f, p) }
            1 -> { p.color = 0x44000000; for (k in 0 until 6) c.drawRect(0f, k * 80f + 20f, side.toFloat(), k * 80f + 50f, p) }
            2 -> { p.color = 0x66FFFFFF; p.style = Paint.Style.STROKE; p.strokeWidth = 14f; for (k in 1..4) c.drawCircle(side / 2f, side / 2f, k * 50f, p) }
            else -> { p.color = 0x55FFFFFF; val path = android.graphics.Path().apply { moveTo(side / 2f, 60f); lineTo(side - 70f, side - 80f); lineTo(70f, side - 80f); close() }; c.drawPath(path, p) }
        }
        return b
    }

    private fun setLanguage(tag: String) {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        loc = app.createConfigurationContext(config)
    }

    private fun s(id: Int, vararg args: Any): String = loc.getString(id, *args)

    // ------------------------------------------------------------------ the pictures

    private fun shootLanguage(folder: String) {
        val dir = File(app.getExternalFilesDir(null), "shots/$folder").apply { mkdirs() }
        var n = 0
        fun snap(settle: Long = 1200) {
            compose.waitForIdle(); Thread.sleep(settle); compose.waitForIdle()
            val bmp = instr.uiAutomation.takeScreenshot() ?: return
            n++
            File(dir, "$n.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun main(): Intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        // Started straight from the app context (ActivityScenario never sees a singleTask activity come
        // up), each launch clearing the task of the one before.
        fun open(intent: Intent, body: () -> Unit) {
            Store.get(app).updateSettings { it.copy(appOpens = 1) }
            val before = resumed()
            app.startActivity(intent)
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end) {
                val now = resumed()
                if (now is MainActivity && now !== before) break
                Thread.sleep(200)
            }
            android.util.Log.i("StoreScreenshots", "open ${intent.action ?: intent.extras?.keySet()} -> ${resumed()?.javaClass?.simpleName}")
            compose.waitForIdle()
            body()
        }
        fun waitText(text: String, ms: Long = 15_000) = compose.waitUntil(ms) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

        // 1 Syncs, the cards in numbers.
        open(main().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SYNCS)) { waitText(Demo.PLAYLISTS[0].second); snap() }
        // 2 A new sync: Spotify to YouTube Music.
        open(main().setAction(MainActivity.ACTION_NEW_SYNC)) {
            step("shot $n") {
                waitText("Spotify")
                compose.waitForIdle()
                val yt = compose.onAllNodesWithText("YouTube Music").fetchSemanticsNodes()
                if (yt.size >= 2) compose.onAllNodesWithText("YouTube Music")[1].performClick()
            }
            snap()
        }
        // 3 Accounts, 4 the add-a-service grid.
        open(main().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACCOUNTS)) {
            waitText(fourthName); snap()
            // The Play build's picker lists, greyed out, the services it cannot reach (Amazon Music
            // among them): not a picture for its listing.
            if (play) return@open
            // The "Add connector" button, then the grid of services. The test sees neither the button's
            // merged node nor the dialog, and the injected click fails, so: a real tap on the label, and
            // the picture kept only if it differs from the one before (the grid did open).
            step("add") {
                val before = instr.uiAutomation.takeScreenshot()
                val r = compose.onAllNodesWithText(s(R.string.connector_add), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInWindow
                instr.uiAutomation.executeShellCommand("input tap ${r.center.x.toInt()} ${r.center.y.toInt()}").close()
                Thread.sleep(2500)
                val after = instr.uiAutomation.takeScreenshot()
                val opened = after != null && (before == null || !after.sameAs(before))
                android.util.Log.i("StoreScreenshots", "add dialog opened: $opened")
                if (opened) { n++; File(dir, "$n.png").outputStream().use { after!!.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
        }
        // 5 Discover.
        open(main().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DISCOVER)) { waitText(s(R.string.discover_wrapped_title)); snap() }
        // 6 A friend's Spotify track, into a YouTube Music playlist.
        open(main().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://open.spotify.com/track/${spotify.id(0)}")) {
            step("shot $n") { waitText("Master of Puppets", 20_000); Thread.sleep(4000) }
            snap()
        }
        // 7 How compatible are we: two playlist links shared together.
        val linkA = "https://music.youtube.com/playlist?list=${Demo.LINK_ID}"
        val linkB = "https://open.spotify.com/playlist/${Demo.OTHER_LINK_ID}"
        open(main().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$linkA\n$linkB")) {
            step("shot $n") {
                waitText(s(R.string.card_share), 30_000)
                compose.onAllNodesWithText(s(R.string.card_share), substring = true).onFirst().performScrollTo()
            }
            snap(2000)
        }
        // 8 The playlist poster.
        open(main().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_DISCOVER)) {
            step("shot $n") {
                waitText(s(R.string.tools_poster_title))
                compose.onNodeWithText(s(R.string.tools_poster_title)).performScrollTo().performClick()
                waitText(s(R.string.card_source_link_hint))
                compose.onNode(hasSetTextAction()).performTextInput(linkA)
                compose.onNodeWithText(s(R.string.link_use)).performClick()
                waitText("1152")
                hideKeyboard()
                compose.onNodeWithText(s(R.string.card_poster_run)).performScrollTo().performClick()
                waitText(s(R.string.card_share), 30_000)
                compose.onAllNodesWithText(s(R.string.card_share), substring = true).onFirst().performScrollTo()
            }
            snap(2000)
        }
        // 9 Unplayable tracks, found and with their replacements.
        open(main().putExtra(MainActivity.EXTRA_TOOL, MainActivity.TOOL_UNPLAYABLE)) {
            step("shot $n") {
                waitText(s(R.string.unplayable_scan))
                compose.onNodeWithText(s(R.string.unplayable_scan)).performClick()
                waitText(Demo.SONGS[3].title, 20_000)
                Thread.sleep(3000)
            }
            snap()
        }
        // 10 Settings, the look.
        open(main().putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)) {
            // The section after it, so the bottom bar sits on that one and the look shows whole.
            step("settings") { compose.onAllNodesWithText(s(R.string.backup_title)).onFirst().performScrollTo() }
            snap()
        }
        // 11 The welcome, first page.
        Store.get(app).updateSettings { it.copy(onboardingDone = false) }
        open(main()) { snap(2000) }
        Store.get(app).updateSettings { it.copy(onboardingDone = true) }
        resumed()?.let { a -> instr.runOnMainSync { a.finish() } }
    }

    /** Settings and Tools from top to bottom, for review: review/<name>.png, never a store picture. */
    private fun shootReview() {
        val dir = File(app.getExternalFilesDir(null), "review").apply { mkdirs() }
        val store = Store.get(app)
        fun snap(name: String, settle: Long = 1200) {
            compose.waitForIdle(); Thread.sleep(settle); compose.waitForIdle()
            val bmp = instr.uiAutomation.takeScreenshot() ?: return
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.i("StoreScreenshots", "review $name")
        }
        fun tab(t: Int, body: () -> Unit) {
            val before = resumed()
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(MainActivity.EXTRA_TAB, t))
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end) {
                val now = resumed()
                if (now is MainActivity && now !== before) break
                Thread.sleep(200)
            }
            compose.waitForIdle()
            step("review tab $t") { body() }
        }
        fun scrollTo(text: String) = compose.onAllNodesWithText(text).onFirst().performScrollTo()
        // To the very end, so the last row shows where the bottom padding leaves it (above the bar).
        fun toBottom(lastText: String) {
            compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(lastText)))
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.waitForIdle()
        }
        fun settingsPages(theme: String) {
            tab(MainActivity.TAB_SETTINGS) {
                compose.waitUntil(30_000) { compose.onAllNodesWithText(s(R.string.settings_theme)).fetchSemanticsNodes().isNotEmpty() }
                snap("settings_${theme}_1")
                scrollTo(s(R.string.settings_section_privacy)); snap("settings_${theme}_2")
                scrollTo(s(R.string.settings_section_advanced)); snap("settings_${theme}_3")
                // Settings › Help › Suggest an idea: empty, then written.
                // Its click action, not a tap: scrolled into view, the row can sit under the bottom bar.
                scrollTo(s(R.string.idea_row)); compose.onAllNodesWithText(s(R.string.idea_row)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(s(R.string.idea_title)).fetchSemanticsNodes().size > 1 }
                snap("idea_${theme}_1")
                compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Sync my Shazam history to a playlist every week")
                hideKeyboard(); snap("idea_${theme}_2")
                instr.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle()
                toBottom(s(R.string.settings_about)); snap("settings_${theme}_4")
            }
        }
        fun toolsPages(theme: String) {
            tab(MainActivity.TAB_TOOLS) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(s(R.string.tools_manage_title)).fetchSemanticsNodes().isNotEmpty() }
                snap("tools_${theme}_1")
                scrollTo(s(R.string.tools_section_clean)); snap("tools_${theme}_2")
                toBottom(s(R.string.tools_section_safety)); snap("tools_${theme}_3")
            }
        }
        // The top bar's update arrow (GitHub build): a newer version, as if GitHub had just said so.
        fun syncsTop(theme: String) {
            app.getSharedPreferences("app_updates", 0).edit().putString("name", "9.9.9")
                .putString("url", "https://github.com/xlollx/Trackhop/releases/latest").putLong("checked", System.currentTimeMillis()).commit()
            tab(MainActivity.TAB_SYNCS) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(s(R.string.update_pill)).fetchSemanticsNodes().isNotEmpty() }
                snap("syncs_${theme}_update", 3000)
            }
        }
        // The welcome's last page: the website and GitHub cards.
        fun welcomeLinks(theme: String) {
            store.updateSettings { it.copy(onboardingDone = false) }; store.flush()
            tab(MainActivity.TAB_SYNCS) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(s(R.string.onboarding_next)).fetchSemanticsNodes().isNotEmpty() }
                repeat(4) { compose.onAllNodesWithText(s(R.string.onboarding_next)).onFirst().performClick(); compose.waitForIdle(); Thread.sleep(500) }
                compose.waitUntil(10_000) { compose.onAllNodesWithText(s(R.string.onboarding_docs_github_desc)).fetchSemanticsNodes().isNotEmpty() }
                snap("welcome_links_$theme")
            }
            store.updateSettings { it.copy(onboardingDone = true) }; store.flush()
        }
        // The "Did you know?" page (widgets, shortcuts, automation), opened from its pill on Syncs.
        fun tips(theme: String) {
            store.updateSettings { it.copy(tipsPillDismissed = false) }; store.flush()
            tab(MainActivity.TAB_SYNCS) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(s(R.string.tips_pill)).fetchSemanticsNodes().isNotEmpty() }
                compose.onAllNodesWithText(s(R.string.tips_pill)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(10_000) { compose.onAllNodesWithText(s(R.string.tips_intro)).fetchSemanticsNodes().isNotEmpty() }
                snap("tips_$theme")
            }
            store.updateSettings { it.copy(tipsPillDismissed = true) }; store.flush()
        }
        for (theme in listOf("DARK", "LIGHT")) {
            store.updateSettings { it.copy(themeMode = theme) }; store.flush()
            val t = theme.lowercase()
            syncsTop(t)
            settingsPages(t)
            toolsPages(t)
            welcomeLinks(t)
            tips(t)
        }
        // A large font: nothing cut, nothing under the bottom bar.
        store.updateSettings { it.copy(themeMode = "DARK") }; store.flush()
        step("font") { instr.uiAutomation.executeShellCommand("settings put system font_scale 1.5").close(); Thread.sleep(1500) }
        tab(MainActivity.TAB_SETTINGS) { snap("settings_bigfont_1"); scrollTo(s(R.string.backup_title)); snap("settings_bigfont_2") }
        tab(MainActivity.TAB_TOOLS) { snap("tools_bigfont_1"); scrollTo(s(R.string.tools_section_share)); snap("tools_bigfont_2") }
        tips("bigfont")
        step("font back") { instr.uiAutomation.executeShellCommand("settings put system font_scale 1.0").close(); Thread.sleep(1500) }
        // Tools with no service connected: the way to Accounts.
        val connectors = store.data.settings.connectors
        store.updateSettings { it.copy(connectors = emptyList()) }; store.flush()
        tab(MainActivity.TAB_TOOLS) { snap("tools_noservice_dark") }
        store.updateSettings { it.copy(connectors = connectors) }; store.flush()
        resumed()?.let { a -> instr.runOnMainSync { a.finish() } }
    }

    /** One fallible part of a picture: a miss is logged and the picture is taken anyway. */
    private fun step(what: String, body: () -> Unit) {
        runCatching(body).onFailure { android.util.Log.w("StoreScreenshots", "$what: $it") }
    }

    private fun hideKeyboard() {
        val a = resumed() ?: return
        instr.runOnMainSync {
            a.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(a.window.decorView.windowToken, 0)
            a.currentFocus?.clearFocus()
        }
        compose.waitForIdle(); Thread.sleep(600)
    }

    private fun resumed(): Activity? {
        var found: Activity? = null
        instr.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() }
        return found
    }
}
