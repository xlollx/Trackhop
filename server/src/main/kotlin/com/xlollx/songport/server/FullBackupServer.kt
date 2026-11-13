package com.xlollx.songport.server

import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.EncryptedBackup
import com.xlollx.songport.notify.Notify
import com.xlollx.songport.providers.Providers

/**
 * The server's side of the encrypted full backup ([EncryptedBackup], 1.18 behind [Features.v2]):
 * the plain set-up with tokens.json, the client secrets and the cookies pasted for the web
 * connectors, sealed with a password. The same file as the app's: a phone's Bridge sessions are
 * not used here, and this server's cookies are not used on a phone.
 */
internal object FullBackupServer {
    /** A vault entry of a web connector (cookies pasted from a browser): it travels as web sign-ins. */
    fun isWeb(id: String): Boolean {
        if (Providers.byId(id) is CookieConnector) return true
        val service = id.substringBefore('@')
        return Providers.all().any { it is CookieConnector && it.serviceId == service }
    }

    /** A vault id or a client secret ([EncryptedBackup.conflicts]) as people read it. */
    fun name(id: String): String = when {
        id.startsWith(EncryptedBackup.CLIENT_SECRET) -> id.removePrefix(EncryptedBackup.CLIENT_SECRET).let { k -> (Providers.byId(k)?.displayName ?: k) + " (client secret)" }
        id.startsWith("notify:") -> "Notifications"
        else -> Providers.byId(id)?.displayName ?: id.trim('_')
    }

    /** The web sign-ins this person may bring in: only with the unofficial connectors allowed (Permissions). */
    fun webHere(env: ServerEnv): String? = if (env.may(Permissions.UNOFFICIAL)) EncryptedBackup.WEB_SERVER else null

    /** The file's text and how many sign-ins went in. [password] is cleared here. */
    fun export(env: ServerEnv, writtenBy: String, history: Boolean, password: CharArray): Pair<String, Int> {
        try {
            val plain = Notify.intoBackup(env, ConfigBackup.export(env, writtenBy, history = history))
            val web = EncryptedBackup.serverWeb(env, ::isWeb, ::name)
            val secrets = EncryptedBackup.collect(env, web, ::isWeb)
            val count = secrets.vault.size + secrets.clientSecrets.size + (web?.accounts?.size ?: 0)
            val text = EncryptedBackup.wrap(plain, secrets, password)
            env.log("config", "encrypted set-up written: $count sign-ins")
            return text to count
        } finally {
            password.fill('\u0000')
        }
    }

    /** What the person reads before the import, and what [import] needs. */
    class Opened(val snapshot: ConfigBackup.Snapshot, val channels: List<Notify.Channel>, val secrets: EncryptedBackup.Secrets, val conflicts: List<String>)

    /** [text] opened with [password] (cleared here); throws as [EncryptedBackup.unwrap] does. */
    fun open(env: ServerEnv, text: String, password: CharArray): Opened {
        try {
            val o = EncryptedBackup.unwrap(text, password)
            return Opened(ConfigBackup.read(o.plain), Notify.inBackup(o.plain), o.secrets, EncryptedBackup.conflicts(env, o.secrets, webHere(env)).map(::name))
        } finally {
            password.fill('\u0000')
        }
    }

    /** How many sign-ins the file holds, web ones included. */
    fun count(s: EncryptedBackup.Secrets): Int = s.vault.size + s.clientSecrets.size + (s.web?.accounts?.size ?: 0)

    class Imported(val summary: ConfigBackup.Summary, val restored: EncryptedBackup.Restored)

    /** Brings [o] in: the set-up as a plain file is, the channels when [channels], then the secrets. */
    fun import(env: ServerEnv, o: Opened, replacesDevice: Boolean, replaceSignIns: Boolean, channels: Boolean): Imported {
        val s = ConfigBackup.import(env, o.snapshot, replacesOther = replacesDevice)
        if (channels) Notify.restore(env, o.channels)
        val r = EncryptedBackup.restore(env, o.secrets, replaceSignIns, webHere(env))
        env.log("config", "imported ${s.syncs} syncs, ${s.files} files and ${r.signIns} sign-ins from ${s.writtenBy} (encrypted)${if (r.kept.isNotEmpty()) ", ${r.kept.size} sign-ins kept as they were" else ""}")
        return Imported(s, r)
    }

    /** What to say after an import: the web sign-ins left out, the other device to turn off. */
    fun notes(i: Imported, replacesDevice: Boolean): String = buildString {
        append(" Sign-ins restored: ${i.restored.signIns}.")
        if (i.restored.kept.isNotEmpty()) append(" This server's own sign-ins kept: ${i.restored.kept.size}.")
        if (i.restored.webSkipped) append(" The web sign-ins in the file come from a phone's Trackhop Bridge (or are not allowed here) and were not used: paste those services' cookies again.")
        if (replacesDevice) append(" Now turn off or uninstall Trackhop on the other device, or both will run the same syncs.")
        append(" A service may still ask you to sign in again: some sessions do not move to another device.")
    }

    const val NEWER = "This file is an encrypted backup with sign-ins, made by a newer Trackhop feature: this Trackhop cannot read it yet. Update it, or use a backup without sign-ins."
}
