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
 * the playlist's covers and with a cover drawn on the phone, a signed poster of a big playlist),
 * in the three layouts and both formats; the compatibility card, printed the same way, too. Each
 * picture is kept with the reports (ui-out/failures/poster-renders, compat-renders) for review.
 */
@RunWith(AndroidJUnit4::class)
class PosterRendersTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext

    private class Case(
        val name: String, val title: String, val artists: List<String>, val qr: Boolean = true, val drawn: Boolean = false,
        val owner: String = "", val trackCount: Int = 128, val durationMs: Long = 27_000_000L,
    )

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
        // A real poster, 12/11: the Bold header read "GIULIA MARCHETTI · SPO… 2220 TRACKS · 140 H 1…".
        Case("signed_big_playlist", "Liked songs", twelve, owner = "Giulia Marchetti", trackCount = 2220, durationMs = 505_000_000L),
        Case("signed_long_name", "Liked songs", twelve, owner = "Maria Fernanda Albuquerque de Souza Lima", trackCount = 12_345, durationMs = 2_950_000_000L),
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
                    c.title, "", "Spotify", c.trackCount, c.durationMs, art, LINK,
                    c.artists, songs, l, titledArt = c.drawn,
                ).with(l, c.owner, withQr = c.qr)
                // The Bold header: the name and the track count always there and whole, nothing cut.
                if (l == PosterPalette.Layout.BOLD) {
                    val head = Cards.boldHeadLines(app, card, f)
                    val printed = head.joinToString(" | ") { it.left + " / " + it.right }
                    assertTrue(printed, head.none { (it.left + it.right).contains('…') })
                    val name = (c.owner.ifBlank { app.getString(com.xlollx.songport.R.string.editor_playlist) }).uppercase()
                    val shownName = if (head.size == 1) head[0].left.substringBefore("  ·  ") else head.dropLast(1).joinToString(" ") { it.left }
                    assertEquals(printed, name, shownName)
                    assertTrue(printed, head.last().right.filter(Char::isDigit).startsWith(c.trackCount.toString()))
                }
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

    /**
     * The compatibility card, printed as the poster: in the three layouts and both formats, with
     * nothing in common, with a few tracks (some covers missing) and with many, with and without
     * the people's names (one of them blank, long ones), and with long playlist names.
     */
    @Test
    fun compatibilityCardsAreDrawnForEveryLayoutAndFormat() {
        val dir = File(app.getExternalFilesDir(null), "compat-renders").apply { deleteRecursively(); mkdirs() }
        val tracks = songs.mapIndexed { i, (t, a) -> com.xlollx.songport.model.Track("t$i", t, listOf(a)) }
        val colours = listOf(0xFF7A1F3D, 0xFF1F4E7A, 0xFF2E7A1F, 0xFF7A5A1F, 0xFF5B3FD9)
        fun result(common: Int, artists: List<String>, score: Int) = com.xlollx.songport.sync.Compatibility.Result(
            40, 60, tracks.take(common).map { com.xlollx.songport.sync.Compatibility.Common(it, it) }, artists, emptyList(), emptyList(), score,
        )
        // A cover for every track but the second: a missing one is a tile, never an error.
        val covers = tracks.take(5).mapIndexed { i, t -> t.id to if (i == 1) null else tile(colours[i].toInt()) }.toMap()
        val cases = listOf(
            "nothing_in_common" to Cards.CompatibilityCard("Brani preferiti", "Workout", result(0, emptyList(), 0), emptyMap()),
            "few_in_common" to Cards.CompatibilityCard("Friday Night Rock", "Workout", result(3, twelve.take(2), 42), covers),
            "many_in_common_named" to Cards.CompatibilityCard("Songs for a very long night drive", "Canzoni da ascoltare in macchina d'estate", result(9, twelve, 100), covers, "Giulia", "Marco"),
            "one_name_long_names" to Cards.CompatibilityCard("Liked songs", "Dark Trap/Phonk/Aesthetic rap", result(5, twelve.take(5), 77), covers, "Maria Fernanda Albuquerque de Souza Lima", ""),
        )
        for ((name, base) in cases) for (l in PosterPalette.Layout.entries) for (f in Cards.Format.entries) {
            val card = base.with(l, base.personA, base.personB)
            val bmp = Cards.compatibility(app, card, f)
            assertTrue(bmp.width == f.width && bmp.height == f.height)
            File(dir, "${name}_${l.name.lowercase()}_${f.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // The same playlists and names in another layout make another picture.
        val few = cases[1].second
        assertTrue(!Cards.compatibility(app, few.with(PosterPalette.Layout.CLASSIC, "", ""), Cards.Format.SQUARE).sameAs(Cards.compatibility(app, few.with(PosterPalette.Layout.BOLD, "", ""), Cards.Format.SQUARE)))
        // The people's names change the picture; without them it is the playlists' card.
        assertTrue(!Cards.compatibility(app, few.with(PosterPalette.Layout.CLASSIC, "", ""), Cards.Format.SQUARE).sameAs(Cards.compatibility(app, few.with(PosterPalette.Layout.CLASSIC, "Anna", ""), Cards.Format.SQUARE)))
        runCatching {
            shell("mkdir -p /sdcard/ui-test-failures/compat-renders")
            shell("cp -r ${dir.absolutePath}/. /sdcard/ui-test-failures/compat-renders/")
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
