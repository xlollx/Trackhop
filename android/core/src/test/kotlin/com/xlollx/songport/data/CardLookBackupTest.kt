package com.xlollx.songport.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cards' size and the tiles view travel in a configuration file like the cards' other looks. */
class CardLookBackupTest {
    @Test fun sizeAndTilesGoToAnotherDevice() {
        val env = com.xlollx.songport.contract.ContractEnv()
        env.store.updateSettings { it.copy(cardSize = "small", minimalSyncs = true, cardAccent = "wash", favoritesOnTop = false) }
        val other = com.xlollx.songport.contract.ContractEnv()
        ConfigBackup.import(other, ConfigBackup.read(ConfigBackup.export(env, "test")))
        val s = other.store.data.settings
        assertEquals("small", s.cardSize)
        assertTrue(s.minimalSyncs)
        assertEquals("wash", s.cardAccent)
        assertFalse(s.favoritesOnTop)
    }

    @Test fun anOlderFileLeavesTheCardsAsTheyWere() {
        val s = Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), """{"settings":{"cardAccent":"fade"}}""").settings
        assertEquals("large", s.cardSize)
        assertFalse(s.minimalSyncs)
        assertTrue(s.favoritesOnTop)
    }
}
