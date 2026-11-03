package com.xlollx.songport.net

import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTest {
    @After fun reset() { Relay.via = null; Relay.available = { true } }

    @Test fun throughAPluginTheRequestIsHandedOverWhole() = runBlocking {
        var seen: Relay.Call? = null
        Relay.via = { c -> seen = c; Relay.Answer(200, "{\"ok\":1}", c.url, mapOf("X-A" to "b")) }
        assertTrue(Relay.throughPlugin)
        val r = Relay.send("POST", "https://music.163.com/api/v3/song/detail", mapOf("Referer" to "r"), FormBody.Builder().add("c", "[1]").build())
        assertEquals(200, r.code)
        assertEquals("{\"ok\":1}", r.body)
        assertEquals("b", r.header("X-A"))
        assertEquals("POST", seen!!.method)
        assertEquals("c=%5B1%5D", seen!!.body)
        assertEquals("r", seen!!.headers["Referer"])
        assertTrue(seen!!.contentType!!.startsWith("application/x-www-form-urlencoded"))
        val landing = Relay.follow("https://vm.tiktok.com/x/")
        assertEquals("https://vm.tiktok.com/x/", landing.url)
    }
}
