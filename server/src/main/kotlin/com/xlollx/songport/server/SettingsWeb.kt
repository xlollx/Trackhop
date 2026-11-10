package com.xlollx.songport.server

import com.xlollx.songport.notify.Notify
import com.xlollx.songport.server.Html.h
import kotlinx.coroutines.runBlocking

/** The page's look: the system's, or always light, or always dark. Each person keeps their own. */
internal object Theme {
    const val SYSTEM = "system"
    val ALL = listOf(SYSTEM to "Same as the system", "light" to "Light", "dark" to "Dark")

    fun of(env: ServerEnv): String = env.keyValues("ui")["theme"]?.takeIf { t -> ALL.any { it.first == t } } ?: SYSTEM

    fun set(env: ServerEnv, theme: String) {
        if (ALL.any { it.first == theme }) env.keyValues("ui")["theme"] = theme
    }
}

/** The home page's side column: where it goes and which cards it shows. Each person keeps their own. */
internal object HomeSide {
    const val LEFT = "left"; const val RIGHT = "right"; const val HIDDEN = "hidden"
    val PLACES = listOf(LEFT to "On the left", RIGHT to "On the right", HIDDEN to "Hidden")
    /** The cards, in order: the chart first, as by default. */
    val CARDS = listOf("activity" to "Last two weeks (chart)", "numbers" to "Your numbers", "runs" to "Latest runs", "services" to "Services")

    fun place(env: ServerEnv): String = env.keyValues("ui")["side"]?.takeIf { p -> PLACES.any { it.first == p } } ?: LEFT

    /** The cards shown: all of them until the person picks. */
    fun cards(env: ServerEnv): Set<String> =
        env.keyValues("ui")["sideCards"]?.split(',')?.filter { c -> CARDS.any { it.first == c } }?.toSet() ?: CARDS.map { it.first }.toSet()

    fun set(env: ServerEnv, place: String, cards: Collection<String>) {
        if (PLACES.any { it.first == place }) env.keyValues("ui")["side"] = place
        env.keyValues("ui")["sideCards"] = CARDS.map { it.first }.filter { it in cards }.joinToString(",")
    }
}

/** True when this person is told about updates: the one who runs Trackhop (the admin, on a shared server). */
internal fun Web.seesUpdates(): Boolean = Updates.configured && env.may(Permissions.ADMIN)

/** The line under the header when a newer version is out. */
internal fun Web.updateBanner(): String {
    if (!seesUpdates()) return ""
    val a = Updates.available() ?: return ""
    return """<p class="update-note">${h(productName)} ${h(a.version)} is out. <a href="${h(a.url)}">What's new</a> · <a href="/settings#updates">How to update</a></p>"""
}

/** Settings: the look, and the update check. */
internal fun Web.settingsPage(r: Web.Req) {
    if (r.method == "POST") {
        when (r.form["action"]) {
            "theme" -> {
                Theme.set(env, r.form["theme"].orEmpty())
                return redirect(r.ex, "/settings")
            }
            "home" -> {
                HomeSide.set(env, r.form["side"].orEmpty(), r.rawForm.filter { it.first == "card" }.map { it.second })
                return redirect(r.ex, "/settings?ok=${q("Saved.")}")
            }
            "updates" -> {
                if (!seesUpdates()) return send(r.ex, 403, page("Settings", NOT_ALLOWED))
                Updates.setEnabled(r.form["check"] == "on")
                return redirect(r.ex, "/settings?ok=${q("Saved.")}#updates")
            }
            "ticketmaster" -> {
                saveTicketmasterKey(r.form)
                return redirect(r.ex, "/settings?ok=${q("Saved.")}#ticketmaster")
            }
            "setlistfm" -> {
                saveSetlistfmKey(r.form)
                return redirect(r.ex, "/settings?ok=${q("Saved.")}#setlistfm")
            "discogs" -> {
                saveDiscogsToken(r.form)
                return redirect(r.ex, "/settings?ok=${q("Saved.")}#discogs")
            }
            "check" -> {
                if (!seesUpdates()) return send(r.ex, 403, page("Settings", NOT_ALLOWED))
                val answered = runBlocking { Updates.check() }
                val msg = when {
                    !answered -> "err=${q("GitHub did not answer: try again later.")}"
                    Updates.available() != null -> "ok=${q("A new version is out.")}"
                    else -> "ok=${q("This is the latest version.")}"
                }
                return redirect(r.ex, "/settings?$msg#updates")
            }
        }
        return redirect(r.ex, "/settings")
    }
    val body = StringBuilder(notice(r))
    val theme = Theme.of(env)
    body.append("""<form method="post" action="/settings" class="card"><input type="hidden" name="action" value="theme"><h3>Appearance</h3>""")
    Theme.ALL.forEach { (key, label) ->
        body.append("""<label class="check"><input type="radio" name="theme" value="$key"${if (key == theme) " checked" else ""}> ${h(label)}</label>""")
    }
    body.append("""<p class="muted">Kept for ${if (account != null) "your account" else "this ${if (edition == Html.Edition.DESKTOP) "computer" else "server"}"}, on every browser.</p><p><button>Save</button></p></form>""")
    val place = HomeSide.place(env)
    val cards = HomeSide.cards(env)
    body.append("""<form method="post" action="/settings" class="card"><input type="hidden" name="action" value="home"><h3>Home page</h3>
<p class="muted">A column next to the syncs, on a wide screen only (not on a phone).</p><label>Column</label>""")
    HomeSide.PLACES.forEach { (key, label) ->
        body.append("""<label class="check inline"><input type="radio" name="side" value="$key"${if (key == place) " checked" else ""}> ${h(label)}</label>""")
    }
    body.append("<label>Show</label>")
    HomeSide.CARDS.forEach { (key, label) ->
        body.append("""<label class="check"><input type="checkbox" name="card" value="$key"${if (key in cards) " checked" else ""}> ${h(label)}</label>""")
    }
    body.append("""<p><button>Save</button></p></form>""")
    if (env.may(Permissions.API)) {
        val n = Notify.channels(env).count { it.enabled }
        body.append("""<div class="card"><h3><a href="/settings/notifications">Notifications</a></h3><p class="muted">Telegram, ntfy, Gotify, Discord, Slack, Matrix or Pushover, when a sync changes something, fails or waits for you. ${if (n == 0) "None set up." else "$n ${if (n == 1) "channel" else "channels"} on."}</p></div>""")
    }
    body.append(ticketmasterKeyCard())
    body.append(setlistfmKeyCard())
    body.append(discogsTokenCard())
    if (seesUpdates()) body.append(updatesCard())
    body.append(
        """<div class="card"><h3>Language</h3><p class="muted">These pages are in English for now. The texts that come from the app (services, notes) follow ${
            if (edition == Html.Edition.DESKTOP) "the computer's language" else "<code>SONGPORT_LOCALE</code>"
        }.</p></div>""",
    )
    send(r.ex, 200, page("Settings", body.toString()))
}

private fun Web.updatesCard(): String = buildString {
    append("""<div class="card" id="updates"><h3>Updates</h3><p>This is ${h(productName)} ${h(Updates.version)}.</p>""")
    val by = Updates.updatedBy
    if (by != null) {
        append("""<p class="muted">Updates come from ${h(by)}: Trackhop does not check on its own.</p></div>""")
        return@buildString
    }
    val a = Updates.available()
    when {
        a != null -> append("""<p><strong>${h(a.version)} is out.</strong> <a href="${h(a.url)}">What's new</a></p>${howToUpdate(a.version)}""")
        Updates.enabled && Updates.checkedAt > 0 -> append("""<p class="muted">The latest version. Last checked ${h(Html.date(Updates.checkedAt))}.</p>""")
    }
    if (Updates.lockedOff) {
        append("""<p class="muted">The check is off: <code>SONGPORT_UPDATE_CHECK</code> is set to false.</p>""")
    } else {
        append(
            """<form method="post" action="/settings"><input type="hidden" name="action" value="updates">
<label class="check"><input type="checkbox" name="check"${if (Updates.enabled) " checked" else ""}> Tell me when a new version is out</label>
<p class="muted">Twice a day at most, Trackhop asks GitHub's public API which version is the latest: no account, nothing about you or your music.</p>
<p class="actions"><button class="secondary">Save</button></p></form>""",
        )
        if (Updates.enabled) append(Html.postButton("/settings", "Check now", "secondary", mapOf("action" to "check")))
    }
    append("</div>")
}

/** What to do for [version], where this Trackhop runs. */
private fun Web.howToUpdate(version: String): String =
    if (edition == Html.Edition.DESKTOP) {
        """<p>Download it and install it over this one: your syncs and sign-ins stay.</p>
<p class="actions"><a class="button" href="${h(Updates.RELEASES)}/tag/server-v${h(version)}">Download ${h(version)}</a> <a href="https://trackhop.org/guide/desktop#install">All the ways to install</a></p>"""
    } else {
        """<p>Pull the new image and start the container again: the data volume stays.</p>
<pre>docker pull ghcr.io/xlollx/trackhop:latest
docker stop trackhop &amp;&amp; docker rm trackhop
docker run … ghcr.io/xlollx/trackhop:latest   # the same command as the first time</pre>
<p class="muted">With Docker Compose: <code>docker compose pull &amp;&amp; docker compose up -d</code>. <a href="https://trackhop.org/guide/docker#update">More</a></p>"""
    }
