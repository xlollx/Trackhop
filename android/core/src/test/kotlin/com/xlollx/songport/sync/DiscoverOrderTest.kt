package com.xlollx.songport.sync

import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.data.StoreData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverOrderTest {
    @Test fun noSavedOrderIsTheDefault() {
        assertEquals(DiscoverOrder.DEFAULT, DiscoverOrder.merge(emptyList()))
        assertEquals(listOf("releases", "link", "concerts", "records", "setlists", "subs"), DiscoverOrder.DEFAULT.take(6))
    }

    @Test fun theSavedOrderIsKept() {
        val saved = DiscoverOrder.DEFAULT.reversed()
        assertEquals(saved, DiscoverOrder.merge(saved))
    }

    @Test fun aNewCardGoesWhereTheDefaultPutsItAndARemovedOneIsDropped() {
        val known = listOf("a", "b", "new", "c", "first")
        // "first" comes after "c" in the default; "new" after "b"; "gone" no longer exists.
        assertEquals(listOf("c", "first", "b", "new", "a"), DiscoverOrder.merge(listOf("c", "gone", "b", "a"), known))
        // A new card with nothing before it goes first.
        assertEquals(listOf("top", "b", "a"), DiscoverOrder.merge(listOf("b", "a"), listOf("top", "a", "b")))
        // Duplicates are kept once.
        assertEquals(listOf("b", "a"), DiscoverOrder.merge(listOf("b", "b", "a"), listOf("a", "b")))
    }

    @Test fun movesStayWithinTheList() {
        val o = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), DiscoverOrder.move(o, "a", 1))
        assertEquals(listOf("a", "c", "b"), DiscoverOrder.move(o, "c", -1))
        assertEquals(o, DiscoverOrder.move(o, "a", -1))
        assertEquals(o, DiscoverOrder.move(o, "c", 1))
        assertEquals(o, DiscoverOrder.move(o, "x", 1))
    }

    @Test fun theDefaultAndAResetAreSavedAsNothing() {
        assertEquals(emptyList<String>(), DiscoverOrder.toSave(DiscoverOrder.DEFAULT))
        val moved = DiscoverOrder.move(DiscoverOrder.DEFAULT, "poster", -9)
        assertEquals(moved, DiscoverOrder.toSave(moved))
        // Moved back where it was: the default again.
        assertEquals(emptyList<String>(), DiscoverOrder.toSave(DiscoverOrder.move(moved, "poster", 9)))
    }

    @Test fun theOrderGoesToAnotherDeviceAndAnOlderFileHasNone() {
        val env = com.xlollx.songport.contract.ContractEnv()
        val moved = DiscoverOrder.move(DiscoverOrder.DEFAULT, "poster", -9)
        env.store.updateSettings { it.copy(discoverOrder = moved) }
        val other = com.xlollx.songport.contract.ContractEnv()
        ConfigBackup.import(other, ConfigBackup.read(ConfigBackup.export(env, "test")))
        assertEquals(moved, other.store.data.settings.discoverOrder)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(StoreData.serializer(), """{"settings":{"cardAccent":"fade"}}""").settings
        assertEquals(emptyList<String>(), old.discoverOrder)
    }
}
