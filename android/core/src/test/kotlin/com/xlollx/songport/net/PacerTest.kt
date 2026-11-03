package com.xlollx.songport.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PacerTest {
    @Test fun callsMadeTogetherAreSpacedOut() = runBlocking {
        val pacer = Pacer(100)
        val starts = Collections.synchronizedList(ArrayList<Long>())
        (1..4).map { async { pacer.paced { starts += System.currentTimeMillis() } } }.awaitAll()
        val sorted = starts.sorted()
        sorted.zipWithNext().forEach { (a, b) -> assertTrue("${b - a} ms apart", b - a >= 90) }
        assertTrue(sorted.last() - sorted.first() < 1000)
    }
}
