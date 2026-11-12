package com.xlollx.songport.providers

import com.xlollx.songport.model.ProviderException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A playlist's picture through the connectors: a plugin from before `setCover` means no cover, said in the log, not an error of its own. */
class BridgeCoverTest {
    @Test fun theMethodAndThePlaylistGoToTheConnector() = runBlocking {
        val asked = ArrayList<Pair<String, String>>()
        BridgePlugin.setCover("spotify.setCover", "made-up-id", byteArrayOf(1, 2, 3)) { m, id, extras -> asked += m to id; extras }
        assertEquals(listOf("spotify.setCover" to "made-up-id"), asked)
    }

    @Test fun anOlderPluginsUnknownMethodIsNoCover() = runBlocking {
        val e = runCatching {
            BridgePlugin.setCover("soundcloud.setCover", "1", byteArrayOf(1)) { _, _, _ -> throw ProviderException("SoundCloud: unknown method soundcloud.setCover") }
        }.exceptionOrNull()
        assertEquals("the connector plugin does not set covers yet", e?.message)
    }

    @Test fun theConnectorsOtherErrorsPassThrough() = runBlocking {
        val e = runCatching {
            BridgePlugin.setCover("setCover", "1", byteArrayOf(1)) { _, _, _ -> throw ProviderException("YouTube Music image upload: HTTP 413") }
        }.exceptionOrNull()
        assertTrue(e is ProviderException && e.message!!.contains("413"))
    }

    @Test fun onlyTheUnknownMethodAnswerCounts() {
        assertTrue(BridgePlugin.refusedAsUnknown("made.up", "unknown method made.up"))
        assertEquals(false, BridgePlugin.refusedAsUnknown("made.up", "SoundCloud refused this"))
        assertEquals(false, BridgePlugin.refusedAsUnknown("made.up", null))
    }
}
