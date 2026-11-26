package com.xlollx.songport.providers

import com.xlollx.songport.net.NetError
import com.xlollx.songport.platform.Env

/**
 * What one renewal of an account's session came to ([MusicProvider.keepAlive]): the twice-daily
 * keep-alive and "Reconnect" ask each signed-in account once, so a session (a refresh token, a web
 * session) stays in use and the person is asked to sign in again only when the service really ended it.
 */
data class KeepAlive(val state: State, val problem: String? = null, val offline: Boolean = false) {
    enum class State {
        /** The session works (renewed, or shown to work by a signed-in call). */
        RENEWED,
        /** The service ended the session: a new sign-in is needed. */
        EXPIRED,
        /** Nothing to renew: the account is not signed in. */
        NOT_SIGNED_IN,
        /** Not renewed this time (no network, an outage, a slow page): nothing changed, tried again next time. */
        FAILED,
    }

    val renewed: Boolean get() = state == State.RENEWED
    val expired: Boolean get() = state == State.EXPIRED

    companion object {
        val RENEWED = KeepAlive(State.RENEWED)
        val EXPIRED = KeepAlive(State.EXPIRED)
        val NOT_SIGNED_IN = KeepAlive(State.NOT_SIGNED_IN)

        /** Not renewed because of [e]: its message, never a token (the connectors' errors carry none). */
        fun failed(e: Throwable): KeepAlive = KeepAlive(State.FAILED, (e.message ?: e.javaClass.simpleName).take(200), offline = NetError.isNetwork(e))

        /**
         * What a web connector says when its session is gone for good, as its error says it (the
         * same words the app's connectors use): an expired web session, an ended one, a player that
         * no longer takes it.
         */
        private val GONE_WORDS = listOf(
            "rejected the session (401)",
            "web session has expired",
            "session has ended",
            "session has expired",
            "cookies have expired",
            "no longer accepts the session",
            "no longer opens the player",
            "served the player to a signed-out visitor",
        )

        /** Whether [error] says the session is gone for good. A throttle, an outage or a slow page does not. */
        fun sessionGone(error: String?): Boolean = error != null && GONE_WORDS.any { error.contains(it, ignoreCase = true) }

        /**
         * One signed-in [call] of a web connector that renews its session the way its other calls do
         * (a refused session renewed, then the call made again): [RENEWED] when it worked,
         * [EXPIRED] when the error says the session is gone or the account is no longer signed in.
         */
        suspend fun probe(ctx: Env, p: MusicProvider, call: suspend () -> Unit): KeepAlive {
            if (!p.isConnected(ctx)) return NOT_SIGNED_IN
            return try {
                call(); RENEWED
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                if (!p.isConnected(ctx) || (!NetError.isNetwork(e) && sessionGone(e.message))) EXPIRED else failed(e)
            }
        }

        /**
         * "Reconnect": the account's session renewed now, as the keep-alive does; a connector with no
         * renewal of its own ([MusicProvider.keepAlive] null) makes one signed-in call (its playlists).
         */
        suspend fun renewNow(ctx: Env, p: MusicProvider): KeepAlive =
            try { p.keepAlive(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed(e) }
                ?: probe(ctx, p) { p.playlists(ctx) }

        /**
         * Whether "Reconnect" goes on to sign the account out and open its sign-in after [k]: anything
         * but a renewal, except a failure of the connection (nothing is signed out while offline).
         */
        fun needsSignIn(k: KeepAlive): Boolean = when (k.state) {
            State.RENEWED -> false
            State.FAILED -> !k.offline
            State.EXPIRED, State.NOT_SIGNED_IN -> true
        }
    }
}
