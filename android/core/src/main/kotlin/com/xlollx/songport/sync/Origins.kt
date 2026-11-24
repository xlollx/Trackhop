package com.xlollx.songport.sync

import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.MusicProvider

/**
 * A track's service id carried through a file (CSV, JSPF, the playlist backups), so a file synced
 * back to the service it came from restores the very tracks, one for one, without searching them
 * (the idea of Exportify's id columns). Only services whose ids are the same for every account:
 * a personal server or Apple Music numbers tracks its own way.
 */
object Origins {
    /** Services by family: the Bridge's connector and the official one share their ids, as YouTube and YouTube Music. */
    private fun family(serviceId: String): String? = when (serviceId.removeSuffix("_bridge")) {
        "spotify" -> "spotify"
        "ytm", "youtube" -> "youtube"
        "deezer" -> "deezer"
        "tidal" -> "tidal"
        "qobuz_web", "qobuz" -> "qobuz"
        else -> null
    }

    /** [tracks] read from [p], each with its origin (where none is set yet): what an export or a backup writes. */
    fun stamp(p: MusicProvider, tracks: List<Track>): List<Track> {
        if (p.serviceId == LocalFilesProvider.serviceId) return tracks
        val fam = family(p.serviceId) ?: return tracks
        return tracks.map { t -> if (t.origin == null && t.id.isNotBlank() && t.kind.isEmpty()) t.copy(origin = "$fam:${t.id}") else t }
    }

    /** The id [t] has on [dst], when it came from a file exported from that service; null otherwise. */
    fun idOn(dst: MusicProvider, t: Track): String? {
        val o = t.origin ?: return null
        val fam = family(dst.serviceId) ?: return null
        return o.removePrefix("$fam:").takeIf { it.length < o.length && it.isNotBlank() }
    }

    /** "spotify:4uLU…" → the service family and its id, for the columns of a file; null when not one of ours. */
    fun split(origin: String?): Pair<String, String>? {
        val o = origin ?: return null
        val i = o.indexOf(':')
        return if (i <= 0 || i == o.length - 1) null else o.substring(0, i) to o.substring(i + 1)
    }

    /** The origin of a file's (service, id) columns; null when either is missing or the service is not one Trackhop restores. */
    fun of(service: String?, id: String?): String? {
        if (service.isNullOrBlank() || id.isNullOrBlank()) return null
        val fam = family(service.trim().lowercase()) ?: return null
        return "$fam:${id.trim()}"
    }

    /** A link to the track on its service, for people reading the file. */
    fun url(origin: String?): String? {
        val (fam, id) = split(origin) ?: return null
        return when (fam) {
            "spotify" -> "https://open.spotify.com/track/$id"
            "youtube" -> "https://music.youtube.com/watch?v=$id"
            "deezer" -> "https://www.deezer.com/track/$id"
            "tidal" -> "https://tidal.com/browse/track/$id"
            "qobuz" -> "https://open.qobuz.com/track/$id"
            else -> null
        }
    }
}
