package com.xlollx.songport.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** Several people on one server, through a real one on a free port: signing in, each one's own Trackhop, the admin's page, the API. */
class MultiUserTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var data: File
    private lateinit var hub: MultiUser
    private lateinit var base: String
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    @Before fun start() {
        data = tmp.newFolder("data")
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
    }

    @After fun stop() {
        hub.stop()
        LocalKey.reset()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun get(path: String, cookie: String? = null, vararg headers: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).apply { cookie?.let { header("Cookie", it) }; if (headers.isNotEmpty()) headers(*headers) }.GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun post(path: String, form: Map<String, String>, cookie: String? = null, origin: Boolean = true) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/x-www-form-urlencoded")
            .apply { cookie?.let { header("Cookie", it) }; if (origin) header("Origin", base) }
            .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" })).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** Signs in and returns the session cookie ("name=value"). */
    private fun signIn(user: String, password: String): String {
        val r = post("/login", mapOf("user" to user, "password" to password, "next" to "/"))
        assertEquals(r.body(), 303, r.statusCode())
        return r.headers().firstValue("Set-Cookie").get().substringBefore(';')
    }

    @Test fun signingInComesFirst() {
        val home = get("/")
        assertEquals(303, home.statusCode())
        assertEquals("/login", home.headers().firstValue("Location").get())
        assertEquals("/login?next=${enc("/tools")}", get("/tools").headers().firstValue("Location").get())
        assertEquals(200, get("/login").statusCode())
        assertEquals(200, get("/health").statusCode())
        assertEquals(200, get("/manifest.webmanifest").statusCode())
        assertEquals(401, post("/login", mapOf("user" to "admin", "password" to "wrong")).statusCode())
        assertEquals(401, post("/login", mapOf("user" to "nobody", "password" to "admin-secret")).statusCode())
        // A sign-in posted from another site is refused.
        assertEquals(403, post("/login", mapOf("user" to "admin", "password" to "admin-secret"), origin = false).let {
            client.send(HttpRequest.newBuilder(URI("$base/login")).header("Origin", "https://evil.example").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("user=admin&password=admin-secret")).build(), HttpResponse.BodyHandlers.ofString())
        }.statusCode())
        val admin = signIn("Admin", "admin-secret")
        val page = get("/", admin)
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("href=\"/users\""))
        // Only a path of this server after signing in.
        val away = post("/login", mapOf("user" to "admin", "password" to "admin-secret", "next" to "//evil.example/"))
        assertEquals("/", away.headers().firstValue("Location").get())
    }

    @Test fun eachPersonHasTheirOwnTrackhop() {
        val admin = signIn("admin", "admin-secret")
        assertTrue(post("/users", mapOf("action" to "add", "name" to "Anna", "password" to "short"), admin).body().contains("at least"))
        assertTrue(post("/users", mapOf("action" to "add", "name" to "../x", "password" to "long enough"), admin).body().contains("lowercase letters"))
        val added = postPairs("/users", listOf("action" to "add", "name" to "Anna", "password" to "anna-password") + Permissions.DEFAULT.map { "perm" to it }, admin)
        assertTrue(added.body(), added.body().contains("Added anna"))
        assertTrue(File(data, "users/anna").isDirectory)

        val anna = signIn("anna", "anna-password")
        val home = get("/", anna)
        assertEquals(200, home.statusCode())
        assertTrue(home.body().contains("anna"))
        assertFalse(home.body().contains("href=\"/users\""))
        // Her log is her engine's own: her data folder, not the admin's.
        val log = get("/log", anna).body()
        assertTrue(log, log.contains(File(data, "users/anna").path))
        assertFalse(get("/log", admin).body().contains(File(data, "users/anna").path))
        // The admin's page is the admin's only.
        assertEquals(404, get("/users", anna).statusCode())
        // Scripts may still send the user name and password with each request.
        val basic = "Basic " + Base64.getEncoder().encodeToString("anna:anna-password".toByteArray())
        assertEquals(200, get("/", null, "Authorization", basic).statusCode())

        // The command line's key: each person's Trackhop leaves its own in their folder, and the API answers as them.
        val annaKey = LocalKeyFile(File(data, "users/anna"))
        val adminKey = LocalKeyFile(data)
        assertNotNull(annaKey); assertNotNull(adminKey)
        assertTrue(annaKey != adminKey)
        assertEquals(200, get("/api/v1/status", null, "Authorization", "Bearer $annaKey").statusCode())
        assertEquals(200, get("/api/v1/status", null, "Authorization", "Bearer $adminKey").statusCode())
        // Nobody's key: the admin's Trackhop answers, its API being off.
        assertEquals(403, get("/api/v1/status", null, "Authorization", "Bearer nope").statusCode())

        // Her own password change signs her other devices out.
        val other = signIn("anna", "anna-password")
        val changed = post("/account", mapOf("current" to "anna-password", "new" to "anna-new-pass", "repeat" to "anna-new-pass"), anna)
        assertTrue(changed.body(), changed.body().contains("Password changed"))
        assertEquals(200, get("/", anna).statusCode())
        assertEquals(303, get("/", other).statusCode())

        // Removed: signed out, her Trackhop stopped, her data kept aside.
        assertTrue(post("/users", mapOf("action" to "remove", "name" to "anna"), admin).body().contains("Removed anna"))
        assertEquals(303, get("/", anna).statusCode())
        assertFalse(File(data, "users/anna").exists())
        assertTrue(File(data, "users-removed").listFiles()!!.any { it.name.startsWith("anna-") })
    }

    @Test fun anInvitationLinkLetsSomeoneChooseTheirPassword() {
        val admin = signIn("admin", "admin-secret")
        val made = postPairs("/users", listOf("action" to "add", "name" to "Bea", "how" to "invite", "perm" to Permissions.RADIO), admin).body()
        assertTrue(made, made.contains("Invitation for bea"))
        val link = Regex("value=\"[^\"]*(/invite/[A-Za-z0-9_-]+)\"").find(made)!!.groupValues[1]
        // Only the link's hash is kept, and the name is not a user yet.
        assertFalse(File(data, "invites.json").readText().contains(link.substringAfterLast('/')))
        assertEquals(401, post("/login", mapOf("user" to "bea", "password" to "whatever1")).statusCode())
        assertTrue(get("/users", admin).body().contains("invited"))

        val page = get(link)
        assertEquals(200, page.statusCode())
        assertTrue(page.body().contains("admin invited you"))
        assertTrue(post(link, mapOf("password" to "bea-password", "repeat" to "other-pass")).body().contains("different"))
        assertEquals(403, client.send(HttpRequest.newBuilder(URI("$base$link")).header("Origin", "https://evil.example").header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("password=bea-password&repeat=bea-password")).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
        val joined = post(link, mapOf("password" to "bea-password", "repeat" to "bea-password"))
        assertEquals(joined.body(), 303, joined.statusCode())
        val bea = joined.headers().firstValue("Set-Cookie").get().substringBefore(';')
        assertEquals(200, get("/", bea).statusCode())
        // What the admin ticked on the invitation.
        assertTrue(File(data, "users.json").readText().contains("\"permissions\":\"radio\""))
        assertTrue(get("/account", bea).body().contains("Signed in as <b>bea</b>"))
        signIn("bea", "bea-password")
        // A link works once.
        assertEquals(404, get(link).statusCode())
        assertEquals(404, post(link, mapOf("password" to "x-password", "repeat" to "x-password")).statusCode())
        assertTrue(File(data, "users/bea").isDirectory)
    }

    @Test fun aNewLinkOrACancelEndsTheOldOne() {
        val admin = signIn("admin", "admin-secret")
        fun linkIn(body: String) = Regex("value=\"[^\"]*(/invite/[A-Za-z0-9_-]+)\"").find(body)!!.groupValues[1]
        val first = linkIn(post("/users", mapOf("action" to "add", "name" to "cid", "how" to "invite"), admin).body())
        val second = linkIn(post("/users", mapOf("action" to "renew", "name" to "cid"), admin).body())
        assertEquals(404, get(first).statusCode())
        assertEquals(200, get(second).statusCode())
        assertTrue(post("/users", mapOf("action" to "cancel", "name" to "cid"), admin).body().contains("no longer works"))
        assertEquals(404, get(second).statusCode())
        assertEquals(404, get("/invite/made-up").statusCode())
        // Adding with a password instead ends the invitation too.
        val third = linkIn(post("/users", mapOf("action" to "add", "name" to "dee", "how" to "invite"), admin).body())
        assertTrue(post("/users", mapOf("action" to "add", "name" to "dee", "how" to "password", "password" to "dee-password"), admin).body().contains("Added dee"))
        assertEquals(404, get(third).statusCode())
    }

    @Test fun songportUserNamesTheAdmin() {
        val admin = signIn("admin", "admin-secret")
        post("/users", mapOf("action" to "add", "name" to "taken", "password" to "taken-password"), admin)
        hub.stop()
        LocalKey.reset()
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true", "SONGPORT_USER" to "Lorenzo")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        // The old name signs in no more, nor do its sessions; the new one is the admin.
        assertEquals(303, get("/", admin).statusCode())
        assertEquals(401, post("/login", mapOf("user" to "admin", "password" to "admin-secret")).statusCode())
        val lorenzo = signIn("lorenzo", "admin-secret")
        val users = get("/users", lorenzo)
        assertEquals(200, users.statusCode())
        assertTrue(users.body().contains("<b>lorenzo</b> keeps"))
        assertTrue(post("/users", mapOf("action" to "add", "name" to "lorenzo", "password" to "whatever-pw"), lorenzo).body().contains("is taken"))
        // Someone else's name cannot become the admin's.
        hub.stop()
        LocalKey.reset()
        val clash = env + ("SONGPORT_USER" to "taken")
        hub = MultiUser.serve(Config(clash), clash)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        signIn("admin", "admin-secret")
        signIn("taken", "taken-password")
    }

        private fun postPairs(path: String, pairs: List<Pair<String, String>>, cookie: String) = client.send(
        HttpRequest.newBuilder(URI("$base$path")).header("Content-Type", "application/x-www-form-urlencoded").header("Cookie", cookie).header("Origin", base)
            .POST(HttpRequest.BodyPublishers.ofString(pairs.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" })).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test fun eachPersonsAccountsStayTheirs() {
        val admin = signIn("admin", "admin-secret")
        postPairs("/users", listOf("action" to "add", "name" to "anna", "password" to "anna-password") + Permissions.DEFAULT.map { "perm" to it }, admin)
        val anna = signIn("anna", "anna-password")
        // The admin: two Jellyfin servers, the second one named.
        post("/connectors/add", mapOf("service" to "jellyfin"), admin)
        assertEquals("/connectors/${enc("jellyfin@2")}", post("/connectors/add", mapOf("service" to "jellyfin@new", "name" to "Admin's friend"), admin).headers().firstValue("Location").get())
        // Anna: a Jellyfin of her own, and one more, named her way.
        post("/connectors/add", mapOf("service" to "jellyfin"), anna)
        post("/connectors/add", mapOf("service" to "jellyfin@new", "name" to "Anna's parents"), anna)
        post("/connectors/add", mapOf("service" to "jellyfin@new"), anna)

        val adminList = get("/connectors", admin).body()
        val annaList = get("/connectors", anna).body()
        assertTrue(adminList.contains("Admin's friend") || adminList.contains("Admin&#39;s friend"))
        assertFalse(adminList.contains("parents"))
        assertFalse(adminList.contains("href=\"/connectors/${enc("jellyfin@3")}\""))
        assertTrue(annaList.contains("parents"))
        assertTrue(annaList.contains("href=\"/connectors/${enc("jellyfin@3")}\""))
        assertFalse(annaList.contains("friend"))
        // Each one's own settings, in their own folder.
        hub.stop()
        LocalKey.reset()
        val adminStore = File(data, "store.json").readText()
        val annaStore = File(data, "users/anna/store.json").readText()
        assertTrue(adminStore.contains("Admin's friend") && !adminStore.contains("parents"))
        assertTrue(annaStore.contains("Anna's parents") && !annaStore.contains("friend"))
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
    }

    @Test fun theAdminSetsWhatEachPersonMayUse() {
        val admin = signIn("admin", "admin-secret")
        // Anna: what a new person gets. Luca: the API only.
        post("/users", mapOf("action" to "add", "name" to "anna", "password" to "anna-password", "perm" to "unofficial"), admin)
        postPairs("/users", listOf("action" to "add", "name" to "anna2", "password" to "anna-password") + Permissions.DEFAULT.map { "perm" to it }, admin)
        postPairs("/users", listOf("action" to "add", "name" to "luca", "password" to "luca-password", "perm" to "api"), admin)
        val anna = signIn("anna2", "anna-password")
        val luca = signIn("luca", "luca-password")

        // Unofficial connectors: not even offered.
        assertTrue(get("/connectors", anna).body().contains("(unofficial, alpha)"))
        assertFalse(get("/connectors", luca).body().contains("(unofficial, alpha)"))
        // Radio syncs.
        assertEquals(200, get("/syncs/radio", anna).statusCode())
        assertEquals(403, get("/syncs/radio", luca).statusCode())
        assertFalse(get("/", luca).body().contains("/syncs/radio"))
        // Folders on the server.
        val folder = tmp.newFolder("backups").path
        val refused = post("/tools/backup", mapOf("action" to "settings", "schedule" to "MANUAL", "folder" to folder), luca)
        assertTrue(refused.headers().firstValue("Location").orElse(""), refused.headers().firstValue("Location").orElse("").contains("err="))
        // Nobody writes into another person's part of the data volume.
        val intoOthers = post("/tools/backup", mapOf("action" to "settings", "schedule" to "MANUAL", "folder" to File(data, "users/luca").path), anna)
        assertTrue(intoOthers.headers().firstValue("Location").orElse("").contains("err="))
        // The API: luca may, anna2 may too; take it away from luca and his key no longer works.
        val lucaKey = LocalKey.read(File(data, "users/luca"))!!.second
        assertEquals(200, get("/api/v1/status", null, "Authorization", "Bearer $lucaKey").statusCode())
        val saved = postPairs("/users", listOf("action" to "permissions", "name" to "luca", "perm" to "radio"), admin)
        assertTrue(saved.body(), saved.body().contains("Saved luca"))
        assertTrue(get("/api/v1/status", null, "Authorization", "Bearer $lucaKey").statusCode() != 200)
        assertEquals(403, get("/tools/api", luca).statusCode())
        // ... and radio is his now, in the restarted Trackhop, without signing in again.
        assertEquals(200, get("/syncs/radio", luca).statusCode())
        // The account page says what one may use.
        assertTrue(get("/account", luca).body().substringAfter("Turned on for you").substringBefore("Not turned on").contains("Radio syncs"))

        // A co-admin manages the others, but cannot remove themselves or drop their own role.
        assertEquals(404, get("/users", anna).statusCode())
        postPairs("/users", listOf("action" to "permissions", "name" to "anna2", "perm" to "admin", "perm" to "api"), admin)
        assertEquals(200, get("/users", anna).statusCode())
        assertTrue(get("/", anna).body().contains("href=\"/users\""))
        assertTrue(post("/users", mapOf("action" to "remove", "name" to "anna2"), anna).body().contains("cannot remove yourself"))
        assertTrue(postPairs("/users", listOf("action" to "permissions", "name" to "anna2", "perm" to "api"), anna).body().contains("cannot take co-admin away"))
        assertTrue(post("/users", mapOf("action" to "password", "name" to "luca", "password" to "luca-new-pass"), anna).body().contains("New password for luca"))
        assertEquals(303, get("/", luca).statusCode())
        signIn("luca", "luca-new-pass")
    }

    @Test fun peopleAddedBefore060MayUseWhatANewPersonGets() {
        hub.stop()
        LocalKey.reset()
        File(data, "users.json").writeText(File(data, "users.json").takeIf { it.exists() }?.readText() ?: "{}")
        UserStore(File(data, "users.json")).add("old", "old-password")
        // As 0.6.0 wrote it: no permissions field.
        val f = File(data, "users.json")
        f.writeText(f.readText().replace(Regex(",\"permissions\":\"[^\"]*\""), ""))
        assertFalse(f.readText().contains("permissions"))
        assertEquals(Permissions.DEFAULT, UserStore(f).permissions("old"))
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        val old = signIn("old", "old-password")
        assertEquals(200, get("/syncs/radio", old).statusCode())
    }

    @Test fun peopleComeBackAfterARestart() {
        val admin = signIn("admin", "admin-secret")
        post("/users", mapOf("action" to "add", "name" to "marco", "password" to "marco-password"), admin)
        val marco = signIn("marco", "marco-password")
        hub.stop()
        LocalKey.reset()
        val env = mapOf("SONGPORT_DATA" to data.path, "SONGPORT_PORT" to "0", "SONGPORT_PASSWORD" to "admin-secret", "SONGPORT_MULTIUSER" to "true")
        hub = MultiUser.serve(Config(env), env)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        // The session survives; so does the person.
        assertEquals(200, get("/", marco).statusCode())
        assertTrue(get("/users", admin).body().contains("marco"))
        // A new admin password in the container's settings signs the admin out, not the others.
        hub.stop()
        LocalKey.reset()
        val changed = env + ("SONGPORT_PASSWORD" to "new-admin-secret")
        hub = MultiUser.serve(Config(changed), changed)
        base = "http://127.0.0.1:${hub.server!!.address.port}"
        assertEquals(303, get("/", admin).statusCode())
        assertEquals(200, get("/", marco).statusCode())
        signIn("admin", "new-admin-secret")
    }

    private fun LocalKeyFile(dir: File): String? = LocalKey.read(dir)?.second
}
