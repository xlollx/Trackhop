package com.xlollx.songport.flows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xlollx.songport.sync.PosterPalette
import com.xlollx.songport.ui.Cards
import com.xlollx.songport.ui.GeneratedCovers
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The poster drawn for the cases that strain its words (one, three and twelve artists, a name of
 * forty characters and more, short, long and Italian titles, with and without the QR code, with
 * the playlist's covers and with a cover drawn on the phone), in the three layouts and both
 * formats. Each picture is kept with the reports (ui-out/failures/poster-renders) for review.
 */
@RunWith(AndroidJUnit4::class)
class PosterRendersTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext

    private class Case(val name: String, val title: String, val artists: List<String>, val qr: Boolean = true, val drawn: Boolean = false)

    private val twelve = listOf(
        "A Day To Remember", "Bring Me The Horizon", "Nothing But Thieves", "Avenged Sevenfold", "Parkway Drive", "Architects",
        "While She Sleeps", "Bullet For My Valentine", "Asking Alexandria", "Of Mice & Men", "Motionless In White", "Ice Nine Kills",
    )

    private val cases = listOf(
        Case("one_artist_short_title", "Brani preferiti", listOf("Muse")),
        Case("three_artists_slashes", "Dark Trap/Phonk/Aesthetic rap", twelve.take(3)),
        Case("twelve_artists_long_title", "Songs for a very long night drive", twelve),
        Case("long_name_italian", "Canzoni da ascoltare in macchina d'estate", listOf("Godspeed You! Black Emperor and the Silver Mt. Zion", "Muse", "Placebo")),
        Case("twelve_artists_no_qr", "Songs for a very long night drive", twelve, qr = false),
        Case("drawn_art_three_artists", "Dark Trap/Phonk/Aesthetic rap", twelve.take(3), drawn = true),
    )

    private fun tile(color: Int): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply { drawColor(color); drawCircle(200f, 200f, 120f, android.graphics.Paint().apply { this.color = Color.WHITE; alpha = 90 }) }
    }

    private val songs = listOf(
        "Nightmare" to "Avenged Sevenfold", "The Downfall of Us All" to "A Day To Remember", "Can You Feel My Heart" to "Bring Me The Horizon",
        "Amsterdam" to "Nothing But Thieves", "If I Could Be Anyone Else In The World Tonight I Would Still Be Me" to "Godspeed You! Black Emperor and the Silver Mt. Zion",
        "Doomed" to "Bring Me The Horizon", "All I Want" to "A Day To Remember", "Sorry" to "Nothing But Thieves", "Bat Country" to "Avenged Sevenfold",
    )

    @Test
    fun postersAreDrawnForEveryCaseLayoutAndFormat() {
        val dir = File(app.getExternalFilesDir(null), "poster-renders").apply { deleteRecursively(); mkdirs() }
        val covers = listOf(0xFF7A1F3D, 0xFF1F4E7A, 0xFF2E7A1F, 0xFF7A5A1F).map { tile(it.toInt()) }
        for (c in cases) {
            // As the poster tool draws it: no artists on a drawn cover (the poster prints them once), all of them on the card.
            val art: List<Bitmap?> = if (c.drawn) listOf(GeneratedCovers.draw(GeneratedCovers.Style.MOSAIC, GeneratedCovers.Input(c.title, emptyList(), covers), 0)) else covers
            for (l in PosterPalette.Layout.entries) for (f in Cards.Format.entries) {
                val card = Cards.PosterCard(
                    c.title, "", "Spotify", 128, 27_000_000L, art, LINK,
                    c.artists, songs, l, titledArt = c.drawn,
                ).with(l, "", withQr = c.qr)
                val bmp = Cards.poster(app, card, f)
                assertTrue(bmp.width == f.width && bmp.height == f.height)
                // The QR code is there, and readable, only when asked for.
                assertEquals("${c.name} ${l.name} ${f.name}", if (c.qr) LINK else null, qr(bmp))
                File(dir, "${c.name}_${l.name.lowercase()}_${f.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        // With the reports: the runner pulls /sdcard/ui-test-failures into ui-out.
        runCatching {
            shell("mkdir -p /sdcard/ui-test-failures/poster-renders")
            shell("cp -r ${dir.absolutePath}/. /sdcard/ui-test-failures/poster-renders/")
        }
    }

    /** The QR code on [bmp], read as a phone's camera would; null when there is none. */
    private fun qr(bmp: Bitmap): String? {
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val source = com.google.zxing.RGBLuminanceSource(bmp.width, bmp.height, px)
        return runCatching {
            com.google.zxing.MultiFormatReader().decode(
                com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)),
                mapOf(com.google.zxing.DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE), com.google.zxing.DecodeHintType.TRY_HARDER to true),
            ).text
        }.getOrNull()
    }

    private companion object { const val LINK = "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M" }

    private fun shell(cmd: String): String =
        instr.uiAutomation.executeShellCommand(cmd).let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() } }
}
