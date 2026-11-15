package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.data.FileStore
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.SavedMatches

/**
 * Tools › Saved matches (1.18), under /tools/matches: the app's list of what Trackhop remembers across
 * all syncs ([SavedMatches]), read-only here but for Forget. The names are those the runs' reports
 * hold; a song they do not name shows its id. Changing a match is done in the app or in a run's review.
 */
internal fun Web.savedMatchesPage(r: Web.Req) {
    val title = "Saved matches"
    val data = env.store.data
    val entries = SavedMatches.entries(data)
    if (r.method == "POST") {
        val e = entries.firstOrNull { it.key == r.form["key"] } ?: return redirect(r.ex, "/tools/matches?err=${q("No longer remembered.")}")
        SavedMatches.forget(env.store, e)
        return redirect(r.ex, "/tools/matches?ok=${q("Forgotten: the next sync searches it again.")}")
    }
    val names = SavedMatches.names(data)
    val query = r.query["q"].orEmpty()
    val kind = r.query["kind"]?.let { k -> SavedMatches.Kind.entries.firstOrNull { it.name.equals(k, ignoreCase = true) } }
    val to = r.query["to"]?.takeIf { it.isNotBlank() }
    val list = entries.filter { (to == null || it.targetService == to) && (kind == null || it.kind == kind) && SavedMatches.matches(it, names, query) }
    fun service(family: String) = family.substringBefore('#').let { fam -> Providers.all().firstOrNull { FileStore.family(it.id) == fam }?.displayName ?: fam }
    fun song(t: Track?, id: String) = t?.let { "${h(it.title)}${if (it.artists.isNotEmpty()) " · " + h(it.artistLine) else ""}" } ?: """<span class="muted">${h(id)}</span>"""
    val body = StringBuilder(notice(r))
    body.append("""<div class="card"><p class="muted">Everything Trackhop remembers across your syncs: a song and the one chosen for it on the other service, a song not found, a song ignored. Forget one and the next sync searches it again; to choose another song, use the app or a run's review.</p>""")
    val kinds = listOf(null to "All", SavedMatches.Kind.CHOSEN to "Chosen by you", SavedMatches.Kind.NOT_FOUND to "Not found", SavedMatches.Kind.IGNORED to "Ignored")
    val targets = entries.map { it.targetService }.distinct()
    body.append("""<form method="get" action="/tools/matches"><input type="search" name="q" value="${h(query)}" placeholder="Search a song or an artist"> <select name="kind">""")
    kinds.forEach { (k, label) -> body.append("""<option value="${k?.name.orEmpty()}"${if (k == kind) " selected" else ""}>${h(label)} (${entries.count { k == null || it.kind == k }})</option>""") }
    body.append("""</select> <select name="to"><option value="">To: all services</option>""")
    targets.forEach { t -> body.append("""<option value="${h(t)}"${if (t == to) " selected" else ""}>To: ${h(service(t))}</option>""") }
    body.append("""</select> <button>Show</button></form></div>""")
    if (list.isEmpty()) body.append("""<div class="card">${if (entries.isEmpty()) "Nothing remembered yet: the list fills up as your syncs run." else "Nothing matches these filters."}</div>""")
    else {
        body.append("""<div class="card"><ul>""")
        list.take(SHOWN).forEach { e ->
            val target = when (e.kind) {
                SavedMatches.Kind.NOT_FOUND -> "no song found"
                SavedMatches.Kind.IGNORED -> "ignored"
                else -> song(names[SavedMatches.nameKey(e.target, e.chosenId!!)], e.chosenId!!)
            }
            val tag = when (e.kind) { SavedMatches.Kind.CHOSEN -> "Chosen by you"; SavedMatches.Kind.AUTO -> "Automatic"; SavedMatches.Kind.NOT_FOUND -> "Not found"; SavedMatches.Kind.IGNORED -> "Ignored" }
            body.append("<li>${h(service(e.source))}: ${song(names[SavedMatches.nameKey(e.source, e.sourceId)], e.sourceId)} → ${h(service(e.target))}: $target <span class=\"badge\">$tag</span> ")
            body.append(Html.postButton("/tools/matches", "Forget", "secondary", fields = mapOf("key" to e.key)))
            body.append("</li>")
        }
        body.append("</ul>")
        if (list.size > SHOWN) body.append("""<p class="muted">…and ${list.size - SHOWN} more: search to find them.</p>""")
        body.append("</div>")
    }
    send(r.ex, 200, page(title, body.toString()))
}

private const val SHOWN = 300
