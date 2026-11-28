package com.xlollx.songport.server

import com.xlollx.songport.platform.AuthMode
import com.xlollx.songport.providers.DeezerProvider
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.OAuthProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * OAuth sign-ins through the browser: the page sends the user to the service, which comes back to
 * `<SONGPORT_URL>/callback` with a code (or, for Deezer, a token in the fragment). PKCE as in the app.
 */
/** Google blocked the sign-in because the Gmail is not a Test user of the person's project (the page then offers the Audience page). */
class GoogleTestUserNeeded(message: String) : IllegalStateException(message)

class Auth(private val env: ServerEnv) {
    private class Pending(val providerId: String, val verifier: String, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val rnd = SecureRandom()

    /** The service's sign-in page for [provider], or null when it signs in with a form (or not at all here). */
    fun begin(provider: MusicProvider): String? {
        val mode = env.requestedAuth(provider)
        if (mode != AuthMode.BROWSER && mode != AuthMode.LOOPBACK) return null
        val p = provider as? OAuthProvider ?: return null
        val verifier = random(64)
        val state = random(24)
        val now = System.currentTimeMillis()
        pending.entries.removeIf { now - it.value.at > TTL_MS }
        pending[state] = Pending(p.id, verifier, now)
        // Deezer's redirect is its own setting (it must be https); every other service comes back here.
        return if (p is DeezerProvider) p.authUrl(env, state, challenge(verifier))
        else p.authUrl(env, state, challenge(verifier), env.redirectUri)
    }

    /** Completes the sign-in the service came back with; returns the connector signed in. */
    suspend fun complete(params: Map<String, String>): MusicProvider {
        params["error"]?.let {
            val domain = params["state"]?.let { s -> pending[s] }?.providerId?.let { id -> com.xlollx.songport.providers.Providers.byId(id)?.authDomain }
            if (com.xlollx.songport.providers.GoogleSignIn.needsTestUser(domain, it, params["error_description"])) throw GoogleTestUserNeeded(env.text("auth_google_test_users"))
            throw IllegalStateException(params["error_description"] ?: it)
        }
        val state = params["state"]
        val entry = if (state != null) pending.remove(state) ?: throw IllegalStateException(env.text("auth_state_mismatch"))
        // Deezer's implicit flow does not always send the state back: the latest sign-in started is the one.
        else pending.entries.maxByOrNull { it.value.at }?.let { pending.remove(it.key) } ?: throw IllegalStateException(env.text("auth_no_pending"))
        val provider = com.xlollx.songport.providers.Providers.byId(entry.providerId) ?: throw IllegalStateException("unknown connector ${entry.providerId}")
        val extra = if (provider is DeezerProvider) emptyMap() else mapOf(OAuthProvider.PARAM_REDIRECT_URI to env.redirectUri)
        provider.completeAuth(env, params + extra, entry.verifier)
        return provider
    }

    private fun random(bytes: Int): String {
        val buf = ByteArray(bytes)
        rnd.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    companion object {
        private const val TTL_MS = 30 * 60_000L

        fun challenge(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
