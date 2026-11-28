package com.xlollx.songport.shared

/**
 * The internal service id of a multi-sync ([SharedProvider.ID], "shared") is never shown: a screen that
 * names a track's service shows the group's name instead (and the multi-sync icon).
 */
object SharedNames {
    /**
     * The name to show for "shared": the group whose log holds [trackKey] ([inGroup] asks it), else the
     * only group there is, else [fallback] (several groups and no way to tell which).
     */
    fun of(groups: List<SharedGroup>, trackKey: String? = null, inGroup: (SharedGroup, String) -> Boolean = { _, _ -> true }, fallback: String): String {
        if (groups.size <= 1) return groups.firstOrNull()?.name?.ifBlank { null } ?: fallback
        val holding = if (trackKey == null) emptyList() else groups.filter { inGroup(it, trackKey) }
        return holding.singleOrNull()?.name?.ifBlank { null } ?: fallback
    }

    /** The name of the group that sync [jobId] belongs to (either half of its pair), else [of] without a key. */
    fun ofJob(groups: List<SharedGroup>, jobId: String, fallback: String): String =
        groups.firstOrNull { it.jobId == jobId || it.twinId == jobId }?.name?.ifBlank { null } ?: of(groups, fallback = fallback)
}
