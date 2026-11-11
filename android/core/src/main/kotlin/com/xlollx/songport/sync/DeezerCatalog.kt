package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.long
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str

/**
 * Cover art for a track, from Deezer's public catalogue (no key, no account: the same lookups
 * anyone makes on deezer.com). Tracks carry no image of their own in Trackhop; a card that shows
 * them needs one. By ISRC when the track has one, else by the same search [IsrcOracle] does, and
 * only an exact enough match. Null when nothing fits: the card draws a placeholder.
 */
object DeezerCatalog {
    private const val API = "https://api.deezer.com"

    suspend fun coverUrl(t: Track): String? {
        t.isrcNorm?.let { isrc ->
            val r = runCatching { Http.send("GET", "$API/track/isrc:$isrc", maxRateLimitRetries = 0) }.getOrNull()
            if (r != null && r.ok) {
                val j = parseJson(r.body)
                cover(j["album"])?.let { return it }
            }
        }
        val artist = t.artists.firstOrNull()?.let { Matcher.searchArtist(it) }.orEmpty()
        val title = Matcher.searchTitle(t.title)
        if (title.isBlank()) return null
        // Deezer's artist:"…" filter answers nothing (#46): the title filtered, the artist as plain words.
        val q = if (artist.isBlank()) title else "track:\"$title\" $artist"
        val resp = runCatching { Http.send("GET", "$API/search?limit=8&q=" + Http.enc(q), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok) return null
        val hits = parseJson(resp.body)["data"].arr
        val candidates = hits.mapNotNull { d ->
            val id = d["id"].long ?: return@mapNotNull null
            Track(
                id = id.toString(), title = d["title"].str ?: return@mapNotNull null,
                artists = listOfNotNull(d["artist"]["name"].str), album = d["album"]["title"].str ?: "",
                durationMs = (d["duration"].long ?: 0) * 1000,
            )
        }
        val best = Matcher.bestScored(t, candidates, 0.0)?.takeIf { it.score >= 0.80 } ?: return null
        val hit = hits.firstOrNull { it["id"].long?.toString() == best.track.id } ?: return null
        return cover(hit["album"])
    }

    /** Thumbnails YouTube serves for any video id without an API call: 1280×720, else 320×180 (no bars). */
    fun ownCover(id: String, serviceId: String): List<String> =
        if ((serviceId == "youtube" || serviceId == "ytm") && Regex("[A-Za-z0-9_-]{11}").matches(id))
            listOf("https://i.ytimg.com/vi/$id/hq720.jpg", "https://i.ytimg.com/vi/$id/mqdefault.jpg")
        else emptyList()

    /** The iTunes Search API (public, no key): artwork for the best matching song, at 600 px. */
    suspend fun itunesCover(t: Track): String? {
        val artist = t.artists.firstOrNull()?.let { Matcher.searchArtist(it) }.orEmpty()
        val title = Matcher.searchTitle(t.title)
        if (title.isBlank()) return null
        val term = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val resp = runCatching { Http.send("GET", "https://itunes.apple.com/search?entity=song&limit=8&term=" + Http.enc(term), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok) return null
        val hits = parseJson(resp.body)["results"].arr
        val candidates = hits.mapIndexedNotNull { i, d ->
            Track(
                id = i.toString(), title = d["trackName"].str ?: return@mapIndexedNotNull null,
                artists = listOfNotNull(d["artistName"].str), album = d["collectionName"].str ?: "",
                durationMs = d["trackTimeMillis"].long ?: 0,
            )
        }
        val best = Matcher.bestScored(t, candidates, 0.0)?.takeIf { it.score >= 0.75 } ?: return null
        val art = hits.getOrNull(best.track.id.toInt())?.get("artworkUrl100").str ?: return null
        return art.replace("100x100bb", "600x600bb")
    }

    /** Album covers already looked up by [albumCover], by artist and title ("" when none fits): asked once per run of the app. */
    private val albumCovers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The cover of [artist]'s album or record [title] in Deezer's catalogue (250 px), for a record
     * that came without a picture (Discogs gives none to calls without a token). Null when no album
     * of that very artist has that title, or Deezer did not answer.
     */
    suspend fun albumCover(artist: String, title: String): String? {
        val key = Matcher.normalizeArtist(artist) + "|" + Matcher.normalizeTitle(title)
        albumCovers[key]?.let { return it.ifEmpty { null } }
        val q = "${Matcher.searchArtist(artist)} ${Matcher.searchTitle(title)}".trim()
        val resp = runCatching { Http.send("GET", "$API/search/album?limit=10&q=" + Http.enc(q), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok) return null
        val found = runCatching { pickAlbum(resp.body, artist, title) }.getOrNull()
        if (albumCovers.size > 500) albumCovers.clear()
        albumCovers[key] = found.orEmpty()
        return found
    }

    /** From an album search's answer: the album of [artist] titled [title] (else an edition of it, "… (Deluxe)"), its cover; null when none. */
    internal fun pickAlbum(body: String, artist: String, title: String): String? {
        val want = Matcher.normalizeArtist(artist)
        val t = Matcher.normalizeTitle(title)
        if (want.isEmpty() || t.isEmpty()) return null
        val hits = parseJson(body)["data"].arr.filter { Matcher.normalizeArtist(it["artist"]["name"].str.orEmpty()) == want }
        val named = { h: kotlinx.serialization.json.JsonElement -> Matcher.normalizeTitle(h["title"].str.orEmpty()) }
        val hit = hits.firstOrNull { it["title"].str?.trim().equals(title.trim(), ignoreCase = true) }
            ?: hits.firstOrNull { named(it) == t } ?: hits.firstOrNull { named(it).startsWith("$t ") } ?: return null
        return webUrl(hit["cover_medium"].str ?: hit["cover"].str).ifEmpty { null }
    }

    /** Artist pictures already looked up by [artistPicture], by name ("" when none): asked once per run of the app. */
    private val artistPictures = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The picture of [artist] in Deezer's catalogue (250 px), for the Followed artists page. Null
     * when Deezer has no artist of that very name, only its blank placeholder, or did not answer.
     */
    suspend fun artistPicture(artist: String): String? {
        val key = Matcher.normalizeArtist(artist)
        if (key.isEmpty()) return null
        artistPictures[key]?.let { return it.ifEmpty { null } }
        val resp = runCatching { Http.send("GET", "$API/search/artist?limit=10&q=" + Http.enc(artist.trim()), maxRateLimitRetries = 0) }.getOrNull() ?: return null
        if (!resp.ok || resp.body.contains("\"error\"")) return null
        val found = runCatching { pickArtistPicture(resp.body, artist) }.getOrNull()
        if (artistPictures.size > 1_000) artistPictures.clear()
        artistPictures[key] = found.orEmpty()
        return found
    }

    /** From an artist search's answer: the picture of the artist named [artist] with the most fans; null when none or Deezer's placeholder. */
    internal fun pickArtistPicture(body: String, artist: String): String? {
        val want = Matcher.normalizeArtist(artist)
        val hit = parseJson(body)["data"].arr.filter { Matcher.normalizeArtist(it["name"].str.orEmpty()) == want }
            .maxByOrNull { it["nb_fan"].long ?: 0L } ?: return null
        // An artist without a picture gets a grey silhouette, its address with no image id: "/artist//250x250…".
        return webUrl(hit["picture_medium"].str ?: hit["picture"].str).takeIf { it.isNotEmpty() && !it.contains("/artist//") }
    }

    private fun cover(album: kotlinx.serialization.json.JsonElement?): String? =
        album["cover_big"].str ?: album["cover_medium"].str ?: album["cover"].str
}
