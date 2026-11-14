package com.xlollx.songport.flows

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xlollx.songport.ui.Cards
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.18's roast cards in both styles, with each picture (the artists' 2×2, the playlist's, none), a
 * short and a long roast, in both formats. Kept with the reports (ui-out/failures/roast-renders).
 */
@RunWith(AndroidJUnit4::class)
class RoastRendersTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val app get() = instr.targetContext

    private fun tile(color: Int): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply { drawColor(color); drawCircle(200f, 200f, 120f, android.graphics.Paint().apply { this.color = Color.WHITE; alpha = 90 }) }
    }

    private val short = "Forty minutes of 80s synths and then Wonderwall. Are you afraid of silence or of choosing?"
    private val long = "This playlist is a road trip that never leaves the driveway: " + "the same three synth anthems, a ballad for crying at red lights, and Wonderwall again because apparently once was not enough. ".repeat(4)

    @Test
    fun roastCardsAreDrawnInEveryStyleArtAndFormat() {
        val dir = File(app.getExternalFilesDir(null), "roast-renders").apply { deleteRecursively(); mkdirs() }
        val covers = listOf(0xFF7A1F3D, 0xFF1F4E7A, 0xFF2E7A1F, 0xFF7A5A1F).map { tile(it.toInt()) }
        val arts = mapOf("artists" to Cards.mosaic(covers), "playlist" to tile(0xFF355C7D.toInt()), "text" to null)
        for (style in Cards.RoastStyle.entries) for ((artName, art) in arts) for ((textName, text) in listOf("short" to short, "long" to long)) for (f in Cards.Format.entries) {
            val card = Cards.RoastCard2("Playlist roast", "Road Trip 2026: the long way home", text, style, art, "Most played: Daft Punk, M83, Toto, Oasis", covers)
            val bmp = Cards.roast2(app, card, f)
            assertTrue(bmp.width == f.width && bmp.height == f.height)
            File(dir, "${style.name.lowercase()}_${artName}_${textName}_${f.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        runCatching {
            shell("mkdir -p /sdcard/ui-test-failures/roast-renders")
            shell("cp -r ${dir.absolutePath}/. /sdcard/ui-test-failures/roast-renders/")
        }
    }

    private fun shell(cmd: String) {
        instr.uiAutomation.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }
}
