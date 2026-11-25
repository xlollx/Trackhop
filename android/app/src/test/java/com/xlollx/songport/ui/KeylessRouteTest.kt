package com.xlollx.songport.ui

import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.FakeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify's key page offers "Spotify (web)" only where this install can add it. */
class KeylessRouteTest {
    private fun web(serviceId: String = "spotify_bridge") = object : MusicProvider by FakeProvider(serviceId) {
        override val pluginBased = true
    }

    @Test fun aWebSignInIsOfferedWhereTheBuildNamesItOrItsConnectorAnswers() {
        assertTrue("full and F-Droid builds", offeredToAdd(web(), pluginLinks = true) { false })
        assertTrue("store build with the connector app", offeredToAdd(web(), pluginLinks = false) { true })
        assertFalse("store build without it", offeredToAdd(web(), pluginLinks = false) { false })
        assertTrue("an official service always", offeredToAdd(FakeProvider("spotify"), pluginLinks = false) { false })
    }

    @Test fun spotifysKeyPageLeadsToSpotifyOnTheWebWhenItIsOffered() {
        val route = web()
        assertEquals(route, KeylessRoute.of(FakeProvider("spotify"), listOf(FakeProvider("deezer"), route)))
    }

    @Test fun noRouteWhenItIsNotOfferedOrForAnotherService() {
        assertNull(KeylessRoute.of(FakeProvider("spotify"), listOf(FakeProvider("spotify"), FakeProvider("deezer"))))
        assertNull(KeylessRoute.of(FakeProvider("youtube"), listOf(web(), web("youtube_bridge"))))
    }
}
