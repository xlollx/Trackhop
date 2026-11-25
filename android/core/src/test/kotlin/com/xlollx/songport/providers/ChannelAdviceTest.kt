package com.xlollx.songport.providers

import com.xlollx.songport.providers.ChannelAdvice.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAdviceTest {
    private val own = Library("", 0, 0)
    private val brand = Library("b1", 4, 10)
    private val small = Library("b2", 1, 0)

    @Test fun anEmptyCurrentChannelNextToAFullOneSuggestsTheFullest() {
        assertEquals("b1", ChannelAdvice.better(listOf(own, small, brand), ""))
    }

    @Test fun musicOnTheCurrentChannelIsNothingToWarnAbout() {
        assertNull(ChannelAdvice.better(listOf(own.copy(likedSongs = 5), brand), ""))
        assertNull(ChannelAdvice.better(listOf(own, brand), "b1"))
    }

    @Test fun unknownCountsNeverMakeAChannelLookEmptyOrFull() {
        assertNull(ChannelAdvice.better(listOf(own.copy(likedSongs = -1), brand), ""))
        assertNull(ChannelAdvice.better(listOf(own, Library("b1", -1, -1)), ""))
    }

    @Test fun nothingBetterOrACurrentChannelGoneSaysNothing() {
        assertNull(ChannelAdvice.better(listOf(own, Library("b1", 0, 0)), ""))
        assertNull(ChannelAdvice.better(listOf(own, brand), "gone"))
    }
}
