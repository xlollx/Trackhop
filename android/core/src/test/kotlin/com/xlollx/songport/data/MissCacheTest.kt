package com.xlollx.songport.data

import com.xlollx.songport.sync.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MissCacheTest {
    @Test fun aMissFoundByAnOlderMatchingIsSearchedAgain() {
        // #57: tracks missed before an update stayed "not found" for a week, the better search unused.
        val store = FileStore(Files.createTempDirectory("misses").toFile())
        val key = FileStore.cacheKey("spotify", "t1", "jellyfin")
        store.update { it.copy(missCache = mapOf(key to System.currentTimeMillis()), missRevision = Matcher.REVISION - 1) }
        assertFalse(store.cachedMiss("spotify", "t1", "jellyfin"))
        // Missed again under this matching: remembered, and the older misses are gone.
        store.update { it.copy(missCache = it.missCache + ("old" to System.currentTimeMillis())) }
        store.putMisses(listOf(FileStore.cacheKey("spotify", "t2", "jellyfin")))
        assertTrue(store.cachedMiss("spotify", "t2", "jellyfin"))
        assertFalse(store.cachedMiss("spotify", "t1", "jellyfin"))
        assertFalse("old" in store.data.missCache)
    }

    @Test fun aMissIsSearchedAgainAfterAMonth() {
        val store = FileStore(Files.createTempDirectory("misses").toFile())
        val day = 24L * 3_600_000
        val now = System.currentTimeMillis()
        store.update {
            it.copy(missRevision = Matcher.REVISION, missCache = mapOf(
                FileStore.cacheKey("spotify", "week", "tidal") to now - 8 * day,
                FileStore.cacheKey("spotify", "month", "tidal") to now - 31 * day,
            ))
        }
        // A week no longer: a month (the search quota of YouTube lasts longer).
        assertTrue(store.cachedMiss("spotify", "week", "tidal"))
        assertFalse(store.cachedMiss("spotify", "month", "tidal"))
        assertEquals(30L * day, FileStore.MISS_TTL_MS)
    }

    @Test fun matchesFoundByTheEngineCarryTheRevisionAndTheUsersChoicesNone() {
        val store = FileStore(Files.createTempDirectory("matches").toFile())
        val auto = FileStore.cacheKey("spotify", "a", "tidal")
        val manual = FileStore.cacheKey("spotify", "m", "tidal")
        val legacy = FileStore.cacheKey("spotify", "l", "tidal")
        // A match saved before 2.2: no revision, never checked again.
        store.update { it.copy(matchCache = mapOf(legacy to "L")) }
        store.putMatches(mapOf(auto to "A"), auto = true)
        store.putMatches(mapOf(manual to "M"))
        assertEquals(Matcher.REVISION, store.autoRevision(auto))
        assertNull(store.autoRevision(manual))
        assertNull(store.autoRevision(legacy))
        // Found again unchanged by a run: what it was stays (the legacy one is still no engine's).
        store.putMatches(mapOf(auto to "A", manual to "M", legacy to "L"), auto = true)
        assertNull(store.autoRevision(manual))
        assertNull(store.autoRevision(legacy))
        // An older revision, then checked by the current matching.
        store.update { it.copy(autoMatches = it.autoMatches + (auto to "${Matcher.REVISION - 1}|A")) }
        assertEquals(Matcher.REVISION - 1, store.autoRevision(auto))
        store.markChecked(mapOf(auto to "A"))
        assertEquals(Matcher.REVISION, store.autoRevision(auto))
        // Changed by hand, even where the review's code writes the cache directly: the user's from then on.
        store.update { it.copy(matchCache = it.matchCache + (auto to "B")) }
        assertNull(store.autoRevision(auto))
        // A check of the old target that ends after the hand-picked one: no engine's mark on the user's choice.
        store.markChecked(mapOf(auto to "A"))
        assertNull(store.autoRevision(auto))
        // Chosen by the user through putMatches: the same.
        store.putMatches(mapOf(auto to "A"), auto = true)
        assertEquals(Matcher.REVISION, store.autoRevision(auto))
        store.putMatches(mapOf(auto to "A"))
        assertNull(store.autoRevision(auto))
    }
}
