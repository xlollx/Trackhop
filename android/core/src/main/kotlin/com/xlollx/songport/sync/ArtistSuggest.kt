package com.xlollx.songport.sync

import com.xlollx.songport.ai.ArtistMixSource
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.json
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/**
 * The names offered while an artist is typed in Discover › Followed artists:
 *
 * 1. the artists the app already met ([known]): those of the playlists looked up in Concerts,
 *    Setlists, Records & merch and New releases ([note]), and those of the last alerts;
 * 2. MusicBrainz's artist search ([musicBrainz]), which gives the name as it is written, what tells
 *    two artists of one name apart, and the artist's id. Asked through [MusicBrainz.pace] (one
 *    request a second for the whole app) and named as [ArtistMixSource.userAgent]; the page waits
 *    [DEBOUNCE_MS] after the last key and asks from [MIN_CHARS] letters on.
 *
 * [merge] puts them in one list, each artist once however it is written.
 */
object ArtistSuggest {
    /**
     * [detail]: what MusicBrainz says of the artist ("metalcore · Australia"), "" for a known name.
     * [known]: met in the user's playlists. [followed]: followed already. [typed]: not a suggestion,
     * the name as the user wrote it.
     */
    data class Suggestion(
        val name: String, val mbid: String? = null, val detail: String = "",
        val known: Boolean = false, val followed: Boolean = false, val typed: Boolean = false,
    )

    const val MIN_CHARS = 2
    const val DEBOUNCE_MS = 450L
    /** The suggestions shown, the typed name aside. */
    const val MAX = 7
    /** The known names shown first, so MusicBrainz's keep their room. */
    const val MAX_LOCAL = 4
    const val MAX_KNOWN = 1_000

    private const val KV = "artist_suggest"
    private val names = ListSerializer(String.serializer())

    // ---------------------------------------------------------------- the artists already met

    /** The artists met so far, the last ones noted last. */
    fun known(env: Env): List<String> =
        env.keyValues(KV)["known"]?.let { runCatching { json.decodeFromString(names, it) }.getOrNull() }.orEmpty()

    /** Remembers [artists] (a playlist's, as a Discover page read them) for the suggestions; the newest [MAX_KNOWN] kept. */
    fun note(env: Env, artists: List<String>) {
        val had = known(env)
        val keys = had.mapTo(HashSet()) { ArtistAlerts.key(it) }
        val fresh = artists.map { it.trim() }.filter { it.isNotEmpty() && keys.add(ArtistAlerts.key(it)) }
        if (fresh.isEmpty()) return
        env.keyValues(KV)["known"] = json.encodeToString(names, (had + fresh).takeLast(MAX_KNOWN))
    }

    /** The known [names] that [query] could mean: those starting with it, then those with a word starting with it, then the others holding it. */
    fun local(names: List<String>, query: String, limit: Int = MAX_LOCAL): List<Suggestion> {
        val q = Labels.fold(query)
        if (q.length < MIN_CHARS) return emptyList()
        fun rank(n: String): Int = Labels.fold(n).let { f -> if (f.startsWith(q)) 0 else if (f.contains(" $q")) 1 else if (f.contains(q)) 2 else 3 }
        return ArtistPick.byName(names.distinctBy { ArtistAlerts.key(it) }) { it }
            .map { it to rank(it) }.filter { it.second < 3 }.sortedBy { it.second }
            .take(limit).map { Suggestion(it.first, known = true) }
    }

    // ---------------------------------------------------------------- MusicBrainz

    /**
     * The search for a name half typed: the words as a phrase first, else all of them with the last
     * one as a beginning ("parkway dri" finds Parkway Drive). "" when there is too little to ask.
     */
    fun query(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.joinToString("").length < MIN_CHARS) return ""
        val terms = words.mapIndexed { i, w ->
            val e = ArtistMixSource.escape(w).lowercase()
            if (i == words.lastIndex && w.all { it.isLetterOrDigit() }) "$e*" else e
        }
        return "\"" + words.joinToString(" ") { ArtistMixSource.escape(it) } + "\"^2 OR (" + terms.joinToString(" AND ") + ")"
    }

    /** The artists of a MusicBrainz artist search, in its order (best first). */
    fun parse(j: JsonElement?): List<Suggestion> = j["artists"].arr.mapNotNull { a ->
        val name = a["name"].str?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val what = a["disambiguation"].str?.trim().orEmpty()
            .ifEmpty { a["tags"].arr.maxByOrNull { it["count"].int ?: 0 }?.get("name").str?.trim().orEmpty() }
        val where = a["area"]["name"].str?.trim().orEmpty()
        Suggestion(name, MusicBrainz.mbid(a["id"].str), listOf(what, where).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    /** MusicBrainz's artists for [text]; empty when there is too little to ask. Throws when it does not answer. */
    suspend fun musicBrainz(text: String): List<Suggestion> {
        val q = query(text)
        if (q.isEmpty()) return emptyList()
        val r = MusicBrainz.pace.paced {
            Http.send(
                "GET", MusicBrainz.base + "/artist?" + Http.query(mapOf("query" to q, "fmt" to "json", "limit" to "8")),
                mapOf("User-Agent" to ArtistMixSource.userAgent, "Accept" to "application/json"),
                maxRateLimitRetries = 0, retryServerErrors = false,
            )
        }
        if (!r.ok) throw java.io.IOException("MusicBrainz ${r.code}")
        return parse(parseJson(r.body))
    }

    /** How the page asks for names: [musicBrainz] in the app, a fake in the tests. */
    @Volatile var remote: suspend (String) -> List<Suggestion> = ::musicBrainz

    // ---------------------------------------------------------------- one list

    /**
     * What to offer for [query]: the [local] names, then the [remote] ones, each artist once (a known
     * name MusicBrainz has too takes its spelling, id and detail, and keeps its place), at most
     * [limit]; marked [Suggestion.followed] against [followed]. Last, the name as typed when none of
     * them is it, so an artist nobody knows can still be followed.
     */
    fun merge(query: String, followed: List<ArtistAlerts.Followed>, local: List<Suggestion>, remote: List<Suggestion>, limit: Int = MAX): List<Suggestion> {
        val out = LinkedHashMap<String, Suggestion>()
        for (s in local + remote) {
            val k = ArtistAlerts.key(s.name)
            if (k.isEmpty()) continue
            val had = out[k]
            out[k] = when {
                had == null -> s
                had.mbid == null && s.mbid != null -> s.copy(known = had.known || s.known)
                else -> had
            }
        }
        val typed = query.trim().replace(Regex("\\s+"), " ")
        val k = ArtistAlerts.key(typed)
        // The typed name itself, when it is one of them, comes first.
        val list = out.values.sortedBy { if (ArtistAlerts.key(it.name) == k) 0 else 1 }.take(limit).toMutableList()
        if (typed.length >= MIN_CHARS && k.isNotEmpty() && list.none { ArtistAlerts.key(it.name) == k }) list += Suggestion(typed, typed = true)
        return list.map { it.copy(followed = ArtistAlerts.isFollowed(followed, it.name)) }
    }
}
