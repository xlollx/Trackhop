package com.xlollx.songport.server

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.ArtistPick
import com.xlollx.songport.sync.Concerts
import kotlinx.coroutines.runBlocking

/**
 * The artists a Discover search looks up (the app's ArtistChooser, [ArtistPick]): the playlist's,
 * the ones it holds most first, or only those ticked, plus any added by name. Plain forms: "Pick
 * the artists…" draws the playlist's artists as boxes, with a field that narrows them.
 */
internal object ArtistPicks {
    /** The artists the form or the call named: ticked boxes ("artist") and names separated by commas ("artists"); null for the whole playlist. */
    fun of(ticked: List<String>, typed: String?): Set<String>? =
        (ticked + typed.orEmpty().split(',')).map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.toSet().ifEmpty { null }

    /** The artists to look up for [tracks] with [picked] (null: the playlist's own, cut later to the search's cap). */
    fun chosen(tracks: List<Track>, picked: Set<String>?): List<String> {
        val all = Concerts.artistsOf(tracks)
        // Spelled as the playlist spells them, where it has them (case and accents ignored).
        return ArtistPick.chosen(all, picked?.fold(emptySet<String>()) { acc, n -> ArtistPick.add(all, acc, n) })
    }
}

internal fun Web.pickedArtists(r: Web.Req): Set<String>? =
    ArtistPicks.of((r.rawForm.takeIf { it.isNotEmpty() } ?: r.ex.requestURI.rawQuery.orEmpty().split('&').map { java.net.URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) })
        .filter { it.first == "artist" }.map { it.second }, (if (r.method == "POST") r.form else r.query)["artists"])

/**
 * The form's artist part: a "Pick the artists…" button (the same form, sent as a GET), or, once
 * asked, the playlist's artists as boxes (alphabetical) with a field to narrow them and one to add a
 * name. [action] is the page's address.
 */
internal fun Web.artistPickFields(r: Web.Req, p: MusicProvider, playlistId: String?): String {
    val params = if (r.method == "POST") r.form else r.query
    val picked = pickedArtists(r).orEmpty()
    if (params["pick"] != "1" || playlistId == null) {
        return """<label for="artists">Only these artists (optional)</label><input type="text" id="artists" name="artists" value="${h(params["artists"].orEmpty())}" placeholder="All the playlist's (the ones it holds most), or names separated by commas">
<p><button class="secondary" formmethod="get" name="pick" value="1" formnovalidate>Pick from the playlist's artists…</button></p>"""
    }
    val all = runCatching { runBlocking { Concerts.artistsOf(p.tracks(env, playlistId)) } }.getOrDefault(emptyList())
    val query = params["find"].orEmpty()
    val rows = ArtistPick.rows(all, picked, query)
    val boxes = rows.joinToString("") { a -> """<label class="check inline"><input type="checkbox" name="artist" value="${h(a)}"${if (a in picked) " checked" else ""}> ${h(a)}</label>""" }
    val add = ArtistPick.addable(all, picked, query)?.let { n -> """<label class="check inline"><input type="checkbox" name="artist" value="${h(n)}" checked> Add “${h(n)}”</label>""" }.orEmpty()
    return """<input type="hidden" name="pick" value="1"><fieldset style="border:0;padding:0;margin:12px 0 0"><legend style="font-weight:600;font-size:14px">Artists (${all.size} in the playlist): none ticked is all of them</legend>
<div class="finder"><input type="search" name="find" value="${h(query)}" placeholder="Narrow, or type a name to add" aria-label="Find an artist"> <button class="secondary" formmethod="get" formnovalidate>Find</button></div>
<div style="max-height:22em;overflow:auto">$boxes$add${if (rows.isEmpty() && add.isEmpty()) """<p class="muted">No artist matches.</p>""" else ""}</div></fieldset>"""
}
