package com.xlollx.songport.flows

import com.xlollx.songport.ai.save
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two cards of Discover: "how compatible are we" (two playlists in, a score and a card out) and
 * the playlist poster with its QR code; and the poster's "cover drawn by your AI" option, with a
 * fake AI: its picture is already in the app's session cache, and any request it would make goes
 * to a closed local port through the closed network.
 */
@RunWith(AndroidJUnit4::class)
class CardsFlowTest : FlowTest() {

    private fun openTool(title: Int) {
        launch(tabIntent(MainActivity.TAB_DISCOVER))
        waitText(s(title))
        click(s(title))
    }

    /** In a playlist source card: the service tile, then its playlist from the drop-down. */
    private fun pickOwnPlaylist(service: String, playlist: String) {
        val tile = compose.onAllNodes(hasClickAction() and hasText(service)).onFirst()
        tile.performScrollTo(); tile.performClick()
        waitText(s(R.string.editor_playlist))
        click(s(R.string.editor_playlist))
        waitText(playlist)
        click(playlist)
        waitText("$playlist  ·  ", substring = true)
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
        val field = compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).onFirst()
        field.performScrollTo()
        field.performTextInput("https://open.spotify.com/playlist/${FakeWorld.PUBLIC_SPOTIFY}")
        click(s(R.string.link_use))
        waitText("Friday Night Rock", substring = true)
        val deezerTiles = compose.onAllNodes(hasClickAction() and hasText("Deezer"))
        // The second source card's Deezer tile.
        deezerTiles[deezerTiles.fetchSemanticsNodes().size - 1].apply { performScrollTo(); performClick() }
        val playlistFields = compose.onAllNodes(hasClickAction() and hasText(s(R.string.editor_playlist)))
        playlistFields[playlistFields.fetchSemanticsNodes().size - 1].apply { performScrollTo(); performClick() }
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
        aiRow().assertIsNotEnabled().assertIsOff()
    }

    @Test
    fun aiCoverOptionExplainsThatClaudeDoesNotDraw() {
        AiClient.save(app, AiClient.Config(AiClient.Vendor.ANTHROPIC, "test-key", "claude-test", "http://127.0.0.1:9/v1"))
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_ai_cover_claude))
        aiRow().assertIsNotEnabled()
    }

    @Test
    fun aiCoverIsUsedOnThePosterAndAFailedNewOneIsReported() {
        AiClient.save(app, AiClient.Config(AiClient.Vendor.OPENAI, "test-key", "gpt-test", "http://127.0.0.1:9/v1"))
        // The fake AI's drawing for Spotify's "Road Trip", where the poster looks before asking the AI.
        val drawing = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        aiCache()["spotify|${FakeWorld.SPOTIFY_ROAD}"] = drawing
        openTool(R.string.tools_poster_title)
        waitText(s(R.string.card_ai_cover_yes, "OpenAI"))
        aiRow().assertIsEnabled().assertIsOff().performClick()
        aiRow().assertIsOn()
        pickOwnPlaylist("Spotify", "Road Trip")
        click(s(R.string.card_poster_run))
        waitText(s(R.string.card_share), ms = 30_000)
        // The AI's own buttons show only when its cover is on the poster.
        waitText(s(R.string.card_ai_again))
        assertTrue(exists(s(R.string.card_ai_save)))
        assertFalse("the cached drawing needs no request", "127.0.0.1" in attemptedHosts)

        // "Another cover" asks the AI again: no network here, so the error is shown, nothing crashes.
        click(s(R.string.card_ai_again))
        waitText("network is closed", ms = 30_000, substring = true)
        assertTrue("127.0.0.1" in attemptedHosts)
    }

    private fun aiRow() = compose.onNode(isToggleable() and hasText(s(R.string.card_ai_cover))).also { runCatching { it.performScrollTo() } }

    @Suppress("UNCHECKED_CAST")
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
