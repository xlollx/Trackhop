package com.xlollx.songport.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transport errors a run waits out and tries again, and the answers it does not. */
class TransientNetworkTest {
    @Test fun droppedLinesAreTransient() {
        listOf(
            "Unable to resolve host \"api.listenbrainz.org\"", "connect timed out", "Connection reset by peer",
            "connection closed", "unexpected end of stream on https://api.listenbrainz.org/...", "Failed to connect to /1.2.3.4",
        ).forEach { assertTrue(it, isTransientNetwork(it)) }
    }

    @Test fun answersOfTheServiceAreNot() {
        listOf("ListenBrainz API 429: You have exceeded your rate limit", "ListenBrainz API 404: Cannot find playlist", null)
            .forEach { assertFalse(it.toString(), isTransientNetwork(it)) }
    }
}
