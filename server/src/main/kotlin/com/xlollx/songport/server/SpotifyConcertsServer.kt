package com.xlollx.songport.server

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.SpotifyConcerts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Spotify (web) as one more source of Concerts on the server ([SpotifyConcerts], as in the app):
 * the tour dates of the artists' pages on open.spotify.com, read with the user's own Spotify (web)
 * cookies (SpotifyWebServer.kt). The same rules as the app: only when a person asks (the page's
 * search and GET /api/v1/concerts, never the followed artists' check), at most
 * [SpotifyConcerts.MAX_DETAILS] concert pages an artist a search, the dates kept a day and each
 * concert's place a month ([SpotifyConcerts]'s caches, in the user's own data).
 *
 * The core's reader is one for the whole process, and a shared server has several people: the
 * person asking travels in the search's coroutines ([asking]), so each one's dates are read with
 * their own sign-in, and nothing at all without one. Off with [Features.v2].
 */
internal object SpotifyConcertsServer {
    private class Asker(val env: Env) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Asker>
    }

    /** The asking user's first Spotify (web) account connected; null when there is none (nothing is asked then). */
    private suspend fun connected(): Pair<Env, SpotifyWebServerProvider>? {
        if (!Features.v2) return null
        val env = currentCoroutineContext()[Asker]?.env ?: return null
        val p = Providers.connectors().filterIsInstance<SpotifyWebServerProvider>().firstOrNull { it.isConnected(env) } ?: return null
        return env to p
    }

    private val reader = object : SpotifyConcerts.Reader {
        override suspend fun dates(artist: String): List<SpotifyConcerts.Date>? = connected()?.let { (env, p) -> p.concertDates(env, artist) }
        override suspend fun details(uri: String): SpotifyConcerts.Date? = connected()?.let { (env, p) -> p.concertDetails(env, uri) }
    }

    /** Runs [block], a Concerts search a person asked for, with [env]'s Spotify (web) sign-in as a source. */
    suspend fun <T> asking(env: Env, block: suspend () -> T): T {
        if (!Features.v2) return block()
        install()
        return withContext(Asker(env)) { block() }
    }

    @Synchronized private fun install() {
        if (SpotifyConcerts.reader !== reader) SpotifyConcerts.reader = reader
    }
}
