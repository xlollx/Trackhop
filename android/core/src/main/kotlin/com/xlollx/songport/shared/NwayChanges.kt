package com.xlollx.songport.shared

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import com.xlollx.songport.sync.Matcher

/**
 * What one run of a multi sync added or removed, song by song: each of its syncs lists the tracks it
 * changed on its own playlist, so a song the source dropped is listed once per target ("Enter
 * Sandman" four times, and Deezer's "Enter Sandman (Remastered 2021)"). Here those lists become one
 * row per song, with the playlists it changed on. Pure Kotlin: covered by the tests.
 *
 * The song is known by the group's list (the hub, [Nway]): every target's sync matches the hub's
 * songs, and the engine remembers each match ("shared:<hub id>><service>" in [StoreData.matchCache]),
 * so a target's track leads back to the hub's song whatever that service calls it. A track with no
 * remembered match goes by its artist and title, versions left out ([nameKey]).
 */
object NwayChanges {
    enum class Kind { ADDED, REMOVED }

    /**
     * One track one sync of the group changed: [member] is the down sync of the playlist it changed
     * ([Nway.Member.down]'s id), null for the group's own list (the hub: that change names the song,
     * it is no service's); [key] the hub's id of the song, when known.
     */
    data class Change(val member: String?, val track: Track, val key: String? = null)

    /** One song: [name] as the group's list (else most of the services) calls it, and its changes on the playlists, one per playlist, in the group's order. */
    data class Song(val name: Track, val changes: List<Change>) {
        /** The playlists it changed on (their down syncs' ids). */
        val members: List<String> get() = changes.mapNotNull { it.member }.distinct()
    }

    /** The fallback key of a song: its first artist and its title, normalised as the matcher does (no "(Remastered 2021)", "- Live", "feat. …"). */
    fun nameKey(t: Track): String =
        Matcher.normalizeArtist(t.artists.firstOrNull().orEmpty()) + "\u0000" + Matcher.normalizeTitle(t.title).ifEmpty { t.title.trim().lowercase() }

    /**
     * The changes of [kind] in [batch] (one run of the group: its syncs' reports), each with the
     * playlist it changed on and the hub's id of its song where the matches say it.
     */
    fun changes(d: StoreData, batch: List<SyncReport>, members: List<Nway.Member>, kind: Kind): List<Change> {
        val downs = members.associateBy { it.down.id }
        val ups = members.mapNotNullTo(HashSet()) { it.up?.id }
        // Hub id by target track id, per target ("shared:<hub id>><target>" -> target id, read backwards).
        val hub = FileStore.family(SharedProvider.ID) + ":"
        val back = HashMap<String, HashMap<String, String>>()
        for ((k, v) in d.matchCache) {
            if (!k.startsWith(hub)) continue
            val arrow = k.lastIndexOf('>')
            if (arrow <= hub.length) continue
            back.getOrPut(k.substring(arrow + 1)) { HashMap() }.putIfAbsent(v, k.substring(hub.length, arrow))
        }
        val out = ArrayList<Change>()
        for (r in batch) {
            val tracks = if (kind == Kind.ADDED) r.addedTracks else r.removedTracks
            if (tracks.isEmpty()) continue
            val down = downs[r.jobId]
            when {
                down != null -> {
                    val target = FileStore.family(FileStore.matchTarget(down.down.target.provider, down.down.target.playlistId))
                    val map = back[target]
                    tracks.forEach { out += Change(down.down.id, it, map?.get(it.id)) }
                }
                // Into the hub: its tracks are the hub's own, their id the song's.
                r.jobId in ups -> tracks.forEach { out += Change(null, it, it.id) }
            }
        }
        return out
    }

    /**
     * [changes] one row per song: by the hub's id where known, else by [nameKey] (a track with no id
     * joins the first song of its name). A song with no playlist changed (the hub alone) is left
     * out. The most playlists first, then by title; each song's changes in [order] (down sync ids).
     */
    fun group(changes: List<Change>, order: List<String> = emptyList()): List<Song> {
        class Acc { val all = ArrayList<Change>() }
        val byKey = LinkedHashMap<String, Acc>()
        val byName = HashMap<String, Acc>()
        val songs = ArrayList<Acc>()
        fun place(c: Change, acc: Acc) { acc.all += c; byName.putIfAbsent(nameKey(c.track), acc) }
        for (c in changes) if (c.key != null) {
            val acc = byKey.getOrPut(c.key) { Acc().also { songs += it } }
            place(c, acc)
        }
        for (c in changes) if (c.key == null) {
            val acc = byName[nameKey(c.track)] ?: Acc().also { songs += it }
            place(c, acc)
        }
        val rank = order.withIndex().associate { it.value to it.index }
        return songs.mapNotNull { acc ->
            val onPlaylists = acc.all.filter { it.member != null }
            if (onPlaylists.isEmpty()) return@mapNotNull null
            val name = acc.all.firstOrNull { it.member == null }?.track
                ?: onPlaylists.groupingBy { it.track.toString() }.eachCount().let { n -> onPlaylists.maxBy { n[it.track.toString()]!! }.track }
            Song(name, onPlaylists.sortedBy { rank[it.member] ?: Int.MAX_VALUE })
        }.sortedWith(compareByDescending<Song> { it.members.size }.thenBy { it.name.title.lowercase() }.thenBy { it.name.artistLine.lowercase() })
    }

    /** Every one of [targets] (the group's targets' down sync ids) changed: "removed from all 4". Never for a single target. */
    fun everyTarget(song: Song, targets: Collection<String>): Boolean = targets.size >= 2 && song.members.containsAll(targets)

    /** The songs a target holds after its sync's last run ([r]), when that run read it; null when unknown. */
    fun targetSize(r: SyncReport?): Int? {
        if (r == null || r.partial) return null
        if (r.targetCount <= 0 && r.added <= 0) return null
        return (r.targetCount + r.added - r.removed).coerceAtLeast(0)
    }
}
