package com.xlollx.songport.data

import com.xlollx.songport.platform.Env
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encrypted full backup (Trackhop 1.18, behind the release switch): the `.trackhop` file of
 * [ConfigBackup] with what that one leaves out, the sign-ins of the token vault, the client secrets
 * and the web sign-ins (the Bridge's sessions on a phone, the pasted cookies on a server), sealed
 * with a password nobody else knows.
 *
 * The file is a small JSON envelope ([Envelope]): AES-256-GCM, its key from the password with
 * PBKDF2WithHmacSHA256 ([ITERATIONS] rounds), a random salt and nonce. Inside is the plain backup's
 * own JSON with one more field, `secrets` ([Secrets]): once opened it reads as a plain file does.
 * A lost password cannot be recovered: there is no other key.
 *
 * Everything here is from the JDK (javax.crypto). Secrets are never logged.
 */
object EncryptedBackup {
    const val FORMAT = "trackhop-config-encrypted"
    const val VERSION = 1
    const val KDF = "PBKDF2WithHmacSHA256"
    const val CIPHER = "AES-256-GCM"
    const val ITERATIONS = 600_000
    const val MIN_PASSWORD = 10
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    /** A file asking for more rounds than this (or fewer than the floor) is refused: it would only hang the import. */
    private const val MAX_ITERATIONS = 2_000_000
    private const val MIN_ITERATIONS = 100_000

    /** The web sign-ins of a phone: the Bridge's sessions, as its `sessions.export` hands them. */
    const val WEB_BRIDGE = "android-bridge"
    /** The web sign-ins of a server: the cookies pasted for its web connectors, as kept in its vault. */
    const val WEB_SERVER = "server-cookies"

    @Serializable
    data class Kdf(val name: String = KDF, val iterations: Int = ITERATIONS, val keyBits: Int = KEY_BITS)

    @Serializable
    data class Envelope(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val cipher: String = CIPHER,
        val kdf: Kdf = Kdf(),
        val salt: String,
        val nonce: String,
        val ciphertext: String,
    )

    /**
     * The web sign-ins, as only the platform that wrote them can put back: [platform] is [WEB_BRIDGE]
     * or [WEB_SERVER], [data] their own JSON, [accounts] their names for the screens.
     */
    @Serializable
    data class WebSessions(val platform: String, val data: String, val accounts: List<String> = emptyList())

    /** What the plain file leaves out. */
    @Serializable
    data class Secrets(
        val vault: Map<String, Tokens> = emptyMap(),
        val clientSecrets: Map<String, String> = emptyMap(),
        val web: WebSessions? = null,
    )

    /** The file opened: [plain] reads as a plain `.trackhop` file ([ConfigBackup.read], the channels...). */
    class Opened(val plain: String, val secrets: Secrets)

    /** The password is not the file's, or the file was changed: nothing was read. */
    class WrongPassword : IllegalArgumentException("Wrong password, or the file was changed: nothing was imported.")

    /** An encrypted file where only a plain one is read (the release switch off, an older path). */
    class EncryptedFile : IllegalArgumentException(
        "This file is an encrypted backup, made by a newer Trackhop feature: this Trackhop cannot read it yet. Use a backup without sign-ins, or update Trackhop.",
    )

    /** What is wrong with a password chosen for a new file: [SHORT], [MISMATCH], or null when it will do. */
    enum class PasswordProblem { SHORT, MISMATCH }

    fun passwordProblem(password: CharArray, again: CharArray): PasswordProblem? = when {
        password.size < MIN_PASSWORD -> PasswordProblem.SHORT
        !password.contentEquals(again) -> PasswordProblem.MISMATCH
        else -> null
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pretty = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /** [text] is an encrypted backup (whatever its version). Never throws. */
    fun isEncrypted(text: String): Boolean {
        if (!text.contains(FORMAT)) return false
        return runCatching { Json.parseToJsonElement(text.removePrefix("﻿")).jsonObject["format"]?.jsonPrimitive?.contentOrNull == FORMAT }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ the envelope

    /** [plain] sealed with [password]. The caller clears [password] afterwards. */
    fun seal(plain: String, password: CharArray, random: SecureRandom = SecureRandom()): String {
        require(password.size >= MIN_PASSWORD) { "The password needs at least $MIN_PASSWORD characters." }
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val kdf = Kdf()
        val key = derive(password, salt, kdf.iterations)
        val sealed = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(VERSION, kdf))
                doFinal(plain.toByteArray(Charsets.UTF_8))
            }
        } finally {
            key.fill(0)
        }
        val b64 = Base64.getEncoder()
        return pretty.encodeToString(Envelope.serializer(), Envelope(kdf = kdf, salt = b64.encodeToString(salt), nonce = b64.encodeToString(nonce), ciphertext = b64.encodeToString(sealed)))
    }

    /**
     * The text sealed in [text]. Throws [WrongPassword] when the password is not its own (or the file
     * was changed), and an [IllegalArgumentException] with a readable message when it is not one.
     */
    fun open(text: String, password: CharArray): String {
        val e = runCatching { json.decodeFromString(Envelope.serializer(), text.removePrefix("﻿")) }
            .getOrElse { throw IllegalArgumentException("Not a Trackhop configuration file.") }
        require(e.format == FORMAT) { "Not a Trackhop configuration file." }
        require(e.version <= VERSION) { "This file comes from a newer Trackhop: update this one first." }
        require(e.cipher == CIPHER && e.kdf.name == KDF && e.kdf.keyBits == KEY_BITS && e.kdf.iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "This encrypted file uses settings this Trackhop does not know." }
        val b64 = Base64.getDecoder()
        val salt: ByteArray
        val nonce: ByteArray
        val sealed: ByteArray
        try {
            salt = b64.decode(e.salt); nonce = b64.decode(e.nonce); sealed = b64.decode(e.ciphertext)
        } catch (x: IllegalArgumentException) {
            throw IllegalArgumentException("Not a Trackhop configuration file.")
        }
        require(salt.size >= SALT_BYTES && nonce.size == NONCE_BYTES && sealed.size > TAG_BITS / 8) { "Not a Trackhop configuration file." }
        val key = derive(password, salt, e.kdf.iterations)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(e.version, e.kdf))
                doFinal(sealed)
            }
        } catch (x: AEADBadTagException) {
            throw WrongPassword()
        } catch (x: java.security.GeneralSecurityException) {
            throw WrongPassword()
        } finally {
            key.fill(0)
        }
        return try { String(plain, Charsets.UTF_8) } finally { plain.fill(0) }
    }

    /** The envelope's own settings are authenticated with the text: changing one makes the file unreadable. */
    private fun aad(version: Int, kdf: Kdf): ByteArray = "$FORMAT/$version/$CIPHER/${kdf.name}/${kdf.iterations}/${kdf.keyBits}".toByteArray(Charsets.UTF_8)

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ------------------------------------------------------------------ the file

    /** [plain] (a plain backup's text, channels and all) with [secrets], sealed with [password]. */
    fun wrap(plain: String, secrets: Secrets, password: CharArray): String {
        val o = runCatching { Json.parseToJsonElement(plain.removePrefix("﻿")).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not a Trackhop configuration file.") }
        val inner = JsonObject(o + ("secrets" to json.encodeToJsonElement(Secrets.serializer(), secrets)))
        return seal(inner.toString(), password)
    }

    /** [text] opened with [password]: the plain backup and its secrets. Throws as [open] does. */
    fun unwrap(text: String, password: CharArray): Opened {
        val inner = open(text, password)
        val o = runCatching { Json.parseToJsonElement(inner).jsonObject }.getOrElse { throw IllegalArgumentException("Not a Trackhop configuration file.") }
        val secrets = o["secrets"]?.let { runCatching { json.decodeFromJsonElement(Secrets.serializer(), it) }.getOrNull() } ?: Secrets()
        return Opened(JsonObject(o - "secrets").toString(), secrets)
    }

    // ------------------------------------------------------------------ the secrets of this Trackhop

    /** An entry with nothing in it (a notification channel's secrets not entered yet): not a sign-in to keep. */
    private fun blank(t: Tokens?): Boolean = t == null || (t.accessToken.isBlank() && t.refreshToken.isNullOrBlank() && t.extra.values.all { it.isBlank() })

    /** Vault entries that never travel: a shared playlist's key is this device's own (its group stays here too). */
    private fun local(id: String): Boolean = id.startsWith(com.xlollx.songport.shared.SharedGroups.tokenKey(""))

    /**
     * This Trackhop's secrets: the vault (without what [isWeb] says is a web sign-in, and without the
     * shared playlists' keys), the client secrets, and [web].
     */
    fun collect(env: Env, web: WebSessions? = null, isWeb: (String) -> Boolean = { false }): Secrets {
        val vault = env.tokens.ids().filterNot { local(it) || isWeb(it) }.sorted()
            .mapNotNull { id -> env.tokens.get(id)?.let { id to it } }.toMap()
        return Secrets(vault, env.store.data.settings.clientSecrets.filterValues { it.isNotBlank() }, web)
    }

    private val vaultMap = MapSerializer(String.serializer(), Tokens.serializer())

    /** A server's web sign-ins: the vault entries [isWeb] picks, as [WEB_SERVER]; null when there are none. */
    fun serverWeb(env: Env, isWeb: (String) -> Boolean, name: (String) -> String = { it }): WebSessions? {
        val entries = env.tokens.ids().filter { !local(it) && isWeb(it) }.sorted().mapNotNull { id -> env.tokens.get(id)?.let { id to it } }.toMap()
        if (entries.isEmpty()) return null
        return WebSessions(WEB_SERVER, json.encodeToString(vaultMap, entries), entries.keys.map(name))
    }

    private fun serverEntries(w: WebSessions): Map<String, Tokens> =
        runCatching { json.decodeFromString(vaultMap, w.data) }.getOrDefault(emptyMap())

    /**
     * What [s] would replace here: vault ids this Trackhop holds otherwise, client secrets it has
     * with another value, and, when this platform takes [s]'s web sign-ins ([webHere]) as a server,
     * those it has too. A phone asks its Bridge for its own (see the app's FullBackup).
     */
    fun conflicts(env: Env, s: Secrets, webHere: String?): List<String> {
        val out = ArrayList<String>()
        s.vault.forEach { (id, t) -> if (!local(id)) env.tokens.get(id)?.takeUnless { blank(it) }?.let { if (it != t) out += id } }
        val here = env.store.data.settings.clientSecrets
        s.clientSecrets.forEach { (k, v) -> if (here[k].orEmpty().isNotBlank() && here[k] != v) out += "$CLIENT_SECRET$k" }
        val w = s.web
        if (w != null && w.platform == WEB_SERVER && webHere == WEB_SERVER) {
            serverEntries(w).forEach { (id, t) -> if (!local(id)) env.tokens.get(id)?.takeUnless { blank(it) }?.let { if (it != t && id !in out) out += id } }
        }
        return out
    }

    /** The prefix of a client secret's name in [conflicts]' answer. */
    const val CLIENT_SECRET = "client-secret:"

    /**
     * [signIns] written to the vault and client secrets taken; [kept]: this Trackhop's own, left as
     * they were (not [restore]'s `replace`); [webSkipped]: the file's web sign-ins belong to another
     * kind of Trackhop (a phone's Bridge on a server, a server's cookies on a phone); [web]: those
     * this platform puts back itself (a phone's Bridge sessions).
     */
    class Restored(val signIns: Int, val kept: List<String>, val webSkipped: Boolean, val web: WebSessions?)

    /**
     * Puts [s] in: each vault entry and client secret is written, those this Trackhop holds with
     * another value only when [replace] (the person said so). [webHere] is the kind of web sign-ins
     * this platform takes ([WEB_SERVER] or [WEB_BRIDGE]); a server's are written here, a phone's are
     * handed back in [Restored.web] for the app to give its Bridge.
     */
    fun restore(env: Env, s: Secrets, replace: Boolean, webHere: String?): Restored {
        val kept = ArrayList<String>()
        var written = 0
        fun put(id: String, t: Tokens) {
            if (local(id)) return
            val old = env.tokens.get(id)
            when {
                old == t -> Unit
                !blank(old) && !replace -> kept += id
                else -> { env.tokens.set(id, t); written++ }
            }
        }
        s.vault.forEach { (id, t) -> put(id, t) }
        if (s.clientSecrets.isNotEmpty()) {
            env.store.updateSettings { st ->
                val next = st.clientSecrets.toMutableMap()
                s.clientSecrets.forEach { (k, v) ->
                    val old = next[k].orEmpty()
                    when {
                        v.isBlank() || old == v -> Unit
                        old.isNotBlank() && !replace -> kept += "$CLIENT_SECRET$k"
                        else -> { next[k] = v; written++ }
                    }
                }
                st.copy(clientSecrets = next)
            }
            env.store.flush()
        }
        val w = s.web
        var skipped = false
        var forPlatform: WebSessions? = null
        when {
            w == null -> Unit
            w.platform != webHere -> skipped = true
            w.platform == WEB_SERVER -> serverEntries(w).forEach { (id, t) -> put(id, t) }
            else -> forPlatform = w
        }
        return Restored(written, kept, skipped, forPlatform)
    }
}
