package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.NamePatterns
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.runBlocking

/**
 * The File service's playlists (CSV files in the data folder) and the Tools that move playlists in
 * and out as files, as the app's Tools › File playlists and Export a playlist do: upload a file in
 * any format Trackhop reads, download one as CSV, M3U, XSPF, JSPF or plain text, delete one, and
 * export any service's playlist the same way.
 */
internal enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    CSV("CSV", "csv", "text/csv"),
    M3U("M3U", "m3u", "audio/x-mpegurl"),
    XSPF("XSPF", "xspf", "application/xspf+xml"),
    JSPF("JSPF (ListenBrainz)", "jspf", "application/json"),
    TEXT("Text", "txt", "text/plain"),
    ;

    fun encode(name: String, tracks: List<Track>): String = when (this) {
        CSV -> CsvCodec.encode(tracks)
        M3U -> PlaylistFiles.toM3u(tracks)
        XSPF -> PlaylistFiles.toXspf(name, tracks)
        JSPF -> PlaylistFiles.toJspf(name, tracks)
        TEXT -> PlaylistFiles.toText(tracks)
    }

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.ext == s?.lowercase() } ?: CSV
    }
}

internal fun Web.csvFiles() = LocalFilesProvider.dir(env).listFiles { f -> f.isFile && f.extension == "csv" }.orEmpty().sortedBy { it.name.lowercase() }

internal fun Web.fileList(sample: String = ""): String {
    val files = csvFiles()
    val sb = StringBuilder("""<div class="card">""")
    if (files.isEmpty()) sb.append("""<p class="muted">No playlist files yet.</p>""")
    for (f in files) {
        val n = CsvCodec.parse(f.readText()).size
        val links = ExportFormat.entries.joinToString(" · ") { """<a href="/files/${q(f.name)}?format=${it.ext}" download>${it.label}</a>""" }
        sb.append(
            // The box belongs to the "bulk" form below (a form cannot hold the row's own Delete form).
            """<div class="row"><label class="inline"><input type="checkbox" form="bulk" name="f:${h(f.name)}" aria-label="Select ${h(f.nameWithoutExtension)}"></label>""" +
                """<div><b>${h(f.nameWithoutExtension)}</b> <span class="muted">$n tracks</span><br><span class="muted">Download: </span>$links · <a href="/syncs/new?src=csv&amp;srcPlaylist=${q(f.nameWithoutExtension)}">Send to a service</a></div>""" +
                Html.postButton("/files/${q(f.name)}", "Delete", "danger", fields = mapOf("action" to "delete"), confirm = "Delete ${f.nameWithoutExtension}? A sync that uses it stops finding it.") +
                "</div>",
        )
    }
    if (files.size > 1) sb.append(
        """<form id="bulk" method="post" action="/files/bulk" onsubmit="return event.submitter.value != 'delete' || confirm('Delete the ticked files? Syncs that use them stop finding them.')">""" +
            """<p class="muted">With the ticked files: <select name="format" aria-label="Format">""" +
            ExportFormat.entries.joinToString("") { """<option value="${it.ext}">${it.label}</option>""" } +
            """</select> <button class="secondary" name="action" value="zip">Download as .zip</button> """ +
            """<button class="danger" name="action" value="delete">Delete</button></p></form>""",
    )
    sb.append(
        """<p class="muted">Playlists saved inside Trackhop. A sync whose target is File writes its playlist here; a file uploaded here can be the source of a sync to any service.</p></div>
<form method="post" action="/files" enctype="multipart/form-data" class="card"><label>Add a playlist file: CSV, M3U, PLS, XSPF, JSPF or JSON, iTunes XML, OPML, or a text list of “Artist - Title”</label>
<input type="file" name="file" required>
<label>Names like “A - B - C” in M3U, PLS and text files</label><select name="names">${nameLayoutOptions()}</select>
<p class="muted">Automatic reads them as Artist - Album - Title when most lines have three parts. A file already added is not changed: upload it again.</p>
<details${if (env.store.data.settings.fileNameLayout == "CUSTOM") " open" else ""}><summary>Your own patterns</summary>
<p class="muted">Used when “Your own patterns” is chosen above. One per line, tried in order; a line none of them reads is read as usual.
Read: <code>{artist}</code>, <code>{album}</code>, <code>{title}</code> (needed), <code>{albumartist}</code>. Skipped: any other <code>{name}</code>, such as <code>{track}</code>, <code>{year}</code> or <code>{disc}</code>; <code>*</code> is any text, <code>**/</code> any folders.
<code>[ ]</code> marks an optional part (<code>\[ \]</code> are brackets), <code>/</code> goes between folders; spaces, dashes and capitals are loose; the pattern is matched against the end of the path.
Examples: <code>{artist}/{album}/{track} {title}</code> · <code>Music/{artist}/**/{year} - {album}/[Disc {disc}/]{track} {title}</code> · <code>{artist} - {album} - {track} {title}</code> · <code>{title} ({artist})</code>.
Or a regular expression after <code>re:</code> with named groups, such as <code>re:^(?&lt;artist&gt;.+?) _ (?&lt;title&gt;.+)$</code>.</p>
<textarea name="patterns" rows="4" spellcheck="false" style="font-family:monospace">${h(env.store.data.settings.fileNamePatterns.joinToString("\n"))}</textarea>
<label>Try a line from your file</label><input name="sample" value="${h(sample)}" spellcheck="false">
<p><button class="secondary" formaction="/files/try" formnovalidate>Try the patterns</button></p></details>
<p><button>Upload</button></p></form>""",
    )
    return sb.toString()
}

/** One of [csvFiles], by its exact name: nothing else in the data folder can be asked for. */
internal fun Web.download(r: Web.Req, name: String) {
    val f = csvFiles().firstOrNull { it.name == name } ?: return send(r.ex, 404, page("Not found", """<p><a href="/connectors/csv">Back</a></p>"""))
    val format = ExportFormat.of(r.query["format"])
    val text = if (format == ExportFormat.CSV) f.readText() else format.encode(f.nameWithoutExtension, CsvCodec.parse(f.readText()))
    attachment(r, "${f.nameWithoutExtension}.${format.ext}", text, format.mime)
}

internal fun Web.attachment(r: Web.Req, fileName: String, text: String, mime: String) {
    val ascii = fileName.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
    r.ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"$ascii\"; filename*=UTF-8''${q(fileName).replace("+", "%20")}")
    send(r.ex, 200, text, mime)
}

/** The ticked files of the list: deleted, or downloaded together as a .zip in one format. */
internal fun Web.bulkFiles(r: Web.Req) {
    val names = r.form.filter { (k, v) -> k.startsWith("f:") && v.isNotEmpty() }.keys.map { it.removePrefix("f:") }.toSet()
    val files = csvFiles().filter { it.name in names }
    if (files.isEmpty()) return redirect(r.ex, "/connectors/csv?err=${q("Tick the files first.")}")
    when (r.form["action"]) {
        "delete" -> {
            files.forEach { it.delete() }
            env.log("files", "deleted ${files.size} files")
            redirect(r.ex, "/connectors/csv?ok=${q("Deleted ${files.size} files.")}")
        }
        "zip" -> {
            val bytes = zipOf(files, ExportFormat.of(r.form["format"]))
            r.ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"Trackhop playlists.zip\"")
            sendBytes(r.ex, 200, bytes, "application/zip")
        }
        else -> redirect(r.ex, "/connectors/csv")
    }
}

internal fun Web.fileAction(r: Web.Req, name: String) {
    val f = csvFiles().firstOrNull { it.name == name } ?: return redirect(r.ex, "/connectors/csv?err=${q("No such file.")}")
    when (r.form["action"]) {
        "delete" -> { f.delete(); redirect(r.ex, "/connectors/csv?ok=${q("Deleted ${f.nameWithoutExtension}.")}") }
        else -> redirect(r.ex, "/connectors/csv")
    }
}

/** The choices for "A - B - C" names (#49), the saved one selected. */
private fun Web.nameLayoutOptions(): String {
    val saved = PlaylistFiles.NameLayout.of(env.store.data.settings.fileNameLayout)
    return listOf(
        PlaylistFiles.NameLayout.AUTO to "Automatic",
        PlaylistFiles.NameLayout.ARTIST_TITLE to "Artist - Title",
        PlaylistFiles.NameLayout.ARTIST_ALBUM_TITLE to "Artist - Album - Title",
        PlaylistFiles.NameLayout.CUSTOM to "Your own patterns",
    ).joinToString("") { (v, label) -> """<option value="${v.name}"${if (v == saved) " selected" else ""}>${h(label)}</option>""" }
}

/** The patterns in the form, saved when they all compile, tried on the sample line: what each field reads. */
internal fun Web.tryPatterns(r: Web.Req) {
    val lines = r.form["patterns"].orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
    val sample = r.form["sample"].orEmpty().trim()
    val back = "/connectors/csv?sample=${q(sample)}"
    lines.forEachIndexed { i, l ->
        NamePatterns.compile(l).exceptionOrNull()?.let { return redirect(r.ex, "$back&err=${q("Pattern on line ${i + 1}: ${it.message}")}") }
    }
    env.store.updateSettings { it.copy(fileNamePatterns = lines) }
    if (sample.isEmpty()) return redirect(r.ex, "$back&ok=${q("Patterns saved. Write a line to try them on.")}")
    val read = NamePatterns.read(sample, NamePatterns.compileAll(lines))
        ?: return redirect(r.ex, "$back&err=${q("No pattern reads this line: it would be read as usual.")}")
    redirect(r.ex, "$back&ok=${q("Artist: ${read.artist.ifEmpty { "–" }} · Album: ${read.album.ifEmpty { "–" }} · Title: ${read.title}")}")
}

internal fun Web.upload(r: Web.Req) {
    val file = r.files["file"] ?: return redirect(r.ex, "/connectors/csv?err=${q("Choose a file.")}")
    // The choice is kept for the next upload, as the app keeps it; bad patterns are refused, with why.
    r.form["patterns"]?.let { raw ->
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lines.forEachIndexed { i, l ->
            NamePatterns.compile(l).exceptionOrNull()?.let { return redirect(r.ex, "/connectors/csv?err=${q("Pattern on line ${i + 1}: ${it.message}")}") }
        }
        env.store.updateSettings { it.copy(fileNamePatterns = lines) }
    }
    r.form["names"]?.let { PlaylistFiles.NameLayout.of(it).name }?.let { v ->
        if (v == "CUSTOM" && env.store.data.settings.fileNamePatterns.isEmpty()) return redirect(r.ex, "/connectors/csv?err=${q("Write at least one pattern under “Your own patterns”.")}")
        env.store.updateSettings { it.copy(fileNameLayout = v) }
    }
    val text = PlaylistFiles.decode(file.bytes)
    val n = runCatching { LocalFilesProvider.importFile(env, file.name.substringAfterLast('/').substringAfterLast('\\'), text) }
        .getOrElse { return redirect(r.ex, "/connectors/csv?err=${q(it.message ?: "Could not read the file.")}") }
    if (n == 0) return redirect(r.ex, "/connectors/csv?err=${q("No tracks found in ${file.name}.")}")
    env.log("files", "imported ${file.name}: $n tracks")
    redirect(r.ex, "/connectors/csv?ok=${q("Added ${file.name}: $n tracks.")}")
}

// ------------------------------------------------------------------ tools

internal fun Web.tools(r: Web.Req, rest: List<String>) {
    when (rest.firstOrNull()) {
        null -> toolsIndex(r)
        "export" -> exportTool(r)
        "task" -> taskPage(r, rest.getOrNull(1).orEmpty())
        "backup", "dedupe", "unplayable", "mbtags" -> toolPage(r, rest[0])
        "ai" -> aiTool(r)
        "stats" -> statsPage(r)
        "api" -> apiPage(r)
        "config" -> configPage(r)
        "lyrics" -> lyricsPage(r)
        "releases" -> releasesTool(r)
        "wanted" -> wantedPage(r)
        "concerts" -> concertsPage(r)
        "setlists" -> setlistsPage(r)
        "records" -> recordsPage(r)
        else -> if (!v2Tool(r, rest)) send(r.ex, 404, page("Not found", """<p><a href="/tools">Back</a></p>"""))
    }
}

private fun Web.toolsIndex(r: Web.Req) {
    val body = StringBuilder(notice(r))
    fun tool(href: String, title: String, text: String) =
        body.append("""<div class="card"><h3><a href="$href">$title</a></h3><p class="muted">$text</p></div>""")
    body.append("<h2>Move</h2>")
    tool("/playlists", "Playlists and transfer", "See a service's playlists and their tracks, rename, delete, take tracks out; or copy several to another service at once.")
    tool("/tools/export", "Export a playlist", "Any playlist of a connected service as CSV, M3U, XSPF, JSPF or plain text, or saved into File.")
    tool("/connectors/csv", "File playlists", "Upload a playlist file to sync it anywhere; download the ones Trackhop saved.")
    tool("/tools/wanted", "Missing on my server", "The tracks of your playlists that your Navidrome, Jellyfin, Emby, Plex, Lyrion or Mopidy server does not have, each once: by album, tick what you got, download the list (CSV, M3U, text, Lidarr).")
    tool("/tools/records", "Records and merch", "The CDs, vinyl and cassettes of a playlist's artists on Discogs, the merch in their own shops, their official sites and shops, and searches on shops: plain links.")
    tool("/tools/concerts", "Concerts", "The upcoming concerts of a playlist's artists in a country, from Ticketmaster and MusicBrainz, with links to the tickets.")
    tool("/tools/setlists", "Setlists", "The latest setlists of a playlist's artists, from setlist.fm: pick a concert and make it a playlist on any service.")
    tool("/tools/stats", "Your numbers", "What your syncs did: tracks moved, time saved, the busiest route, the most added artists.")
    body.append(v2ToolCards())
    body.append("<h2>Make</h2>")
    tool("/tools/releases", "New releases", "The new albums, EPs and singles of a playlist's artists (Deezer and MusicBrainz, no account): as a playlist on any service, now or every week.")
    tool("/tools/ai", "Playlist generator", "From artists you like, with no key (MusicBrainz and ListenBrainz data); or describe a playlist and your own AI writes it (free tiers and local models work). Trackhop creates it on a service.")
    body.append("<h2>Clean up</h2>")
    tool("/tools/dedupe", "Duplicates", "The tracks a playlist has more than once, and the extra copies removed.")
    tool("/tools/unplayable", "Unplayable tracks", "The tracks a service still lists but no longer plays, replaced by a playable version.")
    tool("/tools/mbtags", "MusicBrainz tags", "The songs on your Navidrome, Jellyfin, Emby or Plex server without MusicBrainz tags: with them, a sync finds the exact recording.")
    if (edition == Html.Edition.SERVER && env.may(Permissions.ADMIN)) {
        body.append("<h2>Your library</h2>")
        tool("/tools/lyrics", "Lyrics", "Lyrics for the music files of your server, saved next to them, where Navidrome, Jellyfin and Plex show them.")
    }
    body.append("<h2>Connect</h2>")
    tool("/tools/api", "API and webhook", "Let Home Assistant, n8n or a script start syncs and read how they went; be told when a run ends.")
    body.append("<h2>Safety</h2>")
    tool("/tools/backup", "Backup &amp; restore", "Your set-up (syncs, settings, chosen matches) in one file to keep or move between the app, Docker and Desktop; your playlists as dated files, now or on a schedule, copied to a folder.")
    send(r.ex, 200, page("Tools", body.toString()))
}

private fun Web.exportTool(r: Web.Req) {
    val readable = Providers.connectors().filter { (!it.requiresAuth || it.isConnected(env)) && it !is LocalFilesProvider && !it.linkOnly }
    // Downloads are GETs; saving into File changes something, so it is a POST (the form's second button).
    val params = if (r.method == "POST") r.form else r.query
    val service = params["service"]?.let { id -> readable.firstOrNull { it.id == id } }
    val body = StringBuilder(notice(r))
    if (readable.isEmpty()) {
        body.append("""<div class="card">Connect a service first: <a href="/connectors">Services</a>.</div>""")
        return send(r.ex, 200, page("Export a playlist", body.toString()))
    }
    if (service == null) {
        val opts = readable.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/export" class="card"><label>From</label><select name="service">$opts</select><p><button>Next</button></p></form>""")
        return send(r.ex, 200, page("Export a playlist", body.toString()))
    }
    val lists = runCatching { runBlocking { service.libraryEntries(env, false) + service.playlists(env) } }
    lists.exceptionOrNull()?.let { body.append("""<div class="card err">${h(service.label(env))}: ${h(it.message)}</div>""") }
    val playlistId = params["playlist"]
    val playlist = playlistId?.let { id -> lists.getOrNull()?.firstOrNull { it.id == id } }
    if (playlist != null) {
        // Only playlists the service listed: an id is never typed in.
        val tracks = runCatching { runBlocking { service.tracks(env, playlist.id) } }
            .getOrElse { return redirect(r.ex, "/tools/export?service=${q(service.id)}&err=${q(it.message ?: "Error")}") }
        if (r.method == "POST" && params["to"] == "file") {
            val id = LocalFilesProvider.importTracks(env, playlist.name, tracks)
            return redirect(r.ex, "/connectors/csv?ok=${q("Saved ${playlist.name} into File as $id (${tracks.size} tracks).")}")
        }
        val format = ExportFormat.of(params["format"])
        return attachment(r, "${playlist.name}.${format.ext}", format.encode(playlist.name, tracks), format.mime)
    }
    val opts = lists.getOrDefault(emptyList()).joinToString("") { p -> """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
    val formats = ExportFormat.entries.joinToString("") { """<option value="${it.ext}">${h(it.label)}</option>""" }
    body.append(
        """<form method="get" action="/tools/export" class="card"><input type="hidden" name="service" value="${h(service.id)}">
<label>Playlist on ${h(service.label(env))}</label><select name="playlist" required>$opts</select>
<label>Format</label><select name="format">$formats</select>
<p><button>Download</button> <button class="secondary" name="to" value="file" formmethod="post">Save into File</button> <a class="button secondary" href="/tools/export">Back</a></p></form>""",
    )
    send(r.ex, 200, page("Export a playlist", body.toString()))
}
