package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.platform.BuildDefaults
import com.xlollx.songport.sync.Merch
import com.xlollx.songport.sync.Records
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Records and merch on the server: the API's questions and answer, the command line, the MCP tool
 * and the Discogs token in Settings. The lookups themselves are in :core's RecordsTest; here the
 * playlist has no artist, so nothing leaves the machine.
 */
class RecordsWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Before fun start() {
        // A 2.0 feature: behind the release switch, on for these tests.
        Features.v2 = true
        data = tmp.newFolder("data")
        installServerConnectors()
        BuildDefaults.values = emptyMap()
        env = ServerEnv(data, Strings(emptyMap()), "http://127.0.0.1")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        LocalKey.write(data, server.address.port)
        File(data, "csv").mkdirs()
        // Tracks without an artist: no artist to look up, so no request to Discogs or MusicBrainz.
        File(data, "csv/Quiet.csv").writeText("title,artist\nIntro,\nOutro,\n")
    }

    @After fun stop() {
        Features.v2 = false
        server.stop(0)
        LocalKey.reset()
    }

    private fun api(path: String) = client.send(
        HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun theApiAsksForAPlaylistAndAnswersWithNotesAndAttribution() {
        assertEquals(400, api("records?service=csv").statusCode())
        assertEquals(400, api("records?service=csv&playlist=Quiet&currency=XYZ").statusCode())
        assertEquals(404, api("records?service=csv&playlist=Nope").statusCode())
        assertEquals(404, api("records?service=nope&playlist=Quiet").statusCode())
        assertEquals(400, api("records?service=csv&playlist=Quiet&kind=posters").statusCode())
        val merchOnly = Json.parseToJsonElement(api("records?service=csv&playlist=Quiet&kind=merch").body()).jsonObject
        assertEquals("merch", merchOnly.s("kind"))
        // Discogs not asked: no note about its token.
        assertFalse(merchOnly["notes"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(RecordTexts.NO_TOKEN))
        val r = api("records?service=csv&playlist=quiet&currency=eur")
        assertEquals(r.body(), 200, r.statusCode())
        val o = Json.parseToJsonElement(r.body()).jsonObject
        assertEquals("EUR", o.s("currency"))
        assertEquals("false", o.s("discogsToken"))
        assertEquals(0, o["artists"]!!.jsonArray.size)
        val notes = o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(notes.toString(), RecordTexts.NO_TOKEN in notes)
        assertTrue(o.s("attribution")!!.startsWith("Data provided by Discogs"))
        assertTrue(api("openapi.json").body().contains("/records"))
    }

    @Test fun aResultCarriesRecordsPricesLinksAndSearches() {
        val rec = Records.Record("OK Computer", 1997, listOf(Records.Format.VINYL, Records.Format.CD), "https://www.discogs.com/master/21491", 4950798, 100, 12, 18.5, "EUR")
        val r = Records.Result(
            listOf(
                Records.ArtistRecords("Radiohead", listOf(rec), listOf(Records.Link(Records.Kind.SHOP, "shop.radiohead.com", "https://shop.radiohead.com/")),
                    Records.searches("Radiohead"), "https://www.discogs.com/artist/3840",
                    listOf(Merch.Item(Merch.Source.SHOP, "Logo Tee", "T-Shirt", "https://cdn.shopify.com/a/tee_200x.png", 30.0, "GBP", "https://shop.radiohead.com/products/logo-tee"),
                        Merch.Item(Merch.Source.BANDCAMP, "Tote", "Bag", "", 12.0, null, "https://radiohead.bandcamp.com/merch/tote")),
                    listOf(Merch.Item(Merch.Source.BANDCAMP, "Kid A – Vinyl LP", "Vinyl", "", 32.28, null, "https://radiohead.bandcamp.com/album/kid-a"))),
                Records.ArtistRecords("Muse", null, emptyList(), Records.searches("Muse"), Records.discogsSearch("Muse")),
                Records.ArtistRecords("Architects", null, emptyList(), Records.searches("Architects"), Records.discogsSearch("Architects"), emptyList(), shopsUnread = true),
            ),
            total = 80, token = true, tokenRefused = false, slowedDown = true, currency = "EUR",
        )
        val o = recordsJson(r)
        val first = o["artists"]!!.jsonArray[0].jsonObject
        val ok = first["records"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("Vinyl", "CD"), ok["formats"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("12", ok.s("forSale")); assertEquals("18.5", ok.s("lowestPrice")); assertEquals("https://www.discogs.com/sell/release/4950798", ok.s("sellUrl"))
        assertEquals("Official shop", first["links"]!!.jsonArray[0].jsonObject.s("kind"))
        assertEquals(listOf("Bandcamp", "Discogs", "eBay", "Web"), first["searches"]!!.jsonArray.map { it.jsonObject.s("site") })
        val tee = first["merch"]!!.jsonArray[0].jsonObject
        assertEquals("Logo Tee", tee.s("title")); assertEquals("Official shop", tee.s("source")); assertEquals("30.0", tee.s("price")); assertEquals("GBP", tee.s("currency"))
        assertEquals("https://shop.radiohead.com/products/logo-tee", tee.s("url"))
        assertEquals("Bandcamp", first["merch"]!!.jsonArray[1].jsonObject.s("source"))
        assertEquals(kotlinx.serialization.json.JsonNull, tee["format"])
        // A record in their shop: with the records, with its format.
        val kidA = first["shopRecords"]!!.jsonArray.single().jsonObject
        assertEquals("Vinyl", kidA.s("format")); assertEquals("Bandcamp", kidA.s("source"))
        assertEquals("32.28", RecordTexts.price(Merch.Item(Merch.Source.BANDCAMP, "x", price = 32.28, url = "u")))
        // Merch not looked up for Muse: null.
        assertEquals(kotlinx.serialization.json.JsonNull, o["artists"]!!.jsonArray[1].jsonObject["merch"])
        // A shop that did not answer is told apart from one with nothing.
        assertEquals("false", first.s("shopsUnread"))
        assertEquals("true", o["artists"]!!.jsonArray[2].jsonObject.s("shopsUnread"))
        assertEquals("all", o.s("kind"))
        // Discogs not asked about Muse: null, not an empty list.
        assertEquals(kotlinx.serialization.json.JsonNull, o["artists"]!!.jsonArray[1].jsonObject["records"])
        val notes = o["notes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(RecordTexts.SLOWED, notes.first())
        assertTrue(notes.toString(), notes.any { it.contains("of 80") })
        assertEquals("12 for sale from 18.50 EUR", RecordTexts.forSale(rec))
    }

    @Test fun theCommandLineAndTheAssistantAsk() {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = Cli.run(listOf("records", "csv", "Quiet", "--currency", "USD"), emptyMap(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8")) { data }
        val text = out.toString("UTF-8")
        assertEquals(err.toString("UTF-8"), 0, code)
        assertTrue(text, text.contains(RecordTexts.NO_TOKEN) && text.contains("Data provided by Discogs"))
        assertTrue("find_records" in Mcp.toolNames)
    }

    @Test fun theTokenIsSavedInSettingsAndNeverShown() {
        val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())
        fun post(body: String) = client.send(
            HttpRequest.newBuilder(URI("$base/settings")).header("Authorization", auth).header("Origin", base)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val token = "d".repeat(20) + "Fake" + "x".repeat(16)
        assertEquals(303, post("action=discogs&token=+$token+").statusCode())
        assertEquals(token, Records.token(env))
        val page = client.send(HttpRequest.newBuilder(URI("$base/settings")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString()).body()
        assertTrue(page.contains("Your token is saved."))
        assertFalse(page.contains(token))
        // Kept with the sign-ins, not in the settings a set-up export carries.
        assertFalse(env.store.data.settings.clientIds.values.any { it == token })
        post("action=discogs&token=")
        assertEquals(token, Records.token(env))
        post("action=discogs&token=&remove=1")
        assertEquals(null, Records.token(env))
    }
}
