package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ItunesLibrary
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Big playlist files on Desktop and Docker (#67): the upload is written to disk as it arrives, then
 * read a piece at a time, whatever its format; an Apple Music/iTunes library offers its playlists to
 * pick from. The only limit is the space left on the disk.
 */
object LibraryUploads {
    /** What one upload may take on disk; the free space is checked too. */
    const val MAX_UPLOAD = 2_000_000_000L
    private const val KEEP_MS = 60 * 60 * 1000L
    private const val KEEP_FREE = 200_000_000L

    class Pending(val owner: String, val fileName: String, val library: ItunesLibrary, val at: Long = System.currentTimeMillis())

    private val pending = ConcurrentHashMap<String, Pending>()
    private val random = SecureRandom()

    fun dir(env: ServerEnv) = File(env.dataDir, "uploads")

    /** How much this upload may write: [MAX_UPLOAD], less when the disk has less to spare. */
    fun budget(dir: File): Long {
        dir.mkdirs()
        // Leftovers of an upload cut short: gone after an hour.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        return minOf(MAX_UPLOAD, (dir.usableSpace - KEEP_FREE).coerceAtLeast(0))
    }

    /** A library read from [file]: the file is deleted either way. */
    fun readLibrary(file: File): ItunesLibrary = try {
        file.inputStream().buffered().use { read(it) }
    } finally { file.delete() }

    fun read(input: InputStream): ItunesLibrary =
        ItunesLibrary.read(input.reader(Charsets.UTF_8).buffered(64 * 1024).also { r ->
            // A BOM in front of the XML is not part of it.
            r.mark(1); if (r.read() != '﻿'.code) r.reset()
        })

    /** True when [file] starts like an Apple plist. */
    fun isLibrary(file: File): Boolean = file.inputStream().use { s ->
        ItunesLibrary.looksLike(String(s.readNBytes(4096), Charsets.UTF_8))
    }

    fun keep(p: Pending): String {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.at > KEEP_MS }
        // A few at a time for each user: a library holds its songs' names in memory until it is used.
        pending.entries.filter { it.value.owner == p.owner }.sortedBy { it.value.at }.dropLast(2).forEach { pending.remove(it.key) }
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        pending[token] = p
        return token
    }

    fun get(token: String, owner: String): Pending? = pending[token]?.takeIf { it.owner == owner && System.currentTimeMillis() - it.at <= KEEP_MS }
    fun drop(token: String) { pending.remove(token) }

    private val adding: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True for the one request that may add from [token] now; a second click while it runs gets false. [release] after. */
    fun claim(token: String): Boolean = adding.add(token)
    fun release(token: String) { adding.remove(token) }

    /** The playlists [names] of [lib] (all of the person's own when empty) saved as files; with [whole], the whole library too. */
    fun add(env: ServerEnv, lib: ItunesLibrary, fileName: String, picked: List<ItunesLibrary.Playlist>, whole: Boolean, out: MutableList<Pair<String, Int>> = ArrayList()): List<Pair<String, Int>> {
        for (p in picked) {
            val tracks = lib.tracksOf(p)
            if (tracks.isEmpty()) continue
            out += LocalFilesProvider.importTracks(env, p.name, tracks) to tracks.size
        }
        if (whole) lib.asOneList().takeIf { it.isNotEmpty() }?.let { out += LocalFilesProvider.importTracks(env, baseName(fileName), it) to it.size }
        return out
    }

    fun baseName(fileName: String) = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').ifBlank { "Library" }
}

/** POST /files with a multipart form: the file onto disk first (see [LibraryUploads]). */
internal fun Web.uploadToDisk(ex: HttpExchange, type: String) {
    val dir = LibraryUploads.dir(env)
    val budget = LibraryUploads.budget(dir)
    ex.requestHeaders.getFirst("Content-Length")?.toLongOrNull()?.let {
        if (it > budget + MultipartToDisk.MAX_FIELD) return redirect(ex, "/connectors/csv?err=${q("This file is too big for the space left here.")}")
    }
    val parts = try {
        MultipartToDisk.read(type, ex.requestBody, dir, budget)
    } catch (e: MultipartToDisk.TooLarge) {
        return redirect(ex, "/connectors/csv?err=${q("This file is too big for the space left here.")}")
    } catch (e: Exception) {
        // Cut short (the browser closed, the network dropped): what arrived is deleted, nothing is added.
        return runCatching { redirect(ex, "/connectors/csv?err=${q("The upload stopped before the end, so nothing was added: try again.")}") }.getOrElse { }
    }
    val file = parts.files["file"]
    // The answer is worked out first and sent only once the upload is off the disk: whoever reads it
    // (a browser, a script, a test) never finds the file still there. Sent from inside, the page could
    // arrive before the delete below, and on Windows a check of the folder found the file (2.1.1).
    val to = try {
        if (file == null) "/connectors/csv?err=${q("Choose a file.")}"
        else nameChoiceRefused(parts.fields)
            ?: if (LibraryUploads.isLibrary(file.file)) addLibrary(file) else file.file.inputStream().use { importUploaded(file.name, it) }
    } finally {
        // Every part that came as a file, not only the one used.
        parts.files.values.forEach { it.file.delete() }
    }
    redirect(ex, to)
}

/** An uploaded Apple Music/iTunes library: added whole when small, else kept to pick from. @return the page to go to. */
private fun Web.addLibrary(file: MultipartToDisk.DiskFile): String {
    val lib = runCatching { LibraryUploads.readLibrary(file.file) }
        .getOrElse { return "/connectors/csv?err=${q("Nothing was added from ${file.name}: ${it.message ?: "it is not an Apple Music/iTunes file"}")}" }
    if (!lib.worthPicking) {
        val added = LibraryUploads.add(env, lib, file.name, emptyList(), whole = true)
        val n = added.sumOf { it.second }
        if (n == 0) return "/connectors/csv?err=${q("No tracks found in ${file.name}.")}"
        env.log("files", "imported ${file.name}: $n tracks")
        return "/connectors/csv?ok=${q("Added ${file.name}: $n tracks.")}"
    }
    val token = LibraryUploads.keep(LibraryUploads.Pending(env.owner, file.name, lib))
    return "/files/library?t=$token"
}

/** GET /files/library?t=…: the playlists of an uploaded library, to tick. */
internal fun Web.libraryPage(r: Web.Req) {
    val token = r.query["t"].orEmpty()
    val p = LibraryUploads.get(token, env.owner)
        ?: return redirect(r.ex, "/connectors/csv?err=${q("That library is no longer here (an hour has passed): upload it again.")}")
    val lib = p.library
    val sb = StringBuilder()
    sb.append(notice(r)).append("""<h1>${h(p.fileName)}</h1>
<p class="muted">${"%,d".format(lib.trackCount)} tracks · ${lib.choices.size} playlists. Tick the ones to add: each becomes a file of its own.
${if (lib.choices.size > 1) """ <a href="/files/library?t=${q(token)}&amp;sel=all">Select all</a> · <a href="/files/library?t=${q(token)}&amp;sel=none">None</a>""" else ""}</p>
<form method="post" action="/files/library" class="card"><input type="hidden" name="t" value="${h(token)}">""")
    fun row(i: Int, pl: ItunesLibrary.Playlist, on: Boolean) =
        """<label class="check"><input type="checkbox" name="p" value="$i"${if (on) " checked" else ""}> ${h(pl.name)} <span class="muted">${"%,d".format(pl.size)}${if (pl.kind == ItunesLibrary.Kind.SMART) " · smart" else ""}</span></label>"""
    val choices = lib.choices
    // Select all / None (links): every playlist ticked, the smart ones too, or none; otherwise your own ones.
    val sel = r.query["sel"]
    val own = choices.withIndex().filter { it.value.kind == ItunesLibrary.Kind.USER }
    val smart = choices.withIndex().filter { it.value.kind == ItunesLibrary.Kind.SMART }
    var folder: String? = null
    for ((i, pl) in own) {
        if (pl.folder != folder) { folder = pl.folder; if (folder != null) sb.append("<p class=\"muted\"><b>Folder · ${h(folder)}</b></p>") }
        sb.append(row(i, pl, sel != "none"))
    }
    if (smart.isNotEmpty()) {
        sb.append("<p class=\"muted\"><b>Smart</b></p>")
        smart.forEach { (i, pl) -> sb.append(row(i, pl, sel == "all")) }
    }
    sb.append("""<p class="muted"><b>All at once</b></p>
<label class="check"><input type="checkbox" name="whole" value="1"> The whole library as one file <span class="muted">${"%,d".format(lib.trackCount)}</span></label>
<p><button>Add the ticked playlists</button> <a href="/connectors/csv">Cancel</a></p>
<p class="muted">The library is kept here for an hour, then forgotten; the uploaded file itself is already deleted.</p></form>""")
    send(r.ex, 200, page("Add playlists", sb.toString()))
}

/** POST /files/library: the ticked playlists saved as files. */
internal fun Web.libraryAdd(r: Web.Req) {
    val token = r.form["t"].orEmpty()
    val p = LibraryUploads.get(token, env.owner)
        ?: return redirect(r.ex, "/connectors/csv?err=${q("That library is no longer here (an hour has passed): upload it again.")}")
    val picked = r.rawForm.filter { it.first == "p" }.mapNotNull { it.second.toIntOrNull() }.distinct().mapNotNull { p.library.choices.getOrNull(it) }
    val whole = r.form["whole"] == "1"
    if (picked.isEmpty() && !whole) return redirect(r.ex, "/files/library?t=${q(token)}&err=${q("Tick at least one playlist.")}")
    // One adding at a time from this library: a double click does not save every playlist twice.
    if (!LibraryUploads.claim(token)) return redirect(r.ex, "/connectors/csv?err=${q("Those playlists are already being added.")}")
    val added = ArrayList<Pair<String, Int>>()
    try {
        val failed = runCatching { LibraryUploads.add(env, p.library, p.fileName, picked, whole, added) }.exceptionOrNull()
        if (failed != null) {
            // Kept for another try: the ones already added stay, the rest can be ticked again.
            val done = if (added.isEmpty()) "Nothing was added" else "Added ${added.joinToString(", ") { it.first }}, then it stopped"
            return redirect(r.ex, "/files/library?t=${q(token)}&err=${q("$done: ${failed.message ?: "could not write the file"}.")}")
        }
        LibraryUploads.drop(token)
    } finally {
        LibraryUploads.release(token)
    }
    env.log("files", "imported ${added.size} playlists from ${p.fileName}")
    val names = added.joinToString(", ") { it.first }
    redirect(r.ex, "/connectors/csv?ok=${q(if (added.size == 1) "Added $names: ${added[0].second} tracks." else "Added ${added.size} playlists: $names.")}")
}
