package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.xlollx.songport.ytmbridge.AmazonCaptureActivity
import com.xlollx.songport.ytmbridge.AmazonLoginActivity
import com.xlollx.songport.ytmbridge.BridgeCore
import com.xlollx.songport.ytmbridge.LoginActivity
import com.xlollx.songport.ytmbridge.VerifyActivity
import com.xlollx.songport.ytmbridge.WebLoginActivity
import com.xlollx.songport.ytmbridge.YtmClient
import com.xlollx.songport.ytmbridge.pending

/**
 * GitHub and F-Droid build: the web connectors (YouTube Music, Amazon Music, the Spotify, Apple Music,
 * Deezer and LiveOne web sign-in, SoundCloud) run inside Trackhop, from the :bridge module. They answer the same methods the Trackhop
 * Bridge app answers through its ContentProvider, called in-process: no second app, no signature check.
 */
object BuiltIn {
    const val available = true

    /** The web connectors' own HTTP clients join the app's connections log. */
    fun onAppStart(ctx: Context, interceptor: okhttp3.Interceptor) {
        val app = ctx.applicationContext
        com.xlollx.songport.ytmbridge.BridgeNet.interceptor = interceptor
        com.xlollx.songport.ytmbridge.BridgeNet.logSink = { tag, message -> com.xlollx.songport.data.Diagnostics.log(app, tag, message) }
    }

    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle =
        BridgeCore.call(ctx.applicationContext, method, arg, extras)

    fun loginIntent(ctx: Context, action: String): Intent? = when (action) {
        YouTubeBridgeProvider.LOGIN_ACTION -> Intent(ctx, LoginActivity::class.java)
        AmazonBridgeProvider.LOGIN_ACTION -> Intent(ctx, AmazonLoginActivity::class.java)
        SpotifyBridgeProvider.LOGIN_ACTION -> WebLoginActivity.intent(ctx, WebLoginActivity.SPOTIFY)
        AppleBridgeProvider.LOGIN_ACTION -> WebLoginActivity.intent(ctx, WebLoginActivity.APPLE)
        DeezerBridgeProvider.LOGIN_ACTION -> WebLoginActivity.intent(ctx, WebLoginActivity.DEEZER)
        SoundCloudBridgeProvider.LOGIN_ACTION -> WebLoginActivity.intent(ctx, WebLoginActivity.SOUNDCLOUD)
        LiveOneBridgeProvider.LOGIN_ACTION -> WebLoginActivity.intent(ctx, WebLoginActivity.LIVEONE)
        else -> null
    }

    /**
     * Extra entries for a connector's menu: Google's own page while it blocks YouTube Music, the
     * traffic capture that helps fix Amazon Music when its web player changes.
     */
    fun extraActions(ctx: Context, serviceId: String, developer: Boolean): List<Pair<String, Intent>> = when (serviceId) {
        YouTubeBridgeProvider.SERVICE ->
            if (YtmClient.Verification.pending(ctx)) listOf(ctx.getString(com.xlollx.songport.ytmbridge.R.string.bridge_verify_google) to Intent(ctx, VerifyActivity::class.java))
            else emptyList()
        // The capture is a development aid: shown only with developer options on.
        AmazonBridgeProvider.SERVICE ->
            if (developer) listOf(ctx.getString(com.xlollx.songport.ytmbridge.R.string.bridge_amazon_capture) to Intent(ctx, AmazonCaptureActivity::class.java)) else emptyList()
        else -> emptyList()
    }
}
