package com.xlollx.songport.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xlollx.songport.BuildConfig

/**
 * The Samsung Galaxy Store and Huawei AppGallery builds: the store itself updates the app and tells
 * the user, so Trackhop checks nothing (no Google Play library here: Huawei phones have no Google
 * services). [open] shows Trackhop's page in the store, should anything ever ask for it.
 */
object AppUpdates {
    /** No setting: the store decides. */
    const val configurable = false

    fun enabled(ctx: Context): Boolean = true
    fun setEnabled(ctx: Context, on: Boolean) = Unit
    fun handledElsewhere(ctx: Context): Boolean = true
    fun updatedBy(ctx: Context): String? = if (BuildConfig.FLAVOR == "appgallery") "AppGallery" else "Galaxy Store"

    @Suppress("UNUSED_PARAMETER")
    suspend fun check(ctx: Context, force: Boolean = false) = UpdateState.set(null)

    fun open(ctx: Context) {
        val pkg = ctx.packageName
        val (app, web) = if (BuildConfig.FLAVOR == "appgallery")
            "appmarket://details?id=$pkg" to "https://appgallery.huawei.com/search/$pkg"
        else "samsungapps://ProductDetail/$pkg" to "https://galaxystore.samsung.com/detail/$pkg"
        if (runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
