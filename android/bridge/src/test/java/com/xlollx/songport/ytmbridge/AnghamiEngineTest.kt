package com.xlollx.songport.ytmbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #53: Anghami answers plain HTTP clients with a 406, so on the phone its calls go through a browser
 * engine ([WebEngine]): the client hands it method, address and form, and reads the answer as before.
 */
class AnghamiEngineTest {
    private class Host : WebHost {
        val kept = HashMap<String, String>(mapOf(AnghamiWebClient.SID to "i7:abcdefgh:" + "Q".repeat(60), AnghamiWebClient.FP to "fp-9"))
        override fun cookies(): String? = "anghami=1"
        override fun account(): String? = "Me"
        override fun get(key: String): String? = kept[key]
        override fun put(key: String, value: String?) { if (value == null) kept.remove(key) else kept[key] = value }
        override val userAgent: String = "UA"
    }

    private class Call(val method: String, val url: String, val form: String?)

    private val playlists = """{"status":"ok","sections":[{"type":"playlist","data":[{"id":"111","name":"Road","count":"3"}]}]}"""

    @Test fun callsGoThroughTheTransportWithThePagesParameters() {
        val calls = ArrayList<Call>()
        val c = AnghamiWebClient(Host()) { m, u, f -> calls += Call(m, u, f); 200 to playlists }
        assertEquals(listOf("111"), c.playlists().map { it.id })
        val u = java.net.URI(calls.single().url)
        assertEquals("GET", calls[0].method)
        assertEquals("coussa.anghami.com", u.host)
        val q = u.rawQuery.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("GETplaylists", q["type"]); assertEquals("en", q["userlanguageprod"]); assertEquals("fp-9", q["fingerprint"])
        assertTrue(q["sid"]!!.startsWith("i7:"))
        assertEquals(null, calls[0].form)
    }

    @Test fun aWriteSendsItsFormThroughTheTransport() {
        val calls = ArrayList<Call>()
        val c = AnghamiWebClient(Host()) { m, u, f -> calls += Call(m, u, f); 200 to """{"status":"ok"}""" }
        c.add("111", listOf("42"))
        assertEquals("POST", calls[0].method)
        val form = calls[0].form!!.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("PUTplaylist", form["type"]); assertEquals("append", form["action"]); assertEquals("42", form["songID"])
    }

    @Test fun aRefusalThroughTheEngineIsAnAnswerAndAnEndedSessionSaysSo() {
        val refused = AnghamiWebClient(Host()) { _, _, _ -> 406 to "" }
        try { refused.playlists(); fail() } catch (e: BridgeException) { assertEquals("Anghami HTTP 406", e.message) }
        val ended = AnghamiWebClient(Host()) { _, _, _ -> 200 to """{"_attributes":{"status":"failed"},"error":{"_attributes":{"code":"111","message":"Please login again","logoff":"true"}}}""" }
        try { ended.playlists(); fail() } catch (e: BridgeException) { assertEquals(AnghamiWebClient.EXPIRED, e.message) }
    }

    @Test fun theEngineFetchesOnlyTheServicesHostsOverHttps() {
        val hosts = listOf("anghami.com")
        assertTrue(WebEngine.Scripts.allowed("https://coussa.anghami.com/gateway.php?type=x", hosts))
        assertTrue(WebEngine.Scripts.allowed("https://anghami.com/", hosts))
        assertFalse(WebEngine.Scripts.allowed("http://coussa.anghami.com/gateway.php", hosts))
        assertFalse(WebEngine.Scripts.allowed("https://evil-anghami.com/", hosts))
        assertFalse(WebEngine.Scripts.allowed("https://accounts.google.com/", hosts))
    }

    @Test fun theScriptCarriesMethodBodyAndCredentialsQuoted() {
        val get = WebEngine.Scripts.fetch("TrackhopEngine", "7", "GET", "https://coussa.anghami.com/g?a=\"b", mapOf("Accept" to "application/json"), "ignored", credentials = true)
        assertTrue(get, get.contains("fetch(\"https://coussa.anghami.com/g?a=\\\"b\""))
        assertTrue(get.contains("credentials: 'include'"))
        assertFalse("a GET has no body", get.contains("ignored"))
        assertTrue(get.contains("TrackhopEngine.done(\"7\""))
        val post = WebEngine.Scripts.fetch("TrackhopEngine", "8", "POST", "https://coussa.anghami.com/g", mapOf("Content-Type" to "application/x-www-form-urlencoded"), "type=PUTplaylist&x=1", credentials = false)
        assertTrue(post.contains("body: \"type=PUTplaylist&x=1\"") && post.contains("credentials: 'omit'") && post.contains("method: \"POST\""))
    }
}
