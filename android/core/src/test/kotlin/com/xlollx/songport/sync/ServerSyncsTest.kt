package com.xlollx.songport.sync

import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.RadioSource
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.net.parseJson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections

/** The phone and the user's own server: a sync handed over, the server's syncs read, a run started, choices exchanged. */
class ServerSyncsTest {
    private lateinit var server: MockWebServer
    private val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val bodies: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(200).setBody("{}") }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                calls += "${request.method} ${request.path}"
                bodies += request.body.readUtf8()
                return answer(request)
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun api() = ServerSyncs(server.url("/").toString(), "th_" + "t".repeat(8))

    private val job = SyncJob(
        id = "j", name = "Gym", source = PlaylistRef("spotify_bridge", "37i", "Gym mix"), target = PlaylistRef("tidal", "tt1", "Gym"),
        everyMinutes = 1440, at = "07:30", mirrorRemovals = true, followsChanges = true, favorite = true, tags = mapOf("genre" to "metal"),
    )

    private val services = listOf(
        ServerSyncs.Service("spotify", "spotify", "Spotify", true),
        ServerSyncs.Service("tidal@2", "tidal", "TIDAL (2)", true),
        ServerSyncs.Service("tidal", "tidal", "TIDAL", false),
        ServerSyncs.Service("deezer", "deezer", "Deezer", false),
    )

    @Test fun aSyncGoesToTheServersAccountsOfTheSameServices() {
        val plan = HandOver.plan(job, services)
        assertEquals(HandOver.Plan.Ready("spotify", "tidal@2"), plan)
        assertEquals(
            """{"id":"phone-j","name":"Gym","source":{"service":"spotify","playlist":"37i"},"target":{"service":"tidal@2","playlist":"tt1"},"every":1440,"at":"07:30","days":[],"changes":"mirror","follows":true,"enabled":true,"favorite":true,"tags":{"genre":"metal"}}""",
            HandOver.body(job, plan as HandOver.Plan.Ready).toString(),
        )
        // The main account first when there are several.
        assertEquals("tidal", HandOver.pick("tidal@3", services.map { it.copy(connected = true) }))
        assertEquals(HandOver.Plan.Missing(listOf("deezer")), HandOver.plan(job.copy(target = PlaylistRef("deezer", "d")), services))
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.TWO_WAY), HandOver.plan(job.copy(linkedJobId = "k"), services))
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.FILES), HandOver.plan(job.copy(source = PlaylistRef("csv", "mix")), services))
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.RADIO), HandOver.plan(job.copy(radio = RadioSource("Radio", "http://r")), services))
        // An archive with a link as its source, never run yet (no target playlist): the server makes the target.
        val linked = job.copy(source = PlaylistRef("spotify", null, "x", link = "https://open.spotify.com/playlist/37i"), target = PlaylistRef("tidal"), archive = true, mirrorRemovals = false, everyMinutes = 0, at = null, tags = emptyMap(), favorite = false)
        assertEquals(
            """{"id":"phone-j","name":"Gym","source":{"service":"spotify","link":"https://open.spotify.com/playlist/37i"},"target":{"service":"tidal@2"},"every":0,"days":[],"changes":"archive","enabled":true,"favorite":false}""",
            HandOver.body(linked, HandOver.Plan.Ready("spotify", "tidal@2")).toString(),
        )
    }

    @Test fun handingOverSendsTheSyncAndNoCredentials() = runBlocking {
        answer = { MockResponse().setResponseCode(201).setBody("""{"id":"0f5c-9a","name":"Gym"}""") }
        val id = api().create(job, HandOver.Plan.Ready("spotify", "tidal@2"))
        assertEquals("0f5c-9a", id)
        assertEquals(listOf("POST /api/v1/syncs"), calls.toList())
        assertEquals("Bearer th_tttttttt", server.takeRequest().getHeader("Authorization"))
        assertFalse(bodies.single(), bodies.single().contains("token", ignoreCase = true))
        // An id that is not a plain one is not taken, nor dots alone (they would reach another call).
        for (odd in listOf("../../config", "..", ".")) {
            answer = { MockResponse().setResponseCode(201).setBody("""{"id":"$odd"}""") }
            try { api().create(job, HandOver.Plan.Ready("spotify", "tidal@2")); fail(odd) } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
            try { ServerSyncs.safeId(odd); fail(odd) } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
        }
        assertEquals("v1.2", ServerSyncs.safeId("v1.2"))
    }

    @Test fun aHandOverWhoseAnswerIsLostAsksForTheSameSyncAgain() = runBlocking {
        // The server makes the sync, but its answer never reaches the phone.
        answer = { MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        try { api().create(job, HandOver.Plan.Ready("spotify", "tidal@2")); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.UNREACHABLE, e.kind) }
        answer = { MockResponse().setResponseCode(200).setBody("""{"id":"phone-j","name":"Gym"}""") }
        assertEquals("phone-j", api().create(job, HandOver.Plan.Ready("spotify", "tidal@2")))
        // Both tries ask for the same id, so the server can give back the one it made.
        val ids = bodies.map { (parseJson(it) as kotlinx.serialization.json.JsonObject)["id"].toString() }
        assertTrue(ids.toString(), ids.size >= 2 && ids.all { it == "\"phone-j\"" })
        // Always a plain id, whatever the sync's own.
        assertEquals("phone-ab9_c", HandOver.serverId(job.copy(id = "ab/../9_c é")))
    }

    // A two-way pair as the editor saves it: the original half, and its reverse (twin) half with the playlists swapped.
    private val original = job.copy(mirrorRemovals = false, followsChanges = false, linkedJobId = "j2", twoWayRemovals = true)
    private val reverse = job.copy(id = "j2", name = "Gym \u2194", source = job.target, target = job.source, mirrorRemovals = false, followsChanges = false, linkedJobId = "j", twoWayRemovals = true, twin = true)

    @Test fun aTwoWayPairIsFoundFromEitherHalfAndPlannedAsOne() {
        val jobs = listOf(reverse, original)
        assertEquals(original to reverse, HandOver.pair(original, jobs))
        assertEquals(original to reverse, HandOver.pair(reverse, jobs))
        // A pair saved before the twin flag: the reverse half is the one named "Name \u2194".
        val old = listOf(original, reverse.copy(twin = false))
        assertEquals(original to old[1], HandOver.pair(old[1], old))
        assertEquals(original to old[1], HandOver.pair(original, old))
        // Not a pair: no link, a link to nothing, or a link the other half does not return.
        assertEquals(null, HandOver.pair(job, jobs))
        assertEquals(null, HandOver.pair(original, listOf(original)))
        assertEquals(null, HandOver.pair(original, listOf(original, reverse.copy(linkedJobId = "x"))))
        // Handing over either half plans the pair from the original's services.
        assertEquals(HandOver.Plan.Ready("spotify", "tidal@2"), HandOver.plan(reverse, services, jobs))
        assertEquals(HandOver.Plan.Ready("spotify", "tidal@2"), HandOver.plan(original, services, jobs))
        // The two syncs of a shared playlist are linked alike, but belong to the group.
        val shared = PlaylistRef(com.xlollx.songport.shared.SharedProvider.ID, "g1", "Group")
        val group = listOf(original.copy(source = shared), reverse.copy(target = shared))
        assertEquals(null, HandOver.pair(group[0], group))
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.TWO_WAY), HandOver.plan(group[1], services, group))
        // Its other half gone: it stays on the phone.
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.TWO_WAY), HandOver.plan(original, services, listOf(original)))
        // Still only what the server can run: a pair of File playlists stays here.
        val files = listOf(original.copy(source = PlaylistRef("csv", "mix")), reverse.copy(target = PlaylistRef("csv", "mix")))
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.FILES), HandOver.plan(files[0], services, files))
    }

    @Test fun aPairGoesAsOneRequestAndTheServerNamesItsOtherHalf() = runBlocking {
        val plan = HandOver.plan(original, services, listOf(original, reverse)) as HandOver.Plan.Ready
        assertEquals(
            """{"id":"phone-j","name":"Gym","source":{"service":"spotify","playlist":"37i"},"target":{"service":"tidal@2","playlist":"tt1"},"every":1440,"at":"07:30","days":[],"changes":"add","enabled":true,"twoWay":true,"twoWayRemovals":true,"favorite":true,"tags":{"genre":"metal"}}""",
            HandOver.body(original, plan, pair = true).toString(),
        )
        // Not a pair: no two-way fields at all, as before.
        assertFalse(HandOver.body(job, plan).toString().contains("twoWay"))
        // A pair on archive never asks for removals to travel.
        assertTrue(HandOver.body(original.copy(archive = true), plan, pair = true).toString().contains(""""twoWayRemovals":false"""))
        answer = { MockResponse().setResponseCode(201).setBody("""{"id":"phone-j","name":"Gym","twoWay":true,"linkedSyncId":"f52afc01-10cc","twin":false}""") }
        assertEquals(ServerSyncs.Made("phone-j", "f52afc01-10cc"), api().made(original, plan, pair = true))
        assertEquals(listOf("POST /api/v1/syncs"), calls.toList())
        // A server that makes no other half (an older one ignoring twoWay): not taken as a pair.
        answer = { MockResponse().setResponseCode(201).setBody("""{"id":"phone-j","name":"Gym"}""") }
        try { api().made(original, plan, pair = true); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
        // The other half's id is a plain one too.
        answer = { MockResponse().setResponseCode(201).setBody("""{"id":"phone-j","linkedSyncId":"../../config"}""") }
        try { api().made(original, plan, pair = true); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
        // Without a pair, the answer needs no other half.
        answer = { MockResponse().setResponseCode(201).setBody("""{"id":"phone-j"}""") }
        assertEquals(ServerSyncs.Made("phone-j", null), api().made(job, plan, pair = false))
    }

    @Test fun theServersListNamesBothHalvesOfAPair() {
        val list = ServerSyncs.parseSyncs(parseJson("""[
            {"id":"phone-j","name":"Gym","source":{"service":"spotify","playlistId":"37i"},"target":{"service":"tidal","playlistId":"tt1"},"twoWay":true,"linkedSyncId":"u-1","twin":false,"enabled":false},
            {"id":"u-1","name":"Gym \u2194","source":{"service":"tidal","playlistId":"tt1"},"target":{"service":"spotify","playlistId":"37i"},"twoWay":true,"linkedSyncId":"phone-j","twin":true,"enabled":false},
            {"id":"s3","name":"Plain","source":{"service":"spotify","playlistId":"a"},"target":{"service":"tidal","playlistId":"b"}}
        ]"""))
        assertEquals(listOf("u-1", "phone-j", null), list.map { it.linkedId })
        assertEquals(listOf(false, true, false), list.map { it.twin })
    }

    @Test fun theServersSyncsAreReadAndCheckedEntryByEntry() {
        val list = ServerSyncs.parseSyncs(parseJson("""[
            {"id":"s1","name":"Gym","source":{"service":"spotify","serviceName":"Spotify","playlistId":"37i","playlist":"Gym mix"},
             "target":{"service":"tidal","serviceName":"TIDAL","playlistId":"tt1","playlist":"Gym"},"timing":"daily at 07:30","enabled":true,"running":false,
             "lastRun":1700000000000,"nextRun":null,"lastResult":{"id":"r9","started":1700000000000,"ok":true,"error":null,"added":3,"removed":0,"notFound":2,"toCheck":1}},
            {"id":"bad id/../x","source":{"service":"spotify"},"target":{"service":"tidal"}},
            {"id":"s2","source":{"service":"spotify"}},
            {"id":"s3","name":"Odd","source":{"service":"spotify","playlist":"A"},"target":{"service":"deezer","playlist":"B"},"enabled":"yes","lastRun":"soon","lastResult":{"id":"r1","added":-5}}
        ]"""))
        assertEquals(listOf("s1", "s3"), list.map { it.id })
        val s1 = list[0]
        assertEquals("Spotify", s1.source.serviceName)
        assertEquals(ServerSyncs.Run("r9", 1700000000000, true, null, 3, 0, 2, 1), s1.last)
        assertTrue(s1.enabled)
        val s3 = list[1]
        // Strings where flags and numbers go are not taken as such.
        assertFalse(s3.enabled)
        assertEquals(null, s3.lastRun)
        assertEquals(0, s3.last!!.added)
        try { ServerSyncs.parseSyncs(parseJson("""{"error":"nope"}""")); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
    }

    @Test fun theServersWordsAndItsSilenceBecomeKindsOfFailure() = runBlocking {
        answer = { MockResponse().setResponseCode(401).setBody("""{"error":"Send the API token as: Authorization: Bearer <token>"}""") }
        try { api().syncs(); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.TOKEN, e.kind) }
        answer = { MockResponse().setResponseCode(404).setBody("""{"error":"Unknown call. See https://trackhop.org/guide/docker#api"}""") }
        try { api().exchange("s1", emptyMap()); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.OLD, e.kind) }
        answer = { MockResponse().setResponseCode(409).setBody("""{"error":"TIDAL is not connected."}""") }
        try { api().create(job, HandOver.Plan.Ready("spotify", "tidal")); fail() } catch (e: ServerSyncs.Failure) {
            assertEquals(ServerSyncs.Failure.Kind.REFUSED, e.kind)
            assertEquals("TIDAL is not connected.", e.detail)
        }
        // Deleted there already: taking it back is no failure.
        answer = { MockResponse().setResponseCode(404).setBody("""{"error":"No sync with that id."}""") }
        api().delete("s1")
        val closed = ServerSocket(0).use { it.localPort }
        try { ServerSyncs("127.0.0.1:$closed", "x").syncs(); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.UNREACHABLE, e.kind) }
    }

    @Test fun choicesAreSentAndTheServersComeBackChecked() = runBlocking {
        answer = { MockResponse().setResponseCode(200).setBody("""{"syncId":"s1","decisions":[{"source":"a","kind":"match","track":"da","at":20},{"source":"b","kind":"ignore","at":99999999999999}]}""") }
        val got = api().exchange("s1", mapOf("c" to ReviewDecision(DecisionKind.IGNORE, null, 5)), now = 1_000)
        assertEquals(listOf("POST /api/v1/syncs/s1/decisions"), calls.toList())
        assertEquals("""{"decisions":[{"source":"c","kind":"ignore","at":5}]}""", bodies.single())
        assertEquals(ReviewDecision(DecisionKind.MATCH, "da", 20), got["a"])
        assertEquals(1_000L, got["b"]!!.at)
        answer = { MockResponse().setResponseCode(200).setBody("""{"decisions":[{"source":"a","kind":"rm -rf","at":1}]}""") }
        try { api().exchange("s1", emptyMap()); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
        // A run is started, a review read and acted on by the ids the server gave.
        answer = { r ->
            MockResponse().setResponseCode(if (r.method == "POST" && r.path!!.endsWith("/run")) 202 else 200).setBody(
                if (r.path!!.endsWith("/review")) """{"review":{"notFound":[{"id":"x","artist":"A","title":"T"}],"toCheck":[]},"notFound":[{"id":"x","artist":"A","title":"T"}],"toCheck":[{"source":{"id":"y","artist":"B","title":"U"},"match":{"id":"dy","artist":"B","title":"U (Live)"}}]}"""
                else """{"started":true}""",
            )
        }
        api().run("s1")
        val review = api().review("r9")
        assertEquals(listOf("x"), review.notFound.map { it.id })
        assertEquals("dy", review.toCheck.single().match.id)
        val left = api().act("r9", ignore = true, sourceIds = listOf("x"))
        assertEquals(listOf("x"), left.notFound.map { it.id })
        assertEquals("""{"action":"ignore","tracks":["x"]}""", bodies.last())
        assertEquals(listOf("POST /api/v1/syncs/s1/run", "GET /api/v1/runs/r9/review", "POST /api/v1/runs/r9/review"), calls.takeLast(3))
    }

    @Test fun aSyncTakenBackWithoutTheServerRunsHereAsItWas() {
        val other = job.copy(id = "other", enabled = false)
        val d = com.xlollx.songport.data.StoreData(
            jobs = listOf(job.copy(enabled = false), other),
            handedOver = mapOf(job.id to HandedOver("phone-${job.id}", 1L, wasEnabled = true), "other" to HandedOver("phone-other", 1L, wasEnabled = true)),
        )
        val back = HandOver.takenBack(d, job.id)
        assertEquals(setOf("other"), back.handedOver.keys)
        assertTrue(back.jobs.first { it.id == job.id }.enabled)
        // Only that sync: the other one stays on the server, off here.
        assertFalse(back.jobs.first { it.id == "other" }.enabled)
        // Off when handed over: off when back.
        val wasOff = d.copy(handedOver = mapOf(job.id to HandedOver("s", 1L, wasEnabled = false)))
        assertFalse(HandOver.takenBack(wasOff, job.id).jobs.first { it.id == job.id }.enabled)
        // Not handed over: nothing changes.
        assertEquals(back, HandOver.takenBack(back, job.id))
    }

    // A multi sync (2.2): one source and its targets, each synced with the group's own list ("hub").
    private val hub = PlaylistRef(com.xlollx.songport.shared.SharedProvider.ID, "0123456789abcdef", "Mix")
    private val multi = com.xlollx.songport.shared.SharedGroup("0123456789abcdef", "Mix", "k", "k", "", transport = com.xlollx.songport.shared.Invite.LOCAL, jobId = "d-sp", twinId = "u-sp")
    private val multiJobs = listOf(
        SyncJob("d-sp", "Mix · Spotify", hub, PlaylistRef("spotify", "s1", "Mix"), linkedJobId = "u-sp", twoWayRemovals = true, everyMinutes = 360, at = "08:00"),
        SyncJob("u-sp", "Mix · Spotify ↔", PlaylistRef("spotify", "s1", "Mix"), hub, linkedJobId = "d-sp", twoWayRemovals = true, twin = true, everyMinutes = 360, at = "08:00"),
        SyncJob("d-ti", "Mix · TIDAL", hub, PlaylistRef("tidal", "t1", "Mix"), mirrorRemovals = true, everyMinutes = 360, at = "08:00"),
        SyncJob("d-dz", "Mix · Deezer", hub, PlaylistRef("deezer", "z1", "Mix"), linkedJobId = "u-dz", twoWayRemovals = true, everyMinutes = 360, at = "08:00"),
        SyncJob("u-dz", "Mix · Deezer ↔", PlaylistRef("deezer", "z1", "Mix"), hub, linkedJobId = "d-dz", twoWayRemovals = true, twin = true, everyMinutes = 360, at = "08:00"),
    )

    @Test fun aMultiSyncIsPlannedWholeFromAnyOfItsSyncs() {
        val groups = listOf(multi)
        val ready = services.map { if (it.id == "deezer") it.copy(connected = true) else it }
        // Any of its syncs: the whole multi sync, never "stays on the phone" any more.
        for (j in multiJobs) assertEquals(HandOver.Plan.Group(mapOf("spotify" to "spotify", "tidal" to "tidal@2", "deezer" to "deezer")), HandOver.plan(j, ready, multiJobs, groups))
        // A service the server lacks is said, as for one sync.
        assertEquals(HandOver.Plan.Missing(listOf("deezer")), HandOver.plan(multiJobs[2], services, multiJobs, groups))
        // A File playlist in it: it stays here.
        val files = multiJobs.map { if (it.id == "d-ti") it.copy(target = PlaylistRef("csv", "Mix", "Mix")) else it }
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.FILES), HandOver.plan(files[0], ready, files, groups))
        // Without its group (a shared playlist of other people, or a caller that does not say): as before.
        assertEquals(HandOver.Plan.NotHere(HandOver.Why.TWO_WAY), HandOver.plan(multiJobs[1], ready, multiJobs))
    }

    @Test fun aMultiSyncGoesAsOneBodyAndItsSyncsAreMatchedToTheServers() {
        val members = com.xlollx.songport.shared.Nway.members(multiJobs, multi.id)
        val plan = HandOver.Plan.Group(mapOf("spotify" to "spotify", "tidal" to "tidal@2", "deezer" to "deezer"))
        assertEquals(
            """{"id":"0123456789abcdef","name":"Mix","source":{"service":"spotify","playlist":"s1","name":"Mix"},"targets":[""" +
                """{"service":"tidal@2","playlist":"t1","name":"Mix","way":"exact"},{"service":"deezer","playlist":"z1","name":"Mix","way":"two_way"}],""" +
                """"every":360,"at":"08:00","days":[],"enabled":true}""",
            HandOver.groupBody(multi, members, plan).toString(),
        )
        val answer = parseJson(
            """{"id":"0123456789abcdef","source":{"syncId":"S-d","twinId":"S-u","service":"spotify","playlistId":"s1"},"targets":[""" +
                """{"syncId":"T-d","twinId":null,"service":"tidal@2","playlistId":"t1"},{"syncId":"Z-d","twinId":"Z-u","service":"deezer","playlistId":"z1"},{"service":"odd"}]}""",
        )
        val made = ServerSyncs.parseMulti(answer)
        assertEquals(3, made.size)
        assertEquals(mapOf("d-sp" to "S-d", "u-sp" to "S-u", "d-ti" to "T-d", "d-dz" to "Z-d", "u-dz" to "Z-u"), HandOver.groupIds(members, made))
        try { ServerSyncs.parseMulti(parseJson("[]")); fail() } catch (e: ServerSyncs.Failure) { assertEquals(ServerSyncs.Failure.Kind.ODD, e.kind) }
        // Two TIDAL accounts there with a playlist of the same id: not guessed, so the handover is refused.
        val twice = made + ServerSyncs.MadeMember("tidal@3", "t1", "T3-d", null)
        assertEquals(null, HandOver.groupIds(members, twice)["d-ti"])
        // Unless one of them is this very account's.
        val exact = made.map { if (it.service == "tidal@2") it.copy(service = "tidal") else it } + ServerSyncs.MadeMember("tidal@3", "t1", "T3-d", null)
        assertEquals("T-d", HandOver.groupIds(members, exact)["d-ti"])
    }
}
