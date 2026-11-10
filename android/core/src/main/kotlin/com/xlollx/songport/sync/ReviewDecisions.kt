package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the user chose for a track in a run's review. */
@Serializable
enum class DecisionKind { IGNORE, MATCH, KEEP }

/**
 * One choice of the review: [IGNORE][DecisionKind.IGNORE] (not on that service), [MATCH][DecisionKind.MATCH]
 * (this [track] of the destination, chosen by hand) or [KEEP][DecisionKind.KEEP] (the match found, [track],
 * is right), and when it was made (epoch ms).
 */
@Serializable
data class ReviewDecision(val kind: DecisionKind, val track: String? = null, val at: Long)

/** A sync handed over to the user's own server: its id there, when, and whether it was on here before. */
@Serializable
data class HandedOver(val serverId: String, val at: Long, val wasEnabled: Boolean = true)

/**
 * The review's choices shared between this device and the user's own Trackhop server, for a sync
 * both know (handed over, or the same two playlists on both). Each side keeps its choices with when
 * they were made ([StoreData.reviewDecisions]); an exchange sends one side's to the other, and for
 * each track the newest choice wins on both (the same instant: a fixed order, so both pick alike).
 * A choice taken in does what the review would have done: an ignored track stays out of the sync, a
 * match chosen or kept goes in the match cache, and the track leaves the last run's lists.
 */
object ReviewDecisions {
    /** Choices kept at most on a device: past that, the oldest go (the caches themselves keep them). */
    const val MAX_STORED = 20_000
    /** Choices in one exchange, either way. */
    const val MAX_EXCHANGED = 5_000
    private const val MAX_ID = 512

    fun bounded(m: Map<String, ReviewDecision>): Map<String, ReviewDecision> =
        if (m.size <= MAX_STORED) m else m.entries.sortedBy { it.value.at }.takeLast(MAX_STORED).associate { it.key to it.value }

    /** True when [b] replaces [a]: it is newer; made at the same instant, a fixed order decides. */
    fun wins(b: ReviewDecision, a: ReviewDecision?): Boolean {
        if (a == null) return true
        if (b.at != a.at) return b.at > a.at
        return compareValuesBy(b, a, { it.kind.ordinal }, { it.track.orEmpty() }) > 0
    }

    /** [a] and [b] together, the newest choice for each track. The same whichever is given first. */
    fun merge(a: Map<String, ReviewDecision>, b: Map<String, ReviewDecision>): Map<String, ReviewDecision> =
        (a.keys + b.keys).associateWith { k ->
            val x = a[k]
            val y = b[k]
            if (y != null && wins(y, x)) y else x!!
        }

    /** [job]'s choices, by source track id: those between its two services, whichever sync made them. */
    fun ofSync(data: StoreData, job: SyncJob): Map<String, ReviewDecision> {
        val prefix = FileStore.family(job.source.provider) + ":"
        val suffix = ">" + FileStore.family(job.target.provider)
        return data.reviewDecisions.entries
            .filter { (k, _) -> k.length > prefix.length + suffix.length && k.startsWith(prefix) && k.endsWith(suffix) }
            .associate { (k, v) -> k.substring(prefix.length, k.length - suffix.length) to v }
    }

    /**
     * Takes in [incoming] (by source track id) for the sync [jobId]: each choice newer than this
     * device's is kept and done. Only [jobId]'s two services are touched, whatever [incoming] holds.
     * Returns how many choices were taken.
     */
    fun apply(store: FileStore, jobId: String, incoming: Map<String, ReviewDecision>): Int {
        var taken = 0
        store.update { d ->
            val job = d.jobs.firstOrNull { it.id == jobId } ?: return@update d
            val key = { id: String -> FileStore.cacheKey(job.source.provider, id, job.target.provider) }
            val won = incoming.filter { (id, dec) -> wins(dec, d.reviewDecisions[key(id)]) }
            taken = won.size
            if (won.isEmpty()) return@update d
            val ignored = won.filterValues { it.kind == DecisionKind.IGNORE }.keys
            val matched = won.filterValues { it.kind != DecisionKind.IGNORE && it.track != null }.mapValues { it.value.track!! }
            val matchKeys = matched.mapKeys { key(it.key) }
            // Bounded like the caches: a peer sending thousands of new choices each time cannot grow them for ever.
            val ignoredKeys = ignored.map(key).filter { it !in d.ignoredTracks }.take((MAX_STORED * 2 - d.ignoredTracks.size).coerceAtLeast(0)).toSet()
            var cache = d.matchCache - ignoredKeys + matchKeys
            if (cache.size > MAX_STORED) cache = cache.entries.drop(cache.size - MAX_STORED).associate { it.key to it.value }
            d.copy(
                reviewDecisions = bounded(d.reviewDecisions + won.mapKeys { key(it.key) }),
                // A newer choice of a match takes the track out of the ignored ones, and the other way round.
                ignoredTracks = d.ignoredTracks - matchKeys.keys + ignoredKeys,
                matchCache = cache,
                missCache = d.missCache - matchKeys.keys,
                jobs = d.jobs.map { j ->
                    if (j.id != job.id) j else j.copy(ignoredSourceIds = ((j.ignoredSourceIds - matched.keys) + ignored).distinct().takeLast(MAX_STORED))
                },
                reports = d.reports.map { r ->
                    if (r.id != job.lastReportId) r else {
                        val gone = r.unmatchedTracks.filter { it.id in won.keys }
                        val names = gone.map { it.toString() }.toSet()
                        r.copy(
                            unmatchedTracks = r.unmatchedTracks - gone.toSet(),
                            unmatched = r.unmatched.filter { it !in names },
                            reviewTracks = r.reviewTracks.filter { it.source.id !in won.keys },
                            suggestions = r.suggestions - won.keys,
                        )
                    }
                },
            )
        }
        return taken
    }

    /**
     * As the API sends them: `[{"source": id, "kind": "ignore"|"match"|"keep", "track"?: id, "at": ms}]`,
     * the newest [MAX_EXCHANGED] only.
     */
    fun toJson(decisions: Map<String, ReviewDecision>): JsonArray = buildJsonArray {
        decisions.entries.sortedBy { it.value.at }.takeLast(MAX_EXCHANGED).forEach { (id, d) ->
            add(buildJsonObject {
                put("source", id)
                put("kind", d.kind.name.lowercase())
                d.track?.let { put("track", it) }
                put("at", d.at)
            })
        }
    }

    /**
     * The choices in [e] ([toJson]'s shape), checked: an unexpected shape is an [IllegalArgumentException]
     * with words for the caller. A time later than [now] counts as [now], so a clock ahead (or a
     * hostile peer) cannot make a choice win for ever.
     */
    fun parse(e: JsonElement?, now: Long): Map<String, ReviewDecision> {
        val list = e as? JsonArray ?: throw IllegalArgumentException("\"decisions\" is a list: [{\"source\", \"kind\", \"track\"?, \"at\"}].")
        if (list.size > MAX_EXCHANGED) throw IllegalArgumentException("At most $MAX_EXCHANGED decisions at a time.")
        val out = HashMap<String, ReviewDecision>()
        for (item in list) {
            val o = item as? JsonObject ?: throw IllegalArgumentException("Each decision is an object.")
            fun text(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.contentOrNull
            val source = text("source")?.takeIf { it.isNotBlank() && it.length <= MAX_ID && it.none { c -> c.isISOControl() } }
                ?: throw IllegalArgumentException("\"source\" is the track's id on the source service.")
            val kind = when (text("kind")) {
                "ignore" -> DecisionKind.IGNORE
                "match" -> DecisionKind.MATCH
                "keep" -> DecisionKind.KEEP
                else -> throw IllegalArgumentException("\"kind\" is ignore, match or keep.")
            }
            val track = text("track")
            if (kind != DecisionKind.IGNORE && (track.isNullOrBlank() || track.length > MAX_ID || track.any { it.isISOControl() }))
                throw IllegalArgumentException("\"track\" is the track's id on the destination service, for match and keep.")
            val at = (o["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("\"at\" is when it was chosen, in milliseconds since 1970.")
            val d = ReviewDecision(kind, if (kind == DecisionKind.IGNORE) null else track, minOf(at, now))
            if (wins(d, out[source])) out[source] = d
        }
        return out
    }
}
