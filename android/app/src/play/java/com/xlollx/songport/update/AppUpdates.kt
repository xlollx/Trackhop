package com.xlollx.songport.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The Play build's update check: Google Play's own in-app update API says whether Play has a newer
 * Trackhop for this device; a tap opens Trackhop's page in the Play Store, with its Update button.
 * Nothing is asked of any other server.
 */
object AppUpdates {
    /** No setting: Google Play decides. */
    const val configurable = false

    fun enabled(ctx: Context): Boolean = true
    fun setEnabled(ctx: Context, on: Boolean) = Unit
    fun handledElsewhere(ctx: Context): Boolean = false
    fun updatedBy(ctx: Context): String? = null

    suspend fun check(ctx: Context, force: Boolean = false) {
        val info: AppUpdateInfo? = runCatching {
            suspendCancellableCoroutine<AppUpdateInfo?> { cont ->
                AppUpdateManagerFactory.create(ctx.applicationContext).appUpdateInfo
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        }.getOrNull()
        // Not installed from Play (a test build), offline, or no update: no arrow.
        UpdateState.set(if (info?.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) UpdateState.Available(null, null) else null)
    }

    /** Trackhop's page in the Play Store app (in the browser when the Store app is missing). */
    fun open(ctx: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ctx.packageName}")).setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(market) }.isFailure) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
