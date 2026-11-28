package com.xlollx.songport.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedNamesTest {
    private fun group(id: String, name: String) = SharedGroup(id = id, name = name, creator = "c", me = "m", folder = "f", jobId = "j-$id", twinId = "t-$id")
    private val metal = group("1", "Metal & More")
    private val road = group("2", "Road trip")

    @Test fun oneGroupNamesItself() {
        assertEquals("Metal & More", SharedNames.of(listOf(metal), "isrc:X", fallback = "Several"))
        assertEquals("Metal & More", SharedNames.of(listOf(metal), fallback = "Several"))
    }

    @Test fun severalGroupsAreToldByWhichHoldsTheTrack() {
        val holds = { g: SharedGroup, k: String -> g.id == "2" && k == "isrc:X" }
        assertEquals("Road trip", SharedNames.of(listOf(metal, road), "isrc:X", holds, "Several"))
        assertEquals("Several", SharedNames.of(listOf(metal, road), "isrc:Y", holds, "Several"))
        assertEquals("Several", SharedNames.of(listOf(metal, road), null, holds, "Several"))
    }

    @Test fun noGroupAtAllNeverShowsTheInternalId() {
        assertEquals("Several", SharedNames.of(emptyList(), "k", fallback = "Several"))
    }

    @Test fun aSyncOfThePairFindsItsGroup() {
        assertEquals("Road trip", SharedNames.ofJob(listOf(metal, road), "t-2", "Several"))
        assertEquals("Metal & More", SharedNames.ofJob(listOf(metal, road), "j-1", "Several"))
        assertEquals("Several", SharedNames.ofJob(listOf(metal, road), "other", "Several"))
    }
}
