package com.xlollx.songport.providers

import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Google Play build: no built-in web connectors. YouTube Music, Amazon Music and the web sign-ins
 * are offered only when a connector plugin (Trackhop Bridge) is installed.
 */
object BuiltIn {
    const val available = false

    @Suppress("UNUSED_PARAMETER")
    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): Bundle =
        throw IllegalStateException("no built-in connectors in this build")

    @Suppress("UNUSED_PARAMETER")
    fun loginIntent(ctx: Context, action: String, slot: String = ""): Intent? = null

    @Suppress("UNUSED_PARAMETER")
    fun extraActions(ctx: Context, serviceId: String, developer: Boolean): List<Pair<String, Intent>> = emptyList()

    /** The read-only link sources, TikTok lookups and Concerts' TicketOne and Vivaticket go through an installed plugin, if any. */
    @Suppress("UNUSED_PARAMETER")
    fun onAppStart(ctx: Context, interceptor: okhttp3.Interceptor) = PluginRelay.install(ctx)

    /**
     * setlist.fm's own site without a setlist.fm key, browsed in a connector plugin's screen (the
     * GitHub and F-Droid build has its own): its label, and the screen that opens it on [artist]'s
     * search or a pasted [link]. Null without a plugin; with one too old for it, that screen says
     * to update it ([com.xlollx.songport.ui.SetlistPluginActivity]).
     */
    fun setlistSite(ctx: Context, artist: String?, link: String? = null): Pair<String, Intent>? =
        if (!BridgePlugin.installed(ctx)) null
        else ctx.getString(com.xlollx.songport.R.string.setlists_browse) to com.xlollx.songport.ui.SetlistPluginActivity.intent(ctx, artist, link)

    /** No built-in web connectors here, so no capture of a web player either. */
    fun captureIntent(ctx: Context, url: String): Intent? = null
}
