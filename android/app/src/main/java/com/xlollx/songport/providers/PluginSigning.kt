package com.xlollx.songport.providers

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Who a connector plugin must be signed by before Trackhop uses it ([BridgePlugin.find]). Any app
 * can declare the plugin action; only one signed with a certificate of Trackhop or Trackhop Bridge
 * is asked anything, and only its sign-in screens are opened.
 *
 * The same list as the Bridge's own check of its caller (`TrackhopSigning` in :bridge, which the
 * store builds do not carry; `PluginSigningListTest` keeps the two equal). The sessions' own calls
 * stay stricter: [com.xlollx.songport.sync.FullBackup.BRIDGE_SHA256], release only.
 */
object PluginSigning {
    /** SHA-256 of each trusted certificate, upper-case hex without colons. */
    val TRUSTED: Set<String> = setOf(
        "21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032", // release upload key (GitHub, F-Droid; Trackhop Bridge releases)
        "410826F07FFC9270445AF76FFCAAC642BBE2C76E19328C83832693423E6BBCC5", // Google Play App Signing
        "8BEC806768B3789A40E741BBFCEC462C71950E32A7A1DA15665E95BB7A0AE754", // CI debug key (test builds)
        // AppGallery serves our APK as uploaded, signed with the release key above (if Huawei App Signing is
        // ever enabled, its certificate must be added here and in TrackhopSigning.kt).
    )

    /**
     * A package's signing certificates (SHA-256): the ones it is signed with now ([current]: one, or
     * several for an APK with multiple signers) and, after a key rotation, the earlier ones Android
     * has proof of ([past]).
     */
    class Signers(val current: Set<String>, val past: Set<String> = emptySet())

    /**
     * Whether a package signed by [signers] is a plugin Trackhop may use: its certificate is one of
     * [allowed], or it rotated from one of them, or it is signed exactly as this app itself ([own]: a
     * fork that builds both with its own key). With multiple signers every one must be allowed.
     * Unreadable or missing certificates: never.
     */
    fun trusted(signers: Signers?, own: Signers? = null, allowed: Set<String> = TRUSTED): Boolean {
        val current = signers?.current?.map { it.uppercase() }?.toSet().orEmpty()
        if (current.isEmpty()) return false
        val ownNow = own?.current?.map { it.uppercase() }?.toSet().orEmpty()
        if (ownNow.isNotEmpty() && current == ownNow) return true
        if (current.size > 1) return current.all { it in allowed }
        return current.single() in allowed || signers!!.past.any { it.uppercase() in allowed }
    }

    /** [pkg]'s signing certificates; null when they cannot be read (not installed, not visible). */
    fun read(pm: PackageManager, pkg: String): Signers? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (info.hasMultipleSigners()) {
                Signers(info.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet())
            } else {
                // Oldest first, the current certificate last; a single entry when the key never rotated.
                val history = info.signingCertificateHistory.orEmpty().map { sha256(it.toByteArray()) }
                if (history.isEmpty()) null else Signers(setOf(history.last()), history.dropLast(1).toSet())
            }
        } else {
            @Suppress("DEPRECATION", "PackageManagerGetSignatures")
            val sigs = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            if (sigs.isNullOrEmpty()) null else Signers(sigs.map { sha256(it.toByteArray()) }.toSet())
        }
    }.getOrNull()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    /** An app that declares the plugin action, with the provider authority it names (null: the legacy one). */
    class Candidate(val packageName: String, val authority: String?)

    /**
     * The plugin to use among the apps declaring the action ([declared], in the system's order):
     * the first whose signature is [trusted], Trackhop Bridge's own package ([preferred]) before any
     * other; without one, [preferred] itself when it is installed without the action
     * ([legacyInstalled]: plugin versions from before it) and trusted. Never an app that is not.
     */
    fun choose(declared: List<Candidate>, legacyInstalled: Boolean, preferred: String, trusted: (String) -> Boolean): Candidate? {
        val ok = declared.filter { trusted(it.packageName) }
        return ok.firstOrNull { it.packageName == preferred } ?: ok.firstOrNull()
            ?: Candidate(preferred, null).takeIf { legacyInstalled && trusted(preferred) }
    }
}
