package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ArtistAlerts
import com.xlollx.songport.sync.Concerts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Alerts for the artists the user follows (Features.v2), as the app's Discover › Followed
 * artists: the same list (the settings' followedArtists, so a set-up file carries it between the
 * app and the server), the same look-up and memory ([ArtistAlerts], with the New releases,
 * Concerts and Records & merch sources and caches), checked once a day (or a week) by the
 * scheduler (Runner.kt), and the news told through the notification channels the user set up
 * (Settings › Notifications) that want news of their artists (the New releases event).
 *
 * The page (Tools › Followed artists), the API (/api/v1/followed), the command line (`trackhop
 * followed`, `follow`, `unfollow`) and MCP. Each user's own list, memory and channels.
 */
internal object ServerAlerts {
    const val TITLE = "Followed artists"
    private const val KV = "server_alerts"
    /** After a run with artists left (the time budget ran out): the next one this soon. */
    private const val AGAIN_MS = 15 * 60_000L
    /** Otherwise the scheduler looks again this often: only the artists due are looked up. */
    private const val EVERY_MS = 6 * 3_600_000L

    fun followed(env: ServerEnv): List<ArtistAlerts.Followed> = ArtistAlerts.sorted(env.store.data.settings.followedArtists)

    fun find(env: ServerEnv, name: String): ArtistAlerts.Followed? = ArtistAlerts.key(name).let { k -> env.store.data.settings.followedArtists.firstOrNull { ArtistAlerts.key(it.name) == k } }

    /** Follows [name] (with [kinds], else the user's defaults); false when already followed or not a name. */
    fun follow(env: ServerEnv, name: String, kinds: ArtistAlerts.Kinds? = null): Boolean {
        val n = name.trim().take(200)
        if (ArtistAlerts.key(n).isEmpty() || find(env, n) != null) return false
        env.store.updateSettings { s -> s.copy(followedArtists = ArtistAlerts.follow(s.followedArtists, n, kinds ?: s.artistAlertKinds)) }
        return true
    }

    fun unfollow(env: ServerEnv, name: String): Boolean {
        val f = find(env, name) ?: return false
        env.store.updateSettings { s -> s.copy(followedArtists = ArtistAlerts.toggle(s.followedArtists, f.name, s.artistAlertKinds)) }
        return true
    }

    fun setMuted(env: ServerEnv, name: String, muted: Boolean) = env.store.updateSettings { s -> s.copy(followedArtists = ArtistAlerts.setMuted(s.followedArtists, name, muted)) }

    fun setKinds(env: ServerEnv, name: String, kinds: ArtistAlerts.Kinds) = env.store.updateSettings { s -> s.copy(followedArtists = ArtistAlerts.setKinds(s.followedArtists, name, kinds)) }

    /** The country of the concerts looked for: the last one picked in Concerts (here or in the app), else the server's language's. */
    fun country(env: ServerEnv): String = Concerts.savedCountry(env) ?: Concerts.country(env.keyValues("ui")["concertsCountry"])
        ?: Concerts.defaultCountry(null, null, Locale.getDefault())

    fun running(env: ServerEnv) = Tasks.all(env.owner).any { it.title == TITLE && !it.done }

    /** The scheduler's question, once a minute: alerts on, someone to look up, and the time come. */
    fun isDue(env: ServerEnv, now: Long): Boolean {
        if (!Features.v2 || ArtistAlerts.plan(env.store.data.settings) == null || running(env)) return false
        return now >= (env.keyValues(KV)["next"]?.toLongOrNull() ?: 0L)
    }

    /** A look now ([manual]: every artist, whatever when it was looked up last), as a task. */
    fun start(env: ServerEnv, manual: Boolean): Tasks.Task = Tasks.start(TITLE, "/tools/alerts", { env.log("alerts", it) }, env.owner) { t ->
        t.progress = "Looking up the artists you follow"
        val out = check(env, manual)
        buildString {
            append("""<div class="card ${if (out.notify.isEmpty()) "" else "ok"}">""")
            append(if (out.notify.isEmpty()) "Nothing new." else h(ArtistAlerts.summary(env, out.notify).replaceFirstChar { it.titlecase() }) + ".")
            append(""" <span class="muted">${out.checked} looked up${if (out.remaining > 0) ", ${out.remaining} left for the next run" else ""}.</span></div>""")
        }
    }

    /**
     * Looks the artists up ([ArtistAlerts.check]: the due ones, those looked up longest ago
     * first, within its time budget), tells the news and says when to look again. [manual]
     * looks every artist up now. [lookup] and [now] are the tests'.
     */
    suspend fun check(
        env: ServerEnv, manual: Boolean = false, lookup: ArtistAlerts.Lookup? = null,
        now: () -> Long = { System.currentTimeMillis() }, gapMs: Long = ArtistAlerts.ARTIST_GAP_MS,
    ): ArtistAlerts.Outcome {
        val s = env.store.data.settings
        val period = if (manual) 0L else ArtistAlerts.plan(s)?.periodDays ?: 1L
        val out = ArtistAlerts.check(env, s.followedArtists, period, lookup ?: ArtistAlerts.live(country(env), Concerts.savedRange(env)), gapMs = gapMs, now = now)
        env.keyValues(KV)["next"] = (now() + if (out.remaining > 0) AGAIN_MS else EVERY_MS).toString()
        if (out.notify.isNotEmpty()) notify(env, out.notify)
        return out
    }

    /** The channels told: enabled, ready, and wanting news of the user's artists (the New releases event). */
    fun channels(env: ServerEnv): List<Notify.Channel> =
        if (!env.may(Permissions.API)) emptyList()
        else runCatching { Notify.channels(env) }.getOrDefault(emptyList()).filter { it.enabled && it.missing().isEmpty() && Notify.Event.RELEASES in it.events }

    fun message(env: ServerEnv, news: List<ArtistAlerts.News>): Notify.Message {
        val lines = ArtistAlerts.group(news)
        val links = news.filter { it.url.isNotBlank() }.take(10).joinToString("") { "\n${ArtistAlerts.phrase(env, it)}: ${it.url}" }
        return Notify.Message(
            if (lines.size == 1) "News of an artist you follow" else "News of the artists you follow",
            ArtistAlerts.summary(env, news).replaceFirstChar { it.titlecase() } + "." + links,
        )
    }

    /** Sends the news through [channels] now; how many took it. */
    fun notify(env: ServerEnv, news: List<ArtistAlerts.News>): Int {
        val m = message(env, news)
        return channels(env).count { c -> Notify.send(c, m).also { err -> err?.let { env.log("notify", "${c.name} (${c.kind.label}): $it") } } == null }
    }

    // ------------------------------------------------------------------ JSON

    fun kinds(k: ArtistAlerts.Kinds): JsonObject = buildJsonObject { put("releases", k.releases); put("concerts", k.concerts); put("merch", k.merch); put("records", k.records) }

    /** [base] with the kinds named in [o] (releases, concerts, merch, records: true or false) changed. */
    fun kinds(base: ArtistAlerts.Kinds, o: JsonObject): ArtistAlerts.Kinds =
        ArtistAlerts.Kind.entries.fold(base) { k, kind -> o.flag(word(kind))?.let { k.with(kind, it) } ?: k }

    /** "releases,concerts" as kinds (the others off); null for a word it does not know. */
    fun kinds(words: String): ArtistAlerts.Kinds? {
        val asked = words.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (asked.any { a -> ArtistAlerts.Kind.entries.none { word(it) == a } }) return null
        return ArtistAlerts.Kind.entries.fold(ArtistAlerts.Kinds(false, false, false, false)) { k, kind -> k.with(kind, word(kind) in asked) }
    }

    fun word(k: ArtistAlerts.Kind) = when (k) { ArtistAlerts.Kind.RELEASE -> "releases"; ArtistAlerts.Kind.CONCERT -> "concerts"; ArtistAlerts.Kind.MERCH -> "merch"; ArtistAlerts.Kind.RECORD -> "records" }

    fun json(f: ArtistAlerts.Followed): JsonObject = buildJsonObject {
        put("name", f.name); f.mbid?.let { put("mbid", it) }; put("muted", f.muted); put("kinds", kinds(f.kinds))
    }

    fun newsJson(n: ArtistAlerts.News): JsonObject = buildJsonObject {
        put("artist", n.artist); put("kind", word(n.kind)); put("title", n.title); put("detail", n.detail); put("url", n.url)
    }

    fun state(env: ServerEnv): JsonObject {
        val s = env.store.data.settings
        return buildJsonObject {
            put("enabled", s.artistAlerts)
            put("every", if (s.artistAlertsWeekly) "week" else "day")
            put("country", country(env))
            put("channels", channels(env).size)
            put("defaults", kinds(s.artistAlertKinds))
            put("artists", JsonArray(followed(env).map { json(it) }))
            put("latest", JsonArray(ArtistAlerts.latest(env).map { newsJson(it) }))
            put("checking", running(env))
        }
    }
}

// ------------------------------------------------------------------ the page

/** Tools › Followed artists: follow and unfollow, what each is checked for, the schedule, the latest news. */
internal fun Web.alertsPage(r: Web.Req) {
    val title = ServerAlerts.TITLE
    if (r.method == "POST") {
        val f = r.form
        val name = f["artist"].orEmpty()
        val back = when (f["action"]) {
            "follow" -> {
                val kinds = ArtistAlerts.Kind.entries.fold(ArtistAlerts.Kinds(false, false, false, false)) { k, kind -> k.with(kind, f[ServerAlerts.word(kind)] == "on") }
                if (!kinds.any) "err=${q("Tick at least one thing to be told about.")}"
                else if (ServerAlerts.follow(env, name, kinds)) "ok=${q("Following ${name.trim()}.")}"
                else "err=${q(if (name.isBlank()) "Write an artist's name." else "${name.trim()} is followed already.")}"
            }
            "unfollow" -> if (ServerAlerts.unfollow(env, name)) "ok=${q("No longer following $name.")}" else ""
            "mute" -> { ServerAlerts.setMuted(env, name, true); "" }
            "unmute" -> { ServerAlerts.setMuted(env, name, false); "" }
            "kinds" -> {
                val kinds = ArtistAlerts.Kind.entries.fold(ArtistAlerts.Kinds(false, false, false, false)) { k, kind -> k.with(kind, f[ServerAlerts.word(kind)] == "on") }
                ServerAlerts.setKinds(env, name, kinds); "ok=${q("Saved.")}"
            }
            "settings" -> {
                env.store.updateSettings { it.copy(artistAlerts = f["enabled"] == "on", artistAlertsWeekly = f["every"] == "week") }
                "ok=${q("Saved.")}"
            }
            "check" -> {
                if (ServerAlerts.running(env)) "err=${q("A look is already under way.")}"
                else return redirect(r.ex, "/tools/task/${q(ServerAlerts.start(env, manual = true).id)}")
            }
            else -> ""
        }
        return redirect(r.ex, "/tools/alerts" + if (back.isEmpty()) "" else "?$back")
    }
    val s = env.store.data.settings
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The artists you follow are looked up once a day (or a week): their new releases, their concerts in ${h(Locale("", ServerAlerts.country(env)).getDisplayCountry(Locale.ENGLISH))} (the country picked last in Concerts), their new merch and records. What is new is told through your notification channels that want news of your artists (the New releases event, <a href="/settings/notifications">Settings › Notifications</a>). The first look only takes note of what is there.</p>""")
    if (ServerAlerts.channels(env).isEmpty()) body.append("""<div class="card">No notification channel takes this news yet: add one, or tick "New releases" on one, in <a href="/settings/notifications">Settings › Notifications</a>. The news is still listed here.</div>""")
    fun boxes(k: ArtistAlerts.Kinds) = ArtistAlerts.Kind.entries.joinToString(" ") { kind ->
        val w = ServerAlerts.word(kind)
        """<label class="check"><input type="checkbox" name="$w"${if (k[kind]) " checked" else ""}> ${w.replaceFirstChar { it.uppercase() }}</label>"""
    }
    body.append("""<form method="post" action="/tools/alerts" class="card"><input type="hidden" name="action" value="follow"><label>Follow an artist</label><input name="artist" placeholder="Artist's name" required>
<p>${boxes(s.artistAlertKinds)}</p><p><button>Follow</button></p></form>""")
    val list = ServerAlerts.followed(env)
    if (list.isEmpty()) body.append("""<div class="card">You follow no artist yet.</div>""")
    else {
        body.append("""<h2>Followed (${list.size})</h2>""")
        list.forEach { a ->
            val fields = mapOf("artist" to a.name)
            body.append("""<div class="card"><h3>${h(a.name)}${if (a.muted) """ <span class="badge">muted</span>""" else ""}</h3>""")
            body.append("""<form method="post" action="/tools/alerts" class="inline"><input type="hidden" name="action" value="kinds"><input type="hidden" name="artist" value="${h(a.name)}">${boxes(a.kinds)} <button class="secondary">Save</button></form>""")
            body.append("""<div class="actions">""")
            body.append(Html.postButton("/tools/alerts", if (a.muted) "Turn alerts on" else "Mute", "secondary", fields + ("action" to if (a.muted) "unmute" else "mute")))
            body.append(Html.postButton("/tools/alerts", "Unfollow", "secondary", fields + ("action" to "unfollow"), confirm = "Unfollow ${a.name}?"))
            body.append("</div></div>")
        }
        body.append("""<div class="actions">${Html.postButton("/tools/alerts", if (ServerAlerts.running(env)) "Looking…" else "Look for news now", fields = mapOf("action" to "check"))}</div>""")
    }
    val latest = ArtistAlerts.latest(env)
    if (latest.isNotEmpty()) {
        body.append("""<h2>Latest news</h2><div class="card"><ul>""")
        latest.forEach { n ->
            val what = h(ArtistAlerts.phrase(env, n).replaceFirstChar { it.titlecase() }) + (n.title.takeIf { it.isNotBlank() }?.let { ": " + h(it) } ?: "")
            body.append(if (n.url.isBlank()) "<li>$what</li>" else """<li><a href="${h(n.url)}" rel="noopener noreferrer" target="_blank">$what</a></li>""")
        }
        body.append("</ul></div>")
    }
    body.append("""<form method="post" action="/tools/alerts" class="card"><input type="hidden" name="action" value="settings"><h3>When</h3>
<label class="check"><input type="checkbox" name="enabled"${if (s.artistAlerts) " checked" else ""}> Look for news on a schedule</label>
<label>How often</label><select name="every"><option value="day"${if (!s.artistAlertsWeekly) " selected" else ""}>Every day</option><option value="week"${if (s.artistAlertsWeekly) " selected" else ""}>Every week</option></select>
<p><button class="secondary">Save</button></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

// ------------------------------------------------------------------ the API

/** /api/v1/followed: the list, follow, change, unfollow, the schedule, a look now. */
internal fun Web.apiFollowed(method: String, rest: List<String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest.firstOrNull() != "followed") return false
    when {
        rest.size == 1 && method == "GET" -> json(200, ServerAlerts.state(env))
        rest.size == 1 && method == "PATCH" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"enabled\"?: true, \"every\"?: \"day\" | \"week\"}.") }
            val every = b.text("every")?.lowercase()
            if (every != null && every != "day" && every != "week") return true.also { error(400, "\"every\" is day or week.") }
            env.store.updateSettings { s -> s.copy(artistAlerts = b.flag("enabled") ?: s.artistAlerts, artistAlertsWeekly = every?.let { it == "week" } ?: s.artistAlertsWeekly) }
            json(200, ServerAlerts.state(env))
        }
        rest.size == 1 && method == "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"name\": \"Artist\", \"releases\"?, \"concerts\"?, \"merch\"?, \"records\"?}.") }
            val name = b.text("name") ?: return true.also { error(400, "\"name\" is the artist's name.") }
            val kinds = ServerAlerts.kinds(env.store.data.settings.artistAlertKinds, b)
            if (!kinds.any) return true.also { error(400, "At least one of releases, concerts, merch, records must be true.") }
            val added = ServerAlerts.follow(env, name, kinds)
            val f = ServerAlerts.find(env, name) ?: return true.also { error(400, "\"$name\" is not an artist's name.") }
            json(if (added) 201 else 200, buildJsonObject { put("followed", added); put("artist", ServerAlerts.json(f)) })
        }
        rest == listOf("followed", "check") && method == "POST" -> {
            if (ServerAlerts.followed(env).isEmpty()) return true.also { error(409, "You follow no artist: POST /api/v1/followed first.") }
            if (ServerAlerts.running(env)) return true.also { error(409, "A look is already under way.") }
            json(202, buildJsonObject { put("task", ServerAlerts.start(env, manual = true).id) })
        }
        // The artist's name may hold a slash (AC/DC): the rest of the path is the name.
        rest.size >= 2 && method == "PATCH" -> {
            val name = rest.drop(1).joinToString("/")
            val f = ServerAlerts.find(env, name) ?: return true.also { error(404, "\"$name\" is not followed: see GET /api/v1/followed.") }
            val b = body() ?: return true.also { error(400, "Send JSON: {\"muted\"?, \"releases\"?, \"concerts\"?, \"merch\"?, \"records\"?}.") }
            b.flag("muted")?.let { ServerAlerts.setMuted(env, f.name, it) }
            val kinds = ServerAlerts.kinds(f.kinds, b)
            if (kinds != f.kinds) ServerAlerts.setKinds(env, f.name, kinds)
            json(200, ServerAlerts.json(ServerAlerts.find(env, f.name)!!))
        }
        rest.size >= 2 && method == "DELETE" -> {
            val name = rest.drop(1).joinToString("/")
            val f = ServerAlerts.find(env, name) ?: return true.also { error(404, "\"$name\" is not followed: see GET /api/v1/followed.") }
            ServerAlerts.unfollow(env, f.name)
            json(200, buildJsonObject { put("unfollowed", f.name) })
        }
        else -> return false
    }
    return true
}

/** For the scheduler (Runner.kt): starts the look when it is due. */
internal fun ServerEnv.alertsTick(now: Long) {
    if (ServerAlerts.isDue(this, now)) ServerAlerts.start(this, manual = false)
}
