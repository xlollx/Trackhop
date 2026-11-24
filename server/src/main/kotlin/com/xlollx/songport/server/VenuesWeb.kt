package com.xlollx.songport.server

import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ConcertDiary
import com.xlollx.songport.sync.Concerts
import com.xlollx.songport.sync.Setlists
import com.xlollx.songport.sync.Venues
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Venues you follow and your concert diary (the app's VenuesPage and ConcertDiaryPage): the clubs and
 * halls you follow, with their nights in the coming weeks (Ticketmaster and MusicBrainz) and the
 * concerts in your city; and the concerts you went to, as setlist.fm lists them for your user name,
 * each night's songs ready to become a playlist.
 */
internal fun Web.venuesPage(r: Web.Req) {
    val back = "/tools/venues"
    val s = env.store.data.settings
    if (r.method == "POST") {
        when (r.form["action"]) {
            "follow" -> {
                val v = Venues.Venue(r.form["id"].orEmpty(), r.form["name"].orEmpty(), r.form["city"].orEmpty(), r.form["country"].orEmpty())
                if (v.id.isNotBlank()) env.store.updateSettings { it.copy(followedVenues = Venues.toggle(it.followedVenues, v)) }
            }
            "city" -> env.store.updateSettings { it.copy(concertCity = r.form["city"].orEmpty().trim()) }
        }
        return redirect(r.ex, back)
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The clubs and halls you follow, with their nights in the next ${Venues.WEEKS} weeks, and the concerts in your city (Ticketmaster, with your key in Settings, and MusicBrainz). <a href="/tools/diary">Your concert diary</a></p>""")
    // Search a venue.
    val query = r.query["q"].orEmpty()
    val country = r.query["country"]?.let { Concerts.country(it) } ?: Concerts.savedCountry(env) ?: "IT"
    body.append("""<form method="get" action="$back" class="card"><h3>Follow a venue</h3><div class="finder"><input type="search" name="q" value="${h(query)}" placeholder="A club or a hall" aria-label="Find a venue"> <input type="text" name="country" value="${h(country)}" style="max-width:5em" aria-label="Country code"> <button class="secondary">Find</button></div>""")
    if (query.isNotBlank()) {
        val found = runCatching { runBlocking { Venues.search(env, query, country) } }.getOrElse { emptyList() }
        if (found.isEmpty()) body.append("""<p class="muted">Nothing found.</p>""")
        found.take(15).forEach { v ->
            val on = Venues.isFollowed(s.followedVenues, v)
            body.append("""<p>${h(v.name)} <span class="muted">${h(listOf(v.city, v.country).filter { it.isNotBlank() }.joinToString(", "))}</span> ${Html.postButton(back, if (on) "Unfollow" else "Follow", "secondary", mapOf("action" to "follow", "id" to v.id, "name" to v.name, "city" to v.city, "country" to v.country))}</p>""")
        }
    }
    body.append("</form>")
    body.append("""<form method="post" action="$back" class="card"><input type="hidden" name="action" value="city"><label for="vcity">Your city, for the concerts near you (optional)</label><input type="text" id="vcity" name="city" value="${h(s.concertCity)}" placeholder="Milan"><p><button class="secondary">Save</button></p></form>""")
    if (s.followedVenues.isEmpty() && s.concertCity.isBlank()) return send(r.ex, 200, page("Venues", body.toString()))
    body.append("""<div class="card"><h3>Venues you follow</h3>${s.followedVenues.joinToString("") { v -> """<p>${h(v.name)} <span class="muted">${h(v.city)}</span> ${Html.postButton(back, "Unfollow", "link", mapOf("action" to "follow", "id" to v.id, "name" to v.name))}</p>""" }.ifEmpty { """<p class="muted">None yet.</p>""" }}</div>""")
    val up = runCatching { runBlocking { Venues.upcoming(env, s.followedVenues, s.concertCity, Concerts.savedCountry(env) ?: country) } }.getOrNull()
    if (up == null) body.append("""<div class="card err">The venues could not be read now.</div>""")
    else {
        body.append("""<div class="card"><h3>The coming nights</h3>""")
        if (up.nights.isEmpty()) body.append("""<p class="muted">No date in the next ${Venues.WEEKS} weeks.${if (!up.ticketmaster) " With a Ticketmaster key (Settings) many more are found." else ""}</p>""")
        else body.append("<ul>" + up.nights.joinToString("") { n -> "<li><b>${h(n.event.date)}</b> ${h(n.event.artist)} <span class=\"muted\">· ${h(n.event.venue.ifBlank { n.venue?.name.orEmpty() })}, ${h(n.event.city)}</span>${if (n.event.url.isNotBlank()) """ <a href="${h(n.event.url)}" target="_blank" rel="noopener">Tickets</a>""" else ""}</li>" } + "</ul>")
        if (up.unanswered > 0) body.append("""<p class="muted">${up.unanswered} did not answer: try again later.</p>""")
        body.append("</div>")
    }
    send(r.ex, 200, page("Venues", body.toString()))
}

internal fun Web.diaryPage(r: Web.Req) {
    val back = "/tools/diary"
    if (r.method == "POST") {
        when (r.form["action"]) {
            "user" -> { ConcertDiary.saveUser(env, r.form["user"].orEmpty()); return redirect(r.ex, back) }
            "playlist" -> {
                val d = ConcertDiary.kept(env) ?: return redirect(r.ex, back)
                val s = d.concerts.firstOrNull { it.id == r.form["setlist"] } ?: return redirect(r.ex, back)
                val id = LocalFilesProvider.importTracks(env, Setlists.playlistName(s), Setlists.tracks(s))
                return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id.")}")
            }
            "heard" -> {
                val d = ConcertDiary.kept(env) ?: return redirect(r.ex, back)
                val id = LocalFilesProvider.importTracks(env, "Heard live", ConcertDiary.heard(d.concerts))
                return redirect(r.ex, "/connectors/csv?ok=${q("Saved as the File playlist $id.")}")
            }
            else -> {
                val task = Tasks.startOnce("Concert diary", back, { env.log("diary", it) }, env.owner, env.marks) { t ->
                    val d = ConcertDiary.read(env, refresh = true) { done, n -> t.progress = "Page $done of $n" }
                    """<div class="card ok">${d.concerts.size} concerts read. <a href="$back">See them</a></div>"""
                } ?: return redirect(r.ex, "$back?err=${q("Already reading.")}")
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append("""<p class="muted">The concerts you went to, as setlist.fm lists them for your user name (mark them "I was there" on setlist.fm). Each night's songs can become a playlist. <a href="/tools/venues">Venues</a></p>""")
    if (Setlists.key(env) == null) body.append("""<div class="card">Needs a setlist.fm key: <a href="/settings#setlistfm">add a free key</a>.</div>""")
    body.append("""<form method="post" action="$back" class="card"><input type="hidden" name="action" value="user"><label for="duser">Your setlist.fm user name</label><input type="text" id="duser" name="user" value="${h(ConcertDiary.user(env).orEmpty())}"><p><button class="secondary">Save</button></p></form>""")
    val d = ConcertDiary.kept(env)
    if (ConcertDiary.user(env) != null) {
        body.append("""<p>${Html.postButton(back, if (d == null) "Read my diary" else "Read it again", "", mapOf("action" to "read"))}</p>""")
    }
    if (d != null) {
        val st = ConcertDiary.stats(d.concerts)
        body.append("""<div class="card"><div class="stats"><p class="stat"><b>${st.concerts}</b><span class="muted">concerts</span></p><p class="stat"><b>${st.artists}</b><span class="muted">artists</span></p><p class="stat"><b>${st.venues}</b><span class="muted">venues</span></p></div>""")
        st.topArtist?.let { body.append("""<p class="muted">Seen most: ${h(it)} (${st.topTimes} times)${st.firstYear?.let { y -> " · since $y" } ?: ""}</p>""") }
        body.append("""<p>${Html.postButton(back, "Every song I heard live, as a File playlist", "secondary", mapOf("action" to "heard"))}</p></div>""")
        for ((year, list) in ConcertDiary.byYear(d.concerts)) {
            body.append("""<h3>$year</h3>""")
            list.forEach { s -> body.append("""<div class="card"><b>${h(s.artist)}</b> <span class="muted">· ${h(listOf(s.venue, s.city).filter { it.isNotBlank() }.joinToString(", "))} · ${h(s.date)} · ${Setlists.tracks(s).size} songs</span> ${if (Setlists.tracks(s).isNotEmpty()) Html.postButton(back, "Save as a File playlist", "link", mapOf("action" to "playlist", "setlist" to s.id)) else ""}</div>""") }
        }
    }
    send(r.ex, 200, page("Concert diary", body.toString()))
}

/** GET/POST/DELETE /venues, GET /diary */
internal fun Web.apiVenues(method: String, rest: List<String>, query: Map<String, String>, body: () -> JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun venue(v: Venues.Venue) = buildJsonObject { put("id", v.id); put("name", v.name); put("city", v.city); put("country", v.country) }
    when {
        rest == listOf("venues") && method == "GET" -> {
            val s = env.store.data.settings
            val nights = if (query["nights"] == "true") runCatching { runBlocking { Venues.upcoming(env, s.followedVenues, s.concertCity, Concerts.savedCountry(env) ?: "IT") } }.getOrNull() else null
            json(200, buildJsonObject {
                put("venues", JsonArray(s.followedVenues.map(::venue))); put("city", s.concertCity)
                put("nights", nights?.let { u -> JsonArray(u.nights.map { n -> buildJsonObject { put("date", n.event.date); put("artist", n.event.artist); put("venue", n.event.venue); put("city", n.event.city); put("url", n.event.url) } }) } ?: JsonNull)
            })
        }
        rest == listOf("venues", "search") && method == "GET" -> {
            val q = query["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: return true.also { error(400, "q= the venue's name.") }
            val found = runCatching { runBlocking { Venues.search(env, q, Concerts.country(query["country"]) ?: Concerts.savedCountry(env) ?: "IT") } }.getOrElse { return true.also { _ -> error(502, it.message ?: "Error") } }
            json(200, JsonArray(found.map(::venue)))
        }
        rest == listOf("venues") && method == "POST" -> {
            val b = body() ?: return true.also { error(400, "Send JSON: {\"id\", \"name\", \"city\"?, \"country\"?} (from GET /venues/search).") }
            val v = Venues.Venue(b.text("id") ?: return true.also { error(400, "\"id\" is needed (GET /venues/search).") }, b.text("name").orEmpty(), b.text("city").orEmpty(), b.text("country").orEmpty())
            env.store.updateSettings { if (Venues.isFollowed(it.followedVenues, v)) it else it.copy(followedVenues = it.followedVenues + v) }
            json(200, buildJsonObject { put("followed", venue(v)) })
        }
        rest.size == 2 && rest[0] == "venues" && method == "DELETE" -> {
            env.store.updateSettings { it.copy(followedVenues = it.followedVenues.filter { v -> v.id != rest[1] }) }
            json(200, buildJsonObject { put("unfollowed", rest[1]) })
        }
        rest == listOf("diary") && method == "GET" -> {
            query["user"]?.let { ConcertDiary.saveUser(env, it) }
            if (ConcertDiary.user(env) == null) return true.also { error(400, "user= your setlist.fm user name (kept for the next calls).") }
            val d = runCatching { runBlocking { ConcertDiary.read(env, refresh = query["refresh"] == "true") } }.getOrElse { return true.also { _ -> error(if (it is ConcertDiary.NoUser) 400 else 502, it.message ?: "Error") } }
            val st = ConcertDiary.stats(d.concerts)
            json(200, buildJsonObject {
                put("user", d.user); put("concerts", st.concerts); put("artists", st.artists); put("venues", st.venues)
                put("topArtist", st.topArtist?.let { JsonPrimitive(it) } ?: JsonNull)
                put("setlists", JsonArray(d.concerts.map { setlistJson(it) }))
            })
        }
        else -> return false
    }
    return true
}
