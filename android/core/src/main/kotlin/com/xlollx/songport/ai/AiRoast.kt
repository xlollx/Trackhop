package com.xlollx.songport.ai

import com.xlollx.songport.model.Track
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.Matcher

/**
 * "Roast of a playlist", with the user's own AI ([AiClient]): a short, funny and affectionate
 * comment on a playlist. The AI gets a compact summary only (name, counts, top artists, decades,
 * a sample of titles), never the whole list, and is told to tease the taste, never the person.
 */
object AiRoast {
    /** How many titles of the playlist go to the AI as a sample. */
    const val SAMPLE = 20

    /** The summary sent to the AI; [summary] is pure and covered by tests. */
    fun summary(name: String, tracks: List<Track>): String {
        val songs = tracks.filter { it.kind.isEmpty() && it.title.isNotBlank() }
        val artists = HashMap<String, Int>()
        val spelling = HashMap<String, String>()
        for (t in songs) for (a in t.artists.distinct()) {
            val k = Matcher.normalizeArtist(a)
            if (k.isEmpty()) continue
            spelling.putIfAbsent(k, a.trim()); artists[k] = (artists[k] ?: 0) + 1
        }
        val top = artists.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(8)
        val decades = songs.map { it.year }.filter { it > 1900 }.groupingBy { it / 10 * 10 }.eachCount().entries.sortedBy { it.key }
        val minutes = songs.sumOf { it.durationMs } / 60_000
        // A spread sample, not the first twenty: the start of a playlist is often one album.
        val step = maxOf(1, songs.size / SAMPLE)
        val sample = songs.filterIndexed { i, _ -> i % step == 0 }.take(SAMPLE)
        return buildString {
            append("Playlist name: ").append(name.ifBlank { "(no name)" }).append('\n')
            append("Tracks: ").append(songs.size)
            if (minutes > 0) append(" (about ").append(minutes).append(" minutes)")
            append('\n')
            append("Different artists: ").append(artists.size).append('\n')
            if (top.isNotEmpty()) append("Top artists (tracks): ").append(top.joinToString(", ") { "${spelling[it.key]} (${it.value})" }).append('\n')
            if (decades.isNotEmpty()) append("Decades (tracks): ").append(decades.joinToString(", ") { "${it.key}s (${it.value})" }).append('\n')
            val explicit = songs.count { it.explicit == true }
            if (explicit > 0) append("Explicit tracks: ").append(explicit).append('\n')
            if (sample.isNotEmpty()) {
                append("Sample of titles:\n")
                sample.forEach { append("- ").append(it.toString()).append('\n') }
            }
        }.trimEnd()
    }

    private fun system(language: String) = """
        You write a short, funny, affectionate roast of someone's music playlist, like a friend teasing them.
        Tease the taste, the habits and the choices; never insult people, the listener or the artists as persons,
        no slurs, nothing about appearance, body, gender, religion, origin or health, nothing cruel or sexual.
        3 to 5 sentences, at most 90 words, no hashtags, no emoji spam (one emoji at most).
        Write in ${language.ifBlank { "English" }}.
        Reply with ONLY a JSON object: {"roast": "..."}
    """.trimIndent()

    /** The roast's text out of the AI's answer: the JSON's "roast", else the answer itself, unwrapped. */
    fun parse(answer: String): String {
        val trimmed = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{'); val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) runCatching { parseJson(trimmed.substring(start, end + 1))["roast"].str }.getOrNull()?.let { return it.trim() }
        return trimmed.trim('"').trim()
    }

    suspend fun roast(c: AiClient.Config, name: String, tracks: List<Track>, language: String): String {
        val text = parse(AiClient.complete(c, system(language), "Roast this playlist:\n" + summary(name, tracks)))
        if (text.isBlank()) throw com.xlollx.songport.model.ProviderException("Empty answer")
        return text
    }
}
