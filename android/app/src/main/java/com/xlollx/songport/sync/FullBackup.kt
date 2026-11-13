package com.xlollx.songport.sync

import android.content.Context
import android.os.Bundle
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.Diagnostics
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.platform.AndroidEnv
import com.xlollx.songport.providers.BridgePlugin
import com.xlollx.songport.providers.Providers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The app's side of the encrypted full backup ([EncryptedBackup], 1.18 behind the release switch):
 * the plain set-up with the token vault, the client secrets and the web connectors' sessions.
 *
 * The web sessions live with the connectors: in the GitHub/F-Droid build they are in this app (the
 * :bridge module, called in-process); in the store builds in Trackhop Bridge, asked through its
 * provider, which hands them only to Trackhop. A Bridge too old to know the two methods leaves them
 * out, and the backup says so. Nothing here is logged but counts.
 */
object FullBackup {
    private const val EXPORT = "sessions.export"
    private const val IMPORT = "sessions.import"

    /** The web services as the connectors name their sessions, with the names people know. */
    private val SERVICE_NAMES = mapOf(
        "ytm" to "YouTube Music", "amazon" to "Amazon Music", "spotify" to "Spotify (web)", "apple" to "Apple Music",
        "deezer" to "Deezer", "soundcloud" to "SoundCloud", "liveone" to "LiveOne", "anghami" to "Anghami", "qobuz" to "Qobuz",
    )

    /** [label] ("spotify", "spotify@2") as people read it. */
    fun webName(label: String): String {
        val service = label.substringBefore('@')
        val slot = label.substringAfter('@', "")
        return (SERVICE_NAMES[service] ?: service) + if (slot.isNotEmpty()) " ($slot)" else ""
    }

    /** A vault id or a client secret ([EncryptedBackup.conflicts]) as people read it. */
    fun signInName(id: String): String = when {
        id.startsWith(EncryptedBackup.CLIENT_SECRET) -> id.removePrefix(EncryptedBackup.CLIENT_SECRET).let { k -> (Providers.byId(k)?.displayName ?: k) + " (client secret)" }
        id.startsWith("notify:") -> "Notifications"
        else -> Providers.byId(id)?.displayName ?: id.trim('_')
    }

    /** The web sessions for the file; [missing]: a Bridge is there but did not hand them over (too old). */
    class Web(val sessions: EncryptedBackup.WebSessions?, val missing: Boolean)

    fun webSessions(ctx: Context): Web {
        if (!BridgePlugin.installed(ctx)) return Web(null, false)
        if (!BridgePlugin.knows(EXPORT)) return Web(null, true)
        val b = runCatching { BridgePlugin.call(ctx, EXPORT) }.getOrNull() ?: return Web(null, true)
        b.getString("error")?.let { e ->
            BridgePlugin.refusedAsUnknown(EXPORT, e)
            Diagnostics.log(ctx, "backup", "the web sessions were not handed over: ${e.take(80)}")
            return Web(null, true)
        }
        val text = b.getString("json") ?: return Web(null, true)
        if (b.getInt("count") == 0) return Web(null, false)
        val accounts = runCatching {
            Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray.map { s ->
                val o = s.jsonObject
                val service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val slot = o["slot"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val account = o["account"]?.jsonPrimitive?.contentOrNull.orEmpty()
                webName(if (slot.isEmpty()) service else "$service@$slot") + if (account.isNotBlank()) ": $account" else ""
            }
        }.getOrDefault(emptyList())
        return Web(EncryptedBackup.WebSessions(EncryptedBackup.WEB_BRIDGE, text, accounts), false)
    }

    /** The encrypted file's text; [signIns] how many sign-ins and client secrets, [webMissing] as [Web.missing]. */
    class Exported(val text: String, val signIns: Int, val web: Int, val webMissing: Boolean)

    /** The set-up with its secrets, sealed with [password] (cleared here once used). Blocks: never on the main thread. */
    fun export(ctx: Context, password: CharArray): Exported {
        try {
            val env = AndroidEnv.of(ctx)
            val plain = com.xlollx.songport.notify.Notify.intoBackup(env, ConfigBackup.export(env, "Trackhop ${BuildConfig.VERSION_NAME} (Android)", deviceName = android.os.Build.MODEL.orEmpty()))
            val web = webSessions(ctx)
            val secrets = EncryptedBackup.collect(env, web.sessions)
            val text = EncryptedBackup.wrap(plain, secrets, password)
            Diagnostics.log(ctx, "backup", "encrypted set-up written: ${secrets.vault.size + secrets.clientSecrets.size} sign-ins, ${web.sessions?.accounts?.size ?: 0} web sessions${if (web.missing) ", web sessions not handed over" else ""}")
            return Exported(text, secrets.vault.size + secrets.clientSecrets.size, web.sessions?.accounts?.size ?: 0, web.missing)
        } finally {
            password.fill('\u0000')
        }
    }

    /** The web sessions of [web] this device is signed in to already (asked of the connectors, nothing written). */
    fun webPresent(ctx: Context, web: EncryptedBackup.WebSessions): List<String> =
        bridgeImport(ctx, web, replace = false, dryRun = true)?.getStringArray("present")?.toList().orEmpty()

    /** Puts [web] back through the connectors; false when they could not take it (no Bridge, or too old). */
    fun restoreWeb(ctx: Context, web: EncryptedBackup.WebSessions, replace: Boolean): Boolean {
        val b = bridgeImport(ctx, web, replace, dryRun = false) ?: return false
        Diagnostics.log(ctx, "backup", "web sessions restored: ${b.getInt("restored")}, kept ${b.getStringArray("kept")?.size ?: 0}, skipped ${b.getInt("skipped")}")
        return true
    }

    private fun bridgeImport(ctx: Context, web: EncryptedBackup.WebSessions, replace: Boolean, dryRun: Boolean): Bundle? {
        if (web.platform != EncryptedBackup.WEB_BRIDGE || !BridgePlugin.installed(ctx) || !BridgePlugin.knows(IMPORT)) return null
        val b = runCatching {
            BridgePlugin.call(ctx, IMPORT, null, Bundle().apply {
                putString("json", web.data); putBoolean("replace", replace); putBoolean("dryRun", dryRun)
            })
        }.getOrNull() ?: return null
        b.getString("error")?.let { e -> BridgePlugin.refusedAsUnknown(IMPORT, e); return null }
        return b
    }
}
