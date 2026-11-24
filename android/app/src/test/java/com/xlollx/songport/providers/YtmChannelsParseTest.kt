package com.xlollx.songport.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The connectors' channel list: counts read when given, unknown otherwise; the suggestion follows ChannelAdvice. */
class YtmChannelsParseTest {
    private val p = YouTubeBridgeProvider()

    @Test fun countsAreReadWhenTheConnectorGivesThem() {
        val list = YouTubeBridgeProvider.parseChannels(
            """[{"id":"","name":"Mario","selected":true,"playlists":0,"likedSongs":0},{"id":"b1","name":"Rossi Records","byline":"Brand Account","playlists":24,"likedSongs":312}]""",
        )
        assertEquals(listOf(0, 24), list.map { it.playlists })
        assertEquals(listOf(0, 312), list.map { it.likedSongs })
        assertEquals("b1", p.betterChannel(list)?.id)
    }

    @Test fun anOlderConnectorWithoutCountsSuggestsNothing() {
        val list = YouTubeBridgeProvider.parseChannels("""[{"id":"","name":"Mario","selected":true},{"id":"b1","name":"Rossi Records"}]""")
        assertEquals(listOf(-1, -1), list.map { it.playlists })
        assertNull(p.betterChannel(list))
        assertEquals(emptyList<Any>(), YouTubeBridgeProvider.parseChannels(null))
    }
}
