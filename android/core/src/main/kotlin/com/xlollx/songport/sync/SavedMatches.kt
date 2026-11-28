package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.Track

/**
 * Tools › Saved matches (1.18): what Trackhop remembers across all syncs, one entry per source song
 * and target service, read from the caches the engine already keeps (matches, searches without a
 * result, songs ignored, the review's choices). A change here is made in those same caches, so it
 * holds for every sync between the two services, as a choice in a run's review does. Pure Kotlin.
 */
object SavedMatches {

    enum class Kind { CHOSEN, AUTO, NOT_FOUND, IGNORED }

    /**
     * [source] and [target] are service families ([FileStore.family]; the target keeps a library's
     * kind, "amazon#album"); [keys] every key of the store that says something about this song,
     * the old ones under a connector's own id too, so forgetting it leaves nothing behind.
     */
    data class Entry(
        val source: String,
        val sourceId: String,
        val target: String,
        val kind: Kind,
        val chosenId: String?,
        val at: Long,
        val keys: Set<String>,
    ) {
        val key: String get() = "$source:$sourceId>$target"
        /** The target's service, without a library's kind. */
        val targetService: String get() = target.substringBefore('#')
    }

    /** Everything remembered, the newest choices first. */
    fun entries(d: StoreData, now: Long = System.currentTimeMillis()): List<Entry> {
        class Acc(val source: String, val id: String, val target: String) { val keys = LinkedHashSet<String>() }
        val all = LinkedHashMap<String, Acc>()
        fun add(raw: String) {
            val colon = raw.indexOf(':')
            val arrow = raw.lastIndexOf('>')
            if (colon <= 0 || arrow <= colon + 1 || arrow == raw.length - 1) return
            val src = FileStore.family(raw.substring(0, colon))
            val dst = FileStore.family(raw.substring(arrow + 1))
            val id = raw.substring(colon + 1, arrow)
            all.getOrPut("$src:$id>$dst") { Acc(src, id, dst) }.keys += raw
        }
        // Newest first: the caches keep the order in which they were written.
        d.reviewDecisions.entries.sortedByDescending { it.value.at }.forEach { add(it.key) }
        d.matchCache.keys.reversed().forEach(::add)
        val missesValid = d.missRevision == Matcher.REVISION
        d.missCache.entries.filter { missesValid && now - it.value < FileStore.MISS_TTL_MS && FileStore.missValid(d, it.key) }.sortedByDescending { it.value }.forEach { add(it.key) }
        d.ignoredTracks.reversed().forEach(::add)
        return all.values.mapNotNull { a ->
            val decision = a.keys.mapNotNull { d.reviewDecisions[it] }.maxByOrNull { it.at }
            val cached = a.keys.firstNotNullOfOrNull { d.matchCache[it] }
            val missAt = if (missesValid) a.keys.filter { FileStore.missValid(d, it) }.mapNotNull { d.missCache[it] }.maxOrNull()?.takeIf { now - it < FileStore.MISS_TTL_MS } else null
            val ignored = a.keys.any { it in d.ignoredTracks } || decision?.kind == DecisionKind.IGNORE
            when {
                ignored -> Entry(a.source, a.id, a.target, Kind.IGNORED, null, decision?.at ?: 0, a.keys)
                decision != null && (cached ?: decision.track) != null -> Entry(a.source, a.id, a.target, Kind.CHOSEN, cached ?: decision.track, decision.at, a.keys)
                cached != null -> Entry(a.source, a.id, a.target, Kind.AUTO, cached, 0, a.keys)
                missAt != null -> Entry(a.source, a.id, a.target, Kind.NOT_FOUND, null, missAt, a.keys)
                else -> null
            }
        }
    }

    /** The syncs between [e]'s two services: the ones a change to it reaches. */
    fun syncsBetween(d: StoreData, e: Entry): List<SyncJob> = d.syncs.filter {
        FileStore.family(it.source.provider) == e.source && FileStore.family(FileStore.matchTarget(it.target.provider, it.target.playlistId)) == e.target
    }

    /** Forget it: the next sync that meets the song searches it again. */
    fun forget(store: FileStore, e: Entry) = store.update { d ->
        d.copy(
            matchCache = d.matchCache - e.keys, missCache = d.missCache - e.keys, ignoredTracks = d.ignoredTracks - e.keys,
            reviewDecisions = d.reviewDecisions - e.keys, jobs = unignored(d, e),
        )
    }

    /** [forget] for several at once, in one write of the store (a few thousand ticked rows stay quick). */
    fun forget(store: FileStore, entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        val keys = entries.flatMapTo(HashSet()) { it.keys }
        store.update { d ->
            val between = entries.flatMapTo(HashSet()) { e -> syncsBetween(d, e).map { it.id to e.sourceId } }
            d.copy(
                matchCache = d.matchCache - keys, missCache = d.missCache - keys, ignoredTracks = d.ignoredTracks - keys,
                reviewDecisions = d.reviewDecisions - keys,
                jobs = d.jobs.map { j ->
                    val back = j.ignoredSourceIds.filterTo(HashSet()) { (j.id to it) in between }
                    if (back.isEmpty()) j else j.copy(ignoredSourceIds = j.ignoredSourceIds - back)
                },
            )
        }
    }

    /** How many matches are saved to [service] (a service family, without a library's kind), every kind of target. */
    fun countTo(d: StoreData, service: String): Int =
        d.matchCache.keys.count { FileStore.family(it.substringAfterLast('>', "")).substringBefore('#') == service }

    /**
     * Every saved match to [service] forgotten at once, the songs not found there too: for a server
     * whose library was imported anew (#95), where each one points to a song it no longer has. The
     * next syncs search them again; songs ignored there stay ignored. Returns how many matches went.
     */
    fun forgetAllTo(store: FileStore, service: String): Int = store.forgetMatchesTo(service, null)

    /** Not on the target service: no sync looks for it there any more (the review's "Ignore"). */
    fun notThere(store: FileStore, e: Entry, now: Long = System.currentTimeMillis()) = store.update { d ->
        val key = FileStore.cacheKey(e.source, e.sourceId, e.target)
        d.copy(
            matchCache = d.matchCache - e.keys, missCache = d.missCache - e.keys, ignoredTracks = d.ignoredTracks - e.keys + key,
            reviewDecisions = ReviewDecisions.bounded(d.reviewDecisions - e.keys + (key to ReviewDecision(DecisionKind.IGNORE, null, now))),
        )
    }

    /** [trackId] of the target is the song: every sync between the two services uses it from now on. */
    fun choose(store: FileStore, e: Entry, trackId: String, now: Long = System.currentTimeMillis()) = store.update { d ->
        val key = FileStore.cacheKey(e.source, e.sourceId, e.target)
        d.copy(
            matchCache = d.matchCache - e.keys + (key to trackId), missCache = d.missCache - e.keys, ignoredTracks = d.ignoredTracks - e.keys,
            reviewDecisions = ReviewDecisions.bounded(d.reviewDecisions - e.keys + (key to ReviewDecision(DecisionKind.MATCH, trackId, now))),
            jobs = unignored(d, e),
        )
    }

    /** The review's "Ignore" also kept the song out of its own sync: taken back there too. */
    private fun unignored(d: StoreData, e: Entry): List<SyncJob> {
        val between = syncsBetween(d, e).mapTo(HashSet()) { it.id }
        return d.jobs.map { j -> if (j.id in between && e.sourceId in j.ignoredSourceIds) j.copy(ignoredSourceIds = j.ignoredSourceIds - e.sourceId) else j }
    }

    /** Key of [names]: the service (without a library's kind) and the track's id there. */
    fun nameKey(family: String, id: String) = family.substringBefore('#') + ":" + id

    /** The songs the runs' reports name, on either side: the list shows them without asking any service. */
    fun names(d: StoreData): Map<String, Track> {
        val out = HashMap<String, Track>()
        val jobs = d.jobs.associateBy { it.id }
        for (r in d.reports) {
            val job = jobs[r.jobId] ?: continue
            val src = FileStore.family(job.source.provider)
            val dst = FileStore.family(job.target.provider)
            r.reviewTracks.forEach { out.putIfAbsent(nameKey(src, it.source.id), it.source); out.putIfAbsent(nameKey(dst, it.chosen.id), it.chosen) }
            r.unmatchedTracks.forEach { out.putIfAbsent(nameKey(src, it.id), it) }
            (r.addedTracks + r.removedTracks + r.suggestions.values).forEach { out.putIfAbsent(nameKey(dst, it.id), it) }
        }
        return out
    }

    /** [e] has [query] in the name of its song or of the song chosen for it (or in an id). */
    fun matches(e: Entry, names: Map<String, Track>, query: String): Boolean {
        val q = query.trim().lowercase().ifEmpty { return true }
        fun hit(t: Track?) = t != null && (t.title.lowercase().contains(q) || t.artists.any { it.lowercase().contains(q) } || "${t.artistLine} ${t.title}".lowercase().contains(q))
        return hit(names[nameKey(e.source, e.sourceId)]) || hit(e.chosenId?.let { names[nameKey(e.target, it)] }) || e.sourceId.lowercase() == q
    }
}
