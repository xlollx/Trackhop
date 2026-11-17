package com.xlollx.songport.data

import com.xlollx.songport.providers.*
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xlollx.songport.net.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString


/**
 * Token OAuth per servizio, cifrati con l'Android Keystore (EncryptedSharedPreferences).
 * Se la cifratura non e' disponibile sul dispositivo si ricade su SharedPreferences normali,
 * e il motivo resta in [unavailable] (Account e il registro diagnostico lo dicono).
 */
class TokenStore(context: Context) : TokenVault {
    // Aprire le preferenze cifrate costa (chiave nel Keystore, decifratura dell'indice): si fa una
    // volta per processo, altrimenti ogni ridisegno delle schermate che mostrano "collegato" rallenta.
    private val sp: SharedPreferences = shared(context)

    companion object {
        @Volatile private var cached: SharedPreferences? = null

        private fun shared(context: Context): SharedPreferences = cached ?: synchronized(this) {
            cached ?: open(context.applicationContext).also { cached = it }
        }

        /**
         * Why the encrypted sign-ins could not be opened in this process (the phone's Keystore not
         * answering, its key lost), in technical words for the log; null when they were. The app then
         * runs on an empty store, so every service looks disconnected: Accounts says why.
         */
        @Volatile var unavailable: String? = null
            private set

        /** [unavailable], after opening the store if this process has not yet. */
        fun unavailable(context: Context): String? {
            shared(context)
            return unavailable
        }

        private fun open(context: Context): SharedPreferences = openOr(
            encrypted = {
                val key = MasterKey.Builder(context, "songport_master")
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, "tokens", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            },
            plain = { context.getSharedPreferences("tokens_plain", Context.MODE_PRIVATE) },
            said = { Diagnostics.log(context, "tokens", "the secure storage could not be opened ($it): the saved sign-ins cannot be read until the app is opened again, the services show as not connected") },
        )

        /**
         * [value] saved under [providerId] (null: taken away), on the disk before this returns
         * (commit, not apply). A service that gives a new refresh token at every renewal (Spotify,
         * TIDAL) has already retired the old one: written in the background, a process killed a moment
         * later would keep a token that no longer works, and the person would have to sign in again.
         */
        internal fun write(sp: SharedPreferences, providerId: String, value: String?) {
            val edit = sp.edit().let { if (value == null) it.remove(providerId) else it.putString(providerId, value) }
            // commit() answers false when the disk refused: the value is in memory all the same, as with apply().
            edit.commit()
        }

        /**
         * The [encrypted] store, or [plain] when it cannot be opened: the cause is kept in
         * [unavailable] and handed to [said], never swallowed.
         */
        internal fun <T> openOr(encrypted: () -> T, plain: () -> T, said: (String) -> Unit): T = try {
            encrypted().also { unavailable = null }
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.take(3).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message?.take(160)}" }
            unavailable = cause
            runCatching { said(cause) }
            plain()
        }
    }

    override fun get(providerId: String): Tokens? =
        sp.getString(providerId, null)?.let { runCatching { json.decodeFromString<Tokens>(it) }.getOrNull() }

    override fun set(providerId: String, tokens: Tokens) = write(sp, providerId, json.encodeToString(tokens))

    override fun clear(providerId: String) = write(sp, providerId, null)

    /** The ids kept, for the encrypted full backup (the library's own keyset entries left out). */
    override fun ids(): Set<String> = runCatching { sp.all.keys.filterNot { it.startsWith("__androidx_security_crypto") }.toSet() }.getOrDefault(emptySet())
}
