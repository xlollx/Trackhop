package com.xlollx.songport.providers

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.platform.Env

/**
 * Koel (https://koel.dev): a personal server that speaks the OpenSubsonic API
 * (https://docs.koel.dev/usage/subsonic), so it runs on the Subsonic connector's code. What is its own:
 * the user is the account's email and the secret is the "Subsonic API key" of the profile's Subsonic
 * tab, never the Koel password (Koel checks the salted token against that key); a playlist cannot be
 * made public through this API (Koel always answers `public: false`); songs carry no play count and
 * no file path, lyrics live in Koel itself, so they are only listed, and it keeps no star ratings.
 */
class KoelProvider(slot: String = "") : SubsonicProvider(slot) {
    override val serviceId = SERVICE
    override val displayName = "Koel"
    override val brandColor = 0xFFFF7D2E
    override val noteName = "provider_note_koel"
    // Written from Koel's documentation and source; not yet used against a real Koel server.
    override val beta = true
    override val loginForm = LoginForm(needsUrl = true, needsUser = true, needsSecret = true, secretLabelName = "login_apikey", hintName = "login_hint_koel")
    override val canSetVisibility: Boolean get() = false
    override val lyricsSupport: LyricsSupport get() = LyricsSupport.REPORT_ONLY
    // Koel has favourites, not star ratings.
    override val takesRatings: Boolean get() = false

    override suspend fun login(ctx: Env, url: String, user: String, secret: String) {
        try {
            super.login(ctx, url, user.trim(), secret.trim())
        } catch (e: ProviderException) {
            // 40, wrong user or password: on Koel almost always the account password in place of the key.
            if (e.code == ERROR_WRONG_CREDENTIALS) throw ProviderException(ctx.text("koel_signin_refused"), code = e.code)
            throw e
        }
    }

    override suspend fun setPlaylistVisibility(ctx: Env, playlistId: String, public: Boolean) {
        throw ProviderException(ctx.text("error_manage_unsupported", displayName))
    }

    companion object {
        const val SERVICE = "koel"
        private const val ERROR_WRONG_CREDENTIALS = 40
    }
}
