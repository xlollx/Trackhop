package com.xlollx.songport.ai

import com.xlollx.songport.Features
import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.ProviderException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 1.18 covers drawn by a Cloudflare account (FLUX.1 schnell), whatever the chat AI. */
class CloudflareImageTest {
    private lateinit var mock: MockWebServer
    private val env = ContractEnv()
    private val claude = AiClient.Config(AiClient.Vendor.ANTHROPIC, "k", "m", "")

    @Before fun start() {
        mock = MockWebServer().apply { start() }
        AiClient.cloudflareApi = mock.url("/").toString()
        Features.build = true
    }

    @After fun stop() {
        mock.shutdown()
        AiClient.cloudflareApi = "https://api.cloudflare.com"
        Features.build = false
    }

    @Test fun aCloudflareKeyDrawsForAnAiThatCannot() = runBlocking {
        assertFalse(AiClient.canDraw(env, claude))
        AiClient.saveImageKey(env, AiClient.ImageKey(" acc1 ", "tok"))
        assertTrue(AiClient.canDraw(env, claude))
        mock.enqueue(MockResponse().setBody("""{"result":{"image":"${java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"},"success":true}"""))
        assertArrayEquals(byteArrayOf(1, 2, 3), AiClient.drawCover(env, claude, "a cover"))
        val r = mock.takeRequest()
        assertEquals("/client/v4/accounts/acc1/ai/run/@cf/black-forest-labs/flux-1-schnell", r.path)
        assertEquals("Bearer tok", r.getHeader("Authorization"))
        // Before 1.18 the key is not used.
        Features.build = false
        assertFalse(AiClient.canDraw(env, claude))
    }

    @Test fun aRefusalSaysWhatToFix() = runBlocking {
        AiClient.saveImageKey(env, AiClient.ImageKey("acc1", "tok"))
        mock.enqueue(MockResponse().setResponseCode(403).setBody("""{"errors":[{"message":"Authentication error"}]}"""))
        val e = runCatching { AiClient.drawCover(env, claude, "x") }.exceptionOrNull()
        assertTrue(e is ProviderException && "Workers AI" in e.message.orEmpty())
        // Forgotten: nothing left.
        AiClient.saveImageKey(env, null)
        assertEquals(null, AiClient.imageKey(env))
    }
}
