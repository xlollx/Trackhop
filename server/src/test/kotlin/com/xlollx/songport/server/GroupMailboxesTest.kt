package com.xlollx.songport.server

import com.sun.net.httpserver.HttpServer
import com.xlollx.songport.model.Track
import com.xlollx.songport.shared.Grant
import com.xlollx.songport.shared.Identity
import com.xlollx.songport.shared.MemberCard
import com.xlollx.songport.shared.MemberFile
import com.xlollx.songport.shared.Roster
import com.xlollx.songport.shared.SharedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

/**
 * A shared playlist's mailbox on this server (GroupMailboxes.kt): made with the owner's API token,
 * read and written with the group's key, which opens nothing else; checked, capped and compacted.
 */
class GroupMailboxesTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var web: Web
    private lateinit var base: String
    private lateinit var env: ServerEnv
    private lateinit var token: String
    private val client = HttpClient.newHttpClient()

    private val creator = Identity.create()
    private val groupId = "0123456789abcdef"

    @Before fun start() {
        val data = tmp.newFolder("data")
        installServerConnectors()
        env = ServerEnv(data, Strings(emptyMap()), "https://songport.example.org")
        web = Web(env, Runner(env), Auth(env), "sekret", "test")
        server = web.start(0)
        base = "http://127.0.0.1:${server.address.port}"
        token = Api.newToken(env)
    }

    @After fun stop() {
        server.stop(0)
        GroupMailboxes.maxItems = GroupMailboxes.MAX_ITEMS
        GroupMailboxes.maxTotalBytes = GroupMailboxes.MAX_TOTAL_BYTES
        GroupMailboxes.newPerMinute = GroupMailboxes.NEW_PER_MINUTE
    }

    private class Answer(val code: Int, val text: String) {
        val obj: JsonObject get() = Json.parseToJsonElement(text).jsonObject
    }

    private fun call(method: String, path: String, body: String? = null, bearer: String? = token): Answer {
        val res = client.send(
            HttpRequest.newBuilder(URI("$base/$path")).apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
                .header("Content-Type", "application/json")
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return Answer(res.statusCode(), res.body())
    }

    private fun api(method: String, path: String, body: String? = null, bearer: String? = token) = call(method, "api/v1/$path", body, bearer)

    private fun make(id: String = groupId, name: String = "Road trip"): String {
        val a = api("POST", "groups", """{"group":"$id","creator":"${creator.id}","name":"$name"}""")
        assertEquals(a.text, 201, a.code)
        return a.obj["key"]!!.jsonPrimitive.content.also { assertTrue(it, it.startsWith("thg_")) }
    }

    private fun song(n: Int) = Track("t$n", "Song $n", listOf("Band"), "Album", 200_000, isrc = "QZABC26" + n.toString().padStart(5, '0'))

    private fun event(by: Identity, seq: Long, n: Int, op: String = SharedEvent.ADD, group: String = groupId) =
        SharedEvent.of(op, song(n), by.id, seq, 1_800_000_000_000L + seq).signed(group, by)

    private fun card(who: Identity, name: String, group: String = groupId) = MemberCard(group, who.id, name, "Spotify", 1).signed(who)

    private fun json(cards: List<MemberCard> = emptyList(), rosters: List<Roster> = emptyList(), events: List<SharedEvent> = emptyList()): String {
        val c = MemberFile.codec
        return """{"cards":[${cards.joinToString(",") { c.encodeToString(MemberCard.serializer(), it) }}],""" +
            """"rosters":[${rosters.joinToString(",") { c.encodeToString(Roster.serializer(), it) }}],""" +
            """"events":[${events.joinToString(",") { c.encodeToString(SharedEvent.serializer(), it) }}]}"""
    }

    private fun items(key: String, since: Long = 0): JsonObject = api("GET", "groups/$groupId/items?since=$since", bearer = key).also { assertEquals(it.text, 200, it.code) }.obj

    private fun JsonObject.count(k: String) = this[k]!!.jsonArray.size

    @Test fun theGroupKeyOpensItsOwnItemsAndNothingElse() {
        val key = make()
        val other = make("fedcba9876543210", "Other")
        val roster = Roster(groupId, 1, 1, listOf(Grant(creator.id))).signed(creator)
        val posted = api("POST", "groups/$groupId/items", json(listOf(card(creator, "Ana")), listOf(roster), listOf(event(creator, 1, 1), event(creator, 2, 2))), bearer = key)
        assertEquals(posted.text, 200, posted.code)
        assertEquals(4, posted.obj["added"]!!.jsonPrimitive.long.toInt())
        val got = items(key)
        assertEquals(listOf(1, 1, 2), listOf(got.count("cards"), got.count("rosters"), got.count("events")))
        assertEquals("false", got["more"].toString())

        // Nothing else on the server: syncs, settings, the set-up, the log, the list of groups, the owner's calls.
        for ((m, p) in listOf("GET" to "syncs", "GET" to "config", "GET" to "settings", "GET" to "services", "GET" to "log", "GET" to "status",
            "GET" to "groups", "POST" to "groups", "GET" to "groups/$groupId", "DELETE" to "groups/$groupId", "POST" to "groups/$groupId/key",
            "POST" to "syncs/run-all")) {
            val a = api(m, p, bearer = key)
            assertEquals("$m $p: ${a.text}", 403, a.code)
        }
        // Another group's items, with this group's key: refused.
        assertEquals(401, api("GET", "groups/fedcba9876543210/items", bearer = key).code)
        assertEquals(401, api("POST", "groups/fedcba9876543210/items", json(events = listOf(event(creator, 3, 3, group = "fedcba9876543210"))), bearer = key).code)
        // Nor the AI assistants' door, nor the page.
        assertEquals(401, call("POST", "mcp", "{}", bearer = key).code)
        assertEquals(401, call("GET", "", bearer = key).code)
        // The other group's key opens the other group only.
        assertEquals(200, api("GET", "groups/fedcba9876543210/items", bearer = other).code)
        assertEquals(401, api("GET", "groups/$groupId/items", bearer = other).code)
        // A key that is none of them, and no key.
        assertEquals(401, api("GET", "groups/$groupId/items", bearer = "thg_" + "A".repeat(43)).code)
        assertEquals(401, api("GET", "groups/$groupId/items", bearer = null).code)
        // The owner's token reads it too, and lists the mailboxes; only the key's hash is on disk.
        assertEquals(200, api("GET", "groups/$groupId/items").code)
        assertEquals(2, Json.parseToJsonElement(api("GET", "groups").text).jsonArray.size)
        val stored = File(env.dataDir, "groups").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(key.substring(4) in stored)
        // A shared server asks each person's Trackhop whose the key is.
        assertTrue(web.acceptsApi("Bearer $key", null))
        assertFalse(web.acceptsApi("Bearer thg_" + "B".repeat(43), null))
    }

    @Test fun theOwnerReplacesTheKeyOrDeletesTheMailbox() {
        val key = make()
        val fresh = api("POST", "groups/$groupId/key").also { assertEquals(it.text, 200, it.code) }.obj["key"]!!.jsonPrimitive.content
        assertEquals(401, api("GET", "groups/$groupId/items", bearer = key).code)
        assertEquals(200, api("GET", "groups/$groupId/items", bearer = fresh).code)
        // Turning the API off closes the mailboxes too.
        Api.revoke(env)
        assertEquals(403, api("GET", "groups/$groupId/items", bearer = fresh).code)
        token = Api.newToken(env)
        assertEquals(200, api("GET", "groups/$groupId/items", bearer = fresh).code)
        assertEquals(200, api("DELETE", "groups/$groupId").code)
        assertEquals(401, api("GET", "groups/$groupId/items", bearer = fresh).code)
        assertEquals(404, api("GET", "groups/$groupId").code)
        assertFalse(File(env.dataDir, "groups/$groupId").exists())
    }

    @Test fun garbageIsRefusedAndNothingIsStored() {
        val key = make()
        fun post(body: String) = api("POST", "groups/$groupId/items", body, bearer = key)
        val good = event(creator, 1, 1)
        val c = MemberFile.codec
        val goodJson = c.encodeToString(SharedEvent.serializer(), good)
        for (body in listOf(
            "not json", "[]", "{\"events\": 3}", "{\"events\": [1, 2]}", "{\"events\": [{}]}",
            "{\"events\": [${goodJson.replace("\"op\":\"+\"", "\"op\":\"*\"")}]}",
            "{\"events\": [${goodJson.replace("\"title\":\"Song 1\"", "\"title\":\"Song\\u0000 1\"")}]}",
            "{\"events\": [${goodJson.replace("\"title\":\"Song 1\"", "\"title\":\"${"x".repeat(9000)}\"")}]}",
            // A card or a curators' list of another group.
            json(cards = listOf(card(creator, "Ana", "fedcba9876543210"))),
            json(rosters = listOf(Roster("fedcba9876543210", 1, 1, listOf(Grant(creator.id))).signed(creator))),
            // Forgeries: an event or a card changed after signing, a curators' list not signed by the creator.
            json(events = listOf(good.copy(op = SharedEvent.REMOVE))),
            json(cards = listOf(card(creator, "Ana").copy(name = "Not Ana"))),
            json(rosters = listOf(Roster(groupId, 9, 9, listOf(Grant(Identity.create().id))).let { it.signed(Identity.create()) })),
        )) {
            val a = post(body)
            assertEquals("$body: ${a.text}", 400, a.code)
        }
        assertEquals(413, post("{\"events\": [\"" + "x".repeat(GroupMailboxes.MAX_BODY) + "\"]}").code)
        assertEquals(400, api("GET", "groups/$groupId/items?since=-1", bearer = key).code)
        val got = items(key)
        assertEquals(0, got.count("events") + got.count("cards") + got.count("rosters"))
        assertEquals(0L, got["cursor"]!!.jsonPrimitive.long)
    }

    @Test fun eachItemIsStoredOnceAndReadFromTheCursor() {
        val key = make()
        val first = json(listOf(card(creator, "Ana")), events = (1..3).map { event(creator, it.toLong(), it) })
        assertEquals(4, api("POST", "groups/$groupId/items", first, bearer = key).obj["added"]!!.jsonPrimitive.long.toInt())
        // The same again (another member relaying it): nothing new.
        val again = api("POST", "groups/$groupId/items", first, bearer = key).obj
        assertEquals(0, again["added"]!!.jsonPrimitive.long.toInt())
        val cursor = again["cursor"]!!.jsonPrimitive.long
        api("POST", "groups/$groupId/items", json(events = listOf(event(creator, 4, 4))), bearer = key)
        val next = items(key, cursor)
        assertEquals(1, next.count("events"))
        assertEquals(0, next.count("cards"))
        assertEquals(0, items(key, next["cursor"]!!.jsonPrimitive.long).count("events"))
        // Kept on disk: the server restarted reads the same.
        GroupMailboxes.unload()
        assertTrue(web.acceptsApi("Bearer $key", null))
        assertEquals(5, items(key).let { it.count("events") + it.count("cards") })
    }

    @Test fun aLargeAnswerComesInPages() {
        val key = make()
        // Events with long titles: a few hundred make more than one page.
        val big = (1..1200).map { n ->
            SharedEvent.of(SharedEvent.ADD, song(n).copy(title = "Song $n " + "x".repeat(280), album = "y".repeat(290)), creator.id, n.toLong(), 1_800_000_000_000L + n)
                .copy(artists = (1..10).map { "Artist $it " + "z".repeat(180) }).signed(groupId, creator)
        }
        big.chunked(200).forEach { assertEquals(200, api("POST", "groups/$groupId/items", json(events = it), bearer = key).code) }
        var since = 0L
        var pages = 0
        var seen = 0
        do {
            val page = items(key, since)
            seen += page.count("events")
            since = page["cursor"]!!.jsonPrimitive.long
            pages++
        } while (page["more"].toString() == "true")
        assertEquals(1200, seen)
        assertTrue("pages: $pages", pages > 1)
    }

    @Test fun writesPerMinuteAreCapped() {
        val key = make()
        var refused = 0
        repeat(GroupMailboxes.WRITES_PER_MINUTE + 5) { i ->
            val a = api("POST", "groups/$groupId/items", json(events = listOf(event(creator, i + 1L, i + 1))), bearer = key)
            if (a.code == 429) refused++
        }
        assertEquals(5, refused)
    }

    @Test fun aFullMailboxKeepsWhatMembersCountAndCursorsStillWork() {
        val key = make()
        val ben = Identity.create()
        val mallory = Identity.create()
        val roster = Roster(groupId, 2, 2, listOf(Grant(creator.id), Grant(ben.id))).signed(creator)
        // Ana adds and removes the same songs many times; Ben adds two; Mallory (no curator) and a
        // forged card and list fill the rest.
        val ana = (1..20).map { i -> event(creator, i.toLong(), (i - 1) % 5 + 1, if (i <= 15) SharedEvent.ADD else SharedEvent.REMOVE) }
        val bens = listOf(event(ben, 1, 6), event(ben, 2, 7))
        val junk = (1..20).map { event(mallory, it.toLong(), it) }
        val older = Roster(groupId, 1, 1, listOf(Grant(creator.id))).signed(creator)
        api("POST", "groups/$groupId/items", json(listOf(card(creator, "Ana"), card(ben, "Ben"), card(ben, "Benjamin").copy(at = 0).signed(ben)), listOf(older, roster), ana + bens), bearer = key)
        api("POST", "groups/$groupId/items", json(events = junk), bearer = key)
        val cursor = items(key)["cursor"]!!.jsonPrimitive.long
        GroupMailboxes.maxItems = 40
        val late = event(ben, 3, 8)
        val a = api("POST", "groups/$groupId/items", json(events = listOf(late)), bearer = key)
        assertEquals(a.text, 200, a.code)
        val all = items(key)
        val events = all["events"]!!.jsonArray.map { MemberFile.codec.decodeFromJsonElement(SharedEvent.serializer(), it) }
        // Per song and author only the latest action; Ben's three; nothing of Mallory's.
        assertEquals(5 + 3, events.size)
        assertTrue(events.none { it.by == mallory.id })
        assertEquals(setOf(16L, 17L, 18L, 19L, 20L), events.filter { it.by == creator.id }.map { it.seq }.toSet())
        assertEquals(1, all.count("rosters"))
        assertEquals(listOf("Ana", "Ben"), all["cards"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.sorted())
        // A member who had read up to the cursor gets only what came after it.
        assertEquals(listOf(3L), items(key, cursor)["events"]!!.jsonArray.map { it.jsonObject["seq"]!!.jsonPrimitive.long })
        // Still full of what counts: refused, not dropped.
        GroupMailboxes.maxItems = 11
        assertEquals(507, api("POST", "groups/$groupId/items", json(events = listOf(event(ben, 4, 9), event(ben, 5, 10))), bearer = key).code)
    }

    @Test fun theOwnerChecksWhatAMailboxIsMadeWith() {
        assertEquals(400, api("POST", "groups", """{"group":"XYZ","creator":"${creator.id}"}""").code)
        assertEquals(400, api("POST", "groups", """{"group":"$groupId","creator":"short"}""").code)
        make()
        assertEquals(409, api("POST", "groups", """{"group":"$groupId","creator":"${creator.id}"}""").code)
        assertEquals(401, api("POST", "groups", """{"group":"1111111111111111","creator":"${creator.id}"}""", bearer = "wrong").code)
        val listed = Json.parseToJsonElement(api("GET", "groups").text) as JsonArray
        assertEquals("Road trip", listed.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun aGroupKeyReachesNoOtherCallThroughTheAddress() {
        val key = make()
        // Paths that would leave the group's items, said otherwise: none of them opens anything.
        for (p in listOf("groups/$groupId/items/../../syncs", "groups/$groupId/items/..%2F..%2Fsyncs", "groups%2F$groupId%2Fkey",
            "Groups/$groupId/items", "groups/$groupId/Items", "groups/$groupId/items/x", "groups/$groupId", "syncs?x=groups/$groupId/items")) {
            val a = api("GET", p, bearer = key)
            assertTrue("$p: ${a.code} ${a.text}", a.code == 403 || a.code == 401 || a.code == 404)
        }
        // Another group's id said otherwise (capitals, a longer one, a path in it): never this group's or another's items.
        for (id in listOf(groupId.uppercase(), groupId + "0", "../$groupId", "0123456789abcdeｆ")) {
            val a = api("GET", "groups/${java.net.URLEncoder.encode(id, "UTF-8")}/items", bearer = key)
            assertTrue("$id: ${a.code} ${a.text}", a.code == 401 || a.code == 403)
        }
        // Only GET and POST.
        assertEquals(405, api("PUT", "groups/$groupId/items", "{}", bearer = key).code)
        assertEquals(405, api("DELETE", "groups/$groupId/items", bearer = key).code)
        assertEquals(405, api("OPTIONS", "groups/$groupId/items", bearer = key).code)
        // The same items with a slash more or less.
        assertEquals(200, api("GET", "groups/$groupId/items/", bearer = key).code)
        assertEquals(200, call("GET", "api/v1//groups//$groupId//items", bearer = key).code)
    }

    @Test fun writesAreCountedBeforeTheBodyIsRead() {
        val key = make()
        repeat(GroupMailboxes.WRITES_PER_MINUTE) { assertEquals(400, api("POST", "groups/$groupId/items", "not json", bearer = key).code) }
        val a = api("POST", "groups/$groupId/items", "not json", bearer = key)
        assertEquals(a.text, 429, a.code)
    }

    @Test fun aBodyTooDeepOrOfTooManyValuesIsNotRead() {
        val key = make()
        assertEquals(400, api("POST", "groups/$groupId/items", "{\"events\":" + "[".repeat(20) + "]".repeat(20) + "}", bearer = key).code)
        val tiny = "{\"events\":[" + "{},".repeat(GroupMailboxes.MAX_POST_ITEMS * GroupMailboxes.MAX_VALUES_PER_ITEM) + "{}]}"
        assertTrue(tiny.length < GroupMailboxes.MAX_BODY)
        assertEquals(400, api("POST", "groups/$groupId/items", tiny, bearer = key).code)
        // Brackets and commas inside text are not counted; a real call passes.
        assertTrue(GroupMailboxes.plainEnough("{\"events\":[{\"title\":\"${"[{,".repeat(50_000)}\\\"]\"}]}".toByteArray()))
        assertFalse(GroupMailboxes.plainEnough("{\"a\":\"\\\"\",\"b\":${"[".repeat(9)}${"]".repeat(9)}}".toByteArray()))
        val c = MemberFile.codec
        val real = (1..GroupMailboxes.MAX_POST_ITEMS - 300).map { c.encodeToString(SharedEvent.serializer(), event(creator, it.toLong(), it)) }
        assertTrue(GroupMailboxes.plainEnough("{\"events\":[${real.joinToString(",")}]}".toByteArray()))
    }

    @Test fun aLogSentAgainIsNotCheckedAgainAndNewItemsAreCappedAMinute() {
        val key = make()
        GroupMailboxes.newPerMinute = 3
        val three = json(events = (1..3).map { event(creator, it.toLong(), it) })
        assertEquals(3, api("POST", "groups/$groupId/items", three, bearer = key).obj["added"]!!.jsonPrimitive.long.toInt())
        // The same three again: nothing new to check, not counted.
        assertEquals(200, api("POST", "groups/$groupId/items", three, bearer = key).code)
        val more = api("POST", "groups/$groupId/items", json(events = (4..5).map { event(creator, it.toLong(), it) }), bearer = key)
        assertEquals(more.text, 429, more.code)
    }

    @Test fun aMailboxKeptFullIsNotCompactedAtEveryWrite() {
        val key = make()
        val roster = Roster(groupId, 1, 1, listOf(Grant(creator.id))).signed(creator)
        api("POST", "groups/$groupId/items", json(rosters = listOf(roster), events = listOf(event(creator, 1, 1), event(creator, 2, 2))), bearer = key)
        GroupMailboxes.maxItems = 3
        val before = GroupMailboxes.compactions
        assertEquals(507, api("POST", "groups/$groupId/items", json(events = listOf(event(creator, 3, 3))), bearer = key).code)
        assertEquals(before + 1, GroupMailboxes.compactions)
        assertEquals(507, api("POST", "groups/$groupId/items", json(events = listOf(event(creator, 4, 4))), bearer = key).code)
        assertEquals(before + 1, GroupMailboxes.compactions)
    }

    @Test fun allTheMailboxesTogetherAreCapped() {
        val key = make()
        val other = make("fedcba9876543210", "Other")
        assertEquals(200, api("POST", "groups/$groupId/items", json(events = (1..5).map { event(creator, it.toLong(), it) }), bearer = key).code)
        GroupMailboxes.maxTotalBytes = File(env.dataDir, "groups/$groupId/items.jsonl").length() + 100
        val a = api("POST", "groups/fedcba9876543210/items", json(events = (1..5).map { event(creator, it.toLong(), it, group = "fedcba9876543210") }), bearer = other)
        assertEquals(a.text, 507, a.code)
    }

    @Test fun onlyAFewMailboxesStayInMemory() {
        val keys = (1..GroupMailboxes.MAX_LOADED + 2).map { n -> val id = "%016x".format(n); id to make(id, "G$n") }
        keys.forEach { (id, k) -> assertEquals(200, api("GET", "groups/$id/items", bearer = k).code) }
        assertTrue(GroupMailboxes.loadedCount() <= GroupMailboxes.MAX_LOADED)
        // The owner's list reads them all, and keeps few.
        assertEquals(keys.size, Json.parseToJsonElement(api("GET", "groups").text).jsonArray.size)
        assertTrue(GroupMailboxes.loadedCount() <= GroupMailboxes.MAX_LOADED)
        // One put out of memory is read again from disk.
        val (id, k) = keys.first()
        assertEquals(200, api("POST", "groups/$id/items", json(events = listOf(event(creator, 1, 1, group = id))), bearer = k).code)
        keys.drop(1).forEach { (i, kk) -> api("GET", "groups/$i/items", bearer = kk) }
        assertEquals(1, api("GET", "groups/$id/items", bearer = k).obj.count("events"))
    }

    @Test fun aLineCutShortByACrashDoesNotTakeTheNextOneWithIt() {
        val key = make()
        api("POST", "groups/$groupId/items", json(events = listOf(event(creator, 1, 1))), bearer = key)
        File(env.dataDir, "groups/$groupId/items.jsonl").appendText("{\"s\":2,\"k\":\"e\",\"v\":{\"by\"")
        GroupMailboxes.unload()
        assertEquals(200, api("POST", "groups/$groupId/items", json(events = listOf(event(creator, 2, 2))), bearer = key).code)
        GroupMailboxes.unload()
        assertEquals(2, items(key).count("events"))
    }
}
