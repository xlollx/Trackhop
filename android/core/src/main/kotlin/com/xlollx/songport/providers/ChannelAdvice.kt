package com.xlollx.songport.providers

/**
 * Which YouTube Music profile (the account's own channel or a brand account) the music seems to be
 * on. The Reddit case: likes written to the account's own channel while the user's YouTube Music
 * lives on a brand account. Only counts that were read count: an unknown one (-1) never makes a
 * channel look empty, nor full.
 */
object ChannelAdvice {
    /** A channel as the advice sees it: [id] "" is the account's own; counts -1 when not read. */
    data class Library(val id: String, val playlists: Int, val likedSongs: Int)

    /** True when both counts were read and both are zero. */
    fun looksEmpty(c: Library): Boolean = c.playlists == 0 && c.likedSongs == 0

    /** True when a count was read and is above zero. */
    fun hasMusic(c: Library): Boolean = c.playlists > 0 || c.likedSongs > 0

    /**
     * The id of the channel to suggest instead of [currentId]: only when that one looks empty and
     * another holds music, the fullest of those. Null when there is nothing to say.
     */
    fun better(channels: List<Library>, currentId: String): String? {
        val current = channels.firstOrNull { it.id == currentId.trim() } ?: return null
        if (!looksEmpty(current)) return null
        return channels.filter { it.id != current.id && hasMusic(it) }
            .maxByOrNull { it.playlists.coerceAtLeast(0) + it.likedSongs.coerceAtLeast(0) }?.id
    }
}
