package com.xlollx.songport.ytmbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Vivaticket's gateway for Concerts in Trackhop's store builds: only its answers, as the app itself asks them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VivaticketReadTest {
    companion object { init { BridgeMock.install() } }

    private val search = "https://apigatewayb2cstore.vivaticket.com/api/Home/Search/it/it-IT?q=Cesare%20Cremonini"

    @Before fun setUp() { BridgeMock.start(); VivaticketRead.gapMs = 0 }
    @After fun tearDown() { BridgeMock.stop(); VivaticketRead.gapMs = 1_000 }

    private fun call(url: String, extras: android.os.Bundle? = null) =
        BridgeCore.call(org.robolectric.RuntimeEnvironment.getApplication(), "vivaticket.read", url, extras)

    @Test fun onlyTheGatewaysSearchArtistAndEventAnswers() {
        assertTrue(VivaticketRead.allowed(search))
        assertTrue(VivaticketRead.allowed("https://apigatewayb2cstore.vivaticket.com/api/Artist/66874/it/it-IT"))
        assertTrue(VivaticketRead.allowed("https://apigatewayb2cstore.vivaticket.com/api/Event/313707/it/it-IT"))
        listOf(
            "http://apigatewayb2cstore.vivaticket.com/api/Artist/1/it/it-IT", // not https
            "https://apigatewayb2cstore.vivaticket.com/api/Cart/1", "https://apigatewayb2cstore.vivaticket.com/",
            "https://www.vivaticket.com/it/ticket/x/1", "https://apigatewayb2cstore.vivaticket.com.example.com/api/Artist/1",
            "https://user@apigatewayb2cstore.vivaticket.com/api/Artist/1", "https://apigatewayb2cstore.vivaticket.com:8443/api/Artist/1",
            "https://example.com/api/Artist/1", "javascript:alert(1)", "",
        ).forEach { assertFalse(it, VivaticketRead.allowed(it)) }
        assertNotNull(call("https://example.com/api/Artist/1").getString("error"))
        assertNotNull(call("not an address").getString("error"))
        assertTrue(BridgeMock.calls.isEmpty())
    }

    @Test fun anAnswerComesBackWithItsStatusAsTheAppAsked() {
        BridgeMock.answer("""{"artists":{"items":[]}}""")
        val b = call(search, android.os.Bundle().apply { putString("userAgent", "Trackhop/1.17 ( https://trackhop.org )") })
        assertNull(b.getString("error"))
        assertEquals(200, b.getInt("code"))
        assertEquals("""{"artists":{"items":[]}}""", b.getString("json"))
        val r = BridgeMock.calls.single()
        assertEquals("GET", r.method)
        assertEquals(VivaticketRead.HOST, r.getHeader(BridgeMock.ORIGINAL_HOST))
        assertEquals("/api/Home/Search/it/it-IT?q=Cesare%20Cremonini", r.path)
        assertEquals("Trackhop/1.17 ( https://trackhop.org )", r.getHeader("User-Agent"))
        assertEquals("application/json", r.getHeader("Accept"))
        assertNull("no cookie, no key", r.getHeader("Cookie"))
        // An error or "no content" goes back as it is: Trackhop decides.
        BridgeMock.answer("", 204)
        assertEquals(204, call(search).getInt("code"))
        BridgeMock.answer("<html>queue</html>", 403)
        assertEquals(403, call(search).getInt("code"))
    }

    @Test fun neverABrowsersIdentity() {
        assertEquals(VivaticketRead.DEFAULT_UA, VivaticketRead.userAgent(null))
        assertEquals(VivaticketRead.DEFAULT_UA, VivaticketRead.userAgent(" "))
        assertEquals(VivaticketRead.DEFAULT_UA, VivaticketRead.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/140.0.0.0"))
        assertEquals(VivaticketRead.DEFAULT_UA, VivaticketRead.userAgent("Trackhop\r\nCookie: x"))
        assertEquals("Trackhop ( https://trackhop.org )", VivaticketRead.userAgent("Trackhop ( https://trackhop.org )"))
        BridgeMock.answer("{}")
        call(search, android.os.Bundle().apply { putString("userAgent", "Mozilla/5.0 Safari") })
        assertEquals(VivaticketRead.DEFAULT_UA, BridgeMock.calls.single().getHeader("User-Agent"))
    }

    @Test fun aRedirectIsNotFollowed() {
        BridgeMock.answer("", 302, "Location" to "https://queue.example.com/")
        val b = call(search)
        assertEquals(302, b.getInt("code"))
        assertEquals(1, BridgeMock.all.size)
    }

    @Test fun anAnswerTooLargeForACallBetweenAppsIsAnError() {
        BridgeMock.answer("{" + "x".repeat(VivaticketRead.MAX_ANSWER) + "}")
        assertNotNull(call(search).getString("error"))
    }
}
