package com.xlollx.songport.update

import android.app.Activity
import com.xlollx.songport.model.SyncReport

/**
 * The store's own review prompt: the Google Play build alone has one (src/play). This build never
 * asks and carries no library for it.
 */
object StoreReview {
    @Suppress("UNUSED_PARAMETER")
    fun afterSyncs(activity: Activity, reports: List<SyncReport>) = Unit
}
