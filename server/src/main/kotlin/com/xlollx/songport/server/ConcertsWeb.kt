package com.xlollx.songport.server

import com.xlollx.songport.model.Playlist
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.Concerts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Concerts of a playlist's artists ([Concerts]) on the page (Tools › Concerts), in the API
 * (GET /api/v1/concerts) and, through it, the command line and MCP. Only when asked: nothing runs
 * on a schedule. The Ticketmaster key is the server's own setting (Settings), or the container's
 * TICKETMASTER_API_KEY; without one, MusicBrainz's dates alone.
 */
internal object ConcertTexts {
    const val QUOTA = "Today's Ticketmaster limit is reached: try again tomorrow or add your own key (Settings › Ticketmaster key). Some dates come from MusicBrainz only."
    const val REFUSED = "Ticketmaster refused the key: check it in Settings › Ticketmaster key."
    const val NO_KEY = "Only MusicBrainz dates: add a free Ticketmaster key in Settings for many more."
    const val GAP_IT = "In Italy, concerts sold only on TicketOne are missing: use the search links for those."
    const val GAP = "Not every concert is listed: check the artist's official site too."
    const val UNOFFICIAL = "Vivaticket's dates are read from its website (unofficial): this may stop working at any time."

    /** Where a date's tickets are, as its link says. */
    fun tickets(s: Concerts.Source): String = when (s) {
        Concerts.Source.TICKETMASTER -> "Tickets on Ticketmaster"
        Concerts.Source.VIVATICKET -> "Tickets on Vivaticket (unofficial)"
        else -> s.label
    }

    /** Some dates come from an unofficial source. */
    fun unofficial(r: Concerts.Result) = r.artists.any { a -> a.events.any { e -> e.source.unofficial || e.alsoOn.any { it.source.unofficial } } }

    fun limited(shown: Int, total: Int) = "Only the $shown artists the playlist holds most were looked up: add your own Ticketmaster key to look up all $total."

    /** The notes a result comes with, in the order they matter. */
    fun notes(r: Concerts.Result): List<String> = listOfNotNull(
        QUOTA.takeIf { r.quotaHit }, REFUSED.takeIf { r.keyRefused }, NO_KEY.takeIf { !r.ticketmaster },
        limited(r.artists.size, r.total).takeIf { r.total > r.artists.size },
        if (Concerts.ticketOneGap(r.country)) GAP_IT else GAP,
    )
}

private const val UI_COUNTRY = "concertsCountry"

/** The country the page starts from: the last one chosen here, else the server's language's. */
private fun Web.startCountry(): String =
    Concerts.country(env.keyValues("ui")[UI_COUNTRY]) ?: Concerts.defaultCountry(null, null, Locale.getDefault())

private fun Web.canReadForConcerts(p: MusicProvider) =
    (!p.requiresAuth || p.isConnected(env)) && !p.linkOnly && p.serviceId != "lastfm" && p.serviceId != "listenbrainz"

/** The services a playlist can come from: the connected ones, and File. */
private fun Web.readableForConcerts(): List<MusicProvider> =
    (Providers.connectors() + LocalFilesProvider).distinctBy { it.id }.filter { canReadForConcerts(it) }

private suspend fun Web.playlistsOf(p: MusicProvider): List<Playlist> = p.libraryEntries(env, false) + p.playlists(env)

/** Looks the playlist's artists up: what the page's task and the API both run. */
private suspend fun Web.concertsOf(p: MusicProvider, playlistId: String, country: String, progress: (Int, Int) -> Unit = { _, _ -> }): Concerts.Result {
    val artists = Concerts.artistsOf(p.tracks(env, playlistId))
    val r = Concerts.find(env, artists, country, progress)
    env.log("concerts", "${p.label(env)} / $playlistId in ${r.country}: ${r.artists.count { it.events.isNotEmpty() }} of ${r.artists.size} artists with dates")
    return r
}

internal fun concertsJson(r: Concerts.Result): JsonObject = buildJsonObject {
    put("country", r.country)
    put("artistsInPlaylist", r.total)
    put("artistsLookedUp", r.artists.size)
    put("ticketmaster", r.ticketmaster)
    put("ownKey", r.ownKey)
    put("quotaReached", r.quotaHit)
    put("keyRefused", r.keyRefused)
    put("notes", JsonArray(ConcertTexts.notes(r).map { JsonPrimitive(it) }))
    put("artists", JsonArray(r.artists.map { a ->
        buildJsonObject {
            put("artist", a.artist)
            put("events", JsonArray(a.events.map { e ->
                buildJsonObject {
                    put("date", e.date); put("time", e.time?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("city", e.city); put("venue", e.venue); put("country", e.country)
                    put("name", e.name); put("url", e.url); put("source", e.source.label); put("unofficial", e.source.unofficial)
                    put("alsoOn", JsonArray(e.alsoOn.map { l -> buildJsonObject { put("source", l.source.label); put("url", l.url); put("unofficial", l.source.unofficial) } }))
                }
            }))
            put("searches", JsonArray(a.searches.map { l ->
                buildJsonObject { put("site", l.site ?: "Official site"); put("url", l.url) }
            }))
        }
    }))
    put("attribution", (if (r.ticketmaster) "Concert data from Ticketmaster and MusicBrainz." else "Concert data from MusicBrainz.") +
        (if (ConcertTexts.unofficial(r)) " " + ConcertTexts.UNOFFICIAL else ""))
}

/** GET /api/v1/concerts?service=&playlist=&country= */
internal fun Web.apiConcerts(method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    if (rest != listOf("concerts") || method != "GET") return false
    val able = readableForConcerts()
    val asked = query["service"] ?: query["connector"]
    val p = if (asked != null) Providers.byId(asked)?.takeIf { canReadForConcerts(it) } ?: return true.also { error(404, "No connected service \"$asked\": see GET /api/v1/services.") }
        else able.singleOrNull() ?: return true.also { error(400, "Say which service: ?service= one of ${able.joinToString(", ") { it.id }}.") }
    val wanted = query["playlist"]?.trim()?.takeIf { it.isNotEmpty() } ?: return true.also { error(400, "Say which playlist: ?playlist= its id or name (GET /api/v1/services/${p.id}/playlists).") }
    val country = query["country"]?.let { Concerts.country(it) ?: return true.also { _ -> error(400, "\"country\" is a two-letter country code: IT, US, DE...") } } ?: startCountry()
    val r = runCatching {
        runBlocking {
            val lists = playlistsOf(p)
            val pl = lists.firstOrNull { it.id == wanted } ?: lists.filter { it.name.equals(wanted, ignoreCase = true) }.singleOrNull()
                ?: return@runBlocking null
            concertsOf(p, pl.id, country)
        }
    }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
        ?: return true.also { error(404, "No playlist \"$wanted\" on ${p.label(env)} (or more than one with that name: use its id).") }
    json(200, concertsJson(r))
    return true
}

/** Tools › Concerts: a service, a playlist and a country, then the dates as a background task. */
internal fun Web.concertsPage(r: Web.Req) {
    val title = "Concerts"
    val params = if (r.method == "POST") r.form else r.query
    val body = StringBuilder(notice(r))
    val able = readableForConcerts()
    if (able.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val intro = """<p class="muted">The upcoming concerts of a playlist's artists in a country, from Ticketmaster and MusicBrainz. Only when you ask: nothing is checked on a schedule. ${keyLine()}</p>"""
    val service = params["service"]?.let { id -> able.firstOrNull { it.id == id } }
    if (service == null) {
        val opts = able.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/concerts" class="card">$intro<label>Service</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page(title, body.toString()))
    }
    val lists = runCatching { runBlocking { playlistsOf(service) } }
        .getOrElse { return send(r.ex, 200, page(title, """<div class="card err">${h(it.message)}</div>""")) }
    val playlist = params["playlist"]?.let { id -> lists.firstOrNull { it.id == id } }
    if (r.method == "POST" && playlist != null) {
        val country = Concerts.country(params["country"]) ?: startCountry()
        env.keyValues("ui")[UI_COUNTRY] = country
        val task = Tasks.start(title, "/tools/concerts?service=${q(service.id)}&playlist=${q(playlist.id)}", { env.log("concerts", it) }, env.owner) { t ->
            t.progress = "Reading the playlist"
            val res = concertsOf(service, playlist.id, country) { done, total -> t.progress = "Artist ${minOf(done + 1, total)} of $total" }
            concertsHtml(playlist.name, res)
        }
        return redirect(r.ex, "/tools/task/${q(task.id)}")
    }
    val here = Locale.ENGLISH
    val chosen = startCountry()
    val countries = Locale.getISOCountries().map { it to Locale("", it).getDisplayCountry(here) }.sortedBy { it.second }
        .joinToString("") { (c, n) -> """<option value="$c"${if (c == chosen) " selected" else ""}>${h(n)}</option>""" }
    val opts = lists.joinToString("") { p -> """<option value="${h(p.id)}"${if (p.id == playlist?.id) " selected" else ""}>${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    body.append("""<form method="post" action="/tools/concerts" class="card">$intro<input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist">$opts</select>
<label>Country</label><select name="country">$countries</select>
<p><button>Find concerts</button> <a class="button secondary" href="/tools/concerts">Another service</a></p></form>""")
    send(r.ex, 200, page(title, body.toString()))
}

private fun Web.keyLine(): String = when {
    Concerts.ownKey(env) != null -> "Ticketmaster: your own key."
    Concerts.key(env) != null -> "Ticketmaster: the key given to this server."
    else -> """No Ticketmaster key: MusicBrainz dates only. <a href="/settings#ticketmaster">Add a free key</a>."""
}

/** The result of a search, as the task's page shows it. */
private fun concertsHtml(playlist: String, r: Concerts.Result): String = buildString {
    append("""<p class="muted">${h(playlist)} · ${h(Locale("", r.country).getDisplayCountry(Locale.ENGLISH))}</p>""")
    append("""<div class="card">""")
    ConcertTexts.notes(r).forEach { append("""<p class="muted">${h(it)}</p>""") }
    if (r.quotaHit || r.keyRefused || !r.ticketmaster || r.total > r.artists.size) append("""<p><a href="/settings#ticketmaster">Ticketmaster key</a></p>""")
    append("</div>")
    val (dated, without) = r.artists.partition { it.events.isNotEmpty() }
    if (dated.isEmpty()) append("""<div class="card">No upcoming dates found for these artists.</div>""")
    dated.forEach { a ->
        append("""<div class="card"><h3>${h(a.artist)}</h3><ul>""")
        a.events.forEach { e ->
            val where = listOf(e.city, e.venue).filter { it.isNotBlank() }.joinToString(" · ")
            // A date without a web link (none given, or not http/https) says its source as plain text.
            val source = (if (e.url.isBlank()) h(e.source.label) else """<a href="${h(e.url)}" rel="noopener noreferrer" target="_blank">${h(ConcertTexts.tickets(e.source))}</a>""") +
                e.alsoOn.joinToString("") { l -> """ · <a href="${h(l.url)}" rel="noopener noreferrer" target="_blank">${h(ConcertTexts.tickets(l.source))}</a>""" }
            append("""<li><strong>${h(e.date)}${e.time?.let { " " + h(it) } ?: ""}</strong> ${h(where)} · $source</li>""")
        }
        append("</ul></div>")
    }
    if (without.isNotEmpty()) {
        append("""<div class="card"><h3>${without.size} ${if (without.size == 1) "artist" else "artists"} without dates</h3><p class="muted">Search the ticket sites yourself: these links open the search.</p><ul>""")
        without.forEach { a ->
            append("""<li>${h(a.artist)}: """)
            append(a.searches.joinToString(" · ") { l -> """<a href="${h(l.url)}" rel="noopener noreferrer" target="_blank">${h(l.site ?: "Official site")}</a>""" })
            append("</li>")
        }
        append("</ul></div>")
    }
    append("""<p class="muted">${if (r.ticketmaster) "Concert data from Ticketmaster and MusicBrainz." else "Concert data from MusicBrainz."}</p>""")
    if (ConcertTexts.unofficial(r)) append("""<p class="muted">${h(ConcertTexts.UNOFFICIAL)}</p>""")
}

/** Settings › Ticketmaster key: the card, and its form's handling. */
internal fun Web.ticketmasterKeyCard(): String {
    val own = Concerts.ownKey(env) != null
    val state = when {
        own -> "Your own key is saved."
        Concerts.key(env) != null -> "Using the key given to this server (TICKETMASTER_API_KEY)."
        else -> "No key: Concerts shows MusicBrainz dates only."
    }
    return """<form method="post" action="/settings" class="card" id="ticketmaster"><input type="hidden" name="action" value="ticketmaster"><h3>Ticketmaster key</h3>
<p class="muted">For Tools › Concerts. Free: make an app on <a href="${Concerts.KEY_URL}" rel="noopener noreferrer" target="_blank">Ticketmaster's developer site</a> and paste its <strong>Consumer Key</strong> (never the Consumer Secret). Your own key looks up more artists and has its own daily limit. $state</p>
<label>Consumer Key</label><input name="key" autocomplete="off" placeholder="${if (own) "saved: leave empty to keep it" else ""}">
<p class="actions"><button>Save</button>${if (own) """ <button name="remove" value="1" class="secondary">Remove</button>""" else ""}</p></form>"""
}

internal fun Web.saveTicketmasterKey(form: Map<String, String>) {
    when {
        form["remove"] == "1" -> Concerts.saveKey(env, "")
        !form["key"].isNullOrBlank() -> Concerts.saveKey(env, form["key"]!!)
    }
}
