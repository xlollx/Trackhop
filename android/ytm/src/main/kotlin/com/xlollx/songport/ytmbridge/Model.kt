package com.xlollx.songport.ytmbridge

import kotlinx.serialization.Serializable

/** What crosses the process boundary to Trackhop. Field names are the contract: do not rename. */
/**
 * A channel the signed-in YouTube account can act as ([YtmClient.channels]): [id] is the brand
 * account's page id, "" for the account's own channel; [selected] as the player's switcher marks it.
 */
@Serializable
data class ChannelDto(val id: String, val name: String, val byline: String? = null, val selected: Boolean = false, val hasChannel: Boolean = true)

@Serializable
data class PlaylistDto(
    val id: String, val name: String, val count: Int = -1,
    /** False for a playlist followed from someone else: readable, not writable. */
    val owned: Boolean = true,
    val description: String = "",
)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long = 0,
    /** Id of the entry inside the playlist: needed to remove it. */
    val setVideoId: String? = null,
    /** Native uri (Spotify), when the id alone is not what the service wants back. */
    val uri: String? = null,
    val explicit: Boolean? = null,
    val addedAt: Long? = null,
    /** "album" or "artist" for library objects; null for a track. */
    val kind: String? = null,
    val year: Int? = null,
    /** False when the service lists the entry greyed out (it no longer plays); null when it did not say. */
    val available: Boolean? = null,
    /** The recording's ISRC where the service gives it (Deezer): the matching uses it first. */
    val isrc: String? = null,
)

/** What a service ranks highest for the user: the songs and the artists' names, the first ones first. No counts. */
@Serializable
data class TopDto(val tracks: List<TrackDto> = emptyList(), val artists: List<String> = emptyList())

/**
 * A concert as Spotify's player lists it ([SpotifyWebClient.concerts], [SpotifyWebClient.concert]):
 * [start] as Spotify gives it (ISO 8601, with the venue's UTC offset). The artist's list says the
 * city only: [venue] and [country] (ISO 3166, two letters) come from the concert's own page.
 */
@Serializable
data class ConcertDto(
    val uri: String,
    val title: String = "",
    val start: String,
    val city: String = "",
    val venue: String = "",
    val country: String = "",
    val festival: Boolean = false,
)

/**
 * What an artist's page on Spotify says of their own site ([SpotifyWebClient.artistSites]): the links of
 * its About section and the addresses of the merch on its shelf (the artist's shop). Trackhop looks there
 * for the official site when MusicBrainz names none.
 */
@Serializable
data class ArtistSitesDto(val uri: String, val links: List<String> = emptyList(), val merch: List<String> = emptyList())

@Serializable
data class RemoveItem(val videoId: String, val setVideoId: String? = null)
