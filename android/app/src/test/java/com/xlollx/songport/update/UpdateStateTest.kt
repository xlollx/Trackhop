package com.xlollx.songport.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which release is newer than the running one: part by part as numbers, never as text. */
class UpdateStateTest {
    @Test fun versionsCompareAsNumbers() {
        assertTrue(UpdateState.newer("1.5.4", "1.5.3"))
        assertTrue(UpdateState.newer("v1.5.10", "1.5.9"))
        assertTrue(UpdateState.newer("1.6", "1.5.9"))
        assertTrue(UpdateState.newer("2.0.0", "1.99.99"))
        assertFalse(UpdateState.newer("1.5.3", "1.5.3"))
        assertFalse(UpdateState.newer("1.5.2", "1.5.3"))
        assertFalse(UpdateState.newer("1.5", "1.5.0"))
    }
}
