package com.xlollx.songport.server

import com.xlollx.songport.server.Html.h
import java.text.DateFormat
import java.util.Date

/** Tools › Lyrics (Docker only, for whoever runs the server): see [Lyrics]. */
internal fun Web.lyricsPage(r: Web.Req) {
    if (edition != Html.Edition.SERVER || !env.may(Permissions.ADMIN)) return send(r.ex, 403, page(Lyrics.TITLE, NOT_ALLOWED))
    val root = Lyrics.musicDir
    if (root == null) {
        return send(r.ex, 200, page(Lyrics.TITLE, """<div class="card"><p>Trackhop can save lyrics next to the music files of this server, where Navidrome, Jellyfin and Plex show them.</p>
<p>Give it the music folder: mount it in the container and name it in <code>SONGPORT_MUSIC</code>, then restart.</p>
<pre>    volumes:
      - /path/to/music:/music
    environment:
      - SONGPORT_MUSIC=/music</pre>
<p><a href="https://xlollx.github.io/Trackhop/guide/docker#lyrics">How it works</a></p></div>"""))
    }
    if (r.method == "POST") {
        when (r.form["action"]) {
            "weekly" -> {
                Lyrics.setWeekly(env, r.form["weekly"] == "on")
                return redirect(r.ex, "/tools/lyrics?ok=${q("Saved.")}")
            }
            else -> {
                if (Tasks.running(Lyrics.TITLE)) return redirect(r.ex, "/tools/lyrics?err=${q("Lyrics are already being looked for.")}")
                val folder = r.form["folder"].orEmpty().trim()
                if (Lyrics.target(root, folder) == null) return redirect(r.ex, "/tools/lyrics?err=${q("$folder is not a folder of the library.")}")
                val options = Lyrics.Options(folder, syncedOnly = r.form["synced"] == "on", retryMissing = r.form["retry"] == "on")
                val task = Tasks.start(Lyrics.TITLE, "/tools/lyrics", { env.log("lyrics", it) }) { t -> lyricsResult(Lyrics.run(env, options) { t.progress = it }) }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    body.append(
        """<div class="card"><p>For each music file in <code>${h(root.path)}</code> without lyrics, Trackhop asks <a href="https://lrclib.net">LRCLIB</a>, a free archive written by its users, and saves what it finds next to the file: synced lyrics as <code>.lrc</code>, plain ones as <code>.txt</code>. Navidrome, Jellyfin and Plex show them in their players after their next scan.</p>
<p class="muted">Files that have lyrics already, beside them or in their tags, are left as they are. For each file, its artist, title, album and length are sent to LRCLIB. Lyrics are protected by copyright: they are for your own listening.</p></div>""",
    )
    if (Tasks.running(Lyrics.TITLE)) body.append("""<div class="card"><p>Looking for lyrics now.</p></div>""")
    body.append(
        """<form method="post" action="/tools/lyrics" class="card"><input type="hidden" name="action" value="run">
<label>Folder inside the library (empty: all of it)</label><input type="text" name="folder" placeholder="Queen/Jazz">
<label><input type="checkbox" name="synced"> Synced lyrics only</label>
<label><input type="checkbox" name="retry"> Ask again for the files not found in the last month</label>
<p><button>Find lyrics</button></p></form>""",
    )
    val weekly = if (Lyrics.weekly(env)) " checked" else ""
    body.append(
        """<form method="post" action="/tools/lyrics" class="card"><input type="hidden" name="action" value="weekly">
<label><input type="checkbox" name="weekly"$weekly> Every week, for the files added since</label>
<p><button class="secondary">Save</button></p></form>""",
    )
    Lyrics.lastRun(env)?.let { s ->
        body.append("""<div class="card"><h3>Last time</h3><p class="muted">${h(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.at)))}</p>${counts(s)}</div>""")
    }
    send(r.ex, 200, page(Lyrics.TITLE, body.toString()))
}

private fun counts(s: Lyrics.Summary): String = buildString {
    append("<ul>")
    append("<li>${s.files} music files</li>")
    append("<li>${s.saved} lyrics saved (${s.synced} synced, ${s.plain} plain)</li>")
    if (s.had > 0) append("<li>${s.had} had lyrics already</li>")
    if (s.instrumental > 0) append("<li>${s.instrumental} instrumentals</li>")
    append("<li>${s.missing} not found</li>")
    if (s.unreadable > 0) append("<li>${s.unreadable} without artist and title (tags or name)</li>")
    append("</ul>")
}

/** The result page of a run: the counts, and the files LRCLIB did not know. */
internal fun lyricsResult(s: Lyrics.Summary): String = buildString {
    append("""<div class="card ok"><h3>Lyrics</h3>${counts(s)}</div>""")
    if (s.missingPaths.isNotEmpty()) {
        append("""<div class="card"><h3>Not found</h3><p class="muted">Tagged differently from LRCLIB, or not there yet: anyone can add lyrics on lrclib.net.</p><ul>""")
        s.missingPaths.take(200).forEach { append("<li>${h(it)}</li>") }
        if (s.missingPaths.size > 200) append("<li>… and ${s.missingPaths.size - 200} more</li>")
        append("</ul></div>")
    }
}
