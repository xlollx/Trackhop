package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.sync.Concerts
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * The concerts' calendar address: read without the password by who has its secret, by nobody
 * else, a new address ends the old one, and nothing is there with the release switch off.
 */
class CalendarWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Before fun start() {
        Features.v2 = true
        installServerConnectors()
        BuildDefaults.values = emptyMap()
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://music.home:8080")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        // What the last look kept: one date.
        val event = Concerts.Event(artist = "Radiohead", name = "Radiohead", date = "2027-06-18", time = "21:00", city = "Milano", venue = "San Siro",
            source = Concerts.Source.TICKETMASTER, timeZone = "Europe/Rome", id = "Z698xZG2Z17a1", url = "https://www.ticketmaster.it/event/1")
        env.keyValues("concert_alerts")["calendar"] = Json.encodeToString(ListSerializer(Concerts.Event.serializer()), listOf(event))
    }

    @After fun stop() {
        Features.v2 = false
        server.stop(0)
    }

    private fun get(path: String, signedIn: Boolean = false): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(base + path)).apply {
            if (signedIn) header("Authorization", "Basic " + Base64.getEncoder().encodeToString("test:sekret".toByteArray()))
        }.GET().build(), HttpResponse.BodyHandlers.ofString(),
    )

    @Test fun theAddressIsReadWithItsSecretOnlyAndANewOneEndsTheOld() {
        // No address made yet: nothing to guess.
        assertEquals(404, get("/cal/anything.ics").statusCode())
        val token = ServerCalendar.token(env)
        assertEquals(48, token.length)
        assertEquals(token, ServerCalendar.token(env))
        val r = get("/cal/$token.ics")
        assertEquals(200, r.statusCode())
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/calendar"))
        assertTrue(r.body().startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(r.body().contains("UID:Z698xZG2Z17a1@trackhop\r\n"))
        assertTrue(r.body().contains("DTSTART:20270618T190000Z\r\n"))
        assertEquals(404, get("/cal/${token.dropLast(1)}${if (token.last() == '0') '1' else '0'}.ics").statusCode())
        assertEquals(404, get("/cal/$token").statusCode())
        // The page (behind the password) shows the address with the server's own name.
        assertEquals(401, get("/tools/alerts").statusCode())
        assertTrue(get("/tools/alerts", signedIn = true).body().contains("http://music.home:8080/cal/$token.ics"))
        val fresh = ServerCalendar.rotate(env)
        assertNotEquals(token, fresh)
        assertEquals(404, get("/cal/$token.ics").statusCode())
        assertEquals(200, get("/cal/$fresh.ics").statusCode())
        // A shared server asks each user's Trackhop whose address it is.
        val web = Web(env, Runner(env), Auth(env), "sekret", "test")
        assertTrue(web.acceptsApi(ServerCalendar.HEADER + fresh, null))
        assertFalse(web.acceptsApi(ServerCalendar.HEADER + token, null))
        assertFalse(web.acceptsApi(ServerCalendar.HEADER, null))
    }

    @Test fun withTheSwitchOffThereIsNoCalendar() {
        val token = ServerCalendar.token(env)
        Features.v2 = false
        assertNotEquals(200, get("/cal/$token.ics").statusCode())
        assertFalse(Web(env, Runner(env), Auth(env), "sekret", "test").acceptsApi(ServerCalendar.HEADER + token, null))
    }
}
