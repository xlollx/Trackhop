package com.xlollx.songport.server

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A cover whose header says it is huge is refused before it is decoded. */
class CoverPixelsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aHugeImageIsNotDecodedAndANormalOneIs() {
        val env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "https://songport.example.org")
        val ok = ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(600, 600, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        assertNotNull(env.squareJpeg(ok, 300, 3000, 10_000_000))
        // A PNG header claiming 50000 × 50000 (its pixels never follow): refused, nothing allocated for them.
        val huge = ok.copyOf()
        fun int(at: Int, v: Int) { huge[at] = (v ushr 24).toByte(); huge[at + 1] = (v ushr 16).toByte(); huge[at + 2] = (v ushr 8).toByte(); huge[at + 3] = v.toByte() }
        int(16, 50_000); int(20, 50_000)
        assertNull(env.squareJpeg(huge, 300, 3000, 10_000_000))
    }
}
