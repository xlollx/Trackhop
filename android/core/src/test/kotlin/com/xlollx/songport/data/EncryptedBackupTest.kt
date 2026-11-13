package com.xlollx.songport.data

import com.xlollx.songport.contract.ContractEnv
import com.xlollx.songport.model.PlaylistRef
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.SyncJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** The encrypted full backup: the envelope, the secrets it carries, and how they come back in. */
class EncryptedBackupTest {
    private val password = "correct-horse-1".toCharArray()

    private fun phone(): ContractEnv = ContractEnv().apply {
        fun ref(id: String) = PlaylistRef("csv", id, id)
        store.upsertJob(SyncJob("daily", "Daily", ref("a"), ref("b"), schedule = Schedule.DAILY))
        store.upsertJob(SyncJob("manual", "By hand", ref("a"), ref("c")))
        store.updateSettings { it.copy(clientSecrets = mapOf("youtube" to "secret-a"), clientIds = mapOf("youtube" to "id-a")) }
        tokens.set("spotify", Tokens(accessToken = "token-a", refreshToken = "refresh-a"))
        tokens.set("__ai__", Tokens(accessToken = "key-a"))
        // A shared playlist's own key: this device's, never in a file.
        tokens.set(com.xlollx.songport.shared.SharedGroups.tokenKey("g1"), Tokens(accessToken = "group-key-a"))
    }

    private fun sealed(env: ContractEnv, web: EncryptedBackup.WebSessions? = null): String {
        val plain = ConfigBackup.export(env, "Trackhop test (Android)", deviceName = "Phone A")
        return EncryptedBackup.wrap(plain, EncryptedBackup.collect(env, web), password.copyOf())
    }

    @Test fun roundTripCarriesTheSignInsAndClientSecrets() {
        val text = sealed(phone())
        assertTrue(EncryptedBackup.isEncrypted(text))
        val envelope = Json.parseToJsonElement(text).jsonObject
        assertEquals(EncryptedBackup.FORMAT, envelope["format"]!!.jsonPrimitive.content)
        assertEquals("600000", envelope["kdf"]!!.jsonObject["iterations"]!!.jsonPrimitive.content)
        assertEquals(16, Base64.getDecoder().decode(envelope["salt"]!!.jsonPrimitive.content).size)
        assertEquals(12, Base64.getDecoder().decode(envelope["nonce"]!!.jsonPrimitive.content).size)
        for (secret in listOf("token-a", "refresh-a", "secret-a", "key-a", "Daily", "Phone A")) assertFalse(secret, text.contains(secret))

        val opened = EncryptedBackup.unwrap(text, password.copyOf())
        // The inside reads as a plain file.
        val snap = ConfigBackup.read(opened.plain)
        assertEquals(2, snap.jobs.size)
        assertTrue(snap.settings.clientSecrets.isEmpty())
        assertEquals("token-a", opened.secrets.vault["spotify"]?.accessToken)
        assertEquals("key-a", opened.secrets.vault["__ai__"]?.accessToken)
        assertFalse(opened.secrets.vault.keys.any { it.startsWith("shared:") })
        assertEquals(mapOf("youtube" to "secret-a"), opened.secrets.clientSecrets)

        // Into another Trackhop: the sign-ins land in its vault, the client secret in its settings.
        val other = ContractEnv()
        ConfigBackup.import(other, snap)
        val r = EncryptedBackup.restore(other, opened.secrets, replace = false, webHere = EncryptedBackup.WEB_SERVER)
        assertEquals(3, r.signIns)
        assertTrue(r.kept.isEmpty())
        assertEquals("refresh-a", other.tokens.get("spotify")?.refreshToken)
        assertEquals("secret-a", other.store.data.settings.clientSecrets["youtube"])
        assertNull(other.tokens.get(com.xlollx.songport.shared.SharedGroups.tokenKey("g1")))
    }

    @Test fun aWrongPasswordReadsNothing() {
        val text = sealed(phone())
        try {
            EncryptedBackup.unwrap(text, "wrong-password-0".toCharArray())
            fail("opened with a wrong password")
        } catch (e: EncryptedBackup.WrongPassword) {
            assertTrue(e.message!!.contains("Wrong password"))
        }
    }

    @Test fun aChangedFileDoesNotOpen() {
        val text = sealed(phone())
        val o = Json.parseToJsonElement(text).jsonObject
        val bytes = Base64.getDecoder().decode(o["ciphertext"]!!.jsonPrimitive.content)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        val tampered = JsonObject(o + ("ciphertext" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))).toString()
        try { EncryptedBackup.unwrap(tampered, password.copyOf()); fail("opened a changed file") } catch (_: EncryptedBackup.WrongPassword) {}
        // The envelope's settings are bound to the text too.
        val kdf = JsonObject(o["kdf"]!!.jsonObject + ("iterations" to JsonPrimitive(600_001)))
        val retuned = JsonObject(o + ("kdf" to kdf)).toString()
        try { EncryptedBackup.unwrap(retuned, password.copyOf()); fail("opened with other settings") } catch (_: EncryptedBackup.WrongPassword) {}
        // Absurd settings are refused before any work: more than 2,000,000 rounds would only hang the import.
        for (rounds in listOf(2_000_001, Int.MAX_VALUE, 1_000)) {
            val odd = JsonObject(o + ("kdf" to JsonObject(o["kdf"]!!.jsonObject + ("iterations" to JsonPrimitive(rounds))))).toString()
            try { EncryptedBackup.unwrap(odd, password.copyOf()); fail("accepted $rounds rounds") } catch (e: IllegalArgumentException) {
                assertFalse(e is EncryptedBackup.WrongPassword)
                assertTrue(e.message!!.contains("settings"))
            }
        }
    }

    @Test fun thePlainFileIsUnchanged() {
        val env = phone()
        val plain = ConfigBackup.export(env, "Trackhop test (Android)", now = 1_000)
        assertFalse(EncryptedBackup.isEncrypted(plain))
        assertFalse(plain.contains("secrets") || plain.contains("token-a") || plain.contains("secret-a"))
        assertEquals(ConfigBackup.FORMAT, Json.parseToJsonElement(plain).jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(plain, ConfigBackup.export(env, "Trackhop test (Android)", now = 1_000))
        assertEquals(2, ConfigBackup.read(plain).jobs.size)
        // An encrypted file where only plain ones are read: a clear word, not "not a file".
        try { ConfigBackup.read(sealed(env)); fail("read an encrypted file as a plain one") } catch (e: EncryptedBackup.EncryptedFile) {
            assertTrue(e.message!!.contains("newer Trackhop feature"))
        }
    }

    @Test fun foreignSchedulesStayOnOnlyWhenThisDeviceReplacesTheOther() {
        val opened = EncryptedBackup.unwrap(sealed(phone()), password.copyOf())
        val snap = ConfigBackup.read(opened.plain)
        val kept = ContractEnv()
        assertEquals(1, ConfigBackup.import(kept, snap).paused)
        assertFalse(kept.store.job("daily")!!.enabled)
        val replacing = ContractEnv()
        assertEquals(0, ConfigBackup.import(replacing, snap, replacesOther = true).paused)
        assertTrue(replacing.store.job("daily")!!.enabled)
        assertNull(replacing.store.job("daily")!!.scheduledOn)
    }

    @Test fun signInsHereAreReplacedOnlyWhenAsked() {
        val opened = EncryptedBackup.unwrap(sealed(phone()), password.copyOf())
        val here = ContractEnv().apply {
            tokens.set("spotify", Tokens(accessToken = "token-b"))
            store.updateSettings { it.copy(clientSecrets = mapOf("youtube" to "secret-b")) }
        }
        assertEquals(listOf("spotify", EncryptedBackup.CLIENT_SECRET + "youtube"), EncryptedBackup.conflicts(here, opened.secrets, EncryptedBackup.WEB_BRIDGE))
        val keep = EncryptedBackup.restore(here, opened.secrets, replace = false, webHere = EncryptedBackup.WEB_BRIDGE)
        assertEquals("token-b", here.tokens.get("spotify")?.accessToken)
        assertEquals("secret-b", here.store.data.settings.clientSecrets["youtube"])
        assertEquals(listOf("spotify", EncryptedBackup.CLIENT_SECRET + "youtube"), keep.kept)
        assertEquals("key-a", here.tokens.get("__ai__")?.accessToken)
        EncryptedBackup.restore(here, opened.secrets, replace = true, webHere = EncryptedBackup.WEB_BRIDGE)
        assertEquals("token-a", here.tokens.get("spotify")?.accessToken)
        assertEquals("secret-a", here.store.data.settings.clientSecrets["youtube"])
    }

    @Test fun webSignInsGoOnlyWhereTheyBelong() {
        val server = ContractEnv().apply { tokens.set("ytm", Tokens(accessToken = "", extra = mapOf("cookies" to "cookie-a"))); tokens.set("lastfm", Tokens(accessToken = "token-c")) }
        val isWeb = { id: String -> id == "ytm" }
        val web = EncryptedBackup.serverWeb(server, isWeb)!!
        assertEquals(EncryptedBackup.WEB_SERVER, web.platform)
        val secrets = EncryptedBackup.collect(server, web, isWeb)
        assertEquals(setOf("lastfm"), secrets.vault.keys)
        // Another server takes them.
        val server2 = ContractEnv()
        val r = EncryptedBackup.restore(server2, secrets, replace = false, webHere = EncryptedBackup.WEB_SERVER)
        assertFalse(r.webSkipped)
        assertEquals("cookie-a", server2.tokens.get("ytm")?.extra?.get("cookies"))
        // A phone does not: its Bridge keeps its own kind.
        val phone = ContractEnv()
        val p = EncryptedBackup.restore(phone, secrets, replace = false, webHere = EncryptedBackup.WEB_BRIDGE)
        assertTrue(p.webSkipped)
        assertNull(p.web)
        assertNull(phone.tokens.get("ytm"))
        assertEquals("token-c", phone.tokens.get("lastfm")?.accessToken)
        // A phone's Bridge sessions are handed back to the phone, and skipped on a server.
        val bridge = EncryptedBackup.Secrets(web = EncryptedBackup.WebSessions(EncryptedBackup.WEB_BRIDGE, "{\"sessions\":[]}"))
        assertEquals(EncryptedBackup.WEB_BRIDGE, EncryptedBackup.restore(ContractEnv(), bridge, false, EncryptedBackup.WEB_BRIDGE).web?.platform)
        assertTrue(EncryptedBackup.restore(ContractEnv(), bridge, false, EncryptedBackup.WEB_SERVER).webSkipped)
    }

    @Test fun aShortPasswordNeverSealsAFile() {
        val plain = ConfigBackup.export(phone(), "Trackhop test (Android)")
        for (pw in listOf("", "short-pw1")) {
            try { EncryptedBackup.wrap(plain, EncryptedBackup.Secrets(), pw.toCharArray()); fail("sealed with \"$pw\"") } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("at least"))
            }
        }
        assertTrue(EncryptedBackup.isEncrypted(EncryptedBackup.seal("{}", "ten-chars!".toCharArray())))
    }

    @Test fun passwordRules() {
        assertEquals(EncryptedBackup.PasswordProblem.SHORT, EncryptedBackup.passwordProblem("short-pw".toCharArray(), "short-pw".toCharArray()))
        assertEquals(EncryptedBackup.PasswordProblem.MISMATCH, EncryptedBackup.passwordProblem("long-enough-1".toCharArray(), "long-enough-2".toCharArray()))
        assertNull(EncryptedBackup.passwordProblem("long-enough-1".toCharArray(), "long-enough-1".toCharArray()))
    }
}
