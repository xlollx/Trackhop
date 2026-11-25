package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.shared.Nway
import com.xlollx.songport.shared.NwayEdit
import com.xlollx.songport.shared.SharedGroup
import com.xlollx.songport.shared.SharedGroups
import com.xlollx.songport.shared.SharedProvider
import com.xlollx.songport.sync.HandOver
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.ServerSyncs
import com.xlollx.songport.sync.Timing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Multi syncs on the server (MultiWeb.kt, ApiMulti.kt, Runner.startGroup): made from the form and
 * the API as the app makes them, one card on the home page, the group's page and its changes, run
 * in the group's order as one batch (by hand and by the schedule), and handed over from a phone.
 */
class MultiSyncWebTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private lateinit var runner: Runner
    private lateinit var token: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    private val auth = "Basic " + Base64.getEncoder().encodeToString("any:sekret".toByteArray())

    /** A music service in memory: its catalogue, its playlists, and the order its playlists were written in. */
    private class Fake(override val serviceId: String, private val log: MutableList<String>) : MusicProvider {
        val catalogue = ArrayList<Track>()
        val lists = LinkedHashMap<String, MutableList<Track>>()
        override val displayName = serviceId.uppercase()
        override val brandColor = 0xFF888888
        override val noteName = ""
        override val requiresAuth = false
        override val searchParallelism = 1
        override fun isConfigured(ctx: Env) = true
        override fun isConnected(ctx: Env) = true
        override fun accountName(ctx: Env) = "tester"
        override fun startAuth(ctx: Env) {}
        override suspend fun completeAuth(ctx: Env, params: Map<String, String>, verifier: String) {}
        override fun disconnect(ctx: Env) {}
        override suspend fun playlists(ctx: Env) = synchronized(lists) { lists.map { (id, t) -> Playlist(id, id, t.size) } }
        override suspend fun playlistInfo(ctx: Env, playlistId: String) = Playlist(playlistId, playlistId)
        override suspend fun tracks(ctx: Env, playlistId: String): List<Track> = synchronized(lists) { lists[playlistId]?.toList() } ?: throw IllegalStateException("no playlist $playlistId")
        override suspend fun search(ctx: Env, track: Track): List<Track> = catalogue.filter { Matcher.normalizeTitle(it.title) == Matcher.normalizeTitle(track.title) }
        override suspend fun createPlaylist(ctx: Env, name: String, description: String): Playlist { synchronized(lists) { lists[name] = ArrayList() }; return Playlist(name, name, 0) }
        override suspend fun addTracks(ctx: Env, playlistId: String, tracks: List<Track>) { synchronized(lists) { lists.getOrPut(playlistId) { ArrayList() } += tracks }; synchronized(log) { log += "$serviceId+${tracks.size}" } }
        override suspend fun removeTracks(ctx: Env, playlistId: String, tracks: List<Track>) { synchronized(lists) { lists[playlistId]?.removeAll { t -> tracks.any { it.id == t.id } } }; synchronized(log) { log += "$serviceId-${tracks.size}" } }
        fun song(n: Int) = catalogue.first { it.title == "Song $n" }
        fun titles(id: String) = synchronized(lists) { lists[id].orEmpty().map { it.title.removePrefix("Song ").toInt() }.sorted() }
    }

    private val writes = ArrayList<String>()
    private val fa = Fake("fa", writes)
    private val fb = Fake("fb", writes)
    private val fc = Fake("fc", writes)
    private val fd = Fake("fd", writes)

    @Before fun start() {
        Features.v2 = true
        installServerConnectors()
        for ((s, p) in listOf(fa to "a", fb to "b", fc to "c", fd to "d")) s.catalogue += (1..12).map { Track("$p$it", "Song $it", listOf("Band"), "Album", 200_000) }
        fa.lists["mix"] = (1..10).map { fa.song(it) }.toMutableList()
        fc.lists["cmix"] = mutableListOf(fc.song(11))
        fd.lists["dmix"] = ArrayList()
        Providers.testDoubles = listOf(fa, fb, fc, fd)
        env = ServerEnv(tmp.newFolder("data"), Strings(emptyMap()), "http://127.0.0.1")
        runner = Runner(env)
        server = Web(env, runner, Auth(env), "sekret", "test").start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
    }

    @After fun stop() {
        server.stop(0)
        runner.stop()
        Providers.testDoubles = emptyList()
        Features.v2 = Features.START
    }

    private fun get(path: String) = client.send(HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun post(path: String, body: String, origin: String = base) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Authorization", auth).header("Origin", origin)
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun api(method: String, path: String, body: String? = null) = client.send(
        HttpRequest.newBuilder(URI("$base/api/v1/$path")).header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    private fun HttpResponse<String>.location() = URLDecoder.decode(headers().firstValue("Location").orElse(""), Charsets.UTF_8)
    private fun HttpResponse<String>.obj() = Json.parseToJsonElement(body()).jsonObject
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun group(): SharedGroup = env.store.data.groups.single { Nway.isGroup(it) }
    private fun members() = Nway.members(env.store.data.jobs, group().id)

    private fun waitRun(id: String) {
        val until = System.currentTimeMillis() + 30_000
        Thread.sleep(100)
        while (runner.isGroupRunning(id) && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse("still running", runner.isGroupRunning(id))
    }

    /** "Mix" from fa's mix: fb as an exact copy on a new playlist, fc two-way on its cmix; made from the form, which runs it once. */
    private fun createFromForm(): SharedGroup {
        val form = "src=fa&srcPlaylist=mix&name=Mix&target=fb&pl.fb=&way.fb=exact&target=fc&pl.fc=cmix&way.fc=two_way&every=1440&at=09%3A00"
        val res = post("/multi", form)
        assertEquals(res.body(), 303, res.statusCode())
        val g = group()
        assertTrue(res.location(), res.location().startsWith("/multi/${g.id}?ok="))
        waitRun(g.id)
        return g
    }

    /** What makes a group the same as another, whatever their ids: each sync's sides, ways and link. */
    private fun shape(jobs: List<SyncJob>, groupId: String) = jobs.filter { SharedGroups.sharedSide(it) == groupId }.map { j ->
        fun side(r: PlaylistRef) = if (r.provider == SharedProvider.ID) "hub" else "${r.provider}:${r.playlistId}"
        listOf(side(j.source), side(j.target), j.twoWayRemovals, j.mirrorRemovals, j.linkedJobId != null, j.twin, Timing.interval(j), j.at).joinToString("|")
    }.sorted()

    @Test fun theFormMakesTheGroupAsTheAppDoesAndRunsIt() {
        val g = createFromForm()
        assertEquals("Mix", g.name)
        // A new playlist on fb, named after the multi sync, made when it was created.
        assertTrue(fb.lists.containsKey("Mix"))
        // The same group the engine's own editor makes from the same draft (NwayEdit.create, as the app saves one).
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        val draft = NwayEdit.Draft("Mix", NwayEdit.Item("fa", "mix", "mix"), listOf(
            NwayEdit.Item("fb", "Mix", "Mix", mode = NwayEdit.Mode.EXACT), NwayEdit.Item("fc", "cmix", "cmix", mode = NwayEdit.Mode.TWO_WAY)))
        val twin = NwayEdit.create(other, draft, { Timing.apply(it, 1440, "09:00", emptyList()) })
        assertEquals(shape(other.store.data.jobs, twin.id), shape(env.store.data.jobs, g.id))
        assertEquals(5, env.store.data.jobs.size)
        // The group's own list: its key in the vault and its state file, as the app keeps them.
        assertNotNull(env.tokens.get(SharedGroups.tokenKey(g.id)))
        assertTrue(File(env.dataDir, "shared/${g.id}.json").isFile)
        // The first run reached every playlist: the source's ten songs everywhere, fc's own song back to the source.
        assertEquals((1..11).toList(), fa.titles("mix"))
        assertEquals((1..11).toList(), fb.titles("Mix"))
        assertEquals((1..11).toList(), fc.titles("cmix"))
    }

    @Test fun theApiMakesTheSameGroupAndRefusesWhatCannotBe() {
        val body = """{"name": "Mix", "source": {"service": "fa", "playlist": "mix"}, "targets": [{"service": "fb", "way": "exact"}, {"service": "fc", "playlist": "cmix", "way": "two_way"}], "every": 1440, "at": "09:00"}"""
        val made = api("POST", "multi", body)
        assertEquals(made.body(), 201, made.statusCode())
        val o = made.obj()
        val g = group()
        assertEquals(g.id, o["id"]!!.jsonPrimitive.content)
        assertEquals(listOf("exact", "two_way"), o["targets"]!!.jsonArray.map { it.jsonObject["way"]!!.jsonPrimitive.content })
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        val twin = NwayEdit.create(other, NwayEdit.Draft("Mix", NwayEdit.Item("fa", "mix", "mix"), listOf(
            NwayEdit.Item("fb", "Mix", "Mix", mode = NwayEdit.Mode.EXACT), NwayEdit.Item("fc", "cmix", "cmix", mode = NwayEdit.Mode.TWO_WAY))), { Timing.apply(it, 1440, "09:00", emptyList()) })
        assertEquals(shape(other.store.data.jobs, twin.id), shape(env.store.data.jobs, g.id))
        // Not run by the call unless asked.
        assertTrue(env.store.data.reports.isEmpty())
        assertEquals(1, api("GET", "multi").let { Json.parseToJsonElement(it.body()).jsonArray.size })
        // The same playlist twice, a way that is not one, no targets: refused, nothing made.
        assertEquals(400, api("POST", "multi", """{"source": {"service": "fa", "playlist": "mix"}, "targets": [{"service": "fa", "playlist": "mix"}]}""").statusCode())
        assertEquals(400, api("POST", "multi", """{"source": {"service": "fa", "playlist": "mix"}, "targets": [{"service": "fb", "way": "sideways"}]}""").statusCode())
        assertEquals(400, api("POST", "multi", """{"source": {"service": "fa", "playlist": "mix"}, "targets": []}""").statusCode())
        assertEquals(1, env.store.data.groups.size)
        assertEquals(404, api("GET", "multi/0123456789abcdef").statusCode())
    }

    @Test fun theHomePageShowsOneCardWithTheTargetsFiguresOnly() {
        env.store.upsertJob(SyncJob("classic", "Classic", PlaylistRef("fa", "mix", "mix"), PlaylistRef("fd", "dmix", "dmix")))
        val g = createFromForm()
        val home = get("/").body()
        assertEquals(home, 1, Regex("id=\"multi-${g.id}\"").findAll(home).count())
        // None of its syncs as cards of their own; the classic sync is there.
        for (m in members()) assertFalse(home.contains("id=\"sync-${m.down.id}\""))
        assertTrue(home.contains("id=\"sync-classic\""))
        assertTrue(home, home.contains("2 syncs"))
        assertTrue(home.contains("Mix</b> <span class=\"muted\">· multi sync</span>"))
        assertTrue(home, home.contains("FA: mix → FB, FC"))
        // Added on the playlists only: fb got 11, fc 10, fa fc's one song back = 22; the 11 into the group's own list are not counted.
        assertTrue(home, home.contains(">+22 added<") && home.contains(">−0 removed<") && home.contains(">0 not found<") && home.contains(">11 in source<"))
        assertTrue(home.contains("href=\"/multi/${g.id}/added\"") && home.contains("action=\"/multi/${g.id}/run\"") && home.contains(">Sync now<") && home.contains(">Open<"))
        // A member's own page leads to the group's.
        assertEquals("/multi/${g.id}", get("/syncs/${members()[1].down.id}").location())
    }

    @Test fun theGroupPageListsTheTargetsAndTheirWaysAndChangesThem() {
        val g = createFromForm()
        val page = get("/multi/${g.id}").body()
        assertTrue(page, page.contains("Source · 11 songs"))
        assertTrue(page.contains("Exact copy") && page.contains("Two-way") && page.contains("Always the same as the source: extra songs here are removed."))
        assertTrue(page.contains("FB</b>: Mix") && page.contains("FC</b>: cmix") && page.contains("· 11 songs"))
        assertTrue(page.contains(">+ Add a target<") && page.contains(">Make it the source<") && page.contains(">Remove target<"))
        assertTrue(page.contains("action=\"/multi/${g.id}/schedule\""))
        val fbDown = members().first { it.playlist.provider == "fb" }.down.id
        // fb from an exact copy to keeping its additions.
        val way = post("/multi/${g.id}/way", "member=${enc(fbDown)}&way=keep")
        assertEquals("/multi/${g.id}?ok=Saved: Keep my additions.", way.location())
        assertEquals(NwayEdit.Mode.KEEP, NwayEdit.modeOf(members().first { it.down.id == fbDown }))
        // The same through the API, back to an exact copy.
        assertEquals(200, api("PATCH", "multi/${g.id}/targets/$fbDown", """{"way": "exact"}""").statusCode())
        assertEquals(NwayEdit.Mode.EXACT, NwayEdit.modeOf(members().first { it.down.id == fbDown }))
        // The schedule: every sync of the group on the same timing, paused together.
        post("/multi/${g.id}/schedule", "name=Road+trip&every=360&at=07%3A00&paused=on")
        assertEquals("Road trip", group().name)
        assertTrue(Nway.jobsOf(env.store.data, g.id).all { Timing.interval(it) == 360 && !it.enabled })
    }

    @Test fun aTargetIsAddedAndRemovedWithAConfirmation() {
        val g = createFromForm()
        assertTrue(get("/multi/${g.id}/add").body().contains("<option value=\"fd\">"))
        val form = get("/multi/${g.id}/add?service=fd").body()
        assertTrue(form, form.contains("New playlist: Mix") && form.contains("<option value=\"dmix\">"))
        val add = post("/multi/${g.id}/add", "service=fd&playlist=dmix&way=keep")
        assertTrue(add.location(), add.location().startsWith("/multi/${g.id}?ok="))
        val fdM = members().first { it.playlist.provider == "fd" }
        assertEquals(NwayEdit.Mode.KEEP, NwayEdit.modeOf(fdM))
        // It runs with the group: its timing is the group's.
        assertEquals(Timing.interval(members()[0].down), Timing.interval(fdM.down))
        // The same playlist again: refused.
        assertTrue(post("/multi/${g.id}/add", "service=fd&playlist=dmix&way=keep").location().contains("err=This playlist is already in the multi sync."))
        // Removing asks first, on a page of its own, then removes it; the playlist stays.
        val ask = get("/multi/${g.id}/remove?member=${enc(fdM.down.id)}").body()
        assertTrue(ask, ask.contains("Remove FD from Mix?") && ask.contains(">Remove from this sync<") && ask.contains("method=\"post\""))
        assertTrue(members().any { it.playlist.provider == "fd" })
        post("/multi/${g.id}/remove", "member=${enc(fdM.down.id)}")
        assertFalse(members().any { it.playlist.provider == "fd" })
        assertTrue(fd.lists.containsKey("dmix"))
        // Through the API: a new playlist on fd, then removed.
        val added = api("POST", "multi/${g.id}/targets", """{"service": "fd", "way": "exact"}""")
        assertEquals(added.body(), 201, added.statusCode())
        assertTrue(fd.lists.containsKey("Mix"))
        val id = members().first { it.playlist.provider == "fd" }.down.id
        assertEquals(200, api("DELETE", "multi/${g.id}/targets/$id").statusCode())
        assertEquals(5, env.store.data.jobs.size)
    }

    @Test fun syncNowRunsTheGroupInItsOrderAsOneBatch() {
        val g = createFromForm()
        val order = Nway.order(env, g, manual = true).map { it.id }
        val before = env.store.data.reports.map { it.id }.toSet()
        assertEquals(303, post("/multi/${g.id}/run", "").statusCode())
        waitRun(g.id)
        // The reports, newest first: read backwards, the order they ran in.
        val ran = env.store.data.reports.filter { it.id !in before }.reversed().map { it.jobId }
        assertEquals(order, ran)
        // Every two-way playlist's sync into the group's list first, then the list into every playlist.
        val ups = members().mapNotNull { it.up?.id }.toSet()
        assertTrue(ran.take(ups.size).toSet() == ups)
        // Through the API too.
        assertEquals(202, api("POST", "multi/${g.id}/run").statusCode())
        waitRun(g.id)
    }

    @Test fun theAddedAndRemovedListsAreOneRowPerSong() {
        val g = createFromForm()
        synchronized(fa.lists) { fa.lists["mix"]!!.removeAll { it.title == "Song 2" } }
        post("/multi/${g.id}/run", "")
        waitRun(g.id)
        assertEquals((1..11).filter { it != 2 }, fb.titles("Mix"))
        assertEquals((1..11).filter { it != 2 }, fc.titles("cmix"))
        val removed = get("/multi/${g.id}/removed").body()
        assertEquals(removed, 1, Regex("Band — Song 2</b>").findAll(removed).count())
        assertTrue(removed, removed.contains("removed from all 2"))
        assertTrue(removed.contains("<li>FB: Band — Song 2</li>") && removed.contains("<li>FC: Band — Song 2</li>"))
        // The card's tile counts the targets' playlists: one song from each, not the group's own list.
        assertTrue(get("/").body().contains(">−2 removed<"))
        assertTrue(get("/multi/${g.id}/added").body().contains("Nothing added at the last run."))
    }

    @Test fun theSchedulerRunsAGroupAsOneBatchNeverItsSyncsAlone() = kotlinx.coroutines.runBlocking<Unit> {
        val g = createFromForm()
        env.store.upsertJob(Timing.apply(SyncJob("classic", "Classic", PlaylistRef("fa", "mix", "mix"), PlaylistRef("fd", "dmix", "dmix")), 60, null, emptyList()))
        env.store.update { d -> NwayEdit.scheduled(d, g.id, null, com.xlollx.songport.sync.Schedules.spec(60, null, emptyList())) }
        val later = System.currentTimeMillis() + 3 * 86_400_000L
        val alone = ArrayList<String>()
        val groups = ArrayList<String>()
        runner.tick(later, begin = { alone += it.id }, beginGroup = { groups += it.id })
        assertEquals(listOf("classic"), alone)
        assertEquals(listOf(g.id), groups)
        // Paused: not run by its schedule.
        env.store.update { d -> d.copy(jobs = d.jobs.map { if (SharedGroups.sharedSide(it) == g.id) it.copy(enabled = false) else it }) }
        groups.clear()
        runner.tick(later, begin = {}, beginGroup = { groups += it.id })
        assertTrue(groups.isEmpty())
        // The real start: its syncs one after the other, the ones that are on.
        env.store.update { d -> d.copy(jobs = d.jobs.map { if (SharedGroups.sharedSide(it) == g.id) it.copy(enabled = true) else it }) }
        val before = env.store.data.reports.size
        assertTrue(runner.startGroup(g, scheduled = true))
        assertFalse(runner.startGroup(g, scheduled = true))
        waitRun(g.id)
        assertEquals(5, env.store.data.reports.size - before)
    }

    @Test fun aMultiSyncHandedOverFromAPhoneIsMadeHereWholeAndGoesBack() {
        // The phone's multi sync, made by the engine as the app makes it.
        val phone = ServerEnv(tmp.newFolder("phone"), Strings(emptyMap()), "http://127.0.0.1")
        val pg = NwayEdit.create(phone, NwayEdit.Draft("Mix", NwayEdit.Item("fa", "mix", "mix"), listOf(
            NwayEdit.Item("fb", "bmix", "bmix", mode = NwayEdit.Mode.EXACT), NwayEdit.Item("fc", "cmix", "cmix", mode = NwayEdit.Mode.TWO_WAY))),
            { Timing.apply(it, 360, "08:00", emptyList()) })
        fb.lists["bmix"] = ArrayList()
        val jobs = phone.store.data.jobs
        val services = listOf("fa", "fb", "fc").map { ServerSyncs.Service(it, it, it, true) }
        // Any of its syncs plans the whole multi sync, no longer "stays on the phone".
        for (j in jobs) assertEquals(HandOver.Plan.Group(mapOf("fa" to "fa", "fb" to "fb", "fc" to "fc")), HandOver.plan(j, services, jobs, phone.store.data.groups))
        val plan = HandOver.plan(jobs[0], services, jobs, phone.store.data.groups) as HandOver.Plan.Group
        val pm = Nway.members(jobs, pg.id)
        val body = HandOver.groupBody(pg, pm, plan).toString()
        val made = api("POST", "multi", body)
        assertEquals(made.body(), 201, made.statusCode())
        val g = group()
        assertEquals(pg.id, g.id)
        assertEquals(shape(jobs, pg.id), shape(env.store.data.jobs, g.id))
        // Asked again (an answer lost on the way): the same one, not a second.
        val again = api("POST", "multi", body)
        assertEquals(200, again.statusCode())
        assertEquals(1, env.store.data.groups.size)
        // Every sync of the phone's has its counterpart here.
        val ids = HandOver.groupIds(pm, ServerSyncs.parseMulti(Json.parseToJsonElement(again.body())))
        assertEquals(jobs.map { it.id }.toSet(), ids.keys)
        assertTrue(ids.values.all { env.store.job(it) != null })
        // Run here: the server's own list is filled from the source and the two-way target.
        post("/multi/${g.id}/run", "")
        waitRun(g.id)
        assertEquals((1..11).toList(), fb.titles("bmix"))
        // Brought back: deleted here, the playlists stay.
        assertEquals(200, api("DELETE", "multi/${g.id}").statusCode())
        assertTrue(env.store.data.groups.isEmpty() && env.store.data.jobs.isEmpty())
        assertEquals(404, api("DELETE", "multi/${g.id}").statusCode())
        assertEquals((1..11).toList(), fb.titles("bmix"))
    }

    @Test fun aConfigurationFileCarriesTheMultiSync() {
        val g = createFromForm()
        val text = ConfigBackup.export(env, "test")
        val other = ServerEnv(tmp.newFolder("other"), Strings(emptyMap()), "http://127.0.0.1")
        ConfigBackup.import(other, ConfigBackup.read(text))
        val og = other.store.data.groups.single()
        assertEquals(g.id, og.id)
        assertTrue(Nway.isGroup(og))
        assertEquals(shape(env.store.data.jobs, g.id), shape(other.store.data.jobs, g.id))
        assertNotNull(other.tokens.get(SharedGroups.tokenKey(g.id)))
    }

    @Test fun aPostFromAnotherSiteIsRefused() {
        val g = createFromForm()
        assertEquals(403, post("/multi/${g.id}/delete", "", origin = "http://evil.example").statusCode())
        assertEquals(403, post("/multi/${g.id}/run", "", origin = "null").statusCode())
        assertEquals(403, post("/multi", "src=fa&srcPlaylist=mix&target=fd", origin = "http://evil.example").statusCode())
        assertEquals(1, env.store.data.groups.size)
        // The confirmation is a page; only its POST deletes.
        assertTrue(get("/multi/${g.id}/delete").body().contains("Delete Mix?"))
        assertEquals(1, env.store.data.groups.size)
        assertEquals("/?ok=Mix deleted. Its playlists stay as they are.", post("/multi/${g.id}/delete", "").location())
        assertNull(env.store.data.groups.firstOrNull())
    }
}
