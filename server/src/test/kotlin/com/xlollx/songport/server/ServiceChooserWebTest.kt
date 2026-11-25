package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** "Which service?" on the web page: categories, search on the server (no script), regional services where they are at home, adding. */
class ServiceChooserWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    @Before fun start() {
        installServerConnectors()
        env = ServerEnv(tmp.newFolder("data"), Strings.load(null), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        env.store.updateSettings { it.copy(connectors = listOf("jellyfin"), connectorsInit = true, connectorColors = mapOf("jellyfin" to "teal")) }
    }

    @After fun stop() = server.stop(0)

    private fun get(path: String, language: String = "it-IT") = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Accept-Language", language).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    @Test fun theServicesPageLeadsToTheChooser() {
        val list = get("/connectors").body()
        assertTrue(list, list.contains("""href="/connectors/add""""))
        assertFalse("no long drop-down any more", list.contains("""<select name="service">"""))
    }

    @Test fun allShowsYourAccountsTheChipsAndEachCategoryWithShowAll() {
        val page = get("/connectors/add").body()
        assertTrue(page.contains("Which service?"))
        assertTrue(page, page.contains("Your accounts"))
        // The account, with its colour, opens its own page.
        assertTrue(page.contains("""href="/connectors/jellyfin""""))
        assertTrue(page.contains("logo ringed"))
        // A search box that works without a script: a GET form.
        assertTrue(page.contains("""<form method="get" action="/connectors/add""""))
        listOf("All", "Streaming", "Your server", "Listening", "Files").forEach { assertTrue(it, page.contains(">$it</a>")) }
        assertFalse("Games waits for its first member", page.contains(">Games</a>"))
        assertTrue(page.contains("""href="/connectors/add?cat=server""""))
        assertTrue(page.contains("Show all 7"))
        // Regional services stay out of All in Italy.
        assertFalse(page.contains(">Gaana</a>") || page.contains("<b>Gaana</b>"))
        assertTrue(page.contains("""href="/connectors/add?cat=regional""""))
    }

    @Test fun aRegionalServiceIsInAllWhereItIsAtHome() {
        assertTrue(get("/connectors/add", language = "hi-IN").body().contains("<b>Gaana</b>"))
        val regional = get("/connectors/add?cat=regional").body()
        assertTrue(regional.contains("<b>Gaana</b>"))
        assertTrue(regional.contains("India · Middle East"))
    }

    @Test fun theSearchRunsOnTheServerAndFindsOtherNames() {
        for (name in listOf("Navidrome", "Gonic", "Nextcloud Music", "Supysonic", "Astiga", "Airsonic-Advanced", "Ampache", "LMS", "Funkwhale")) {
            val page = get("/connectors/add?q=${enc(name)}").body()
            assertTrue(name, page.contains("<b>Subsonic / Navidrome</b>"))
            assertFalse(name, page.contains("<b>Jellyfin</b>"))
        }
        assertTrue(get("/connectors/add?q=plexamp").body().contains("<b>Plex</b>"))
        assertTrue(get("/connectors/add?q=zzzz").body().contains("No service found"))
    }

    @Test fun aServicesWaysInAreAddedWithAPlainForm() {
        val spotify = get("/connectors/add?service=spotify").body()
        assertTrue(spotify.contains("""<input type="hidden" name="service" value="spotify">"""))
        // Jellyfin is added already: one more account of it.
        assertTrue(get("/connectors/add?service=jellyfin").body().contains("""value="jellyfin@new""""))
        assertEquals(404, get("/connectors/add?service=nothing").statusCode())
    }

    @Test fun theApiAndItsSearch() {
        // What GET /api/v1/catalog answers.
        val all = catalogJson(env, null, null)
        assertTrue(all.any { it.jsonObject["name"]!!.jsonPrimitive.content == "Jellyfin" && it.jsonObject["category"]!!.jsonPrimitive.content == "server" })
        val hits = catalogJson(env, "navidrome", null)
        assertEquals(listOf("Subsonic / Navidrome"), hits.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        val listening = catalogJson(env, null, "listening")
        assertEquals(setOf("Last.fm", "ListenBrainz"), listening.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
    }
}
