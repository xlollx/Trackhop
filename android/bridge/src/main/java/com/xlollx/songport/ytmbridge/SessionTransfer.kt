package com.xlollx.songport.ytmbridge

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.security.MessageDigest

/**
 * The web sessions handed to Trackhop for its encrypted full backup, and taken back from it
 * (`sessions.export`, `sessions.import`): every value each sign-in keeps (the cookies, the account,
 * the cached tokens), per service and account, as the sessions store them.
 *
 * These are the only methods that hand the sessions themselves out, so they answer one caller only,
 * checked here on top of the provider's own check ([callerOk]): the code running in this very app
 * (Trackhop's GitHub/F-Droid build, where this module is built in) or, through the Bridge app's
 * provider, the Trackhop package alone signed with one of Trackhop's certificates. Anyone else gets
 * an error and nothing else. The values are never logged.
 */
object SessionTransfer {
    const val EXPORT = "sessions.export"
    const val IMPORT = "sessions.import"
    const val FORMAT = "trackhop-bridge-sessions"
    const val VERSION = 1
    const val NOT_ALLOWED = "caller not allowed"

    /** The extras of [IMPORT]: the sessions' JSON, whether to replace the sessions here, whether only to say which are here. */
    const val EXTRA_JSON = "json"
    const val EXTRA_REPLACE = "replace"
    const val EXTRA_DRY_RUN = "dryRun"

    /** Trackhop's package id, unchanged since it was called Songport. */
    const val TRACKHOP = "com.xlollx.songport"

    /**
     * SHA-256 of the signing certificates of official Trackhop builds (upper-case hex, no colons): the
     * same list as the Bridge app's provider check (its `Allowed`), the release upload key and the CI
     * debug key. A fork that rebuilds Trackhop with its own key must rebuild the Bridge with its own list.
     */
    val TRACKHOP_SHA256: Set<String> = setOf(
        "21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032", // release upload key
        "8BEC806768B3789A40E741BBFCEC462C71950E32A7A1DA15665E95BB7A0AE754", // CI debug key
    )

    // ------------------------------------------------------------------ who may ask

    /** Whether the binder call being served comes from this app itself or from Trackhop (see [callerOk] below). */
    fun callerOk(ctx: Context): Boolean {
        val uid = Binder.getCallingUid()
        val pm = ctx.packageManager
        return callerOk(uid, Process.myUid(), runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull()) { pkg -> signers(pm, pkg) }
    }

    /**
     * The rule, apart from Android: [callingUid] is this app's own (a call in-process, never through
     * a binder from another app), or a uid that is the [TRACKHOP] package alone (no shared uid) whose
     * signing certificates ([certsOf], SHA-256 hex) include one of [allowed].
     */
    fun callerOk(callingUid: Int, myUid: Int, packages: List<String>?, allowed: Set<String> = TRACKHOP_SHA256, certsOf: (String) -> Set<String>?): Boolean {
        if (callingUid == myUid) return true
        if (packages == null || packages.size != 1 || packages[0] != TRACKHOP) return false
        val certs = runCatching { certsOf(TRACKHOP) }.getOrNull().orEmpty()
        return certs.any { it.uppercase() in allowed }
    }

    /** The SHA-256 of [pkg]'s current signing certificates; null when they cannot be read. */
    private fun signers(pm: PackageManager, pkg: String): Set<String>? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        sigs?.map { sha256(it.toByteArray()) }?.toSet()
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    // ------------------------------------------------------------------ the calls

    /** [EXPORT] and [IMPORT], after the caller check. */
    internal fun call(ctx: Context, method: String, extras: Bundle?): Bundle {
        if (!callerOk(ctx)) return Bundle().apply { putString("error", NOT_ALLOWED) }
        return try {
            when (method) {
                EXPORT -> exported(ctx).let { (text, count) -> Bundle().apply { putString(EXTRA_JSON, text); putInt("count", count) } }
                IMPORT -> {
                    val text = extras?.getString(EXTRA_JSON) ?: return Bundle().apply { putString("error", "missing sessions") }
                    imported(ctx, text, replace = extras.getBoolean(EXTRA_REPLACE), dryRun = extras.getBoolean(EXTRA_DRY_RUN))
                }
                else -> Bundle().apply { putString("error", "unknown method $method") }
            }
        } catch (e: Exception) {
            // The kind of failure only: a message could quote a value.
            Bundle().apply { putString("error", "the sessions could not be ${if (method == EXPORT) "read" else "written"} (${e.javaClass.simpleName})") }
        }
    }

    /** A session kept here: its service, its account's slot ("" the primary) and how to reach it. */
    private class Kept(val service: String, val slot: String, val opened: Boolean, val connected: () -> Boolean, val account: () -> String?, val stored: () -> Map<String, *>, val restore: (Map<String, Any?>) -> Unit)

    /** The services whose extra accounts are kept apart (as [BridgeCore] serves them). */
    private val SLOTTED = setOf("ytm", "spotify", "apple", "deezer", "anghami", "qobuz", "liveone")

    /** The web services, as their sessions' files are named. */
    val SERVICES = listOf("ytm", "amazon", "spotify", "apple", "deezer", "soundcloud", "liveone", "anghami", "qobuz")

    private fun kept(ctx: Context, service: String, slot: String): Kept? {
        if (slot.isNotEmpty() && service !in SLOTTED) return null
        return when (service) {
            "ytm" -> Session.forSlot(slot).let { s -> Kept(service, slot, s.opened, { s.isConnected(ctx) }, { s.account(ctx) }, { s.stored(ctx) }, { s.restore(ctx, it) }) }
            "amazon" -> Kept(service, slot, AmazonSession.opened, { AmazonSession.isConnected(ctx) }, { AmazonSession.account(ctx) }, { AmazonSession.stored(ctx) }, { AmazonSession.restore(ctx, it) })
            else -> BridgeCore.resettable(service, slot)?.let { s -> Kept(service, slot, s.opened, { s.isConnected(ctx) }, { s.account(ctx) }, { s.stored(ctx) }, { s.restore(ctx, it) }) }
        }
    }

    /** The file a session lives in, without its ".xml" (the encrypted one; "_plain" follows it where the Keystore failed). */
    private fun fileOf(service: String, slot: String): String = when (service) {
        "ytm" -> if (slot.isEmpty()) "ytm_session" else "ytm_session_s$slot"
        "amazon" -> "amazon_session"
        else -> if (slot.isEmpty()) "${service}_session" else "${service}_s${slot}_session"
    }

    private val SLOT_FILE = Regex("""^(ytm_session_s|([a-z]+)_s)([0-9]{1,4})(_session)?(_plain)?\.xml$""")

    /**
     * The sessions this device keeps, found by their files or opened already (a service never signed
     * in to is not opened, and no key is made for it): every primary account and every extra one.
     */
    private fun present(ctx: Context): List<Kept> {
        val names = File(ctx.applicationInfo.dataDir, "shared_prefs").list()?.toSet().orEmpty()
        fun exists(base: String) = "$base.xml" in names || "${base}_plain.xml" in names
        val slots = names.mapNotNull { n ->
            val m = SLOT_FILE.matchEntire(n) ?: return@mapNotNull null
            val service = if (m.groupValues[1] == "ytm_session_s") "ytm" else m.groupValues[2]
            if (service != "ytm" && m.groupValues[4].isEmpty()) return@mapNotNull null
            service to m.groupValues[3]
        }.filter { (s, _) -> s in SLOTTED }
        val all = SERVICES.map { it to "" } + slots
        return all.distinct().mapNotNull { (s, slot) -> kept(ctx, s, slot) }.filter { it.opened || exists(fileOf(it.service, it.slot)) }
    }

    /** Every signed-in session as one JSON text, with how many. */
    internal fun exported(ctx: Context): Pair<String, Int> {
        val sessions = present(ctx).filter { runCatching { it.connected() }.getOrDefault(false) }.map { k ->
            JsonObject(mapOf(
                "service" to JsonPrimitive(k.service),
                "slot" to JsonPrimitive(k.slot),
                "account" to JsonPrimitive(k.account().orEmpty()),
                "values" to encode(k.stored()),
            ))
        }
        val out = JsonObject(mapOf("format" to JsonPrimitive(FORMAT), "version" to JsonPrimitive(VERSION), "sessions" to JsonArray(sessions)))
        return out.toString() to sessions.size
    }

    /**
     * The sessions of [text] put back: one this device is signed in to already is replaced only when
     * [replace]; [dryRun] only names those (`present`). Answers `restored`, `kept` and `skipped`
     * (an unknown service, an extra account this WebView cannot keep apart).
     */
    internal fun imported(ctx: Context, text: String, replace: Boolean, dryRun: Boolean): Bundle {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) { "not the Bridge's sessions" }
        require((root["version"]?.jsonPrimitive?.intOrNull ?: 0) <= VERSION) { "sessions from a newer Bridge" }
        val present = ArrayList<String>()
        val kept = ArrayList<String>()
        var restored = 0
        var skipped = 0
        for (e in root["sessions"]?.jsonArray.orEmpty()) {
            val o = e as? JsonObject ?: continue
            val service = o["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val slot = o["slot"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val values = (o["values"] as? JsonObject)?.let { decode(it) }.orEmpty()
            val k = if (Slots.valid(slot) == slot && service in SERVICES) kept(ctx, service, slot) else null
            if (k == null || values.isEmpty() || (slot.isNotEmpty() && !Slots.supported())) { skipped++; continue }
            val label = if (slot.isEmpty()) service else "$service@$slot"
            val here = runCatching { k.connected() }.getOrDefault(false)
            if (here) present += label
            if (dryRun) continue
            if (here && !replace) { kept += label; continue }
            k.restore(values)
            BridgeCore.forgetCached(service, slot)
            restored++
        }
        if (!dryRun) BridgeNet.log("backup", "web sessions restored from a backup: $restored${if (kept.isNotEmpty()) ", ${kept.size} kept as they were" else ""}${if (skipped > 0) ", $skipped skipped" else ""}")
        return Bundle().apply {
            putStringArray("present", present.toTypedArray())
            putStringArray("kept", kept.toTypedArray())
            putInt("restored", restored)
            putInt("skipped", skipped)
        }
    }

    // ------------------------------------------------------------------ the values, typed

    /** A session's values as JSON, each with its type, so they go back exactly as they were. */
    internal fun encode(values: Map<String, *>): JsonObject = JsonObject(values.entries.mapNotNull { (k, v) ->
        // The Keystore library's own entries stay with the device's key.
        if (k.startsWith("__androidx_security_crypto")) return@mapNotNull null
        val typed: JsonElement = when (v) {
            is String -> JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive(v)))
            is Boolean -> JsonObject(mapOf("t" to JsonPrimitive("b"), "v" to JsonPrimitive(v)))
            is Int -> JsonObject(mapOf("t" to JsonPrimitive("i"), "v" to JsonPrimitive(v)))
            is Long -> JsonObject(mapOf("t" to JsonPrimitive("l"), "v" to JsonPrimitive(v)))
            is Float -> JsonObject(mapOf("t" to JsonPrimitive("f"), "v" to JsonPrimitive(v)))
            is Set<*> -> JsonObject(mapOf("t" to JsonPrimitive("ss"), "v" to JsonArray(v.filterIsInstance<String>().map { JsonPrimitive(it) })))
            else -> return@mapNotNull null
        }
        k to typed
    }.toMap())

    internal fun decode(o: JsonObject): Map<String, Any?> = o.entries.mapNotNull { (k, e) ->
        val t = (e as? JsonObject) ?: return@mapNotNull null
        val p = t["v"]
        val v: Any? = when (t["t"]?.jsonPrimitive?.contentOrNull) {
            "s" -> p?.jsonPrimitive?.contentOrNull
            "b" -> p?.jsonPrimitive?.booleanOrNull
            "i" -> p?.jsonPrimitive?.intOrNull
            "l" -> p?.jsonPrimitive?.longOrNull
            "f" -> p?.jsonPrimitive?.doubleOrNull?.toFloat()
            "ss" -> (p as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            else -> null
        } ?: return@mapNotNull null
        k to v
    }.toMap()

    /** Writes [values] into [prefs] at once, each with its own type. */
    internal fun write(prefs: SharedPreferences, values: Map<String, Any?>) {
        val e = prefs.edit()
        values.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
        }
        e.commit()
    }
}
