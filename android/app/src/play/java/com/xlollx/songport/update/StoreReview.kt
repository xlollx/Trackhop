package com.xlollx.songport.update

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory
import com.xlollx.songport.model.SyncReport

/**
 * The Google Play build: now and then, after syncs that went well, Google Play is asked to show its
 * own review prompt (Play In-App Review API). When and how often is [ReviewPolicy]; Google Play
 * decides whether the prompt appears at all and never says whether a review was left. Nothing is
 * sent anywhere by Trackhop, and the other builds have neither this code nor the library.
 */
object StoreReview {
    private const val PREFS = "store_review"
    private const val ASKED = "asked"
    private const val DONE = "done"

    /** Called when the syncs running while the app is open have all ended; [reports] newest first. */
    fun afterSyncs(activity: Activity, reports: List<SyncReport>) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!ReviewPolicy.due(reports, ReviewPolicy.State(prefs.getLong(ASKED, 0L), prefs.getBoolean(DONE, false)), now)) return
        // Whatever comes of it, not again for months.
        prefs.edit().putLong(ASKED, now).apply()
        runCatching {
            val manager = ReviewManagerFactory.create(activity.applicationContext)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful || activity.isFinishing || activity.isDestroyed) return@addOnCompleteListener
                runCatching {
                    manager.launchReviewFlow(activity, request.result).addOnCompleteListener { flow ->
                        // Started and completed: never asked again.
                        if (flow.isSuccessful) prefs.edit().putBoolean(DONE, true).apply()
                    }
                }
            }
        }
    }
}
