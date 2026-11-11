package com.xlollx.songport.flows

import com.xlollx.songport.ai.save
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.providers.tracks
import com.xlollx.songport.sync.Compatibility
import com.xlollx.songport.sync.Wrapped
import com.xlollx.songport.ui.Cards
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two cards of Discover: "how compatible are we" (two playlists in, a score and a card out) and
 * the playlist poster with its QR code; the poster's covers drawn on the phone; and its "your AI"
 * choice, with a fake AI: its picture is already in the app's session cache, and any request it
 * would make goes to a closed local port through the closed network.
 */
@RunWith(AndroidJUnit4::class)
class CardsFlowTest : FlowTest() {

    /** The poster's art choice is remembered: every test starts from the default (the playlist's covers). */
    @org.junit.Before
    fun forgetPosterChoice() {
        app.getSharedPreferences("poster", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_wrapped_title))
        // The cards are a lazy grid: the one asked for may be further down.
        clickTile(s(title))
    }

    /**
     * A playlist source: its field opens the picker's sheet (with a playlist kept from an earlier
     * visit or none); there the service's mark, then its playlist from the rows. The sheet closes
     * and the field shows the playlist's name and "Change".
     */
    private fun pickOwnPlaylist(service: String, playlist: String) {
        compose.waitForIdle()
        waitText(s(R.string.editor_playlist))
        click(s(R.string.editor_playlist))
        val tile = hasClickAction() and hasText(service)
        waitFor("the picker's services") { compose.onAllNodes(tile).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(tile).onFirst().performClick()
        waitText(playlist)
        click(playlist)
        waitText(s(R.string.share_sheet_change))
        waitText(playlist, substring = true)
    }

    @Test
    fun twoSharedLinksAreComparedAtOnce() {
        val a = "https://open.spotify.com/playlist/${FakeWorld.PUBLIC_SPOTIFY}"
        val b = "https://www.deezer.com/playlist/${FakeWorld.PUBLIC_DEEZER}"
        launch(shareIntent("Ours?\n$a\n$b"))
        waitText(s(R.string.tools_compat_title), ms = 20_000)
        // The expected outcome, from the same playlists.
        val expected = runBlocking { Compatibility.compare(FakeWorld.spotify.tracks(app, FakeWorld.PUBLIC_SPOTIFY), FakeWorld.deezer.tracks(app, FakeWorld.PUBLIC_DEEZER)) }
        assertEquals("songs 2-5 are on both", 4, expected.common.size)
        waitText("${expected.score}%", ms = 30_000)
        waitText(s(R.string.card_compat_subtitle, expected.common.size, expected.sharedArtists.size))
        // The card itself, ready to share or save, in both formats.
        waitText(s(R.string.card_share), ms = 30_000)
        assertTrue(exists(s(R.string.card_save)))
        click(s(R.string.card_format_square))
        assertTrue(exists(s(R.string.card_share)))

        // A rotation keeps the comparison on screen.
        rotate(UiAutomation.ROTATION_FREEZE_90)
        waitText(s(R.string.card_compat_first), ms = 10_000)
        waitText("${expected.score}%", ms = 30_000)
    }

    @Test
    fun compareTwoPlaylistsPickedByHand() {
        openTool(R.string.tools_compat_title)
        waitText(s(R.string.card_compat_first))
        // First: a pasted link; second: one of my own Deezer playlists.
        click(s(R.string.card_compat_first))
        waitNode("the link field in the picker", androidx.compose.ui.test.hasSetTextAction())
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst().performTextInput("https://open.spotify.com/playlist/${FakeWorld.PUBLIC_SPOTIFY}")
        click(s(R.string.link_use))
        // The sheet closes on the playlist the link leads to: the first field shows it.
        waitFor("the first playlist in its field") { compose.onAllNodes(hasText(s(R.string.card_compat_first)) and hasText("Friday Night Rock", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // The second: Deezer's mark in its picker, then the playlist.
        click(s(R.string.card_compat_second))
        waitFor("the picker's services") { compose.onAllNodes(hasClickAction() and hasText("Deezer")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasClickAction() and hasText("Deezer")).onFirst().performClick()
        waitText("Workout"); click("Workout")
        click(s(R.string.card_compat_run))
        waitText(s(R.string.card_share), ms = 30_000)
        val expected = runBlocking { Compatibility.compare(FakeWorld.spotify.tracks(app, FakeWorld.PUBLIC_SPOTIFY), FakeWorld.deezer.tracks(app, FakeWorld.DEEZER_WORKOUT)) }
        waitText("${expected.score}%")
    }

    @Test
    fun posterCarriesAQrCodeOfThePlaylistPage() {
        openTool(R.string.tools_poster_title)
        pickOwnPlaylist("Spotify", "Road Trip")
        click(s(R.string.card_poster_run))
        waitText(s(R.string.card_share), ms = 30_000)
        assertFalse("a Spotify playlist has a public page", exists(s(R.string.card_poster_no_qr)))
        click(s(R.string.card_format_square))
        val url = "https://open.spotify.com/playlist/${FakeWorld.SPOTIFY_ROAD}"
        assertEquals(url, readQrOnScreen())
    }

    @Test
    fun aiCoverOptionIsOffWithoutAnAiAndExplainsWhy() {
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_ai_cover_none))
        aiChip().assertIsNotEnabled()
        // The default: the playlist's own covers.
        artChip(R.string.card_cover_covers).assertIsSelected()
        assertTrue(exists(s(R.string.card_cover_covers_note)))
    }

    @Test
    fun aiCoverOptionExplainsThatClaudeDoesNotDraw() {
        AiClient.save(app, AiClient.Config(AiClient.Vendor.ANTHROPIC, "test-key", "claude-test", "http://127.0.0.1:9/v1"))
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_ai_cover_claude))
        aiChip().assertIsNotEnabled()
    }

    @Test
    fun aiCoverIsUsedOnThePosterAndAFailedNewOneIsReported() {
        AiClient.save(app, AiClient.Config(AiClient.Vendor.OPENAI, "test-key", "gpt-test", "http://127.0.0.1:9/v1"))
        // The fake AI's drawing for Spotify's "Road Trip", where the poster looks before asking the AI.
        val drawing = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        aiCache()["spotify|${FakeWorld.SPOTIFY_ROAD}"] = drawing
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_cover_title))
        aiChip().assertIsEnabled().assertIsNotSelected().performClick()
        aiChip().assertIsSelected()
        // Chosen, it says which AI draws and that it may cost.
        waitText(s(R.string.card_ai_cover_yes, "OpenAI"))
        pickOwnPlaylist("Spotify", "Road Trip")
        click(s(R.string.card_poster_run))
        waitText(s(R.string.card_share), ms = 30_000)
        // The AI's own buttons show only when its cover is on the poster.
        waitText(s(R.string.card_ai_again))
        assertTrue(exists(s(R.string.card_ai_save)))
        assertFalse("the cached drawing needs no request", "127.0.0.1" in attemptedHosts)

        // "Another cover" asks the AI again: no network here, so the error is shown, nothing crashes,
        // and the poster is still made, with a cover drawn on the phone.
        click(s(R.string.card_ai_again))
        waitText("network is closed", ms = 30_000, substring = true)
        assertTrue("127.0.0.1" in attemptedHosts)
        waitText(s(R.string.card_share), ms = 30_000)
    }

    @Test
    fun aCoverDrawnOnThePhoneIsChosenRemadeAndRemembered() {
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_cover_title))
        artChip(R.string.card_cover_gradient).performClick()
        artChip(R.string.card_cover_gradient).assertIsSelected()
        artChip(R.string.card_cover_covers).assertIsNotSelected()
        waitText(s(R.string.card_cover_drawn_note))
        pickOwnPlaylist("Spotify", "Road Trip")
        click(s(R.string.card_poster_run))
        waitText(s(R.string.card_share), ms = 30_000)
        // A drawn cover can be drawn again (another seed) and saved alone; no AI is asked.
        waitText(s(R.string.card_cover_another))
        assertTrue(exists(s(R.string.card_ai_save)))
        assertFalse(exists(s(R.string.card_ai_again)))
        click(s(R.string.card_cover_another))
        waitText(s(R.string.card_share), ms = 30_000)
        assertFalse("127.0.0.1" in attemptedHosts)
        // The square poster still carries its QR code with the drawn art.
        click(s(R.string.card_format_square))
        assertEquals("https://open.spotify.com/playlist/${FakeWorld.SPOTIFY_ROAD}", readQrOnScreen())

        // The choice is kept for the next poster.
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_cover_title))
        artChip(R.string.card_cover_gradient).assertIsSelected()
    }

    @Test
    fun everyDrawnStyleMakesASquareCoverForAnyTitle() {
        val covers = listOf(Color.RED, Color.BLUE, Color.YELLOW, Color.GREEN).map { c -> Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(c) } }
        val inputs = listOf(
            com.xlollx.songport.ui.GeneratedCovers.Input("Chill", listOf("Bonobo"), covers),
            com.xlollx.songport.ui.GeneratedCovers.Input("A very long playlist title ".repeat(8), listOf("An artist with a rather long name".repeat(3)), covers.take(1)),
            com.xlollx.songport.ui.GeneratedCovers.Input("", emptyList(), emptyList()),
        )
        for (input in inputs) for (style in com.xlollx.songport.ui.GeneratedCovers.Style.entries) {
            val bmp = com.xlollx.songport.ui.GeneratedCovers.draw(style, input)
            assertEquals(com.xlollx.songport.ui.GeneratedCovers.SIDE, bmp.width)
            assertEquals(com.xlollx.songport.ui.GeneratedCovers.SIDE, bmp.height)
            // Deterministic: the same playlist draws the same cover.
            assertTrue(style.name, bmp.sameAs(com.xlollx.songport.ui.GeneratedCovers.draw(style, input)))
        }
    }

    /** A chip of the poster's art choice, scrolled into view. */
    private fun artChip(label: Int) = compose.onNode(isSelectable() and hasText(s(label))).also { runCatching { it.performScrollTo() } }

    private fun aiChip() = artChip(R.string.card_cover_ai)

    @Suppress("UNCHECKED_CAST")
    @Test
    fun wrappedIsKeptWithTheServicesChosenAndCanBeDeleted() {
        openTool(R.string.discover_wrapped_title)
        waitText(s(R.string.discover_wrapped_pick))
        // Both connected services start ticked; Deezer is left out this time.
        val deezer = { compose.onAllNodes(hasClickAction() and hasText("Deezer")).onFirst() }
        scrollTo(deezer()).assertIsSelected()
        tap(deezer(), "Deezer chip")
        waitFor("Deezer left out") { "deezer" in store.data.settings.wrappedSkipped }
        // Spotify, now the only one ticked, cannot be left out too.
        tap(compose.onAllNodes(hasClickAction() and hasText("Spotify")).onFirst(), "Spotify chip")
        waitText(s(R.string.discover_wrapped_keep_one))
        assertTrue(store.data.settings.wrappedSkipped.none { it == "spotify" })

        click(s(R.string.discover_wrapped_button))
        waitFor("a Wrapped kept", 30_000) { store.data.wrapped.size == 1 }
        val w = store.data.wrapped.single()
        assertEquals(listOf("Spotify"), w.services.map { it.name })
        val expected = runBlocking {
            Wrapped.compute(FakeWorld.playlists.getValue("spotify").filter { it.owned }.flatMap { FakeWorld.spotify.tracks(app, it.id) }, w.year)
        }
        assertEquals(expected.trackCount, w.trackCount)
        assertEquals(expected.topArtists, w.topArtists)
        val madeOn = s(R.string.discover_wrapped_made_on, DateFormat.getDateInstance(DateFormat.MEDIUM, app.resources.configuration.locales[0]).format(Date(w.madeAt)))
        // The line goes on with where the numbers come from ("· From your playlists").
        waitText(madeOn, substring = true)
        waitText(s(R.string.card_share), ms = 30_000)

        // Back on the page later: the last one is there at once, the chips still there with the last choice.
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(R.string.discover_wrapped_title))
        click(s(R.string.discover_wrapped_title))
        waitText(madeOn, substring = true, ms = 5_000)
        waitText(s(R.string.discover_wrapped_history))
        scrollTo(deezer()).assertIsNotSelected()
        assertTrue(exists(s(R.string.discover_wrapped_new)))

        // Deleted from the history, then put back.
        clickDesc(s(R.string.discover_wrapped_delete))
        waitFor("history empty") { store.data.wrapped.isEmpty() }
        click(s(R.string.unplayable_undo))
        waitFor("history back") { store.data.wrapped.map { it.id } == listOf(w.id) }
    }

    @Test
    fun wrappedCardDrawsAnyLengthInBothFormats() {
        val long = "Sir Reginald Alexander Montgomery-Featherstonehaugh and the Extraordinarily Long Orchestra of Everything ".repeat(3)
        val marks = (0 until 40).map { Cards.Mark("Service number $it with a long name", 0xFF1DB954.toInt(), if (it % 2 == 0) "spotify" else null) }
        val full = Cards.WrappedCard(
            eyebrow = s(R.string.discover_wrapped_eyebrow, 2026), headline = s(R.string.discover_wrapped_age, 46) + " " + long,
            subline = long, artistsTitle = long, artists = (1..5).map { "$it $long" to plural(R.plurals.card_tracks, 1234, 1234) },
            decadesTitle = long, decades = listOf(long to 100, "1990s" to 48, "2000s" to 0, "1960s" to 3),
            tiles = listOf("1,234,567,890" to long, "12" to long, "999" to long), facts = listOf(long to long, long to long),
            servicesTitle = long, services = marks,
        )
        val empty = Cards.WrappedCard(s(R.string.discover_wrapped_eyebrow, 2026), plural(R.plurals.card_tracks, 1, 1), null, "", emptyList(), "", emptyList(), emptyList(), emptyList(), "", emptyList())
        // With a drawn cover too: from covers, and from the title alone (a Wrapped kept before covers were).
        val tile = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE63946.toInt()) }
        val arts = listOf(null) + com.xlollx.songport.ui.GeneratedCovers.Style.entries.map { st ->
            Cards.WrappedArt(st, com.xlollx.songport.ui.GeneratedCovers.Input(long, listOf(long), if (st.ordinal % 2 == 0) List(9) { tile } else emptyList()))
        }
        for (card in listOf(full, empty)) for (art in arts) for (f in Cards.Format.entries) {
            val bmp = Cards.wrapped(app, card, f, art)
            assertEquals(f.width, bmp.width)
            assertEquals(f.height, bmp.height)
        }
    }

    private fun aiCache(): HashMap<String, Bitmap> {
        val f = Class.forName("com.xlollx.songport.ui.CardsScreenKt").getDeclaredField("aiCovers").apply { isAccessible = true }
        return (f.get(null) as HashMap<String, Bitmap>).also { it.clear() }
    }

    /** The QR code on screen, decoded; the card is scrolled so the poster shows whole. */
    private fun readQrOnScreen(): String {
        val attempts = listOf(s(R.string.card_share), s(R.string.card_format_square))
        for (anchor in attempts) {
            val node = compose.onAllNodesWithText(anchor).onFirst()
            node.performScrollTo()
            clearOfBottomBar(node)
            compose.waitForIdle()
            val bmp = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
            decodeQr(bmp)?.let { return it }
        }
        throw AssertionError("no QR code readable on screen")
    }

    private fun decodeQr(bmp: Bitmap): String? {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val source = RGBLuminanceSource(bmp.width, bmp.height, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
    }
}
