package com.xlollx.songport.server

/**
 * The features of the next release, each behind this one switch: Wrapped from real listening
 * (WrappedWeb.kt), the playlist poster (PosterWeb.kt), the followed-artist alerts (AlertsWeb.kt)
 * and Spotify (web) as a Concerts source (SpotifyConcertsServer). Off, their pages, API calls,
 * commands, MCP tools and the daily check are gone, and everything else works as before.
 */
internal object Features {
    /** The next release's features. On for now; the release switch turns them off here, in one line. */
    @Volatile @JvmField var v2: Boolean = true
}
