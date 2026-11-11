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
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
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
import com.xlollx.songport.platform.env
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
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Merch
import com.xlollx.songport.sync.Records
import com.xlollx.songport.sync.SoundCloudPublic
import com.xlollx.songport.sync.Wrapped
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
        // The Play listing shows no other brand's mark: initials on plain circles.
        com.xlollx.songport.ui.LogoMode.neutral = play
        installDemo()
        // -e review true: only the pictures for checking the Settings and Tools layout (English,
        // dark and light, a large font, Tools with no service), kept apart from the store's.
        if (InstrumentationRegistry.getArguments().getString("review") == "true") {
            setLanguage("en")
            shootReview()
            return
        }
        // -e carousel true: the pictures for a social carousel (English, dark), kept apart from the store's.
        if (InstrumentationRegistry.getArguments().getString("carousel") == "true") {
            setLanguage("en")
            shootCarousel()
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
                waitText(s(R.string.discover_wrapped_title))
                // The cards are a lazy grid: the poster may be further down.
                compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.tools_poster_title)))
                compose.onNodeWithText(s(R.string.tools_poster_title)).performClick()
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

    // ------------------------------------------------------------------ the carousel

    /** The pictures the carousel's pages show (covers, concerts, merch, artists), by their made-up address. */
    private val carouselImages = HashMap<String, Bitmap>()

    /** The artists followed in the carousel, A to Z; the last one muted. */
    private val CAROUSEL_FOLLOWED = listOf("Architects", "Bring Me the Horizon", "Metallica", "Sleep Token")

    private fun img(kind: String, n: Int) = "https://img.carousel.invalid/$kind/$n.jpg"

    /** Puts the pictures where the pages look first: none is fetched. Again before each page, as a fetch elsewhere may empty the cache. */
    @Suppress("UNCHECKED_CAST")
    private fun carouselPictures() {
        preloadCovers()
        val field = com.xlollx.songport.ui.Cards::class.java.getDeclaredField("coverCache").apply { isAccessible = true }
        val cache = field.get(com.xlollx.songport.ui.Cards) as HashMap<String, Bitmap?>
        synchronized(cache) { cache.putAll(carouselImages) }
        // The artists' round pictures: Deezer's address for each, kept as if asked already.
        val pics = com.xlollx.songport.sync.DeezerCatalog::class.java.getDeclaredField("artistPictures").apply { isAccessible = true }
        val byName = pics.get(com.xlollx.songport.sync.DeezerCatalog) as MutableMap<String, String>
        CAROUSEL_FOLLOWED.forEachIndexed { i, name -> byName[com.xlollx.songport.sync.Matcher.normalizeArtist(name)] = img("artist", i) }
    }

    /** A thing on sale: a plain background and its shape (a tee, a record, a poster, a hoodie). */
    private fun product(bg: Int, fg: Int, shape: Int): Bitmap {
        val side = 480
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        when (shape) {
            0, 3 -> {
                val tee = android.graphics.Path().apply {
                    moveTo(170f, 90f); lineTo(310f, 90f); lineTo(420f, 150f); lineTo(380f, 240f); lineTo(340f, 215f)
                    lineTo(340f, 420f); lineTo(140f, 420f); lineTo(140f, 215f); lineTo(100f, 240f); lineTo(60f, 150f); close()
                }
                c.drawPath(tee, p)
                if (shape == 3) { p.color = fg; c.drawCircle(240f, 95f, 55f, p) }
                p.color = bg; c.drawCircle(240f, 88f, 34f, p)
                p.color = 0x99FFFFFF.toInt(); c.drawCircle(240f, 270f, 52f, p)
            }
            1 -> {
                p.color = 0xFF111111.toInt(); c.drawCircle(240f, 240f, 200f, p)
                p.color = 0xFF2A2A2A.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                for (r in 90..190 step 14) c.drawCircle(240f, 240f, r.toFloat(), p)
                p.style = Paint.Style.FILL; p.color = fg; c.drawCircle(240f, 240f, 70f, p)
                p.color = bg; c.drawCircle(240f, 240f, 9f, p)
            }
            else -> {
                p.color = fg; c.drawRect(110f, 50f, 370f, 430f, p)
                p.color = 0x66FFFFFF; c.drawCircle(240f, 190f, 80f, p)
                p.color = 0x99000000.toInt(); c.drawRect(140f, 330f, 340f, 350f, p); c.drawRect(170f, 370f, 310f, 385f, p)
            }
        }
        return b
    }

    private fun carouselDemo() {
        val palettes = listOf(
            intArrayOf(0xFF1B1B3A.toInt(), 0xFFE63946.toInt()), intArrayOf(0xFF0F2027.toInt(), 0xFF2C5364.toInt()),
            intArrayOf(0xFF3A1C71.toInt(), 0xFFFFAF7B.toInt()), intArrayOf(0xFF000000.toInt(), 0xFF8E9EAB.toInt()),
            intArrayOf(0xFF42275A.toInt(), 0xFF734B6D.toInt()), intArrayOf(0xFF141E30.toInt(), 0xFFF7971E.toInt()),
            intArrayOf(0xFF232526.toInt(), 0xFFB31217.toInt()), intArrayOf(0xFF1D2B64.toInt(), 0xFFF8CDDA.toInt()),
            intArrayOf(0xFF0B486B.toInt(), 0xFFF56217.toInt()),
        )
        for (i in 0 until 9) carouselImages[img("wrapped", i)] = cover(palettes[i], i)
        for (i in 0 until 8) carouselImages[img("concert", i)] = cover(palettes[(i + 3) % palettes.size], i + 2)
        for (i in 0 until 8) carouselImages[img("record", i)] = cover(palettes[(i + 5) % palettes.size], i + 1)
        for (i in CAROUSEL_FOLLOWED.indices) carouselImages[img("artist", i)] = cover(palettes[(i * 2 + 1) % palettes.size], i)
        val goods = listOf(
            Triple(0xFF2B2B2B.toInt(), 0xFF0E0E0E.toInt(), 0), Triple(0xFFE9E4DA.toInt(), 0xFFC62828.toInt(), 1),
            Triple(0xFF263238.toInt(), 0xFFB0BEC5.toInt(), 2), Triple(0xFFD7CCC8.toInt(), 0xFF3E2723.toInt(), 3),
            Triple(0xFF1A237E.toInt(), 0xFFFFD54F.toInt(), 0), Triple(0xFFF5F5F5.toInt(), 0xFF6A1B9A.toInt(), 1),
            Triple(0xFF4E342E.toInt(), 0xFFFFCC80.toInt(), 2), Triple(0xFF212121.toInt(), 0xFF8D6E63.toInt(), 3),
        )
        goods.forEachIndexed { i, (bg, fg, shape) -> carouselImages[img("merch", i)] = product(bg, fg, shape) }

        val store = Store.get(app)
        val now = System.currentTimeMillis()
        fun t(id: String, title: String, artist: String, album: String, sec: Int) =
            com.xlollx.songport.model.Track(id = id, title = title, artists = listOf(artist), album = album, durationMs = sec * 1000L)
        val missing = listOf(
            t(spotify.id(700), "Master of Puppets (Remastered)", "Metallica", "Master of Puppets (Deluxe Box Set)", 516),
            t(spotify.id(701), "Chop Suey! - Live at Big Day Out", "System of a Down", "Live Sessions", 214),
            t(spotify.id(702), "Numb / Encore", "Linkin Park", "Collision Course", 205),
        )
        val proposals = listOf(
            t(ytm.id(0), "Master of Puppets", "Metallica", "Master of Puppets", 515),
            t(ytm.id(2), "Chop Suey!", "System of a Down", "Toxicity", 210),
            t(ytm.id(3), "Numb/Encore (Live)", "Linkin Park", "Collision Course", 205),
        )
        val unsure = listOf(
            com.xlollx.songport.model.MatchReview(t(spotify.id(703), "Duality (Live)", "Slipknot", "9.0: Live", 260), t(ytm.id(5), "Duality", "Slipknot", "Vol. 3: (The Subliminal Verses)", 252), 0.82),
            com.xlollx.songport.model.MatchReview(t(spotify.id(704), "The Trooper (2015 Remaster)", "Iron Maiden", "Piece of Mind (2015 Remaster)", 252), t(ytm.id(13), "The Trooper", "Iron Maiden", "Piece of Mind", 250), 0.91),
        )
        val summerReports = (0 until 8).map { r ->
            SyncReport(
                id = "job-summer-$r", jobId = "job-summer", jobName = Demo.CAROUSEL_NAME, startedEpoch = now - (8 - r) * 24 * 3_600_000L - 11 * 60_000L,
                durationMs = 9_000L + r * 400, sourceCount = 22, targetCount = 15, added = if (r == 7) 6 else r % 3 + 1, removed = if (r == 7) 2 else r % 2,
            )
        }
        val summer = SyncJob(
            "job-summer", Demo.CAROUSEL_NAME, PlaylistRef("spotify", Demo.CAROUSEL_SRC, Demo.CAROUSEL_NAME), PlaylistRef(yt, Demo.CAROUSEL_DST, Demo.CAROUSEL_NAME),
            Schedule.DAILY, mirrorRemovals = true, label = "green", color = "green", lastRunEpoch = summerReports.last().startedEpoch, lastReportId = summerReports.last().id,
        )
        store.update { d ->
            val jobs = d.jobs.map { j ->
                when (j.id) {
                    "job-metal" -> j.copy(favorite = true, label = "red", color = "red")
                    "job-road" -> j.copy(label = "orange", color = "orange")
                    "job-chill" -> j.copy(label = "teal", color = "teal")
                    "job-workout" -> j.copy(label = "purple", color = "purple")
                    else -> j
                }
            }
            val reports = d.reports.map { r ->
                if (r.id != "job-metal-9") r
                else r.copy(unmatched = missing.map { it.toString() }, unmatchedTracks = missing, suggestions = missing.zip(proposals).associate { (m, p) -> m.id to p }, reviewTracks = unsure)
            }
            d.copy(jobs = jobs + summer, reports = reports + summerReports, wrapped = listOf(carouselWrapped(now)))
        }
        val kinds = com.xlollx.songport.sync.ArtistAlerts.Kinds()
        store.updateSettings {
            it.copy(
                labels = listOf(
                    com.xlollx.songport.model.Label("red", "red", "Metal"), com.xlollx.songport.model.Label("orange", "orange", "Road"),
                    com.xlollx.songport.model.Label("teal", "teal", "Chill"), com.xlollx.songport.model.Label("purple", "purple", "Gym"),
                    com.xlollx.songport.model.Label("green", "green", "Summer"),
                ),
                discoverImages = true, discoverImagesWifiOnly = false, artistAlerts = true,
                followedArtists = CAROUSEL_FOLLOWED.mapIndexed { i, name ->
                    com.xlollx.songport.sync.ArtistAlerts.Followed(
                        name, kinds = if (i == 1) kinds.copy(merch = false) else kinds, muted = name == "Sleep Token", mbid = "carousel-artist-$i",
                    )
                },
            )
        }
        store.flush()
        // The last news of the followed artists, as the alerts left them.
        fun news(artist: String, kind: String, id: String, title: String, detail: String) = org.json.JSONObject()
            .put("artist", artist).put("kind", kind).put("id", id).put("title", title).put("detail", detail).put("url", "https://www.example.com/news/$id")
        val latest = org.json.JSONArray()
            .put(news("Architects", "RELEASE", "r1", "Seeing Red", "SINGLE"))
            .put(news("Bring Me the Horizon", "RELEASE", "r2", "Post Human: Nex Gen", "ALBUM"))
            .put(news("Metallica", "CONCERT", "c1", "Allegiant Stadium", "Las Vegas"))
            .put(news("Architects", "CONCERT", "c2", "The O2", "London"))
            .put(news("Metallica", "RECORD", "v1", "72 Seasons (Vinyl)", ""))
            .put(news("Bring Me the Horizon", "CONCERT", "c3", "Madison Square Garden", "New York"))
            .put(news("Sleep Token", "RELEASE", "r3", "Even in Arcadia", "ALBUM"))
        app.env.keyValues("artist_alerts")["latest"] = latest.toString()
        app.getSharedPreferences("records", Context.MODE_PRIVATE).edit().putString("look", "ALL").commit()
        keptShow("com.xlollx.songport.ui.ConcertsKept", carouselConcerts())
        keptShow("com.xlollx.songport.ui.RecordsKept", carouselRecords(now))
    }

    /** A page's last search, as if it had just ended ([com.xlollx.songport.ui.KeptWork.show]). */
    private fun keptShow(holder: String, result: Any) {
        val kept = Class.forName(holder)
        val instance = kept.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val work = kept.getDeclaredMethod("getWork").apply { isAccessible = true }.invoke(instance)!!
        instr.runOnMainSync { work.javaClass.getDeclaredMethod("show", String::class.java, Any::class.java).apply { isAccessible = true }.invoke(work, null, result) }
    }

    private fun carouselWrapped(now: Long): Wrapped.Saved {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return Wrapped.Saved(
            id = "carousel", madeAt = now - 3_600_000L, year = year, trackCount = 1283, age = year - 1997, bornIn = 1997,
            topArtists = listOf(Wrapped.Artist("Metallica", 48), Wrapped.Artist("Slipknot", 37), Wrapped.Artist("Linkin Park", 31), Wrapped.Artist("System of a Down", 26), Wrapped.Artist("Avenged Sevenfold", 22)),
            decades = listOf(Wrapped.Decade(2000, 590, 46), Wrapped.Decade(1990, 308, 24), Wrapped.Decade(1980, 231, 18)),
            artistCount = 214, durationMs = 1283L * 251_000L,
            oldest = Wrapped.Song("Paranoid", "Black Sabbath", 1970), newest = Wrapped.Song("Emergence", "Sleep Token", 2025, now - 2 * 86_400_000L),
            services = listOf(Wrapped.Service("spotify", "Spotify", 0xFF1DB954), Wrapped.Service("ytm", "YouTube Music", 0xFFFF0000)),
            covers = (0 until 9).map { img("wrapped", it) },
        )
    }

    private fun carouselConcerts(): Concerts.Result {
        val today = java.time.LocalDate.now()
        var n = 0
        fun ev(artist: String, days: Long, time: String, city: String, venue: String) = Concerts.Event(
            artist = artist, name = "$artist live", date = today.plusDays(days).toString(), time = time, city = city, venue = venue, country = "US",
            url = "https://www.ticketmaster.com/event/carousel-${n}", source = Concerts.Source.TICKETMASTER, timeZone = "America/Los_Angeles", image = img("concert", n++),
        )
        fun search(artist: String) = listOf(Concerts.Link("Ticketmaster", "https://www.ticketmaster.com/search?q=" + artist.replace(' ', '+')), Concerts.Link(null, "https://www.example.com/search?q=" + artist.replace(' ', '+')))
        val artists = listOf(
            Concerts.ArtistDates("Metallica", listOf(ev("Metallica", 23, "19:00", "Las Vegas", "Allegiant Stadium"), ev("Metallica", 25, "19:00", "Inglewood", "SoFi Stadium"), ev("Metallica", 31, "18:30", "Seattle", "Lumen Field")), emptyList()),
            Concerts.ArtistDates("Slipknot", listOf(ev("Slipknot", 44, "19:30", "Chicago", "United Center"), ev("Slipknot", 47, "19:30", "Detroit", "Little Caesars Arena")), emptyList()),
            Concerts.ArtistDates("Linkin Park", listOf(ev("Linkin Park", 62, "20:00", "New York", "Madison Square Garden")), emptyList()),
            Concerts.ArtistDates("Evanescence", emptyList(), search("Evanescence")),
            Concerts.ArtistDates("Disturbed", emptyList(), search("Disturbed")),
        )
        return Concerts.Result(
            country = "US", artists = artists, total = artists.size, ticketmaster = true, ownKey = true, quotaHit = false, keyRefused = false,
            range = Concerts.Range.SIX_MONTHS,
        )
    }

    private fun carouselRecords(now: Long): Records.Result {
        var n = 0
        fun rec(title: String, year: Int, formats: List<Records.Format>, id: Long, forSale: Int, price: Double) = Records.Record(
            title = title, year = year, formats = formats, url = "https://www.discogs.com/master/$id", releaseId = id, have = 4000,
            forSale = forSale, lowestPrice = price, currency = "USD", thumb = img("record", n++),
        )
        var k = 0
        fun item(title: String, type: String, price: Double, source: Merch.Source = Merch.Source.SHOP) =
            Merch.Item(source = source, title = title, type = type, image = img("merch", k++), price = price, currency = "USD", url = "https://shop.example.com/products/item-$k")
        fun searches(artist: String) = listOf(Records.Search("Discogs", "https://www.discogs.com/search?q=$artist"), Records.Search("eBay", "https://www.ebay.com/sch?q=$artist"), Records.Search(null, "https://www.example.com/search?q=$artist"))
        val vinylCd = listOf(Records.Format.VINYL, Records.Format.CD)
        val artists = listOf(
            Records.ArtistRecords(
                "Metallica",
                records = listOf(
                    rec("72 Seasons", 2023, vinylCd + Records.Format.CASSETTE, 3046125, 214, 24.99),
                    rec("Master of Puppets", 1986, vinylCd, 6495, 1380, 18.50),
                    rec("Ride the Lightning", 1984, vinylCd, 6440, 962, 16.00),
                ),
                links = listOf(Records.Link(Records.Kind.OFFICIAL, "metallica.com", "https://www.metallica.com"), Records.Link(Records.Kind.SHOP, "", "https://www.metallica.com/store")),
                searches = searches("Metallica"), discogsPage = "https://www.discogs.com/artist/18839",
                merch = listOf(item("M72 World Tour Tee", "T-Shirt", 35.0), item("72 Seasons Burnt Vinyl", "Vinyl", 32.0), item("Ride the Lightning Poster", "Poster", 20.0), item("Seek & Destroy Hoodie", "Hoodie", 65.0)),
                shopRecords = emptyList(),
            ),
            Records.ArtistRecords(
                "Ghost",
                records = listOf(rec("Skeletá", 2025, vinylCd, 3511820, 88, 27.00), rec("Impera", 2022, vinylCd, 2534371, 312, 21.50)),
                links = listOf(Records.Link(Records.Kind.OFFICIAL, "ghost-official.com", "https://www.ghost-official.com")),
                searches = searches("Ghost"), discogsPage = "https://www.discogs.com/artist/1036412",
                merch = listOf(item("Skeletá Tour Tee", "T-Shirt", 32.0), item("Papa V Picture Disc", "Vinyl", 38.0), item("Impera Poster", "Poster", 18.0), item("Ritual Hoodie", "Hoodie", 60.0)),
                shopRecords = emptyList(),
            ),
        )
        return Records.Result(artists = artists, total = artists.size, token = true, tokenRefused = false, slowedDown = false, currency = "USD", look = Records.Look.ALL, at = now)
    }

    /** The carousel: the best of Trackhop, one page each, English and dark. carousel/<nn>_<what>.png. */
    private fun shootCarousel() {
        carouselDemo()
        val store = Store.get(app)
        val dir = File(app.getExternalFilesDir(null), "carousel").apply { mkdirs() }
        fun snap(name: String, settle: Long = 1500) {
            compose.waitForIdle(); Thread.sleep(settle); compose.waitForIdle()
            val bmp = instr.uiAutomation.takeScreenshot() ?: return
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            android.util.Log.i("StoreScreenshots", "carousel $name")
        }
        fun main(): Intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        fun open(intent: Intent, what: String, body: () -> Unit) {
            store.updateSettings { it.copy(appOpens = 1) }
            carouselPictures()
            val before = resumed()
            app.startActivity(intent)
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end) {
                val now = resumed()
                if (now is MainActivity && now !== before) break
                Thread.sleep(200)
            }
            compose.waitForIdle()
            step("carousel $what") { body() }
        }
        fun waitText(text: String, ms: Long = 15_000) = compose.waitUntil(ms) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        fun tapText(text: String) {
            compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and hasText(text)).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        fun tab(t: Int) = main().putExtra(MainActivity.EXTRA_TAB, t)
        // Brings [text] (the [index]th of its kind) into view: straight, or through the lazy list holding it.
        fun bringIn(text: String, index: Int = 0) {
            val direct = runCatching { compose.onAllNodesWithText(text)[index].performScrollTo() }
            if (direct.isFailure) compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().indices.forEach { i ->
                runCatching { compose.onAllNodes(hasScrollToNodeAction())[i].performScrollToNode(hasText(text)) }
            }
            compose.waitForIdle()
        }
        fun discoverPage(title: String) {
            waitText(s(R.string.discover_wrapped_title))
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(title))
            tapText(title)
        }
        val firstJob = Demo.PLAYLISTS[0].second

        // 1 Syncs: the cards, scrolled a little so "Sync all" floats over them.
        open(tab(MainActivity.TAB_SYNCS), "syncs") {
            waitText(firstJob)
            Thread.sleep(1500)
            compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(firstJob))).fetchSemanticsNodes().size.let { k ->
                compose.onAllNodes(hasScrollAction() and hasAnyDescendant(hasText(firstJob)))[k - 1]
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 640f) }
            }
        }
        snap("01_syncs")
        // 2 A new sync: Spotify's "Metal & More" to YouTube Music.
        open(main().setAction(MainActivity.ACTION_NEW_SYNC), "editor") {
            waitText("Spotify")
            compose.waitForIdle()
            if (compose.onAllNodesWithText("YouTube Music").fetchSemanticsNodes().size >= 2) compose.onAllNodesWithText("YouTube Music")[1].performClick()
            compose.waitForIdle()
            tapText(s(R.string.editor_playlist))
            waitText(firstJob)
            tapText(firstJob)
            Thread.sleep(800)
            hideKeyboard()
            runCatching { compose.onAllNodesWithText(s(R.string.editor_source)).onFirst().performScrollTo() }
        }
        snap("02_editor")
        // 3 What a run could not find, with what to add instead.
        open(tab(MainActivity.TAB_SYNCS), "review") {
            waitText(firstJob)
            tapText(s(R.string.pill_not_found, 3))
            waitText(s(R.string.review_section_unmatched, 3))
        }
        snap("03_review")
        // 4 What the next run of "Summer Vibes" would do.
        open(tab(MainActivity.TAB_SYNCS), "preview") {
            waitText(firstJob)
            bringIn(Demo.CAROUSEL_NAME)
            compose.onAllNodes(hasText(Demo.CAROUSEL_NAME) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).onFirst()
                .performSemanticsAction(SemanticsActions.OnLongClick)
            waitText(s(R.string.preview))
            tapText(s(R.string.preview))
            waitText(s(R.string.run_now), 30_000)
        }
        snap("04_preview", 2500)
        // 5 Discover.
        open(tab(MainActivity.TAB_DISCOVER), "discover") { waitText(s(R.string.discover_wrapped_title)) }
        snap("05_discover")
        // 6 The Wrapped card, square, ready to share.
        open(tab(MainActivity.TAB_DISCOVER), "wrapped") {
            waitText(s(R.string.discover_wrapped_title))
            tapText(s(R.string.discover_wrapped_button))
            waitText(s(R.string.card_share), 30_000)
            tapText(s(R.string.card_format_square))
            Thread.sleep(1000)
            bringIn(s(R.string.card_share))
        }
        snap("06_wrapped", 2500)
        // 7 Concerts.
        open(tab(MainActivity.TAB_DISCOVER), "concerts") {
            discoverPage(s(R.string.discover_concerts_title))
            waitText("Allegiant Stadium")
            bringIn(s(R.string.concerts_add_calendar), 2)
        }
        snap("07_concerts", 2500)
        // 8 Records and merch.
        open(tab(MainActivity.TAB_DISCOVER), "records") {
            discoverPage(s(R.string.discover_records_title))
            waitText("Master of Puppets")
            bringIn("M72 World Tour Tee")
        }
        snap("08_records", 2500)
        // 9 Followed artists.
        open(main().putExtra(MainActivity.EXTRA_TOOL, MainActivity.TOOL_FOLLOWED), "followed") { waitText("Architects") }
        snap("09_followed", 2500)
        // 10 How compatible are we: two playlist links shared together.
        val linkA = "https://music.youtube.com/playlist?list=${Demo.LINK_ID}"
        val linkB = "https://open.spotify.com/playlist/${Demo.OTHER_LINK_ID}"
        open(main().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$linkA\n$linkB"), "compat") {
            waitText(s(R.string.card_share), 30_000)
            bringIn(s(R.string.card_share))
        }
        snap("10_compat", 2000)
        // 11 The syncs as small tiles.
        store.updateSettings { it.copy(minimalSyncs = true) }; store.flush()
        open(tab(MainActivity.TAB_SYNCS), "tiles") { waitText(firstJob) }
        snap("11_tiles")
        store.updateSettings { it.copy(minimalSyncs = false) }; store.flush()
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
