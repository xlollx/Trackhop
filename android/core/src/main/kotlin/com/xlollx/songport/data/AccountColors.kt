package com.xlollx.songport.data

/**
 * The colours an account (a connector) can wear, as a ring round its service's logo, so two
 * accounts of one service tell apart at a glance. The logo itself keeps the brand's colours.
 * Stored by key in [Settings.connectorColors], by provider id, beside the connector's own name;
 * the Android app and the server's web page draw the same shades.
 */
object AccountColors {
    /** Key and shade ("#RRGGBB"), in the order the pickers show them. */
    val ALL: List<Pair<String, String>> = listOf(
        "red" to "#E5484D",
        "amber" to "#F5A524",
        "green" to "#30A46C",
        "blue" to "#3E63DD",
        "purple" to "#8E4EC6",
        "pink" to "#D6409F",
        "teal" to "#12A594",
    )

    /** The shade of [key], or null for none (or a key this version does not know). */
    fun hex(key: String?): String? = ALL.firstOrNull { it.first == key }?.second

    /** The shade of [key] as an opaque ARGB value, or null. */
    fun argb(key: String?): Long? = hex(key)?.let { 0xFF000000L or it.substring(1).toLong(16) }

    /** The colour connector [id] wears in [s], or null. */
    fun of(s: Settings, id: String): String? = s.connectorColors[id]?.takeIf { hex(it) != null }

    /** [s] with connector [id] wearing [key]; a null or unknown [key] takes its colour off. */
    fun set(s: Settings, id: String, key: String?): Settings =
        if (key == null || hex(key) == null) s.copy(connectorColors = s.connectorColors - id)
        else s.copy(connectorColors = s.connectorColors + (id to key))
}
