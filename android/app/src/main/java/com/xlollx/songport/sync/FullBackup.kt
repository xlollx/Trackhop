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

    /** Trackhop Bridge's package: the only app the web sessions are handed to or taken from. */
    const val BRIDGE_PACKAGE = "com.xlollx.songport.ytmbridge"

    /**
     * SHA-256 of the certificates official Trackhop Bridge builds are signed with (upper-case hex, no
     * colons), read from the Bridge's release APK (v1.10.2, GitHub releases).
     */
    val BRIDGE_SHA256: Set<String> = setOf(
        "21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032", // release key (GitHub releases)
        // PLACEHOLDER: the Bridge's Google Play App Signing and AppGallery certificates go here, if it ships there, once the owner provides them.
    )

    /**
     * The rule for the sessions' two calls, apart from Android: in-process ([builtIn]), or a provider
     * that belongs to [BRIDGE_PACKAGE] ([providerPackage], the package the authority really resolves
     * to) and is signed with one of [pinned]. Any other connector plugin never sees them.
     */
    fun bridgeTrusted(builtIn: Boolean, providerPackage: String?, certs: Set<String>?, pinned: Set<String> = BRIDGE_SHA256): Boolean {
        if (builtIn) return true
        if (providerPackage != BRIDGE_PACKAGE) return false
        return certs.orEmpty().any { it.uppercase() in pinned }
    }

    /** The authority to send the sessions' calls to: the official Bridge's, checked now; null when there is none. */
    private fun trustedAuthority(ctx: Context): android.net.Uri? {
        val info = BridgePlugin.find(ctx) ?: return null
        val pm = ctx.packageManager
        val owner = runCatching { pm.resolveContentProvider(info.authority.authority ?: return null, 0)?.packageName }.getOrNull()
        val certs = owner?.let { pkg -> signers(pm, pkg) }
        return info.authority.takeIf { bridgeTrusted(false, owner, certs) }
    }

    private fun signers(pm: android.content.pm.PackageManager, pkg: String): Set<String>? = runCatching {
        val sigs = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.map { s -> java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02X".format(it) } }?.toSet()
    }.getOrNull()

    /**
     * One of the sessions' calls: in-process when built in, else only to the official Bridge checked
     * just now ([trustedAuthority]); null when there is none to ask (then nothing is sent anywhere).
     */
    private fun sessionsCall(ctx: Context, method: String, extras: Bundle?): Bundle? {
        if (BridgePlugin.builtIn) return BridgePlugin.call(ctx, method, null, extras)
        val authority = trustedAuthority(ctx) ?: return null
        return ctx.contentResolver.call(authority, method, null, extras)
    }

    /** Why the web sessions are not in the file. */
    enum class WebGap { NONE, NOT_HANDED, TOO_LARGE }

    /** The web sessions for the file; [gap]: a Bridge is there but they are not in it (too old, not the official one, too large). */
    class Web(val sessions: EncryptedBackup.WebSessions?, val gap: WebGap)

    fun webSessions(ctx: Context): Web {
        if (!BridgePlugin.installed(ctx)) return Web(null, WebGap.NONE)
        if (!BridgePlugin.knows(EXPORT)) return Web(null, WebGap.NOT_HANDED)
        val b = runCatching { sessionsCall(ctx, EXPORT, null) }.getOrNull()
        if (b == null) {
            Diagnostics.log(ctx, "backup", "the web sessions were not asked: no official Trackhop Bridge answers")
            return Web(null, WebGap.NOT_HANDED)
        }
        b.getString("error")?.let { e ->
            BridgePlugin.refusedAsUnknown(EXPORT, e)
            Diagnostics.log(ctx, "backup", "the web sessions were not handed over: ${e.take(80)}")
            return Web(null, if (b.getBoolean("tooLarge")) WebGap.TOO_LARGE else WebGap.NOT_HANDED)
        }
        val text = b.getString("json") ?: return Web(null, WebGap.NOT_HANDED)
        if (b.getInt("count") == 0) return Web(null, WebGap.NONE)
        return Web(EncryptedBackup.WebSessions(EncryptedBackup.WEB_BRIDGE, text, accounts(text)), WebGap.NONE)
    }

    /** The sessions of [text] as people read them ("Spotify (web): account"). */
    private fun accounts(text: String): List<String> = runCatching {
        Json.parseToJsonElement(text).jsonObject["sessions"]!!.jsonArray.map { s ->
            val o = s.jsonObject
            val service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val slot = o["slot"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val account = o["account"]?.jsonPrimitive?.contentOrNull.orEmpty()
            webName(if (slot.isEmpty()) service else "$service@$slot") + if (account.isNotBlank()) ": $account" else ""
        }
    }.getOrDefault(emptyList())

    /** The encrypted file's text; [signIns] how many sign-ins and client secrets, [webGap] as [Web.gap]. */
    class Exported(val text: String, val signIns: Int, val web: Int, val webGap: WebGap)

    /** The set-up with its secrets, sealed with [password] (cleared here once used). Blocks: never on the main thread. */
    fun export(ctx: Context, password: CharArray): Exported {
        try {
            val env = AndroidEnv.of(ctx)
            val plain = com.xlollx.songport.notify.Notify.intoBackup(env, ConfigBackup.export(env, "Trackhop ${BuildConfig.VERSION_NAME} (Android)", deviceName = android.os.Build.MODEL.orEmpty()))
            val web = webSessions(ctx)
            val secrets = EncryptedBackup.collect(env, web.sessions)
            val text = EncryptedBackup.wrap(plain, secrets, password)
            Diagnostics.log(ctx, "backup", "encrypted set-up written: ${secrets.vault.size + secrets.clientSecrets.size} sign-ins, ${web.sessions?.accounts?.size ?: 0} web sessions${if (web.gap != WebGap.NONE) ", web sessions not handed over" else ""}")
            return Exported(text, secrets.vault.size + secrets.clientSecrets.size, web.sessions?.accounts?.size ?: 0, web.gap)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Puts [web] back through the connectors, once the person confirmed the import; false when they
     * could not take it (no official Bridge, or one too old).
     */
    fun restoreWeb(ctx: Context, web: EncryptedBackup.WebSessions, replace: Boolean): Boolean {
        if (web.platform != EncryptedBackup.WEB_BRIDGE || !BridgePlugin.installed(ctx) || !BridgePlugin.knows(IMPORT)) return false
        val b = runCatching {
            sessionsCall(ctx, IMPORT, Bundle().apply { putString("json", web.data); putBoolean("replace", replace) })
        }.getOrNull() ?: return false
        b.getString("error")?.let { e -> BridgePlugin.refusedAsUnknown(IMPORT, e); return false }
        Diagnostics.log(ctx, "backup", "web sessions restored: ${b.getInt("restored")}, kept ${b.getStringArray("kept")?.size ?: 0}, skipped ${b.getInt("skipped")}")
        return true
    }

    /** The web services of [web] as people read them, for the import's question (whether this device has them is asked of no one before the import). */
    fun webServices(web: EncryptedBackup.WebSessions): List<String> = runCatching {
        Json.parseToJsonElement(web.data).jsonObject["sessions"]!!.jsonArray.map { s ->
            val o = s.jsonObject
            val service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val slot = o["slot"]?.jsonPrimitive?.contentOrNull.orEmpty()
            webName(if (slot.isEmpty()) service else "$service@$slot")
        }.distinct()
    }.getOrDefault(emptyList())
}
