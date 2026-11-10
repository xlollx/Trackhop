package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.data.WantedMark
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * "Missing on my server": one list, without doubles, of the tracks the user's streaming playlists
 * hold and their own server (Navidrome and the other Subsonic servers, Jellyfin, Emby, Plex, Lyrion,
 * Mopidy) does not, to buy or rip. It is made from what the syncs already keep: the not found of
 * each sync's last complete run towards such a server ([SyncReport.unmatchedTracks]). A track
 * several syncs miss is one entry naming them all; two services' copies of it are one entry too
 * (same ISRC, or the same artist and title once written alike).
 *
 * The only memory of its own is [StoreData.wanted]: when each entry first showed up and whether
 * the user ticked it ("got it": hidden from then on). MusicBrainz ids come from the cache of the
 * lookups the syncs already made ([MusicBrainz.known]); this list never asks MusicBrainz anything.
 * Nothing is downloaded or bought here: it is only a list.
 */
object Wanted {
    /** The services that are the user's own server. */
    val SERVERS = setOf("subsonic", "jellyfin", "emby", "plex", "lyrion", "mopidy")

    fun isServer(providerId: String) = providerId.substringBefore('@') in SERVERS

    /** A sync that misses the track: its id and name, and its two services (provider ids). */
    data class From(val syncId: String, val syncName: String, val source: String, val target: String)

    data class Want(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String,
        val durationMs: Long,
        val isrc: String?,
        /** The MusicBrainz recording, when the syncs' lookups found exactly one; null otherwise. */
        val recordingMbid: String?,
        /** The recording's artists on MusicBrainz, when known. */
        val artistMbids: List<String>,
        val syncs: List<From>,
        val firstSeen: Long,
        val got: Boolean,
        /** Every key the entry answers to (its ISRCs, its artist and title): a mark saved under any of them is its own. */
        internal val keys: Set<String> = emptySet(),
    ) {
        val artistLine: String get() = artists.joinToString(", ")
        fun track() = Track(id = id, title = title, artists = artists, album = album, durationMs = durationMs, isrc = isrc)
        override fun toString() = if (artists.isEmpty()) title else "$artistLine – $title"
    }

    /** An album's entries, for the list by album: [album] is "" for the tracks whose album is not known. */
    data class Album(val artist: String, val album: String, val wants: List<Want>)

    /**
     * What a list call asks for: one sync (its id or name), one service (a sync's source or target,
     * by provider id like "jellyfin@2" or by service like "jellyfin"), entries first seen at or after
     * [since] (epoch ms), and whether the ticked ones are listed too.
     */
    data class Filter(val sync: String? = null, val service: String? = null, val since: Long = 0, val withGot: Boolean = false)

    // ------------------------------------------------------------------ the list

    /** The list, sorted by artist and title ("The" left aside). [known] gives what MusicBrainz said of an ISRC, from the cache only. */
    fun list(data: StoreData, known: (String?) -> MusicBrainz.Known? = { null }, filter: Filter = Filter()): List<Want> =
        collect(data, known).filter { w -> keep(w, filter, data.jobs) }

    private fun keep(w: Want, f: Filter, jobs: List<SyncJob>): Boolean {
        if (!f.withGot && w.got) return false
        if (w.firstSeen < f.since) return false
        f.sync?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            val ids = jobs.filter { it.id == s || it.name.equals(s, ignoreCase = true) }.map { it.id }.toSet()
            if (w.syncs.none { it.syncId in ids }) return false
        }
        f.service?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { s ->
            fun same(p: String) = p.lowercase() == s || (!s.contains('@') && p.substringBefore('@').lowercase() == s)
            if (w.syncs.none { same(it.source) || same(it.target) }) return false
        }
        return true
    }

    /** The newest complete run of [job] that read its source: a run skipped as unchanged lists nothing and says nothing. */
    private fun lastRun(data: StoreData, job: SyncJob): SyncReport? =
        data.reports.firstOrNull { it.jobId == job.id && !it.partial && it.ok && (it.sourceCount > 0 || it.sourceEmpty) }

    private class Item(val track: Track, val from: From, val seen: Long)

    private fun collect(data: StoreData, known: (String?) -> MusicBrainz.Known?): List<Want> {
        val items = ArrayList<Item>()
        for (job in data.jobs) {
            if (!isServer(job.target.provider)) continue
            val run = lastRun(data, job) ?: continue
            val ignored = job.ignoredSourceIds.toHashSet()
            val from = From(job.id, job.name, job.source.provider, job.target.provider)
            for (t in run.unmatchedTracks) if (t.kind.isEmpty() && t.title.isNotBlank() && t.id !in ignored) items += Item(t, from, run.startedEpoch)
        }
        // Union by key: the same ISRC, or the same artist and title, is one entry.
        val parent = IntArray(items.size) { it }
        fun root(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val firstWith = HashMap<String, Int>()
        val keys = items.map { keysOf(it.track) }
        keys.forEachIndexed { i, ks -> ks.forEach { k -> firstWith.putIfAbsent(k, i)?.let { j -> parent[root(i)] = root(j) } } }
        val groups = items.indices.groupBy { root(it) }.values
        return groups.map { idx ->
            val members = idx.map { items[it] }
            val tracks = members.map { it.track }
            // The fullest copy names the entry; what it lacks comes from the others.
            val best = tracks.maxBy { (if (it.isrcNorm != null) 4 else 0) + (if (it.album.isNotBlank()) 2 else 0) + (if (it.durationMs > 0) 1 else 0) }
            val isrc = best.isrcNorm ?: tracks.firstNotNullOfOrNull { it.isrcNorm }
            val allKeys = idx.flatMap { keys[it] }.toSet()
            val id = tracks.mapNotNull { it.isrcNorm }.minOrNull()?.let { "isrc:$it" } ?: allKeys.filter { it.startsWith("t:") }.min()
            val mark = data.wanted[id] ?: allKeys.firstNotNullOfOrNull { data.wanted[it] }
            val mb = tracks.mapNotNull { it.isrcNorm }.distinct().mapNotNull { known(it) }
            val recordings = mb.flatMap { it.recordings }.distinct()
            Want(
                id = id,
                title = best.title,
                artists = best.artists,
                album = best.album.ifBlank { tracks.firstOrNull { it.album.isNotBlank() }?.album.orEmpty() },
                durationMs = best.durationMs.takeIf { it > 0 } ?: tracks.firstOrNull { it.durationMs > 0 }?.durationMs ?: 0,
                isrc = isrc,
                recordingMbid = recordings.singleOrNull(),
                artistMbids = mb.flatMap { it.artists }.distinct(),
                syncs = members.map { it.from }.distinctBy { it.syncId },
                firstSeen = mark?.firstSeen ?: members.minOf { it.seen },
                got = mark?.got == true,
                keys = allKeys + id,
            )
        }.sortedWith(compareBy({ sortKey(it.artistLine) }, { sortKey(it.title) }))
    }

    private fun sortKey(s: String) = Matcher.stripDiacritics(s).lowercase().removePrefix("the ")

    /** The keys a track answers to: its ISRC, and its first artist with its title (versions kept apart: a live is not the studio take). */
    internal fun keysOf(t: Track): Set<String> {
        val out = LinkedHashSet<String>()
        t.isrcNorm?.let { out += "isrc:$it" }
        out += "t:" + titleKey(t)
        return out
    }

    internal fun titleKey(t: Track): String {
        val first = t.artists.firstOrNull().orEmpty()
        val artist = Matcher.normalizeArtist(Matcher.primaryArtist(first) ?: first)
        val title = Matcher.normalizeTitle(t.title).ifBlank { t.title.trim().lowercase() }
        val version = (Matcher.versionMarkers(t.title) - setOf("remaster", "mono", "stereo")).sorted().joinToString(" ")
        return "$artist|$title" + if (version.isEmpty()) "" else "|$version"
    }

    /** The list by album: one group per artist and album, the tracks without an album last for their artist. */
    fun albums(wants: List<Want>): List<Album> =
        wants.groupBy { Matcher.normalizeArtist(it.artists.firstOrNull().orEmpty()) to Matcher.normalizeTitle(it.album) }
            .map { (_, ws) -> Album(ws.first().artistLine, ws.firstOrNull { it.album.isNotBlank() }?.album.orEmpty(), ws) }
            .sortedWith(compareBy({ sortKey(it.artist) }, { it.album.isEmpty() }, { sortKey(it.album) }))

    // ------------------------------------------------------------------ memory

    /**
     * Brings the memory up to date with the syncs' last runs: a new entry gets today as its first
     * sighting, an entry no longer missing is forgotten (back later, it is news again). Returns the
     * new entries; none the first time ever, when everything already missing is old news.
     */
    fun refresh(store: FileStore, now: Long = System.currentTimeMillis(), known: (String?) -> MusicBrainz.Known? = { null }): List<Want> {
        var added: List<Want> = emptyList()
        store.update { d ->
            val first = d.wantedSince == 0L
            val news = ArrayList<Want>()
            val marks = HashMap<String, WantedMark>()
            for (w in collect(d, known)) {
                val old = d.wanted[w.id] ?: w.keys.firstNotNullOfOrNull { d.wanted[it] }
                marks[w.id] = old ?: WantedMark(if (first) w.firstSeen else now).also { if (!first) news += w.copy(firstSeen = now) }
            }
            added = news
            if (!first && marks == d.wanted) d else d.copy(wanted = marks, wantedSince = if (first) now else d.wantedSince)
        }
        return added
    }

    /** Ticks ([got]) or unticks the entries with these ids; returns how many changed. */
    fun mark(store: FileStore, ids: Collection<String>, got: Boolean): Int {
        var changed = 0
        store.update { d ->
            val wanted = ids.toSet()
            val marks = d.wanted.toMutableMap()
            changed = 0
            for (w in collect(d) { null }) {
                if (w.id !in wanted || w.got == got) continue
                w.keys.forEach { marks.remove(it) }
                marks[w.id] = WantedMark(w.firstSeen, got)
                changed++
            }
            if (changed == 0) d else d.copy(wanted = marks)
        }
        return changed
    }

    /**
     * A "since" as a call writes it: epoch milliseconds, a date ("2026-11-01", UTC) or a span back
     * from [now] ("24h", "7d", "2w"). Null when it is none of these.
     */
    fun parseSince(s: String?, now: Long = System.currentTimeMillis()): Long? {
        val v = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return it }
        Regex("""(\d+)\s*([hdw])""").matchEntire(v)?.let { m ->
            val n = m.groupValues[1].toLong()
            return now - n * when (m.groupValues[2]) { "h" -> 3_600_000L; "d" -> 86_400_000L; else -> 7 * 86_400_000L }
        }
        return runCatching { LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    // ------------------------------------------------------------------ formats

    /**
     * The ways out: JSON (everything), CSV (Trackhop and spreadsheets read it back), M3U, plain
     * "Artist - Title" text, and Lidarr's "Custom List" import list.
     */
    enum class Format(val ext: String, val mime: String) {
        JSON("json", "application/json"),
        CSV("csv", "text/csv"),
        M3U("m3u", "audio/x-mpegurl"),
        TEXT("txt", "text/plain"),
        /**
         * Lidarr's Settings › Import Lists › "Custom List": a GET of the list's URL answered with a
         * JSON array of artists, `[{"MusicBrainzId": "<artist MBID>"}]` (Lidarr's CustomImport reads
         * only that field and ignores the rest; ArtistName is for people reading it). Only artists
         * whose MusicBrainz id is known: Lidarr adds artists, not single tracks.
         */
        LIDARR("json", "application/json"),
        ;

        fun encode(wants: List<Want>): String = when (this) {
            JSON -> json(wants).toString()
            CSV -> csv(wants)
            M3U -> PlaylistFiles.toM3u(wants.map { it.track() })
            TEXT -> PlaylistFiles.toText(wants.map { it.track() })
            LIDARR -> lidarr(wants).toString()
        }

        companion object {
            /** By name ("csv", "text" or "txt", "lidarr"…); JSON when not given, null when unknown. */
            fun of(s: String?): Format? {
                val v = s?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return JSON
                return entries.firstOrNull { it.name.lowercase() == v } ?: if (v == "txt") TEXT else null
            }
        }
    }

    fun json(w: Want): JsonObject = buildJsonObject {
        put("id", w.id)
        put("artist", w.artistLine)
        put("artists", buildJsonArray { w.artists.forEach { add(it) } })
        put("title", w.title)
        put("album", w.album)
        put("durationMs", w.durationMs)
        put("isrc", w.isrc?.let { JsonPrimitive(it) } ?: JsonNull)
        put("musicBrainzId", w.recordingMbid?.let { JsonPrimitive(it) } ?: JsonNull)
        put("artistMusicBrainzIds", buildJsonArray { w.artistMbids.forEach { add(it) } })
        put("syncs", buildJsonArray {
            w.syncs.forEach { f -> add(buildJsonObject { put("id", f.syncId); put("name", f.syncName); put("source", f.source); put("target", f.target) }) }
        })
        put("firstSeen", w.firstSeen)
        put("got", w.got)
    }

    fun json(wants: List<Want>): JsonArray = JsonArray(wants.map { json(it) })

    fun lidarr(wants: List<Want>): JsonArray {
        val seen = LinkedHashMap<String, String>()
        for (w in wants) w.artistMbids.forEachIndexed { i, id -> seen.putIfAbsent(id, if (w.artistMbids.size == 1) w.artistLine else w.artists.getOrNull(i) ?: w.artistLine) }
        return JsonArray(seen.map { (id, name) -> buildJsonObject { put("MusicBrainzId", id); put("ArtistName", name) } })
    }

    private fun csv(wants: List<Want>): String = buildString {
        // The first five columns are the playlist CSV's: Trackhop (and other tools) read the file back as a playlist.
        append("title,artist,album,duration_ms,isrc,musicbrainz_id,syncs,first_seen\r\n")
        for (w in wants) {
            val day = Instant.ofEpochMilli(w.firstSeen).atOffset(ZoneOffset.UTC).toLocalDate().toString()
            append(listOf(w.title, w.artistLine, w.album, if (w.durationMs > 0) w.durationMs.toString() else "", w.isrc.orEmpty(), w.recordingMbid.orEmpty(), w.syncs.joinToString("; ") { it.syncName }, day)
                .joinToString(",") { CsvCodec.quote(it) }).append("\r\n")
        }
    }
}
