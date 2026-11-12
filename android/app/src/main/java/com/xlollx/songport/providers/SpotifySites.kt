package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.platform.env
import com.xlollx.songport.sync.ArtistSites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

/**
 * Merch's and Concerts' look for an artist's own site ([ArtistSites]): the links and merch shelf of the
 * artist's page on Spotify, with the session of the first Spotify (web) account connected, where the
 * connectors are the full ones. None, or connectors too old to know the method: nothing is asked.
 */
object SpotifySites {
    fun reader(app: Context): ArtistSites.SpotifyReader = object : ArtistSites.SpotifyReader {
        override suspend fun artist(name: String): ArtistSites.SpotifyArtist? = withContext(Dispatchers.IO) {
            val env = app.env
            val p = Providers.connectors().filterIsInstance<SpotifyBridgeProvider>().firstOrNull { it.isConnected(env) }?.takeIf { BridgePlugin.full }
                ?: return@withContext null
            val b = BridgePlugin.call(app, "spotify.artist", name, null, p.slot) ?: return@withContext null
            b.getString("error")?.let { if (it.startsWith("unknown method")) return@withContext null; throw ProviderException("Spotify (web): $it") }
            parse(parseJson(b.getString("json") ?: return@withContext null))
        }
    }

    /** The connectors' ArtistSitesDto; null for "no artist of that name". */
    internal fun parse(j: JsonElement?): ArtistSites.SpotifyArtist? {
        val uri = j["uri"].str?.takeIf { it.startsWith("spotify:artist:") } ?: return null
        return ArtistSites.SpotifyArtist(uri.substringAfterLast(':'), j["links"].arr.mapNotNull { it.str }, j["merch"].arr.mapNotNull { it.str })
    }
}
