package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.MatchReview
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The API's calls beyond the first ones (ApiSyncs.kt, ApiMore.kt, ApiDocs.kt), against a real server with File playlists. */
class ApiCallsTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var data: File
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()

    @Before fun start() {
        data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        server = Web(env, Runner(env), Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
        File(data, "csv").mkdirs()
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\n")
    }

    @After fun stop() { server.stop(0); Features.v2 = false }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
        val arr: JsonArray get() = Json.parseToJsonElement(text).jsonArray
    }

    private fun call(method: String, path: String, body: String? = null, auth: Boolean = true): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).apply { if (auth) header("Authorization", "Bearer $token") }
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun newReleasesAreListedAndMakeAWeeklySync() {
        // A 1.18 feature: behind the release switch, on for this test.
        Features.v2 = true
        // The day's answers already kept for the playlist's two artists: nothing is asked of Deezer or MusicBrainz.
        val today = java.time.LocalDate.now()
        fun kept(vararg r: Pair<String, java.time.LocalDate>) = "${System.currentTimeMillis()}|" +
            r.joinToString(",", "[", "]") { (t, d) -> """{"artist":"Band","title":"$t","type":"ALBUM","date":"$d","deezerId":${t.length}}""" }
        env.keyValues("new_releases")["band"] = kept("Fresh" to today.minusDays(3), "Old" to today.minusDays(60), "Soon" to today.plusDays(20))
        env.keyValues("new_releases")["other band"] = "${System.currentTimeMillis()}|[]"
        val r = call("GET", "releases?service=csv&playlist=In&days=30").also { assertEquals(it.text, 200, it.code) }.obj
        assertEquals(listOf("Fresh"), r["releases"]!!.jsonArray.map { it.jsonObject.s("title") })
        assertEquals("""["Band","Other Band"]""", r["artists"].toString())
        assertEquals("https://www.deezer.com/album/5", r["releases"]!!.jsonArray[0].jsonObject.s("link"))
        val both = call("GET", "releases?playlist=csv:In&days=90&upcoming=true").obj
        assertEquals(listOf("Soon", "Fresh", "Old"), both["releases"]!!.jsonArray.map { it.jsonObject.s("title") })
        assertEquals("true", both["releases"]!!.jsonArray[0].jsonObject.s("upcoming"))
        assertEquals(400, call("GET", "releases?service=csv&playlist=In&days=0").code)
        assertEquals(400, call("GET", "releases").code)

        val source = r["source"]!!.jsonObject
        assertEquals("30:csv:In", source.s("playlist"))
        val made = call("POST", "syncs", """{"source":$source,"target":{"service":"csv"},"schedule":"weekly","changes":"archive"}""")
        assertEquals(made.text, 201, made.code)
        assertEquals("releases", made.obj["source"]!!.jsonObject.s("service"))
        assertEquals("true", made.obj.s("archive"))
        assertEquals("weekly", made.obj.s("schedule"))
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"releases","playlist":"nonsense"},"target":{"service":"csv"}}""").code)
    }

    @Test fun favouritesLabelsAndTagsAreWrittenFilteredAndRunAsAGroup() {
        fun make(name: String, extra: String) =
            call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"name":"$name"$extra}""").also { assertEquals(it.text, 201, it.code) }
        val a = make("A", ""","tags":{" Genre ":"Metal","mood":"gym"},"favorite":true,"color":"teal"""").obj
        val b = make("B", ""","tags":{"genre":"Heavy Metal","kids":null},"enabled":false""").obj
        val c = make("C", "").obj
        assertEquals("""{"Genre":"Metal","mood":"gym"}""", a["tags"].toString())
        assertEquals("true", a.s("favorite"))
        // A ready colour is its own label.
        assertEquals("teal", a.s("label"))
        assertEquals("""{"genre":"Heavy Metal","kids":""}""", b["tags"].toString())
        assertEquals("false", c.s("favorite"))
        assertEquals("{}", c["tags"].toString())

        fun names(q: String) = call("GET", "syncs$q").also { assertEquals(it.text, 200, it.code) }.arr.map { it.jsonObject.s("name") }
        assertEquals(listOf("A", "B", "C"), names(""))
        // Exactly that tag, whatever the case; "metal" is not "Heavy Metal".
        assertEquals(listOf("A"), names("?tag=genre:metal"))
        assertEquals(listOf("A"), names("?tag=GENRE%3DMetal"))
        assertEquals(listOf("B"), names("?tag=genre%3Aheavy%20metal"))
        // The key alone, any value; a key without a value.
        assertEquals(listOf("A", "B"), names("?tag=genre"))
        assertEquals(listOf("B"), names("?tag=kids"))
        // Several: all wanted.
        assertEquals(listOf("A"), names("?tag=genre,mood:gym"))
        assertEquals(emptyList<String?>(), names("?tag=genre:metal,kids"))
        assertEquals(listOf("A"), names("?favorite=true"))
        assertEquals(listOf("B", "C"), names("?favorite=false"))
        assertEquals(400, call("GET", "syncs?favorite=maybe").code)
        assertEquals(400, call("GET", "syncs?tag=%3Ametal").code)
        assertEquals("""{"Genre":["Heavy Metal","Metal"],"kids":[],"mood":["gym"]}""", call("GET", "tags").obj.toString())

        // Labels: a ready colour named, a colour of one's own saved, a sync given it by name.
        val gym = call("POST", "labels", """{"color":"teal","name":"Gym"}""")
        assertEquals(gym.text, 201, gym.code)
        assertEquals("teal", gym.obj.s("id")); assertEquals("1", gym.obj.s("syncs"))
        assertEquals(listOf("A"), names("?label=Gym"))
        assertEquals(listOf("A"), names("?label=teal"))
        assertEquals(emptyList<String?>(), names("?label=nope"))
        assertEquals("Gym", call("GET", "syncs/${a.s("id")}").obj.s("labelName"))
        val mine = call("POST", "labels", """{"color":"#12a594","name":"Mine"}""").obj
        val labelId = mine.s("id")!!
        assertEquals("#12A594", mine.s("color"))
        val worn = call("PATCH", "syncs/${c.s("id")}", """{"label":"mine"}""")
        assertEquals(worn.text, 200, worn.code)
        assertEquals("#12A594", worn.obj.s("color")); assertEquals(labelId, worn.obj.s("label")); assertEquals("Mine", worn.obj.s("labelName"))
        assertEquals(400, call("PATCH", "syncs/${c.s("id")}", """{"label":"nope"}""").code)
        assertEquals(400, call("POST", "labels", """{"color":"mauve","name":"x"}""").code)
        assertEquals(400, call("POST", "labels", """{"color":"red"}""").code)
        // Recoloured and renamed: the sync follows.
        val ours = call("PATCH", "labels/$labelId", """{"color":"#000001","name":"Ours"}""")
        assertEquals(ours.text, 200, ours.code)
        assertEquals("Ours", ours.obj.s("name"))
        assertEquals("#000001", env.store.job(c.s("id")!!)!!.color)
        assertEquals(400, call("PATCH", "labels/teal", """{"color":"#000001"}""").code)
        assertEquals(9, call("GET", "labels").arr.size)
        // A colour given as such takes the label off.
        assertEquals("null", call("PATCH", "syncs/${c.s("id")}", """{"color":"#abcdef"}""").obj["label"].toString())
        call("PATCH", "syncs/${c.s("id")}", """{"label":"$labelId"}""")
        // Deleted: the colour stays, without the name.
        assertEquals(200, call("DELETE", "labels/$labelId").code)
        assertEquals("#000001", env.store.job(c.s("id")!!)!!.color)
        assertEquals(null, env.store.job(c.s("id")!!)!!.label)
        assertEquals(404, call("DELETE", "labels/$labelId").code)
        // "label": null takes label and colour off.
        assertEquals("null", call("PATCH", "syncs/${c.s("id")}", """{"label":null}""").obj["color"].toString())

        // Tags are replaced as a whole; not an object is refused.
        assertEquals("""{"x":""}""", call("PATCH", "syncs/${c.s("id")}", """{"tags":{"x":null}}""").obj["tags"].toString())
        assertEquals("{}", call("PATCH", "syncs/${c.s("id")}", """{"tags":null}""").obj["tags"].toString())
        assertEquals(400, call("PATCH", "syncs/${c.s("id")}", """{"tags":"genre"}""").code)

        // A group run: needs a group; starts those turned on (B is off).
        assertEquals(400, call("POST", "syncs/run").code)
        assertEquals(400, call("POST", "syncs/run?tag=%3D").code)
        val none = call("POST", "syncs/run?tag=nothing")
        assertEquals(none.text, 202, none.code); assertEquals("0", none.obj.s("started"))
        val run = call("POST", "syncs/run?tag=genre")
        assertEquals(run.text, 202, run.code)
        assertEquals("1", run.obj.s("started"))
        assertEquals(listOf(a.s("id")), run.obj["syncs"]!!.jsonArray.map { it.jsonObject.s("id") })
        val until = System.currentTimeMillis() + 20_000
        while (env.store.job(a.s("id")!!)!!.lastReportId == null && System.currentTimeMillis() < until) Thread.sleep(100)
        assertTrue(env.store.job(a.s("id")!!)!!.lastReportId != null)
        assertEquals(null, env.store.job(b.s("id")!!)!!.lastReportId)
        assertEquals(null, env.store.job(c.s("id")!!)!!.lastReportId)
    }

    /** A two-way pair comes from the app's configuration file: the API shows it and turns its removals on and off, on both halves. */
    @Test fun aTwoWayPairsRemovalsAreTurnedOnAndOffOnBothHalves() {
        env.store.upsertJob(SyncJob("ab", "Pair", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out"), linkedJobId = "ba"))
        env.store.upsertJob(SyncJob("ba", "Pair ↔", PlaylistRef("csv", "Out", "Out"), PlaylistRef("csv", "In", "In"), linkedJobId = "ab", twin = true))
        env.store.upsertJob(SyncJob("solo", "Solo", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        val before = call("GET", "syncs/ab").obj
        assertEquals("ba", before.s("linkedSyncId"))
        assertEquals("false", before.s("twoWayRemovals"))
        val on = call("PATCH", "syncs/ab", """{"twoWayRemovals":true}""")
        assertEquals(on.text, 200, on.code)
        assertEquals("true", on.obj.s("twoWayRemovals"))
        assertTrue(env.store.job("ab")!!.twoWayRemovals && env.store.job("ba")!!.twoWayRemovals)
        // The state of a pair is forgotten when the option goes off.
        env.store.putPairState(env.store.job("ab")!!, com.xlollx.songport.data.PairState("csv|In", "csv|Out", mapOf("1" to "1")))
        assertEquals(200, call("PATCH", "syncs/ba", """{"twoWayRemovals":false}""").code)
        assertFalse(env.store.job("ab")!!.twoWayRemovals || env.store.job("ba")!!.twoWayRemovals)
        assertTrue(env.store.data.pairStates.isEmpty())
        // A sync on its own has no other side.
        assertEquals(400, call("PATCH", "syncs/solo", """{"twoWayRemovals":true}""").code)
        assertEquals("null", call("GET", "syncs/solo").obj.s("linkedSyncId") ?: "null")
    }

    /** Two-way made here as in the app's editor: the "↔" half is made, follows the first, and goes when two-way is turned off. */
    @Test fun aTwoWayPairIsMadeFollowedAndUnmade() {
        val made = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"},"name":"Pair","schedule":"daily","at":"07:30","tags":{"mood":"gym"},"favorite":true,"color":"teal","twoWay":true,"twoWayRemovals":true}""")
        assertEquals(made.text, 201, made.code)
        val id = made.obj.s("id")!!
        assertEquals("true", made.obj.s("twoWay")); assertEquals("false", made.obj.s("twin")); assertEquals("true", made.obj.s("twoWayRemovals"))
        val twinId = made.obj.s("linkedSyncId")!!
        var twin = env.store.job(twinId)!!
        assertTrue(twin.twin && twin.twoWayRemovals && !twin.mirrorRemovals)
        assertEquals("Pair ↔", twin.name); assertEquals(id, twin.linkedJobId)
        assertEquals("Out", twin.source.playlistId); assertEquals("In", twin.target.playlistId)
        assertEquals(mapOf("mood" to "gym"), twin.tags)
        assertTrue(twin.favorite); assertEquals("teal", twin.color); assertEquals("teal", twin.label)
        assertEquals("07:30", twin.at); assertEquals(1440, com.xlollx.songport.sync.Timing.interval(twin))
        assertEquals("true", call("GET", "syncs/$twinId").obj.s("twin"))

        // The first one changed: the other half follows what the two share, and its name while it is the editor's.
        val edited = call("PATCH", "syncs/$id", """{"name":"Both","every":180,"favorite":false,"enabled":false,"twoWayRemovals":false}""")
        assertEquals(edited.text, 200, edited.code)
        twin = env.store.job(twinId)!!
        assertEquals("Both ↔", twin.name)
        assertEquals(180, com.xlollx.songport.sync.Timing.interval(twin))
        assertFalse(twin.favorite || twin.enabled || twin.twoWayRemovals)
        assertFalse(env.store.job(id)!!.twoWayRemovals)
        // Asked again, nothing is made twice.
        assertEquals(200, call("PATCH", "syncs/$id", """{"twoWay":true,"twoWayRemovals":true}""").code)
        assertEquals(2, env.store.data.jobs.size)
        assertTrue(env.store.job(id)!!.twoWayRemovals && env.store.job(twinId)!!.twoWayRemovals)

        // Two-way is turned off on the first sync, not on its ↔ half; the half goes, with the pair's state.
        val refused = call("PATCH", "syncs/$twinId", """{"twoWay":false}""")
        assertEquals(400, refused.code); assertTrue(refused.text, refused.text.contains(id))
        env.store.putPairState(env.store.job(id)!!, com.xlollx.songport.data.PairState("csv|In", "csv|Out", mapOf("1" to "1")))
        val off = call("PATCH", "syncs/$id", """{"twoWay":false}""")
        assertEquals(off.text, 200, off.code)
        assertEquals("false", off.obj.s("twoWay")); assertEquals("false", off.obj.s("twoWayRemovals"))
        assertEquals(null, env.store.job(twinId))
        assertEquals(null, env.store.job(id)!!.linkedJobId)
        assertTrue(env.store.data.pairStates.isEmpty())
        // The playlists stay as they were.
        assertTrue(File(data, "csv/In.csv").isFile && File(data, "csv/Out.csv").isFile)

        // Not towards a playlist still to be made, nor from a radio's or a link's source; nothing is saved then.
        val fresh = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"twoWay":true}""")
        assertEquals(400, fresh.code); assertTrue(fresh.text, fresh.text.contains("existing destination"))
        assertEquals(1, env.store.data.jobs.size)
        // Made later on a sync that has both playlists.
        assertEquals(200, call("PATCH", "syncs/$id", """{"twoWay":true}""").code)
        val again = env.store.job(env.store.job(id)!!.linkedJobId!!)!!
        assertTrue(again.twin && !again.twoWayRemovals); assertEquals("Both ↔", again.name)
    }

    /** A pair is turned on and off, and deleted, as one: from either half. */
    @Test fun aPairIsToggledAndDeletedAsOne() {
        fun pair(): Pair<String, String> {
            val o = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"},"name":"Pair","twoWay":true,"twoWayRemovals":true}""").obj
            return o.s("id")!! to o.s("linkedSyncId")!!
        }
        env.store.upsertJob(SyncJob("solo", "Solo", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        val (a, b) = pair()
        assertEquals(200, call("POST", "syncs/$b/disable").code)
        assertFalse(env.store.job(a)!!.enabled || env.store.job(b)!!.enabled)
        assertTrue(env.store.job("solo")!!.enabled)
        assertEquals(200, call("POST", "syncs/$a/enable").code)
        assertTrue(env.store.job(a)!!.enabled && env.store.job(b)!!.enabled)
        // Deleted from the ↔ half: both go, with the pair's state; the others stay.
        env.store.putPairState(env.store.job(a)!!, com.xlollx.songport.data.PairState("csv|In", "csv|Out", mapOf("1" to "1")))
        assertEquals(200, call("DELETE", "syncs/$b").code)
        assertEquals(listOf("solo"), env.store.data.jobs.map { it.id })
        assertTrue(env.store.data.pairStates.isEmpty())
        // And from the first half.
        val (c, _) = pair()
        assertEquals(200, call("DELETE", "syncs/$c").code)
        assertEquals(listOf("solo"), env.store.data.jobs.map { it.id })
        assertTrue(File(data, "csv/In.csv").isFile && File(data, "csv/Out.csv").isFile)
    }

    /** Removals a run holds back are confirmed for one run started by a call; a scheduled run never makes them. */
    @Test fun heldRemovalsAreConfirmedForOneRun() {
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\nOther Song,Other Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\nSong,Band\n" + (1..12).joinToString("") { "Gone $it,Old Band $it\n" })
        env.store.upsertJob(SyncJob("m", "Mirror", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out"), mirrorRemovals = true))
        fun ran(after: String?): JsonObject {
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                val o = call("GET", "syncs/m").obj
                val last = o["lastResult"] as? JsonObject
                if (o.s("running") == "false" && last != null && last.s("id") != after) return last
                Thread.sleep(100)
            }
            error("the run did not end")
        }
        assertEquals(202, call("POST", "syncs/m/run").code)
        val first = ran(null)
        assertEquals(first.toString(), "12", first.s("heldRemovals")); assertEquals("0", first.s("removed"))
        val preview = call("GET", "syncs/m/preview").obj
        assertEquals("true", preview.s("removalsNeedConfirmation"))
        assertEquals(12, preview["toRemove"]!!.jsonArray.size)

        // A confirmation of only some of them does not cover the run: all are held again (and it is spent).
        env.store.confirmRemovals("m", preview["toRemove"]!!.jsonArray.take(3).map { it.jsonObject.s("id")!! })
        assertEquals(202, call("POST", "syncs/m/run").code)
        val partly = ran(first.s("id"))
        assertEquals("12", partly.s("heldRemovals"))

        // Confirmed by the call: this run makes them, the next one has nothing to hold.
        val confirmed = call("POST", "syncs/m/run", """{"confirmRemovals":true}""")
        assertEquals(confirmed.text, 202, confirmed.code)
        assertEquals("true", confirmed.obj.s("removalsConfirmed"))
        val done = ran(partly.s("id"))
        assertEquals(done.toString(), "12", done.s("removed")); assertEquals("0", done.s("heldRemovals"))
        assertFalse(File(data, "csv/Out.csv").readText().contains("Gone"))
        assertTrue(env.store.data.removalConfirmations.isEmpty())
    }

    /** The ids of the preview, as the app's tick sends them: only those go. */
    @Test fun heldRemovalsConfirmedByTheirIdsAndNeverByASchedule() {
        File(data, "csv/In.csv").writeText("title,artist\nSong,Band\n")
        File(data, "csv/Out.csv").writeText("title,artist\nSong,Band\n" + (1..12).joinToString("") { "Gone $it,Old Band $it\n" })
        val job = SyncJob("m", "Mirror", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out"), mirrorRemovals = true)
        env.store.upsertJob(job)
        val runner = Runner(env)
        fun wait(after: String?): SyncReport {
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                val j = env.store.job("m")!!
                if (!runner.isRunning("m") && j.lastReportId != null && j.lastReportId != after) return env.store.report(j.lastReportId!!)!!
                Thread.sleep(100)
            }
            error("the run did not end")
        }
        // By its schedule, even asked to confirm: held.
        runner.start(job, scheduled = true, confirmRemovals = true)
        val scheduled = wait(null)
        assertEquals(12, scheduled.heldBack); assertEquals(0, scheduled.removed)
        assertTrue(env.store.data.removalConfirmations.isEmpty())
        val ids = call("GET", "syncs/m/preview").obj["toRemove"]!!.jsonArray.map { it.jsonObject.s("id")!! }
        val sent = call("POST", "syncs/m/run", """{"confirmRemovals":[${ids.joinToString(",") { "\"$it\"" }}]}""")
        assertEquals(sent.text, 202, sent.code)
        val until = System.currentTimeMillis() + 20_000
        while (File(data, "csv/Out.csv").readText().contains("Gone") && System.currentTimeMillis() < until) Thread.sleep(100)
        assertFalse(env.store.data.reports.first().toString() + sent.text, File(data, "csv/Out.csv").readText().contains("Gone"))
    }

    /** Missing on my server: the list in every format, its filters, the ticks, and Lidarr's key that opens it alone. */
    @Test fun theTracksMissingOnTheServer() {
        fun job(id: String, src: String, dst: String) = SyncJob(id, "To $id", PlaylistRef(src, "p", "P"), PlaylistRef(dst, "t", "T"))
        val jude = Track("s1", "Hey Jude", listOf("The Beatles"), album = "Hey Jude", durationMs = 431_000, isrc = "GBAYE0601690")
        val judeDeezer = Track("d1", "Hey Jude - Remastered 2015", listOf("The Beatles"), isrc = "GBAYE0601690")
        val paranoid = Track("s2", "Paranoid", listOf("Black Sabbath"), album = "Paranoid")
        fun run(job: String, vararg t: Track) = SyncReport("r$job", job, "To $job", 1_780_000_000_000, 10, sourceCount = 5, unmatched = t.map { it.toString() }, unmatchedTracks = t.toList())
        env.store.update { d ->
            d.copy(jobs = listOf(job("a", "spotify", "jellyfin"), job("b", "deezer", "subsonic"), job("c", "spotify", "tidal")),
                reports = listOf(run("a", jude, paranoid), run("b", judeDeezer), run("c", Track("x", "Elsewhere", listOf("Nobody")))))
        }
        // What the syncs' MusicBrainz lookups learnt is used; MusicBrainz itself is never asked here.
        env.keyValues("musicbrainz_isrc")["GBAYE0601690"] = "b2181aae-5cba-496c-bb0c-b4cc0109ebf8|1"
        env.keyValues("musicbrainz_isrc_artists")["GBAYE0601690"] = "b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d"

        val all = call("GET", "wanted")
        assertEquals(all.text, 200, all.code)
        assertEquals("2", all.obj.s("count"))
        val tracks = all.obj["tracks"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Hey Jude", "Paranoid"), tracks.map { it.s("title") })
        assertEquals("b2181aae-5cba-496c-bb0c-b4cc0109ebf8", tracks[0].s("musicBrainzId"))
        assertEquals(listOf("a", "b"), tracks[0]["syncs"]!!.jsonArray.map { it.jsonObject.s("id") })
        assertEquals("2", call("GET", "wanted?service=jellyfin&since=2026-01-01").obj.s("count"))
        assertEquals("0", call("GET", "wanted?since=2026-06-01").obj.s("count"))
        assertEquals("1", call("GET", "wanted?sync=To%20b").obj.s("count"))
        assertEquals("1", call("GET", "wanted?service=deezer").obj.s("count"))
        assertEquals(400, call("GET", "wanted?since=someday").code)
        assertEquals(400, call("GET", "wanted?format=xlsx").code)
        val albums = call("GET", "wanted?group=album").obj["albums"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Hey Jude", "Paranoid"), albums.map { it.s("album") })

        assertTrue(call("GET", "wanted?format=csv").text.startsWith("title,artist,album,duration_ms,isrc,musicbrainz_id,syncs,first_seen\r\nHey Jude,The Beatles,Hey Jude,431000,GBAYE0601690,b2181aae"))
        assertEquals("The Beatles - Hey Jude\nBlack Sabbath - Paranoid\n", call("GET", "wanted?format=text").text)
        assertTrue(call("GET", "wanted?format=m3u").text.startsWith("#EXTM3U"))
        assertEquals("""[{"MusicBrainzId":"b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d","ArtistName":"The Beatles"}]""", call("GET", "wanted?format=lidarr").text)

        // Lidarr only GETs a URL: the list's key opens this call and no other; a new token changes it.
        val key = Api.listKey(env)!!
        assertEquals(200, call("GET", "wanted?format=lidarr&key=$key", auth = false).code)
        assertEquals(401, call("GET", "wanted?key=nope", auth = false).code)
        assertEquals(401, call("GET", "syncs?key=$key", auth = false).code)
        assertEquals(401, call("POST", "wanted/got?key=$key", """{"ids":[]}""", auth = false).code)
        // The key sent as a header opens nothing, not even the list: it only ever works as ?key= on GET /wanted.
        fun withHeader(method: String, path: String) = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "ListKey $key")
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode()
        assertEquals(401, withHeader("GET", "syncs"))
        assertEquals(401, withHeader("GET", "config"))
        assertEquals(401, withHeader("POST", "syncs/run-all"))
        assertEquals(401, withHeader("GET", "wanted"))

        // Ticked: hidden, unless all=true; and back.
        val id = tracks[1].s("id")!!
        assertEquals("1", call("POST", "wanted/got", """{"ids":["$id"]}""").obj.s("changed"))
        assertEquals("1", call("GET", "wanted").obj.s("count"))
        assertEquals("true", call("GET", "wanted?all=true").obj["tracks"]!!.jsonArray[1].jsonObject.s("got"))
        assertEquals("1", call("POST", "wanted/got", """{"ids":["$id"],"got":false}""").obj.s("changed"))
        assertEquals("2", call("GET", "wanted").obj.s("count"))

        token = Api.newToken(env)
        assertEquals(401, call("GET", "wanted?key=$key", auth = false).code)
        assertTrue(call("GET", "openapi.json", auth = false).text.contains("/wanted"))
    }

    /** The songs of a personal server without MusicBrainz tags, as the page's tool lists them. */
    @Test fun theSongsWithoutMusicBrainzTags() {
        assertEquals(400, call("GET", "tools/mbtags").code)
        assertEquals(400, call("GET", "tools/mbtags?service=csv").code)
        assertEquals(404, call("GET", "tools/mbtags?service=nowhere").code)
        val navidrome = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val songs = if (ex.requestURI.path.endsWith("search3")) ""","searchResult3":{"song":[
                    {"id":"a","title":"Tagged","artist":"Band","musicBrainzId":"5fb524f1-8cc8-4c04-a921-e34c0a911ea7"},
                    {"id":"b","title":"Bare","artist":"Band","album":"Demo"}]}""" else ""
                val body = """{"subsonic-response":{"status":"ok","version":"1.16.1"$songs}}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            env.tokens.set("subsonic", com.xlollx.songport.data.Tokens(accessToken = "pw", userName = "me", extra = mapOf("url" to "http://127.0.0.1:${navidrome.address.port}")))
            env.store.updateSettings { it.copy(connectors = it.connectors + "subsonic") }
            // The only such server connected: no need to name it; "connector" names it too.
            for (path in listOf("tools/mbtags", "tools/mbtags?service=subsonic", "tools/mbtags?connector=subsonic")) {
                val a = call("GET", path)
                assertEquals(a.text, 200, a.code)
                assertEquals("2", a.obj.s("songs")); assertEquals("1", a.obj.s("untagged")); assertEquals("subsonic", a.obj.s("service"))
                val t = a.obj["tracks"]!!.jsonArray.single().jsonObject
                assertEquals("Bare", t.s("title")); assertEquals("Demo", t.s("album"))
                assertTrue(a.obj.s("guide")!!.contains("picard"))
            }
        } finally {
            navidrome.stop(0)
        }
    }

    @Test fun aSyncIsMadeChangedMovedAndDeleted() {
        val made = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"},"name":"Mine","schedule":"daily","at":"07:30","changes":"mirror","color":"teal"}""")
        assertEquals(made.text, 201, made.code)
        val id = made.obj.s("id")!!
        assertEquals("Mine", made.obj.s("name"))
        assertEquals("daily at 07:30", made.obj.s("timing"))
        assertEquals("true", made.obj.s("mirrorRemovals"))
        assertEquals("teal", made.obj.s("color"))
        // The page's checks: a playlist the service does not list, a playlist onto itself, an unknown service.
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"Nope"},"target":{"service":"csv"}}""").code)
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"In"}}""").code)
        assertEquals(404, call("POST", "syncs", """{"source":{"service":"nope","playlist":"In"},"target":{"service":"csv"}}""").code)
        assertEquals(400, call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"schedule":"sometimes"}""").code)
        // Changed by halves: what is not named stays.
        val edited = call("PATCH", "syncs/$id", """{"name":"Renamed","every":180,"changes":"archive","color":"#12a594"}""")
        assertEquals(edited.text, 200, edited.code)
        assertEquals("Renamed", edited.obj.s("name"))
        assertEquals("every 3 h", edited.obj.s("timing"))
        assertEquals("true", edited.obj.s("archive"))
        assertEquals("#12A594", edited.obj.s("color"))
        assertEquals(400, call("PATCH", "syncs/$id", """{"at":"25:00"}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"color":"mauve"}""").code)
        // Weekly on chosen days.
        assertEquals("Mon, Fri at 08:00", call("PATCH", "syncs/$id", """{"schedule":"daily","days":[1,5],"at":"08:00"}""").obj.s("timing"))
        // Moved in the list.
        env.store.upsertJob(SyncJob("other", "Other", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        assertEquals("1", call("POST", "syncs/other/move", """{"to":"top"}""").obj.s("position"))
        assertEquals("2", call("POST", "syncs/other/move", """{"to":2}""").obj.s("position"))
        assertEquals(listOf(id, "other"), env.store.data.jobs.map { it.id })
        // Stop when nothing runs: said so.
        assertEquals(409, call("POST", "syncs/$id/stop").code)
        assertEquals(200, call("DELETE", "syncs/$id").code)
        assertEquals(null, env.store.job(id))
        assertEquals(404, call("DELETE", "syncs/$id").code)
    }

    @Test fun aSyncMadeWithItsOwnIdIsMadeOnceHoweverOftenAsked() {
        // The app's hand-over, sent again after its answer was lost: the sync made then, not a second one.
        val body = """{"id":"phone-0f5c-9a","source":{"service":"csv","playlist":"In"},"target":{"service":"csv"},"name":"Handed"}"""
        val made = call("POST", "syncs", body)
        assertEquals(made.text, 201, made.code)
        assertEquals("phone-0f5c-9a", made.obj.s("id"))
        val again = call("POST", "syncs", body.replace("Handed", "Other name"))
        assertEquals(again.text, 200, again.code)
        assertEquals("Handed", again.obj.s("name"))
        assertEquals(1, env.store.data.jobs.count { it.id == "phone-0f5c-9a" })
        assertEquals(1, env.store.data.jobs.size)
        // Only a plain id: nothing that could reach another call from an address.
        for (bad in listOf("..", "a/b", "x".repeat(101), "a b")) {
            assertEquals(bad, 400, call("POST", "syncs", body.replace("phone-0f5c-9a", bad)).code)
        }
        assertEquals(1, env.store.data.jobs.size)
    }

    @Test fun aOneDigitHourIsReadAndASwitchAlreadyOffChangesNothing() {
        val made = call("POST", "syncs", """{"source":{"service":"csv","playlist":"In"},"target":{"service":"csv","playlist":"Out"},"schedule":"daily","at":"7:30","changes":"mirror"}""")
        assertEquals(made.text, 201, made.code)
        val id = made.obj.s("id")!!
        assertEquals("daily at 07:30", made.obj.s("timing"))
        assertEquals("daily at 06:05", call("PATCH", "syncs/$id", """{"at":"6:05"}""").obj.s("timing"))
        assertEquals(400, call("PATCH", "syncs/$id", """{"at":"7:3"}""").code)
        assertEquals(400, call("PATCH", "syncs/$id", """{"at":"730"}""").code)
        // Mirror is on: archive, already off, sent as false leaves it on.
        val still = call("PATCH", "syncs/$id", """{"archive":false}""").obj
        assertEquals("true", still.s("mirrorRemovals")); assertEquals("false", still.s("archive"))
        // And the other way round.
        assertEquals("true", call("PATCH", "syncs/$id", """{"changes":"archive"}""").obj.s("archive"))
        val kept = call("PATCH", "syncs/$id", """{"mirrorRemovals":false}""").obj
        assertEquals("true", kept.s("archive")); assertEquals("false", kept.s("mirrorRemovals"))
        // The one that is on, sent as false, goes off.
        val off = call("PATCH", "syncs/$id", """{"archive":false}""").obj
        assertEquals("false", off.s("archive")); assertEquals("false", off.s("mirrorRemovals"))
    }

    @Test fun aWebhookAddressThatIsNoUrlDoesNotStopARun() {
        Api.setWebhook(env, "https://exa mple.org/a b", null, setOf("sync.started"))
        Api.notifyStarted(env, SyncJob("w", "W", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")), false)
    }

    @Test fun onASharedServerEachPersonSeesTheirOwnTasks() {
        // A second person of the same server, without co-admin: their own data folder and token.
        val theirs = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "https://songport.example.org", Permissions.DEFAULT)
        val other = Web(theirs, Runner(theirs), Auth(theirs), "sekret", "test").start(0)
        try {
            val mine = Tasks.start("Mine", "/tools", {}, env.owner) { "done" }
            val their = Tasks.start("Theirs", "/tools", {}, theirs.owner) { "done" }
            fun asOther(path: String) = client.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${other.address.port}$path")).header("Authorization", "Bearer ${Api.newToken(theirs)}").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val listed = Json.parseToJsonElement(asOther("/api/v1/tasks").body()).jsonArray.map { it.jsonObject.s("id") }
            assertTrue(their.id in listed)
            assertFalse(mine.id in listed)
            assertEquals(404, asOther("/api/v1/tasks/${mine.id}").statusCode())
            assertEquals(200, asOther("/api/v1/tasks/${their.id}").statusCode())
            assertEquals(null, Tasks.get(mine.id, theirs.sees))
            // The admin (here: a server for one person) sees every run.
            val all = call("GET", "tasks").arr.map { it.jsonObject.s("id") }
            assertTrue(mine.id in all && their.id in all)
            assertEquals(200, call("GET", "tasks/${their.id}").code)
        } finally {
            other.stop(0)
        }
    }

    @Test fun previewSaysWhatARunWouldDoAndChangesNothing() {
        env.store.upsertJob(SyncJob("p1", "Prev", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")))
        val p = call("GET", "syncs/p1/preview")
        assertEquals(p.text, 200, p.code)
        assertTrue(p.text, p.obj["toAdd"]!!.jsonArray.any { it.jsonObject.s("title") == "Other Song" })
        assertEquals("title,artist\n", File(data, "csv/Out.csv").readText())
    }

    @Test fun runsArePagedAndAReviewIsDoneFromAScript() {
        env.store.upsertJob(SyncJob("r", "Rev", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out")))
        val a = Track(id = "a", title = "Alpha", artists = listOf("One"))
        val b = Track(id = "b", title = "Beta", artists = listOf("Two"))
        val c = Track(id = "c", title = "Gamma", artists = listOf("Three"))
        env.store.addReport(SyncReport(id = "r0", jobId = "r", jobName = "Rev", startedEpoch = 1, durationMs = 1))
        env.store.addReport(
            SyncReport(
                id = "r1", jobId = "r", jobName = "Rev", startedEpoch = 2, durationMs = 1, notes = listOf("A note"),
                unmatched = listOf(a.toString(), b.toString()), unmatchedTracks = listOf(a, b),
                reviewTracks = listOf(MatchReview(c, c.copy(id = "c2"), 0.7)),
            ),
        )
        val page = call("GET", "runs?sync=r&limit=1")
        assertEquals("2", page.obj.s("total"))
        assertEquals("r1", page.obj["runs"]!!.jsonArray.single().jsonObject.s("id"))
        assertEquals("r0", call("GET", "syncs/r/runs?offset=1").obj["runs"]!!.jsonArray.single().jsonObject.s("id"))
        val run = call("GET", "runs/r1").obj
        assertEquals("A note", run["notes"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("a", run["notFoundTracks"]!!.jsonArray.first().jsonObject.s("id"))
        val review = call("GET", "runs/r1/review").obj
        assertEquals(2, review["notFound"]!!.jsonArray.size)
        // Pick a track for Alpha by "Artist - Title" on the target (File takes any), ignore Beta, keep Gamma.
        val picked = call("POST", "runs/r1/review", """{"action":"pick","tracks":["a"],"choice":"One - Alpha"}""")
        assertEquals(picked.text, 200, picked.code)
        assertTrue(File(data, "csv/Out.csv").readText().contains("Alpha"))
        assertEquals(200, call("POST", "runs/r1/review", """{"action":"ignore","tracks":"all"}""").code)
        val kept = call("POST", "runs/r1/review", """{"action":"keep","tracks":["c"]}""")
        assertEquals(kept.text, 200, kept.code)
        val left = kept.obj["review"]!!.jsonObject
        assertTrue(left["notFound"]!!.jsonArray.isEmpty() && left["toCheck"]!!.jsonArray.isEmpty())
        assertTrue("b" in env.store.job("r")!!.ignoredSourceIds)
        assertEquals(400, call("POST", "runs/r1/review", """{"action":"keep","tracks":["c"]}""").code)
        assertEquals(400, call("POST", "runs/r1/review", """{"action":"dance"}""").code)
    }

    /** The phone sends its review choices for a sync it handed over; the server takes the newer ones in and answers all of its own. */
    @Test fun reviewChoicesAreExchangedNewestFirst() {
        env.store.upsertJob(SyncJob("d", "Dec", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", "Out", "Out"), lastReportId = "d1"))
        val a = Track(id = "a", title = "Alpha", artists = listOf("One"))
        env.store.addReport(SyncReport(id = "d1", jobId = "d", jobName = "Dec", startedEpoch = 2, durationMs = 1, unmatched = listOf(a.toString()), unmatchedTracks = listOf(a)))
        assertEquals("[]", call("GET", "syncs/d/decisions").obj["decisions"].toString())
        // Ignored on the server through its review: kept with its time.
        assertEquals(200, call("POST", "runs/d1/review", "{\"action\":\"ignore\",\"tracks\":[\"a\"]}").code)
        val serverAt = call("GET", "syncs/d/decisions").obj["decisions"]!!.jsonArray.single().jsonObject.also { assertEquals("ignore", it.s("kind")) }.s("at")!!.toLong()
        // The phone's: an older match for a (loses), a newer match for b, a keep for c.
        val sent = call("POST", "syncs/d/decisions", """{"decisions":[
            {"source":"a","kind":"match","track":"A1","at":${serverAt - 1000}},
            {"source":"b","kind":"match","track":"B1","at":${serverAt + 1}},
            {"source":"c","kind":"keep","track":"C1","at":5}]}""")
        assertEquals(sent.text, 200, sent.code)
        val all = sent.obj["decisions"]!!.jsonArray.associate { it.jsonObject.s("source")!! to it.jsonObject }
        assertEquals("ignore", all["a"]!!.s("kind"))
        assertEquals("B1", all["b"]!!.s("track"))
        assertEquals("keep", all["c"]!!.s("kind"))
        assertEquals("B1", env.store.cachedMatch("csv", "b", "csv"))
        assertTrue(env.store.isIgnored("csv", "a", "csv"))
        assertEquals(400, call("POST", "syncs/d/decisions", """{"decisions":[{"source":"x","kind":"delete","at":1}]}""").code)
        assertEquals(400, call("POST", "syncs/d/decisions", """{"decisions":"all"}""").code)
        assertEquals(404, call("GET", "syncs/nope/decisions").code)
        assertEquals(401, call("GET", "syncs/d/decisions", auth = false).code)
    }

    @Test fun filesSettingsPatternsZipAndDelete() {
        assertEquals("AUTO", call("GET", "files/settings").obj.s("layout"))
        assertEquals(400, call("PUT", "files/settings", """{"layout":"CUSTOM"}""").code)
        assertEquals(400, call("PUT", "files/settings", """{"patterns":["{artist} - [{album}"]}""").code)
        val saved = call("PUT", "files/settings", """{"layout":"CUSTOM","patterns":["{artist}/{album}/{track} {title}"]}""")
        assertEquals(saved.text, 200, saved.code)
        assertEquals("CUSTOM", env.store.data.settings.fileNameLayout)
        val tried = call("POST", "files/try", """{"sample":"Music/Band/Album/01 Song.mp3"}""").obj
        assertEquals("Band", tried.s("artist")); assertEquals("Album", tried.s("album")); assertEquals("Song", tried.s("title"))
        // Tried with other patterns, never saved.
        assertEquals("false", call("POST", "files/try", """{"sample":"x","patterns":["{title} ({artist})"]}""").obj.s("matched"))
        assertEquals(listOf("{artist}/{album}/{track} {title}"), env.store.data.settings.fileNamePatterns)
        val zip = client.send(
            HttpRequest.newBuilder(URI("$base/api/v1/files/zip")).header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString("""{"files":["In.csv","Out"],"format":"m3u"}""")).build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        assertEquals(200, zip.statusCode())
        val names = java.util.zip.ZipInputStream(zip.body().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("In.m3u", "Out.m3u"), names)
        assertEquals(200, call("DELETE", "files/Out.csv").code)
        assertFalse(File(data, "csv/Out.csv").exists())
        assertEquals(404, call("DELETE", "files/Out.csv").code)
    }

    @Test fun theSetUpComesBackInWithADryRunFirst() {
        env.store.upsertJob(SyncJob("cfg", "Saved", PlaylistRef("csv", "In", "In"), PlaylistRef("csv", null, "Copy")))
        val file = call("GET", "config").text
        env.store.deleteJob("cfg")
        val dry = call("POST", "config?dryRun=true", file).obj
        assertEquals("false", dry.s("imported")); assertEquals("1", dry.s("syncs"))
        assertEquals(null, env.store.job("cfg"))
        assertEquals("true", call("POST", "config", file).obj.s("imported"))
        assertEquals("Saved", env.store.job("cfg")!!.name)
        assertEquals(400, call("POST", "config", "not a set-up").code)
    }

    @Test fun webhookNotificationsAndSettings() {
        assertEquals(listOf("sync.finished", "backup.finished"), call("GET", "webhook").obj["events"]!!.jsonArray.map { it.jsonPrimitive.content })
        val hook = call("PUT", "webhook", """{"url":"https://example.org/hook","secret":"s3cret","events":["sync.started","sync.failed"]}""").obj
        assertEquals("true", hook.s("secret"))
        assertFalse(hook.toString().contains("s3cret"))
        assertEquals(setOf("sync.started", "sync.failed"), Api.events(env))
        assertEquals(400, call("PUT", "webhook", """{"url":"ftp://x"}""").code)
        assertEquals(400, call("PUT", "webhook", """{"url":"https://x","events":["sync.exploded"]}""").code)
        assertEquals("", call("DELETE", "webhook").obj.s("url"))
        // Channels: the kinds, one added, changed (its token kept), removed.
        assertTrue(call("GET", "notifications/kinds").obj["kinds"]!!.jsonArray.any { it.jsonObject.s("id") == "ntfy" })
        val added = call("POST", "notifications", """{"kind":"gotify","name":"Phone","settings":{"server":"https://gotify.example.org","token":"AbCdEfGhIjKlMn"},"events":["failed"]}""")
        assertEquals(added.text, 201, added.code)
        assertFalse(added.text.contains("AbCdEfGhIjKlMn"))
        val cid = added.obj.s("id")!!
        val changed = call("PATCH", "notifications/$cid", """{"name":"Laptop","enabled":false}""")
        assertEquals(changed.text, 200, changed.code)
        assertEquals("AbCdEfGhIjKlMn", Notify.channel(env, cid)!!.value("token"))
        assertEquals(false, Notify.channel(env, cid)!!.enabled)
        assertEquals(400, call("POST", "notifications", """{"kind":"gotify","settings":{"server":"nope","token":"x"}}""").code)
        assertEquals(200, call("DELETE", "notifications/$cid").code)
        assertEquals(null, Notify.channel(env, cid))
        // Settings.
        val s = call("PATCH", "settings", """{"theme":"dark","backupSchedule":"weekly"}""")
        assertEquals(s.text, 200, s.code)
        assertEquals("dark", s.obj.s("theme")); assertEquals("weekly", s.obj.s("backupSchedule"))
        assertEquals(400, call("PATCH", "settings", """{"theme":"neon"}""").code)
        assertEquals(400, call("PATCH", "settings", """{"backupFolder":"/no/such/folder"}""").code)
    }

    @Test fun servicesSearchConvertDuplicatesExportAndStats() {
        val svc = call("GET", "services/csv").obj
        assertEquals("true", svc.s("canCreatePlaylists"))
        assertEquals(404, call("GET", "services/nope").code)
        val found = call("GET", "services/csv/search?q=Band%20-%20Song").arr
        assertTrue(found.toString(), found.isNotEmpty())
        val conv = call("GET", "convert?track=Band%20-%20Song&to=csv").obj
        assertEquals("Band - Song", conv.s("asked"))
        val many = call("POST", "convert", """{"to":"csv","tracks":["Band - Song",{"title":"Other Song","artist":"Other Band"}]}""").arr
        assertEquals(2, many.size)
        val dupes = call("GET", "services/csv/playlists/In/duplicates").obj
        assertEquals("1", dupes.s("extraCopies"))
        val removal = call("POST", "services/csv/playlists/In/duplicates/remove")
        assertEquals(removal.text, 202, removal.code)
        val task = removal.obj.s("task")!!
        val deadline = System.currentTimeMillis() + 20_000
        while (call("GET", "tasks/$task").obj.s("done") != "true" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue(call("GET", "tasks").arr.any { it.jsonObject.s("id") == task })
        assertEquals("0", call("GET", "services/csv/playlists/In/duplicates").obj.s("extraCopies"))
        val m3u = call("GET", "services/csv/playlists/In/export?format=m3u")
        assertTrue(m3u.text, m3u.code == 200 && m3u.text.startsWith("#EXTM3U"))
        assertEquals(200, call("GET", "stats").code)
        // The log is the admin's: here the only user is the admin.
        assertEquals(200, call("GET", "log?lines=5").code)
    }

    @Test fun theDescriptionIsPublicAndListsEveryCall() {
        val doc = call("GET", "openapi.json", auth = false)
        assertEquals(200, doc.code)
        val paths = doc.obj["paths"]!!.jsonObject
        for (c in ApiDocs.CALLS) assertTrue("${c.method} ${c.path}", paths[c.path]?.jsonObject?.containsKey(c.method.lowercase()) == true)
        // Every call in the description is answered (a 404 for its unknown ids, never "Unknown call").
        for (c in ApiDocs.CALLS.filter { it.method == "GET" && "{" !in it.path }) {
            val a = call("GET", c.path.removePrefix("/"))
            assertFalse("${c.path}: ${a.text}", a.text.contains("Unknown call"))
        }
        // And the guide names each of them.
        val guide = listOf(File("../docs/guide/docker.md"), File("docs/guide/docker.md")).first { it.isFile || it.path.startsWith("docs") }
        if (guide.isFile) {
            val text = guide.readText()
            for (c in ApiDocs.CALLS) assertTrue("guide lacks ${c.method} ${c.path}", text.contains("${c.method} /api/v1${c.path}"))
        }
    }
}
