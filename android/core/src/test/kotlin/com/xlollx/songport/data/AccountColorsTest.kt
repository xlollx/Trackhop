package com.xlollx.songport.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An account's colour (the ring round its logo): kept by provider id, beside its name, and carried by a configuration file. */
class AccountColorsTest {
    @Test fun setAndClear() {
        var s = Settings()
        s = AccountColors.set(s, "spotify@2", "teal")
        assertEquals("teal", AccountColors.of(s, "spotify@2"))
        assertEquals(0xFF12A594L, AccountColors.argb("teal"))
        // An unknown key is no colour: it takes the colour off rather than storing it.
        s = AccountColors.set(s, "spotify@2", "chartreuse")
        assertNull(AccountColors.of(s, "spotify@2"))
        assertTrue(s.connectorColors.isEmpty())
        s = AccountColors.set(AccountColors.set(s, "deezer", "red"), "deezer", null)
        assertTrue(s.connectorColors.isEmpty())
        assertEquals(listOf("red", "amber", "green", "blue", "purple", "pink", "teal"), AccountColors.ALL.map { it.first })
    }

    @Test fun storedAndReadBack() {
        val env = com.xlollx.songport.contract.ContractEnv()
        env.store.updateSettings { AccountColors.set(it, "jellyfin@2", "purple") }
        val text = Json { encodeDefaults = false }.encodeToString(StoreData.serializer(), env.store.data)
        val back = Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), text).settings
        assertEquals(mapOf("jellyfin@2" to "purple"), back.connectorColors)
        // A data folder from before the colours: no account wears one.
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), """{"settings":{"connectorNames":{"deezer":"Mine"}}}""").settings
        assertTrue(old.connectorColors.isEmpty())
    }

    @Test fun travelsInAConfigurationFileWithTheNames() {
        val env = com.xlollx.songport.contract.ContractEnv()
        env.store.updateSettings { AccountColors.set(it.copy(connectorNames = mapOf("deezer" to "Family")), "deezer", "amber") }
        val other = com.xlollx.songport.contract.ContractEnv()
        other.store.updateSettings { AccountColors.set(it, "spotify", "blue") }
        ConfigBackup.import(other, ConfigBackup.read(ConfigBackup.export(env, "test")))
        val s = other.store.data.settings
        assertEquals("Family", s.connectorNames["deezer"])
        assertEquals(mapOf("spotify" to "blue", "deezer" to "amber"), s.connectorColors)
    }
}
