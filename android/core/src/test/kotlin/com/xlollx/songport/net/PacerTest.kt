package com.xlollx.songport.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PacerTest {
    /**
     * Four calls asked at once: the last cannot start before three gaps have passed. Only that lower
     * bound is checked: a slow machine may start any call later, never earlier.
     */
    @Test fun callsMadeTogetherAreSpacedOut() = runBlocking {
        val pacer = Pacer(100)
        val starts = Collections.synchronizedList(ArrayList<Long>())
        val before = System.currentTimeMillis()
        (1..4).map { async { pacer.paced { starts += System.currentTimeMillis() } } }.awaitAll()
        assertTrue("last started ${starts.max() - before} ms after the first was asked", starts.max() - before >= 290)
    }
}
