package com.xlollx.songport.ui

import com.xlollx.songport.sync.Setlists
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A search with a wrong setlist.fm key leaves "key refused" on the page: saving another key
 * (or removing it) forgets that result, so the message does not stay and the next search uses the new key.
 */
class SetlistsKeptTest {
    @Test fun aNewKeyForgetsTheRefusedResult() {
        val k = SetlistsKept
        k.result = Setlists.Result(emptyList(), 1, hasKey = true, ownKey = true, quotaHit = false, keyRefused = true)
        k.open = "x1"
        k.keyChanged()
        assertNull(k.result)
        assertNull(k.open)
    }
}
