package com.xlollx.songport.sync

import com.xlollx.songport.data.StoreData
import com.xlollx.songport.model.Label
import com.xlollx.songport.model.SyncJob
import java.util.Locale
import java.util.UUID

/**
 * Labels and tags, the two ways to group syncs.
 *
 * A sync wears ONE label: its card's colour. The eight ready colours are labels without a name,
 * their key as the id ("teal"); the user can name any of them, and can save a colour of their own
 * ("#RRGGBB") as a named label, which then shows among the ready colours. A colour of the user's
 * own without a name is a free colour: no label, no chip. SyncJob.color always holds the colour
 * the card wears, so an older Trackhop, a widget or a script reads it as before.
 *
 * Tags are colourless key/value pairs ("genre" = "metal"); the value may be empty.
 */
object Labels {
    /** The ready colours, in the order the pickers show them. */
    val BASE = listOf("red", "orange", "yellow", "green", "teal", "blue", "purple", "pink")

    private val HEX = Regex("#[0-9A-Fa-f]{6}")
    private val SPACES = Regex("\\s+")
    private val NOT_IN_KEY = Regex("[:=,\"]")
    const val MAX_TAGS = 20
    const val MAX_KEY = 40
    const val MAX_VALUE = 60
    const val MAX_NAME = 40

    fun isBase(key: String?) = key != null && key in BASE
    fun isCustom(color: String?) = color != null && HEX.matches(color)

    /** Lower case, without accents, single spaces: how names, keys and values are compared. */
    fun fold(s: String): String = Matcher.stripDiacritics(s).lowercase(Locale.ROOT).replace(SPACES, " ").trim()

    /** The label with that id: a saved one, or a ready colour nobody named. */
    fun find(labels: List<Label>, id: String?): Label? =
        if (id == null) null else labels.firstOrNull { it.id == id } ?: if (isBase(id)) Label(id, id) else null

    /** The label [job] wears, or null (no colour, or a free colour). */
    fun of(job: SyncJob, labels: List<Label>): Label? = find(labels, job.label)

    /** The name of the label [job] wears; "" when it has none. */
    fun name(job: SyncJob, labels: List<Label>): String = of(job, labels)?.name.orEmpty()

    /** The label [wanted] names: by id, by a ready colour's key, or by name (case and accents aside). */
    fun resolve(labels: List<Label>, wanted: String): Label? {
        val w = wanted.trim()
        find(labels, w)?.let { return it }
        val f = fold(w)
        return labels.firstOrNull { it.name.isNotBlank() && fold(it.name) == f } ?: find(labels, f)
    }

    /**
     * [job] as it is kept, and the rule that carries the colours of before labels over:
     * a label that exists gives the card its colour; a label that is gone leaves the colour as a
     * free one; a sync with one of the ready colours and no label wears that colour's label. A
     * colour of the user's own stays as it is, without a label. Tags are tidied ([cleanTags]).
     */
    fun normalized(job: SyncJob, labels: List<Label>): SyncJob {
        val l = find(labels, job.label)
        val tags = cleanTags(job.tags)
        val label = l?.id ?: job.color?.takeIf { job.label == null && isBase(it) }
        val color = l?.color ?: job.color
        return if (label == job.label && color == job.color && tags == job.tags) job else job.copy(label = label, color = color, tags = tags)
    }

    fun normalized(d: StoreData): StoreData {
        val labels = d.settings.labels.filter { it.id.isNotBlank() && (isBase(it.color) || isCustom(it.color)) }.distinctBy { it.id }
        val jobs = d.jobs.map { normalized(it, labels) }
        return if (jobs == d.jobs && labels == d.settings.labels) d else d.copy(jobs = jobs, settings = d.settings.copy(labels = labels))
    }

    /**
     * Gives [color] (a ready colour's key or "#RRGGBB") the name [name]: names a ready colour, or
     * saves a colour of the user's own as a label; the syncs already wearing that exact colour wear
     * the label from now on. Returns the data and the label's id (null when [color] is not a colour).
     */
    fun save(d: StoreData, color: String, name: String, newId: () -> String = { "l-" + UUID.randomUUID().toString().take(8) }): Pair<StoreData, String?> {
        val n = name.trim().take(MAX_NAME)
        val labels = d.settings.labels
        if (isBase(color)) {
            val next = labels.filter { it.id != color } + listOfNotNull(Label(color, color, n).takeIf { n.isNotEmpty() })
            return normalized(d.copy(settings = d.settings.copy(labels = next))) to color
        }
        if (!isCustom(color) || n.isEmpty()) return d to null
        val hex = color.uppercase(Locale.ROOT)
        val existing = labels.firstOrNull { it.color.equals(hex, ignoreCase = true) }
        val label = existing?.copy(name = n) ?: Label(newId(), hex, n)
        val next = if (existing != null) labels.map { if (it.id == label.id) label else it } else labels + label
        val jobs = d.jobs.map { if (it.label == null && it.color.equals(hex, ignoreCase = true)) it.copy(label = label.id) else it }
        return normalized(d.copy(jobs = jobs, settings = d.settings.copy(labels = next))) to label.id
    }

    /** Renames the label [id]; an empty name deletes it ([delete]). */
    fun rename(d: StoreData, id: String, name: String): StoreData {
        val n = name.trim().take(MAX_NAME)
        if (n.isEmpty()) return delete(d, id)
        if (isBase(id)) return save(d, id, n).first
        return d.copy(settings = d.settings.copy(labels = d.settings.labels.map { if (it.id == id) it.copy(name = n) else it }))
    }

    /** Gives the label [id] (one saved from a colour of the user's own) another colour: its syncs follow. */
    fun recolor(d: StoreData, id: String, color: String): StoreData {
        if (isBase(id) || !isCustom(color) || d.settings.labels.none { it.id == id }) return d
        val hex = color.uppercase(Locale.ROOT)
        return normalized(d.copy(settings = d.settings.copy(labels = d.settings.labels.map { if (it.id == id) it.copy(color = hex) else it })))
    }

    /** Deletes the label [id]: its syncs keep their colour, which has no name any more. */
    fun delete(d: StoreData, id: String): StoreData =
        normalized(d.copy(settings = d.settings.copy(labels = d.settings.labels.filter { it.id != id })))

    /**
     * What the card menu's "Style" saves for the syncs [ids]: [pick] is a ready colour's key, a saved
     * label's id, "#RRGGBB" or null (no colour); [name] names that colour (empty: no name, so a saved
     * label is deleted and its colour stays); [recolor] gives a saved label another colour first.
     */
    fun styled(d: StoreData, ids: Set<String>, pick: String?, name: String, recolor: String? = null, newId: () -> String = { "l-" + UUID.randomUUID().toString().take(8) }): StoreData {
        var data = d
        var p = pick
        when {
            p == null -> {}
            isBase(p) -> data = save(data, p, name).first
            isCustom(p) -> if (name.isNotBlank()) save(data, p, name, newId).let { (next, id) -> data = next; p = id }
            else -> {
                if (recolor != null) data = recolor(data, p, recolor)
                // Without a name the label goes: these syncs take its colour as a free one.
                if (name.isBlank()) p = find(data.settings.labels, p)?.color.also { data = delete(data, pick!!) }
                else data = rename(data, p, name)
            }
        }
        val labels = data.settings.labels
        return data.copy(jobs = data.jobs.map { j ->
            if (j.id !in ids) j
            else normalized(if (p == null || isCustom(p)) j.copy(color = p, label = null) else j.copy(label = p), labels)
        })
    }

    /**
     * Where FileStore.moveJob puts [id] so that the list as shown ([favoritesFirst]) has it at
     * [shownIndex]: before the sync that follows it there. A sync dropped among those of the other
     * kind (starred or not) ends at the edge of its own.
     */
    fun storeIndex(jobs: List<SyncJob>, id: String, shownIndex: Int): Int {
        val job = jobs.firstOrNull { it.id == id } ?: return shownIndex
        val shown = favoritesFirst(jobs).filter { it.id != id }
        val rest = jobs.map { it.id }.filter { it != id }
        // Its own kind's stretch of the list as shown: the starred, then the others.
        val stars = shown.count { it.favorite }
        val split = jobs.any { it.favorite } && jobs.any { !it.favorite }
        val from = if (split && !job.favorite) stars else 0
        val to = if (split && job.favorite) stars else shown.size
        val at = shownIndex.coerceIn(from, to)
        return when {
            at > from -> rest.indexOf(shown[at - 1].id) + 1
            at < to -> rest.indexOf(shown[at].id)
            else -> if (job.favorite) 0 else rest.size
        }
    }

    /** The labels the syncs wear, each once: ready colours first in their order, then the user's own. */
    fun inUse(jobs: List<SyncJob>): List<String> {
        val used = jobs.mapNotNullTo(LinkedHashSet()) { it.label }
        return BASE.filter { it in used } + used.filter { it !in BASE }
    }

    // ------------------------------------------------------------------ tags

    /**
     * Tags as they are kept: trimmed, without the characters that split a key from its value
     * (: = , "), no empty keys, a key once whatever its case, within the limits.
     */
    fun cleanTags(tags: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        for ((k, v) in tags) {
            val key = NOT_IN_KEY.replace(k, " ").replace(SPACES, " ").trim().take(MAX_KEY).trim()
            if (key.isEmpty() || !seen.add(fold(key))) continue
            out[key] = v.replace('"', ' ').replace(SPACES, " ").trim().take(MAX_VALUE).trim()
            if (out.size >= MAX_TAGS) break
        }
        return if (out == tags && out.keys.toList() == tags.keys.toList()) tags else out
    }

    /**
     * "genre:metal", "genre=metal", "genre" or "genre:" as a key and the value wanted (null = any
     * value); either part may be in double quotes (genre:"heavy metal"). Null when there is no key.
     */
    fun parseTag(spec: String): Pair<String, String?>? {
        val s = spec.trim()
        var quoted = false
        var cut = -1
        for ((i, c) in s.withIndex()) {
            if (c == '"') quoted = !quoted
            else if (!quoted && (c == ':' || c == '=')) { cut = i; break }
        }
        fun bare(x: String) = x.trim().removeSurrounding("\"").trim()
        val key = bare(if (cut < 0) s else s.substring(0, cut))
        if (key.isEmpty()) return null
        val value = if (cut < 0) null else bare(s.substring(cut + 1)).takeIf { it.isNotEmpty() }
        return key to value
    }

    /** Whether [job] has the tag [key], with [value] when one is asked (case and accents aside). */
    fun hasTag(job: SyncJob, key: String, value: String? = null): Boolean {
        val k = fold(key)
        val v = job.tags.entries.firstOrNull { fold(it.key) == k }?.value ?: return false
        return value.isNullOrBlank() || fold(v) == fold(value)
    }

    /** The tag keys the syncs use, each once (as first written), by name. */
    fun keysInUse(jobs: List<SyncJob>): List<String> =
        jobs.flatMap { it.tags.keys }.distinctBy { fold(it) }.sortedBy { fold(it) }

    /** The values the syncs give [key], each once, by name; the empty value (a key alone) is not one. */
    fun valuesInUse(jobs: List<SyncJob>, key: String): List<String> {
        val k = fold(key)
        return jobs.flatMap { j -> j.tags.entries.filter { fold(it.key) == k }.map { it.value } }
            .filter { it.isNotEmpty() }.distinctBy { fold(it) }.sortedBy { fold(it) }
    }

    /**
     * The syncs a script asks for: every tag in [tags] ("key:value", "key=value" or "key"), the
     * label [label] (id, colour key or name) and, when given, starred or not. Throws
     * IllegalArgumentException with a readable message for a tag without a key.
     */
    fun select(jobs: List<SyncJob>, labels: List<Label>, tags: List<String> = emptyList(), label: String? = null, favorite: Boolean? = null): List<SyncJob> {
        val wanted = tags.filter { it.isNotBlank() }.map { parseTag(it) ?: throw IllegalArgumentException("A tag is key:value, or a key alone.") }
        val l = label?.takeIf { it.isNotBlank() }?.let { resolve(labels, it)?.id ?: return emptyList() }
        return jobs.filter { j ->
            wanted.all { (k, v) -> hasTag(j, k, v) } && (l == null || j.label == l) && (favorite == null || j.favorite == favorite)
        }
    }

    /** Starred syncs first; the others, and the starred among themselves, in the order given. */
    fun favoritesFirst(jobs: List<SyncJob>): List<SyncJob> =
        if (jobs.none { it.favorite } || jobs.all { it.favorite }) jobs else jobs.sortedByDescending { it.favorite }
}
