package com.xlollx.songport.providers

import com.xlollx.songport.model.Track

/**
 * What a personal server lets Trackhop do with its songs' lyrics (Tools › Synced lyrics, 1.18; see
 * [com.xlollx.songport.sync.SyncedLyrics]). Each connector says what its server's documented API allows.
 */
enum class LyricsSupport {
    /** Not a personal server: the tool is not about it. */
    NONE,
    /** A personal server with no notion of lyrics in its API (Mopidy): the tool says it is not supported. */
    UNSUPPORTED,
    /** The server says which songs have lyrics, and has no way to add them (Lyrion): the report only. */
    REPORT_ONLY,
    /** The server takes lyrics through its API (Jellyfin 10.9 and newer): report and fill, from the app too. */
    SERVER_API,
    /**
     * The server reads lyrics from a `.lrc` (synced) or `.txt` (plain) file next to the audio file, named
     * like it (Navidrome, Emby, Plex): report everywhere; fill only on Desktop and Docker, with the
     * server's music folder reachable there ([com.xlollx.songport.sync.SyncedLyrics.Sidecars]).
     */
    SIDECAR,
}

/**
 * A song of a personal server's library for the lyrics report: [hasLyrics] as the server tells it,
 * [path] the audio file's path as the server reports it (null where it does not).
 */
data class LyricsSong(val track: Track, val hasLyrics: Boolean, val path: String? = null)
