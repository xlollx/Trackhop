package com.xlollx.songport.sync

import com.xlollx.songport.data.FileStore
import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import com.xlollx.songport.model.Track

/**
 * Saved matches › To fix (2.1, issue #69): every track the last run of every sync left not found
 * or matched loosely, in one list. A track that several syncs between the same two services list
 * is one row: what is chosen for it is a saved match ([SavedMatches]'s caches), so it holds for all
 * of them: fix once. "Later" sets a row aside until the next run of its sync. Pure Kotlin.
 */
object ToFix {

    enum class Kind { NOT_FOUND, UNCERTAIN }

    /** One sync whose last report lists the track; [chosen] is the uncertain match that run made, with its [score]. */
    data class Place(val jobId: String, val jobName: String, val reportId: String, val chosen: Track? = null, val score: Double = 0.0)

    /**
     * A track to fix: [source] and [target] are the caches' families ([FileStore.cacheKey]),
     * [suggestion] the song to accept (the uncertain match, or the best candidate under the
     * threshold of a track not found) with its [score]; [absent] when the service returned nothing
     * at all. [places] are the syncs it shows in, in the order of the syncs' list.
     */
    data class Item(
        val source: String,
        val target: String,
        val track: Track,
        val kind: Kind,
        val suggestion: Track?,
        val score: Double,
        val absent: Boolean,
        val places: List<Place>,
    ) {
        val key: String get() = "$source:${track.id}>$target"
        /** The target's service, without a library's kind. */
        val targetService: String get() = target.substringBefore('#')
    }

    /** What applying a choice did: the syncs it reached, and those whose playlist could not be written now. */
    data class Outcome(val syncs: List<String>, val failed: List<String>)

    /** A group of the list: a sync ([id] its job) or a target service ([id] its family). */
    data class Group(val id: String, val name: String, val items: List<Item>)

    private fun keyOf(job: SyncJob, sourceId: String) =
        FileStore.cacheKey(job.source.provider, sourceId, FileStore.matchTarget(job.target.provider, job.target.playlistId))

    /** The last report of each sync that is not being written right now, in the syncs' order. */
    private fun lastReports(d: StoreData): List<Pair<SyncJob, SyncReport>> {
        val reports = d.reports.associateBy { it.id }
        return d.syncs.mapNotNull { j -> j.lastReportId?.let(reports::get)?.takeIf { !it.partial }?.let { j to it } }
    }

    /**
     * The list: one item per track and pair of services, the rows set aside with "Later" left out
     * (all of them with [withLater], which is what a choice reaches).
     */
    fun items(d: StoreData, withLater: Boolean = false): List<Item> {
        class Acc(val source: String, val target: String, val track: Track) {
            val places = ArrayList<Place>()
            var suggestion: Track? = null
            var score = 0.0
            var uncertain = false
            var absent = true
        }
        val all = LinkedHashMap<String, Acc>()
        for ((job, report) in lastReports(d)) {
            val later = if (withLater) emptySet() else d.toFixLater[report.id].orEmpty()
            val source = FileStore.family(job.source.provider)
            val target = FileStore.family(FileStore.matchTarget(job.target.provider, job.target.playlistId))
            for (r in report.reviewTracks.distinctBy { it.source.id }) {
                if (r.source.id in later) continue
                val a = all.getOrPut(keyOf(job, r.source.id)) { Acc(source, target, r.source) }
                a.places += Place(job.id, job.name, report.id, r.chosen, r.score)
                // The match a run made counts more than a candidate another run only proposed.
                if (!a.uncertain) { a.suggestion = r.chosen; a.score = r.score }
                a.uncertain = true
                a.absent = false
            }
            for (t in report.unmatchedTracks.distinctBy { it.id }) {
                if (t.id in later) continue
                val a = all.getOrPut(keyOf(job, t.id)) { Acc(source, target, t) }
                // Listed twice by one report (an old one): once here.
                if (a.places.any { it.reportId == report.id }) continue
                a.places += Place(job.id, job.name, report.id)
                val hint = report.suggestions[t.id]
                if (a.suggestion == null && hint != null) { a.suggestion = hint; a.score = Matcher.score(t, hint, job.policy) }
                if (t.id !in report.absent) a.absent = false
            }
        }
        return all.values.map { a ->
            Item(a.source, a.target, a.track, if (a.uncertain) Kind.UNCERTAIN else Kind.NOT_FOUND, a.suggestion, a.score, a.absent && a.suggestion == null, a.places)
        }
    }

    /** How many tracks wait: the figure in the tab. */
    fun count(d: StoreData): Int = items(d).size

    /** [items] under the sync that lists them: a track of several syncs stays one row, under the first of them. */
    fun bySync(items: List<Item>): List<Group> =
        items.groupBy { it.places.first().jobId }.map { (id, list) -> Group(id, list.first().places.first().jobName, list) }

    /** [items] under the service they are missing on; [name] says how a family is called. */
    fun byService(items: List<Item>, name: (String) -> String): List<Group> =
        items.groupBy { it.targetService }.map { (family, list) -> Group(family, name(family), list) }

    /**
     * The rows ticked in the list ([keys]), in its order, as "Accept suggestions" splits them: first
     * those with a suggestion, which it accepts, then those it leaves (nothing to accept).
     */
    fun ticked(items: List<Item>, keys: Set<String>): Pair<List<Item>, List<Item>> =
        items.filter { it.key in keys }.partition { it.suggestion != null }

    /** "Later": [item] leaves the list until the next run of each sync that lists it. */
    fun later(store: FileStore, item: Item) = store.update { d ->
        // Only a sync's last report is listed: what was set aside in an older one is forgotten.
        val live = d.jobs.mapNotNullTo(HashSet()) { it.lastReportId }
        var later = d.toFixLater.filterKeys { it in live }
        for (p in item.places) if (p.reportId in live) later = later + (p.reportId to (later[p.reportId].orEmpty() + item.track.id))
        d.copy(toFixLater = later)
    }

    /** Takes [later] back: the row returns now. */
    fun undoLater(store: FileStore, item: Item) = store.update { d ->
        val reports = item.places.mapTo(HashSet()) { it.reportId }
        d.copy(toFixLater = d.toFixLater.mapValues { (id, set) -> if (id in reports) set - item.track.id else set }.filterValues { it.isNotEmpty() })
    }

    /**
     * The choice for [item], written in one go before any playlist is touched: [chosenId] of the
     * target is its song (null: it is not on that service, the review's "Ignore"), for every sync
     * between the two services, and the track leaves the last report of each sync that lists it
     * (those set aside with "Later" too). Done twice it changes nothing more. Returns the syncs it
     * left, with the uncertain match each had: what [SyncEngine.fixEverywhere] then writes to.
     */
    fun settle(store: FileStore, item: Item, chosenId: String?, now: Long = System.currentTimeMillis()): List<Place> {
        var places = emptyList<Place>()
        store.update { d ->
            val id = item.track.id
            val current = items(d, withLater = true).firstOrNull { it.key == item.key }
            places = current?.places.orEmpty()
            val jobs = d.syncs.filter { keyOf(it, id) == item.key }
            // The keys written before they were per family, under a connector's own id, go too.
            val keys = jobs.mapTo(HashSet()) { "${it.source.provider}:$id>${FileStore.matchTarget(it.target.provider, it.target.playlistId)}" } + item.key
            val jobIds = jobs.mapTo(HashSet()) { it.id }
            val reportIds = places.mapTo(HashSet()) { it.reportId }
            val listed = places.mapTo(HashSet()) { it.jobId }
            val kept = chosenId != null && places.isNotEmpty() && places.all { it.chosen?.id == chosenId }
            val decision = when {
                chosenId == null -> ReviewDecision(DecisionKind.IGNORE, null, now)
                kept -> ReviewDecision(DecisionKind.KEEP, chosenId, now)
                else -> ReviewDecision(DecisionKind.MATCH, chosenId, now)
            }
            d.copy(
                matchCache = if (chosenId == null) d.matchCache - keys else d.matchCache - keys + (item.key to chosenId),
                missCache = d.missCache - keys,
                ignoredTracks = if (chosenId == null) d.ignoredTracks - keys + item.key else d.ignoredTracks - keys,
                reviewDecisions = ReviewDecisions.bounded(d.reviewDecisions - keys + (item.key to decision)),
                jobs = d.jobs.map { j ->
                    when {
                        j.id !in jobIds -> j
                        chosenId == null -> if (id in j.ignoredSourceIds || j.id !in listed) j else j.copy(ignoredSourceIds = j.ignoredSourceIds + id)
                        id in j.ignoredSourceIds -> j.copy(ignoredSourceIds = j.ignoredSourceIds - id)
                        else -> j
                    }
                },
                reports = d.reports.map { r ->
                    if (r.id !in reportIds) r else {
                        val names = r.unmatchedTracks.filter { it.id == id }.mapTo(HashSet()) { it.toString() }
                        r.copy(
                            unmatchedTracks = r.unmatchedTracks.filter { it.id != id },
                            unmatched = r.unmatched.filter { it !in names },
                            reviewTracks = r.reviewTracks.filter { it.source.id != id },
                            suggestions = r.suggestions - id,
                        )
                    }
                },
                toFixLater = d.toFixLater.mapValues { (rid, set) -> if (rid in reportIds) set - id else set }.filterValues { it.isNotEmpty() },
            )
        }
        return places
    }
}
