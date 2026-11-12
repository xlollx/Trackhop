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
     * with the ones not chosen yet. A song is matched by ISRC when both sides carry one, else by
     * its normalized title and first artist. Without plays the list is [posterTracks]'s, unchanged.
     */
    fun mostPlayed(tracks: List<Track>, played: List<Listening.Play>, n: Int = 9): PosterPicks {
        if (played.isEmpty() || tracks.isEmpty()) return PosterPicks(posterTracks(tracks, n), 0)
        val byIsrc = HashMap<String, Track>()
        val byName = HashMap<String, Track>()
        for (t in tracks) {
            t.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { byIsrc.putIfAbsent(it, t) }
            nameKey(t)?.let { byName.putIfAbsent(it, t) }
        }
        val chosen = LinkedHashSet<Track>()
        // Stable: a service that only ranks (every count 0) keeps its own order.
        for (p in played.sortedByDescending { it.plays }) {
            if (chosen.size >= n) break
            val hit = p.track.isrc?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { byIsrc[it] }
                ?: nameKey(p.track)?.let { byName[it] }
            if (hit != null) chosen += hit
        }
        val fromPlays = chosen.size
        if (chosen.size < n) {
            val rest = tracks.filter { it !in chosen }
            posterTracks(rest, n - chosen.size).forEach { chosen += it }
        }
        return PosterPicks(chosen.toList(), fromPlays)
    }

    /** A song's title and first artist, normalized; null when either is missing. */
    private fun nameKey(t: Track): String? {
        val title = Matcher.normalizeTitle(t.title).takeIf { it.isNotEmpty() } ?: return null
        val artist = Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()).takeIf { it.isNotEmpty() } ?: return null
        return "$title|$artist"
    }

    fun build(title: String, artists: List<String>, description: String = ""): String = buildString {
        append("Square album cover art for a music playlist titled \"").append(title.trim().take(120)).append("\".")
        if (artists.isNotEmpty()) append(" The music sounds like ").append(artists.take(5).joinToString(", ") { it.take(60) }).append('.')
        val d = description.trim().replace(Regex("\\s+"), " ").take(300)
        if (d.isNotEmpty()) append(" Mood and theme: ").append(d).append('.')
        append(" Style: bold, modern, abstract, music-poster.")
        append(" No text, no letters, no words, no logos, no brand marks, no real people's faces.")
    }
}
