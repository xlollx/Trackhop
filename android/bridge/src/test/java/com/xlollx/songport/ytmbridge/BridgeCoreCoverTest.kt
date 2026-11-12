package com.xlollx.songport.ytmbridge

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The connectors' `setCover` methods: the playlist in the argument, the JPEG in the `jpeg` extra; nothing sent without either. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BridgeCoreCoverTest {
    companion object { init { BridgeMock.install() } }

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test fun thePictureTravelsInTheJpegExtra() = assertEquals("jpeg", BridgeCore.COVER)

    @Test fun withoutAPlaylistOrAPictureNothingIsSent() {
        BridgeMock.start()
        try {
            val image = Bundle().apply { putByteArray(BridgeCore.COVER, byteArrayOf(1, 2, 3)) }
            for (m in listOf("setCover", "spotify.setCover", "soundcloud.setCover")) {
                assertEquals(m, "missing playlist id", BridgeCore.call(app, m, null, image).getString("error"))
                assertEquals(m, "missing image", BridgeCore.call(app, m, "made-up-id", Bundle()).getString("error"))
            }
            assertTrue(BridgeMock.calls.isEmpty())
        } finally { BridgeMock.stop() }
    }
}
