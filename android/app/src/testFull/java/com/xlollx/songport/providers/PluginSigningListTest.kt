package com.xlollx.songport.providers

import com.xlollx.songport.ytmbridge.TrackhopSigning
import org.junit.Assert.assertEquals
import org.junit.Test

/** The certificates a plugin may be signed with are the ones Trackhop itself is signed with (:bridge's list, absent from the store builds). */
class PluginSigningListTest {
    @Test fun sameCertificatesAsTheBridgesCallerCheck() {
        assertEquals(TrackhopSigning.ALL, PluginSigning.TRUSTED)
    }
}
