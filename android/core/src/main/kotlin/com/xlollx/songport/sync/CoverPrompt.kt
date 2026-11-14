package com.xlollx.songport.sync

import com.xlollx.songport.model.Track

/**
 * The words sent to the user's image AI for a playlist cover: the playlist's title, its most
 * frequent artists and its description, and nothing else (no account, no service, no track list).
 * The art is asked to be square, without lettering, logos, brand marks or real people's faces.
 */
object CoverPrompt {

    /** The [n] artists that appear most often, in order of frequency, then of first appearance. */
    fun topArtists(tracks: List<Track>, n: Int = 5): List<String> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (t in tracks) {
            val name = t.artists.firstOrNull()?.trim().orEmpty()
            if (name.isEmpty()) continue
            val key = name.lowercase()
            val (shown, c) = counts[key] ?: (name to 0)
            counts[key] = shown to c + 1
        }
        return counts.values.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Pair<String, Int>>> { it.value.second }.thenBy { it.index })
            .take(n).map { it.value.first }
    }

    /**
     * The tracks a poster lists: up to [perArtist] from each of the [artists] that appear most
     * (in playlist order, grouped by artist), then, if the list is still short, one each from the
     * next most frequent artists, then the playlist's own order. Same playlist, same list.
     */
    fun posterTracks(tracks: List<Track>, n: Int = 9, artists: Int = 5, perArtist: Int = 2): List<Track> {
        val ranked = topArtists(tracks, Int.MAX_VALUE).map { it.lowercase() }
        val byArtist = tracks.groupBy { it.artists.firstOrNull()?.trim()?.lowercase().orEmpty() }
        val out = LinkedHashSet<Track>()
        ranked.take(artists).forEach { a -> byArtist[a].orEmpty().take(perArtist).forEach { if (out.size < n) out += it } }
        ranked.drop(artists).forEach { a -> byArtist[a]?.firstOrNull()?.let { if (out.size < n) out += it } }
        tracks.forEach { if (out.size < n) out += it }
        return out.toList()
    }

    /** What a poster lists: the songs, and how many of them (the first ones) come from the user's plays. */
    class PosterPicks(val tracks: List<Track>, val fromPlays: Int)

    /**
     * The poster's songs ranked by what the user really plays: first the playlist's songs that are
     * among [played] (a listening service's top songs), the most played first (by plays when the
     * service counts them, else in the service's own rank), then [posterTracks] fills up to [n]
     * with the ones not chosen yet; at most [perArtist] songs of one artist come from the plays. A song is matched by ISRC when both sides carry one, else by
     * its normalized title and first artist. Without plays the list is [posterTracks]'s, unchanged.
     */
    fun mostPlayed(tracks: List<Track>, played: List<Listening.Play>, n: Int = 9, perArtist: Int = 2): PosterPicks {
        if (played.isEmpty() || tracks.isEmpty()) return PosterPicks(posterTracks(tracks, n), 0)
        val byIsrc = HashMap<String, Track>()
        val byName = HashMap<String, Track>()
        for (t in tracks) {
            t.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { byIsrc.putIfAbsent(it, t) }
            nameKey(t)?.let { byName.putIfAbsent(it, t) }
        }
        val chosen = LinkedHashSet<Track>()
        // At most [perArtist] songs of one artist, so one favourite band doesn't fill the poster alone.
        val perArtistCount = HashMap<String, Int>()
        // Stable: a service that only ranks (every count 0) keeps its own order.
        for (p in played.sortedByDescending { it.plays }) {
            if (chosen.size >= n) break
            val hit = p.track.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { byIsrc[it] }
                ?: nameKey(p.track)?.let { byName[it] }
                ?: continue
            if (hit in chosen) continue
            val artist = Matcher.normalizeArtist(hit.artists.firstOrNull().orEmpty())
            if ((perArtistCount[artist] ?: 0) >= perArtist) continue
            perArtistCount[artist] = (perArtistCount[artist] ?: 0) + 1
            chosen += hit
        }
        val fromPlays = chosen.size
        if (chosen.size < n) {
            val rest = tracks.filter { it !in chosen }
            posterTracks(rest, n - chosen.size).forEach { chosen += it }
        }
        return PosterPicks(chosen.toList(), fromPlays)
    }

    /**
     * The poster's songs ranked by the artists the user really plays, for a service that tells
     * artists and no songs (TIDAL): the playlist's songs by [played] (the most streamed first), up
     * to [perArtist] each in playlist order, then [posterTracks] fills up to [n]. An artist is
     * matched by normalized name against any of a song's artists. Without plays, [posterTracks]'s list.
     */
    fun byArtists(tracks: List<Track>, played: List<Listening.ArtistPlays>, n: Int = 9, perArtist: Int = 2): PosterPicks {
        if (played.isEmpty() || tracks.isEmpty()) return PosterPicks(posterTracks(tracks, n), 0)
        val chosen = LinkedHashSet<Track>()
        for (a in played.sortedByDescending { it.plays }) {
            if (chosen.size >= n) break
            val k = Matcher.normalizeArtist(a.name).takeIf { it.isNotEmpty() } ?: continue
            tracks.filter { t -> t !in chosen && t.artists.any { Matcher.normalizeArtist(it) == k } }
                .take(minOf(perArtist, n - chosen.size)).forEach { chosen += it }
        }
        val fromPlays = chosen.size
        if (chosen.size < n) posterTracks(tracks.filter { it !in chosen }, n - chosen.size).forEach { chosen += it }
        return PosterPicks(chosen.toList(), fromPlays)
    }

    /** A song's title and first artist, normalized; null when either is missing. */
    private fun nameKey(t: Track): String? {
        val title = Matcher.normalizeTitle(t.title).takeIf { it.isNotEmpty() } ?: return null
        val artist = Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()).takeIf { it.isNotEmpty() } ?: return null
        return "$title|$artist"
    }

    /**
     * The request for an AI cover. Image models write out any text they are given in quotes, and most
     * ignore "no text" (FLUX among them): the playlist's name and description go in as a theme in lower
     * case, never quoted, and the picture is asked for as a wordless illustration (owner, 14/11: the
     * title and a garbled line of the description came out written on the cover). The poster prints
     * the name itself.
     */
    fun build(title: String, artists: List<String>, description: String = ""): String = buildString {
        fun theme(s: String) = s.replace(Regex("[\"“”«»'‘’]"), " ").replace(Regex("\\s+"), " ").trim().lowercase()
        append("A wordless square illustration for a music album cover, pure imagery without any typography.")
        val t = theme(title.take(120))
        if (t.isNotEmpty()) append(" Visual theme inspired by: ").append(t).append('.')
        if (artists.isNotEmpty()) append(" The music sounds like ").append(artists.take(5).joinToString(", ") { it.take(60) }).append('.')
        val d = theme(description.take(300))
        if (d.isNotEmpty()) append(" Mood: ").append(d).append('.')
        append(" Style: bold, modern, abstract, music-poster.")
        append(" A clean image with blank space instead of any lettering, logos or brand marks; no real people's faces.")
    }
}
