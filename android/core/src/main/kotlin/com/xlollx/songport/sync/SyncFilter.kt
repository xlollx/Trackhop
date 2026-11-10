package com.xlollx.songport.sync

import com.xlollx.songport.model.Label
import com.xlollx.songport.model.SyncJob
import com.xlollx.songport.model.SyncReport
import java.util.Locale

/**
 * Find and filter on the Syncs tab, for someone with many syncs: a search over the names, labels
 * and tags ("genre:metal" asks for exactly that tag), and chips for the favourites, by status, by
 * service, by label and by tag.
 *
 * Choices in one group widen (errors OR running), groups narrow each other (errors AND Spotify).
 * An empty filter keeps every sync, in the order given.
 */
data class SyncFilter(
    val query: String = "",
    val statuses: Set<Status> = emptySet(),
    /** Provider ids (an account: "spotify", "spotify@2"): a sync matches when its source or target is one. */
    val services: Set<String> = emptySet(),
    /** Label ids (SyncJob.label; a ready colour's key is one). */
    val labels: Set<String> = emptySet(),
    /** The starred syncs only. */
    val favorite: Boolean = false,
    /** Tag keys with the values wanted (any of them); an empty set = the key with any value. Keys narrow each other. */
    val tags: Map<String, Set<String>> = emptyMap(),
    /** These syncs only (the crowded-schedule card's "Show them"); empty: no such limit. */
    val ids: Set<String> = emptySet(),
) {
    /**
     * By the last run: UP_TO_DATE ran fine with nothing left to look at; NEEDS_FIX ran but left songs
     * not found, matches to check or removals held back; ERROR failed; NEVER_RUN has not run yet.
     * RUNNING and DISABLED are about now, whatever the last run was.
     */
    enum class Status { UP_TO_DATE, ERROR, NEEDS_FIX, RUNNING, NEVER_RUN, DISABLED }

    val isActive: Boolean get() = query.isNotBlank() || statuses.isNotEmpty() || services.isNotEmpty() || labels.isNotEmpty() || favorite || tags.isNotEmpty() || ids.isNotEmpty()

    fun toggle(status: Status) = copy(statuses = statuses.toggled(status))
    fun toggleService(id: String) = copy(services = services.toggled(id))
    fun toggleLabel(id: String) = copy(labels = labels.toggled(id))
    fun toggleFavorite() = copy(favorite = !favorite)

    /** Turns the tag [key] on (any value) or off. */
    fun toggleTag(key: String) = copy(tags = if (key in tags) tags - key else tags + (key to emptySet()))

    /** Turns one value of [key] on or off; with its last value off, the key is off. */
    fun toggleTag(key: String, value: String): SyncFilter {
        val next = tags[key].orEmpty().toggled(value)
        return copy(tags = if (next.isEmpty()) tags - key else tags + (key to next))
    }

    /**
     * Without the services, labels and tags no sync uses any more (a sync deleted or changed since):
     * their chips are gone, so they could not be turned off and would hide every card.
     */
    fun pruned(jobs: List<SyncJob>): SyncFilter {
        val s = services.intersect(servicesInUse(jobs).toSet())
        val c = labels.intersect(Labels.inUse(jobs).toSet())
        val i = ids.intersect(jobs.map { it.id }.toSet())
        val f = favorite && jobs.any { it.favorite }
        val t = tags.filterKeys { k -> jobs.any { Labels.hasTag(it, k) } }
            .mapValues { (k, vs) -> vs.filterTo(LinkedHashSet()) { v -> jobs.any { Labels.hasTag(it, k, v) } } }
            .filter { (k, vs) -> vs.isNotEmpty() || tags[k].isNullOrEmpty() }
        return if (s.size == services.size && c.size == labels.size && i.size == ids.size && f == favorite && t == tags) this
        else copy(services = s, labels = c, ids = i, favorite = f, tags = t)
    }

    /**
     * The syncs this filter keeps. [running] are the ids of the syncs running now; [serviceName]
     * gives what a service is called on screen (its label, the account name), so the search finds
     * "spotify" in a sync that only names its playlists; [labelList] are the saved labels, so it
     * finds a label by its name.
     */
    fun select(
        jobs: List<SyncJob>,
        reports: List<SyncReport>,
        running: Set<String>,
        labelList: List<Label> = emptyList(),
        serviceName: (String) -> String = { it },
    ): List<SyncJob> {
        if (!isActive) return jobs
        val terms = terms(query)
        val last = lastReports(jobs, reports)
        return jobs.filter { job ->
            (statuses.isEmpty() || statuses.any { s -> has(job, s, last, running) }) &&
                (services.isEmpty() || job.source.provider in services || job.target.provider in services) &&
                (labels.isEmpty() || job.label in labels) &&
                (!favorite || job.favorite) &&
                tags.all { (k, vs) -> if (vs.isEmpty()) Labels.hasTag(job, k) else vs.any { Labels.hasTag(job, k, it) } } &&
                (ids.isEmpty() || job.id in ids) &&
                (terms.isEmpty() || haystack(job, labelList, serviceName).let { h -> terms.all { matches(job, h, it) } })
        }
    }

    companion object {
        /** How many syncs each status holds now, for the numbers on the chips. */
        fun counts(jobs: List<SyncJob>, reports: List<SyncReport>, running: Set<String>): Map<Status, Int> {
            val last = lastReports(jobs, reports)
            return Status.entries.associateWith { s -> jobs.count { has(it, s, last, running) } }
        }

        private fun lastReports(jobs: List<SyncJob>, reports: List<SyncReport>): Map<String, SyncReport> {
            val ids = jobs.mapNotNullTo(HashSet()) { it.lastReportId }
            return reports.filter { it.id in ids }.associateBy { it.id }
        }

        private fun has(job: SyncJob, s: Status, last: Map<String, SyncReport>, running: Set<String>): Boolean {
            val r = job.lastReportId?.let { last[it] }
            return when (s) {
                Status.ERROR -> r != null && !r.ok
                Status.NEEDS_FIX -> r != null && r.ok && needsFix(r)
                Status.UP_TO_DATE -> r != null && r.ok && !needsFix(r) && !r.partial
                Status.NEVER_RUN -> job.lastReportId == null
                Status.RUNNING -> job.id in running
                Status.DISABLED -> !job.enabled
            }
        }

        private fun needsFix(r: SyncReport) = r.unmatched.isNotEmpty() || r.reviewTracks.isNotEmpty() || r.heldBack > 0

        /** The services the syncs use, each once, in the order the list shows them. */
        fun servicesInUse(jobs: List<SyncJob>): List<String> =
            jobs.flatMap { listOf(it.source.provider, it.target.provider) }.filter { it.isNotBlank() }.distinct()

        /**
         * Lower case, without accents, split on spaces: "Café  Mix" -> [cafe, mix]. Double quotes keep
         * words together, in a tag too: genre:"heavy metal" -> [genre:heavy metal].
         */
        fun terms(query: String): List<String> {
            val out = ArrayList<String>()
            val word = StringBuilder()
            var quoted = false
            fun end() { if (word.isNotBlank()) out += word.toString().trim(); word.setLength(0) }
            for (c in fold(query)) when {
                c == '"' -> quoted = !quoted
                c == ' ' && !quoted -> end()
                else -> word.append(c)
            }
            end()
            return out
        }

        private fun fold(s: String): String = Labels.fold(s)

        /**
         * A word of the search: "key:value" (or "key:") asks for exactly that tag; any other word, and
         * a "key:value" no tag answers (a name with a colon in it), is looked for in the text.
         */
        private fun matches(job: SyncJob, haystack: String, term: String): Boolean {
            val cut = term.indexOf(':')
            if (cut > 0 && Labels.hasTag(job, term.substring(0, cut), term.substring(cut + 1))) return true
            return term in haystack
        }

        private fun haystack(job: SyncJob, labels: List<Label>, serviceName: (String) -> String): String = fold(
            (listOf(
                job.name, job.source.playlistName, job.target.playlistName,
                serviceName(job.source.provider), serviceName(job.target.provider),
                Labels.name(job, labels),
            ) + job.tags.flatMap { listOf(it.key, it.value) }).joinToString(" "),
        )

        private fun <T> Set<T>.toggled(x: T): Set<T> = if (x in this) this - x else this + x
    }
}
