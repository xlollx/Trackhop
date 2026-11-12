package com.xlollx.songport

import com.xlollx.songport.data.Settings
import com.xlollx.songport.providers.MusicProvider

/**
 * The release switch. The features of Trackhop 2.0 (podcasts and OPML, shared playlists and the
 * app's syncs with the user's server, Discover's new pages, listening-based Wrapped and poster,
 * playlist covers on more services, the new library lists) are in the code but offered only when
 * [v2] is on: in a build made with it ([build], the app's `BuildConfig.V2`, the server's
 * `TRACKHOP_PREVIEW_2`), or on a device whose owner turned on Settings › Developer options ›
 * Preview 2.0 features ([preview], `Settings.preview2`).
 *
 * Off, only the ways to start something new are hidden: what a user already has (a sync made on a
 * test build that reads a 2.0 list, a group, followed artists) keeps working and is never deleted.
 */
object Features {
    /** The build's switch: on in a 2.0 build. The app sets it from `BuildConfig.V2`, the server from its own switch. */
    @Volatile @JvmStatic var build: Boolean = false

    /** The developer toggle, asked each time (the app reads `Settings.preview2`); off where nothing sets it. */
    @Volatile @JvmStatic var preview: () -> Boolean = { false }

    /** The features of 2.0 are offered. */
    @JvmStatic val v2: Boolean get() = build || runCatching { preview() }.getOrDefault(false)

    /** [v2] as [settings] say: for a screen that redraws when the settings change. */
    @JvmStatic fun v2(settings: Settings): Boolean = build || settings.preview2

    /** [p]'s library list [playlistId] is offered in the pickers (some are new in 2.0, see [MusicProvider.libraryIsV2]). */
    @JvmStatic fun libraryOffered(p: MusicProvider, playlistId: String, asTarget: Boolean): Boolean = v2 || !p.libraryIsV2(playlistId, asTarget)

    /** A sync puts the source's cover on [p]'s playlist (covers on the services added in 2.0 wait for it). */
    @JvmStatic fun coversOn(p: MusicProvider): Boolean = p.canSetCover && (v2 || !p.coverIsV2)

    /** [p] moves a playlist into the source's order (TIDAL's and YouTube's moves wait for 2.0). */
    @JvmStatic fun reorderOn(p: MusicProvider): Boolean = p.canReorder && (v2 || !p.reorderIsV2)
}
