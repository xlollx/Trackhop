package com.xlollx.songport.ytmbridge

import android.os.Handler
import android.os.Looper
import android.webkit.WebStorage
import androidx.webkit.ProfileStore

/**
 * "Start over" for a web connector: besides its session and cookies (its disconnect), the data its
 * sites keep in the browser engine (local storage, IndexedDB, service workers) is deleted, so the
 * next sign-in starts as on a new device. A site that remembered the account (Anghami signed in with
 * Google, #53) asks again instead of reopening the old session the app could not read.
 *
 * Only the service's own sites: the sign-in pages shared with other services and apps (Google's,
 * Apple ID's, Facebook's) keep their data, or every connector signed in through them would go.
 */
object WebReset {
    private val SHARED = Regex("""(^|\.)(google\.com|apple\.com|facebook\.com|gstatic\.com|youtube\.com)$""")
    private val OWN_APPLE = setOf("music.apple.com")

    /** The origins whose stored data a start over deletes, from the session's cookie sites. */
    fun storageOrigins(sites: List<String>): List<String> =
        sites.filter { it in OWN_APPLE || !SHARED.containsMatchIn(it) }.distinct().map { "https://$it" }

    /** How many cookies [session]'s sites hold in its jar now (for the log, never their values). */
    fun cookieCount(session: WebSession): Int = runCatching {
        val jar = session.jar()
        session.sites.flatMap { d -> jar.getCookie("https://$d")?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty() }
            .filter { it.isNotEmpty() }.distinct().size
    }.getOrDefault(0)

    /**
     * Deletes the stored data of [session]'s own sites, in the account's profile (the WebView's own
     * for the primary, the slot's for another account); on the main thread, as the engine wants.
     * @return how many sites were asked to forget.
     */
    @android.annotation.SuppressLint("RequiresFeature") // a slot exists only where profiles are supported
    fun forgetStorage(session: WebSession): Int {
        val origins = storageOrigins(session.sites)
        runCatching {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val storage: WebStorage? = if (session.slot.isEmpty()) WebStorage.getInstance()
                    else if (Slots.supported()) ProfileStore.getInstance().getProfile(Slots.profileName(session.serviceName, session.slot))?.webStorage
                    else null
                    if (storage == null) BridgeNet.log(session.serviceName, "start over: no storage for account ${session.slot}, cookies only")
                    storage?.let { s -> origins.forEach(s::deleteOrigin) }
                }
            }
        }
        return origins.size
    }
}
