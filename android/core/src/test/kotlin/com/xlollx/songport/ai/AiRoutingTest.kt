package com.xlollx.songport.ai

import com.xlollx.songport.ai.AiClient.LocalState
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which AI is used when: the user's key, the phone's own model, or none. No device model here, only a fake. */
class AiRoutingTest {
    private val key = AiClient.Config(AiClient.Vendor.GEMINI, "k", "gemini-2.5-flash", "")
    private val nano = "Gemini Nano"

    /** Answers like a model would and remembers what it was asked. */
    private class FakeLocal(val answer: String) : AiClient.LocalModel {
        val asked = ArrayList<String>()
        override suspend fun complete(system: String, user: String): String { asked += user; return answer }
    }

    @After fun reset() { AiClient.localModel = null }

    @Test fun keyWinsOverTheDeviceModel() {
        for (state in LocalState.entries) assertSame(key, AiClient.pick(key, state, nano))
    }

    @Test fun noKeyUsesTheDeviceModelWhenOffered() {
        for (state in listOf(LocalState.READY, LocalState.DOWNLOADABLE, LocalState.DOWNLOADING)) {
            val c = AiClient.pick(null, state, nano)!!
            assertTrue(c.local); assertTrue(c.complete); assertEquals(nano, c.label); assertEquals("", c.apiKey)
        }
    }

    @Test fun noKeyAndNoDeviceModelMeansNoAi() {
        assertNull(AiClient.pick(null, LocalState.NONE, nano))
        // A half-filled setup (no model chosen) is not a key.
        assertNull(AiClient.pick(key.copy(model = ""), LocalState.NONE, nano))
        assertTrue(AiClient.pick(key.copy(model = ""), LocalState.READY, nano)!!.local)
    }

    @Test fun deviceModelHasNoImages() {
        assertEquals(AiClient.ImageSupport.NO_IMAGES, AiClient.imageSupport(AiClient.onDevice(nano)))
    }

    @Test fun localConfigGoesToTheLocalModel() = runBlocking {
        val fake = FakeLocal("""{"roast": "So many ballads."}""")
        AiClient.localModel = fake
        val text = AiRoast.roast(AiClient.onDevice(nano), "Mix", listOf(Track(id = "1", title = "Song", artists = listOf("A"))), "English")
        assertEquals("So many ballads.", text)
        assertEquals(1, fake.asked.size)
    }

    @Test fun localConfigWithoutModelFailsCleanly() = runBlocking {
        AiClient.localModel = null
        val r = runCatching { AiRoast.roast(AiClient.onDevice(nano), "Mix", emptyList(), "English") }
        assertTrue(r.exceptionOrNull() is ProviderException)
    }

    @Test fun deviceModelGetsShortRoundsAndContext() = runBlocking {
        val fake = FakeLocal("""[{"artist": "A", "title": "One"}]""")
        AiClient.localModel = fake
        val existing = (1..300).map { "Old $it - Song $it" }
        AiClient.generate(AiClient.onDevice(nano), AiClient.Prompt("rainy day", 50, existing = existing))
        val asked = fake.asked.single()
        assertTrue(asked.contains("Number of NEW tracks to return: ${AiClient.LOCAL_ROUND}"))
        assertTrue(asked.contains("Old 300 - Song 300"))
        assertFalse(asked.contains("Old 1 - Song 1\n"))
        assertEquals(AiClient.LOCAL_ROUND, AiClient.roundFor(AiClient.onDevice(nano)))
        assertEquals(AiClient.ROUND, AiClient.roundFor(key))
    }
}
