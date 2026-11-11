package com.xlollx.songport.sync

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Env
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * From a setlist.fm link to the songs played: with a setlist.fm key (the user's or the build's) the
 * official API ([Setlists.byId]). Without a key nothing is read in the background: setlist.fm's terms
 * do not allow programs to read its pages. The GitHub and F-Droid builds open setlist.fm's own site in
 * the app instead, where the person opens a concert's page and the songs are read from the page they
 * see ([fromDom]): one page, no other request.
 */
object SetlistImport {

    private val LINK = Regex("""https?://(?:www\.)?setlist\.fm/setlist/[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val SONG = Regex("""class="songLabel"[^>]*>([^<]+)<""")
    private val TITLE = Regex("""<meta property="og:title" content="([^"]+)"""")
    private val DATE = Regex("""class="month">\s*(\w{3})\w*\s*</span>\s*<span class="day">\s*(\d{1,2})\s*</span>\s*<span class="year">\s*(\d{4})""")
    private val DATE_SUFFIX = Regex(""" on \w+ \d{1,2}, \d{4}$""")
    private val COVER_OF = Regex("""\s*\((.*?)\bcover\)$""", RegexOption.IGNORE_CASE)

    fun isLink(text: String): Boolean = LINK.containsMatchIn(text)
    fun linkIn(text: String): String? = LINK.find(text)?.value

    data class Setlist(val name: String, val artist: String, val tracks: List<Track>)

    /** No setlist.fm key: the link is not read in the background (the full build opens setlist.fm's site instead). */
    class NoKey : Exception("setlist.fm links need a setlist.fm key: add one in Settings › Service keys")

    suspend fun fetch(env: Env, url: String): Setlist {
        if (Setlists.key(env) == null) throw NoKey()
        val id = Setlists.idFromLink(url) ?: throw ProviderException("setlist.fm: no setlist in this link")
        val s = Setlists.byId(env, id) ?: throw ProviderException("setlist.fm: this setlist was not found")
        val tracks = Setlists.tracks(s)
        if (tracks.isEmpty()) throw ProviderException("setlist.fm: no songs on this page")
        return Setlist(Setlists.playlistName(s), s.artist, tracks)
    }

    /**
     * The page shown in the in-app browser, as `document.documentElement.outerHTML` hands it back
     * through `evaluateJavascript` (a JSON string): the same as [fromPage].
     */
    fun fromDom(jsResult: String, url: String): Setlists.Setlist {
        val html = runCatching { Json.decodeFromString(String.serializer(), jsResult) }.getOrNull()
            ?: throw ProviderException("setlist.fm: the page could not be read")
        return fromPage(html, url)
    }

    /** A setlist.fm concert page ([url] its address): the artist, venue, city, date and songs, a cover under its original artist. */
    fun fromPage(html: String, url: String): Setlists.Setlist {
        // "Muse Setlist at Forum, Milan, Italy" (or "… Concert Setlist at … on November 8, 2026 | setlist.fm").
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty().substringBefore(" | ")
        val artist = title.substringBefore(" Setlist").substringBefore(" Concert").trim()
        val place = title.substringAfter(" Setlist at ", "").replace(DATE_SUFFIX, "").split(",").map { it.trim() }
        val songs = SONG.findAll(html).map { unescape(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.map { raw ->
            // "Song (Original Artist cover)": the song is by the original artist, not the band on stage.
            val cover = COVER_OF.find(raw)
            Setlists.Song(if (cover != null) raw.removeRange(cover.range).trim() else raw, cover?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() })
        }.toList()
        if (songs.isEmpty()) throw ProviderException("setlist.fm: no songs on this page")
        val date = DATE.find(html)?.let { m ->
            runCatching {
                LocalDate.parse("${m.groupValues[1]} ${m.groupValues[2].padStart(2, '0')} ${m.groupValues[3]}", DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH)).toString()
            }.getOrNull()
        }.orEmpty()
        val page = url.substringBefore('?').substringBefore('#')
        return Setlists.Setlist(
            id = Setlists.idFromLink(page) ?: page, artist = artist.ifBlank { "Setlist" }, date = date,
            venue = place.getOrNull(0).orEmpty(), city = place.getOrNull(1).orEmpty(), url = page, songs = songs,
        )
    }

    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'")
}
