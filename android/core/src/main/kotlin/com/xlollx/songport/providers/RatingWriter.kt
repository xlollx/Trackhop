package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env

/**
 * A service that keeps the user's star ratings (0 = none, 1..5) on songs and albums and lets an app
 * set them (Subsonic's `setRating`: Navidrome and the other compatible servers). A sync with
 * [com.xlollx.songport.model.SyncJob.copyRatings] towards it copies the source's ratings: a song's
 * current one comes with its listing ([com.xlollx.songport.model.Track.rating]), an album's is asked.
 */
interface RatingWriter {
    /** False where the connector shares the code but its server has no ratings (Koel). */
    val takesRatings: Boolean get() = true

    /** Sets the rating of the song [songId] to [stars] (1..5). */
    suspend fun setSongRating(ctx: Env, songId: String, stars: Int)

    /** The rating the user gave the album [albumId], 0 when none. */
    suspend fun albumRating(ctx: Env, albumId: String): Int

    /** Sets the rating of the album [albumId] to [stars] (1..5). */
    suspend fun setAlbumRating(ctx: Env, albumId: String, stars: Int)

    companion object {
        /** [p] as a service that takes ratings, or null: what the engine and the editors ask. */
        fun of(p: MusicProvider?): RatingWriter? = (p as? RatingWriter)?.takeIf { it.takesRatings }

        /**
         * Whether a sync from [src] to [dst] offers "Copy star ratings": a playlist file (where an
         * iTunes library's ratings are kept) towards a service that takes them.
         */
        fun offered(src: MusicProvider?, dst: MusicProvider?): Boolean = src is LocalFilesProvider && of(dst) != null
    }
}
