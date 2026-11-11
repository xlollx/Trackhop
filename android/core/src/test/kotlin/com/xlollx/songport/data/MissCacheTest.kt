package com.xlollx.songport.data

import com.xlollx.songport.sync.Matcher
import org.junit.Assert.assertFalse
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
}
