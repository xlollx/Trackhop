package com.xlollx.songport.sync

import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.Env

/**
 * The exact ids the app already knows for an artist, so New releases, Setlists and Concerts can ask
 * the services by id instead of by name (fewer namesakes): never a request of its own.
 *
 * - The MusicBrainz id ([mbid]): the one of a followed artist (picked from MusicBrainz's suggestions),
 *   else the one Records & merch settled on (several artists of the name told apart by the playlist's
 *   titles, [Records.keptLinks]), else one remembered here from an earlier answer that named a single
 *   artist of that very name ([rememberMbid]).
 * - The Ticketmaster attraction id ([attraction]): remembered from an earlier Ticketmaster answer whose
 *   events had a single attraction of that name, or the one MusicBrainz's id points to ([rememberAttraction]).
 *
 * Each caller keeps the name search as it was: when the id is unknown, or the call by id does not
 * answer, the name is asked as before.
 */
object ArtistIds {
    private const val KV = "artist_ids"
    /** How long a remembered id is trusted before the name is asked again (and the id learnt again). */
    const val TTL_MS = 90L * 24 * 3_600_000
    private const val MAX = 5_000

    @Volatile internal var now: () -> Long = { System.currentTimeMillis() }

    private fun key(artist: String) = ArtistAlerts.key(artist)

    /** The artist's MusicBrainz id when the app knows it for sure; null otherwise. */
    fun mbid(env: Env, artist: String): String? {
        val k = key(artist)
        if (k.isEmpty()) return null
        env.store.data.settings.followedArtists.firstOrNull { ArtistAlerts.key(it.name) == k }?.mbid?.let { MusicBrainz.mbid(it) }?.let { return it }
        Records.keptLinks(env, artist)?.takeIf { !it.sameName }?.id?.let { MusicBrainz.mbid(it) }?.let { return it }
        return kept(env, "mb|$k")?.let { MusicBrainz.mbid(it) }
    }

    /** Remembers [mbid] for [artist] (an answer that named a single artist of that name). */
    fun rememberMbid(env: Env, artist: String, mbid: String?) {
        val id = MusicBrainz.mbid(mbid) ?: return
        keep(env, "mb|${key(artist)}", id)
    }

    /** The Ticketmaster attraction id the app learnt for [artist]; null when it knows none. */
    fun attraction(env: Env, artist: String): String? = key(artist).takeIf { it.isNotEmpty() }?.let { kept(env, "tm|$it") }

    fun rememberAttraction(env: Env, artist: String, id: String?) {
        val a = id?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return
        keep(env, "tm|${key(artist)}", a)
    }

    /** The one id of a MusicBrainz artist search named exactly [artist] (score 90 or more); null with none or several. */
    fun singleMbArtist(body: String, artist: String): String? = runCatching { Records.pickArtists(body, artist).singleOrNull() }.getOrNull()

    /**
     * The attraction of a Ticketmaster events answer that is [artist]: the one whose MusicBrainz link
     * is [mbid] when known, else the single attraction of that very name (none when several share it).
     */
    fun ticketmasterAttraction(body: String, artist: String, mbid: String?): String? = runCatching {
        val want = Matcher.normalizeArtist(artist)
        if (want.isEmpty()) return@runCatching null
        val all = parseJson(body)["_embedded"]["events"].arr.flatMap { it["_embedded"]["attractions"].arr }
        if (mbid != null) all.firstOrNull { a -> a["externalLinks"]["musicbrainz"].arr.any { MusicBrainz.mbid(it["id"].str) == mbid } }?.get("id").str?.let { return@runCatching it }
        all.filter { Matcher.normalizeArtist(it["name"].str.orEmpty()) == want }.mapNotNull { it["id"].str }.distinct().singleOrNull()
    }.getOrNull()

    private fun kept(env: Env, k: String): String? {
        val saved = env.keyValues(KV)[k] ?: return null
        val at = saved.substringBefore('|').toLongOrNull() ?: return null
        return saved.substringAfter('|').takeIf { now() - at in 0 until TTL_MS && it.isNotEmpty() }
    }

    private fun keep(env: Env, k: String, value: String) {
        if (k.length <= 3) return
        val kv = env.keyValues(KV)
        if (kv.size >= MAX) kv.clear()
        kv[k] = "${now()}|$value"
    }
}
