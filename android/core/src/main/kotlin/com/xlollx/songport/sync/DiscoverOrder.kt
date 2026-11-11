package com.xlollx.songport.sync

/**
 * The order of the Discover tab's cards (the Wrapped stays on top, outside it). The user's order is
 * kept in Settings.discoverOrder by card id; empty means the default. A card added in a later version
 * goes where the default puts it (after the card that comes before it there), a card that no longer
 * exists is dropped.
 */
object DiscoverOrder {
    /** The default order, by card id. */
    val DEFAULT = listOf("releases", "link", "concerts", "records", "setlists", "subs", "compat", "roast", "vanished", "poster", "followed")

    /**
     * The cards in [saved]'s order: the ids of [known] (in their default order) that [saved] names
     * first as it orders them, then each one it leaves out right after the card that comes before
     * it in [known] (at the start when none does).
     */
    fun merge(saved: List<String>, known: List<String> = DEFAULT): List<String> {
        val out = saved.filter { it in known }.distinct().toMutableList()
        known.forEachIndexed { i, id ->
            if (id in out) return@forEachIndexed
            val before = known.subList(0, i).lastOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
        }
        return out
    }

    /** [order] with [id] moved by [by] places (-1 earlier, +1 later), kept within the list. */
    fun move(order: List<String>, id: String, by: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0) return order
        val to = (from + by).coerceIn(0, order.size - 1)
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** What to save for [order]: nothing when it is the default, so a reset and the default are one. */
    fun toSave(order: List<String>, known: List<String> = DEFAULT): List<String> = if (order == merge(emptyList(), known)) emptyList() else order
}
