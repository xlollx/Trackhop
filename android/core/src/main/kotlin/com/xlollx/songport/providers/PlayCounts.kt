package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env
import com.xlollx.songport.sync.Listening

/**
 * A personal server that counts the plays of every song it holds, for the signed-in user
 * (Subsonic's `playCount`, Jellyfin's and Emby's `UserData.PlayCount`, Plex's `viewCount`, Lyrion's
 * `playcount`). Over all time: a server keeps a count, not a history. Read through each server's
 * documented API, with the sign-in the connector already has.
 */
interface PlayCounts {
    /** Every song of [playlistId] (a library id too, where the connector reads one), with the plays the server counted. */
    suspend fun playlistPlays(ctx: Env, playlistId: String): List<Listening.Play>

    /**
     * The songs the user played most on the server, at most [n], the most played first, with their
     * counts. Where the API cannot sort songs by plays (Subsonic, Lyrion) they are gathered from the
     * albums it ranks most played: the counts are real, the list may miss a song of a less played album.
     */
    suspend fun topPlays(ctx: Env, n: Int = Listening.TOP): List<Listening.Play>
}
