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

/** The relay of the Google Play build's link sources: only their services, long answers in pieces. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NetRelayTest {
    companion object { init { BridgeMock.install() } }

    @Before fun setUp() { BridgeMock.start() }
    @After fun tearDown() { BridgeMock.stop() }

    @Test fun onlyTheLinkSourcesServicesAreReached() {
        assertTrue(NetRelay.allowed("https://music.163.com/api/v6/playlist/detail?id=1"))
        assertTrue(NetRelay.allowed("https://u.y.qq.com/cgi-bin/musicu.fcg"))
        assertTrue(NetRelay.allowed("https://coussa.anghami.com/gateway.php"))
        assertTrue(NetRelay.allowed("https://vm.tiktok.com/abc/"))
        assertTrue(NetRelay.allowed("https://www.jiosaavn.com/api.php?__call=webapi.get"))
        assertFalse(NetRelay.allowed("https://example.com/"))
        assertFalse(NetRelay.allowed("https://evil-qq.com/"))
        assertFalse(NetRelay.allowed("http://music.163.com/"))
        assertNotNull(NetRelay.fetch("https://example.com/", null).getString("error"))
        assertTrue(BridgeMock.calls.isEmpty())
    }

    @Test fun aLongAnswerComesBackInPieces() {
        val body = "x".repeat(NetRelay.CHUNK * 2 + 10)
        BridgeMock.answer(body)
        val first = NetRelay.fetch("https://music.163.com/api/x", android.os.Bundle().apply {
            putString("method", "POST"); putString("body", "c=1"); putString("contentType", "application/x-www-form-urlencoded")
            putStringArray("headers", arrayOf("Referer\nhttps://music.163.com/"))
        })
        assertEquals(200, first.getInt("code"))
        assertEquals(body.length, first.getInt("length"))
        val id = first.getString("id")!!
        val text = StringBuilder(first.getString("chunk")!!)
        while (text.length < body.length) text.append(NetRelay.chunk(id, text.length).getString("chunk")!!)
        assertEquals(body, text.toString())
        assertNotNull(NetRelay.chunk(id, 0).getString("error"))
        val r = BridgeMock.calls[0]
        assertEquals("POST", r.method)
        assertEquals("c=1", r.body.readUtf8())
        assertEquals("https://music.163.com/", r.getHeader("Referer"))
        assertNull(BridgeMock.calls.getOrNull(1))
    }
}
