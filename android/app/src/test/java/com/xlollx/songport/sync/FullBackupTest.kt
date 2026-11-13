package com.xlollx.songport.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The web sessions of the encrypted backup go only to the official Trackhop Bridge (or stay in this
 * app when the connectors are built in): never to another connector plugin, whatever it declares.
 */
class FullBackupTest {
    private val pinned = setOf("AB".repeat(32))

    @Test fun onlyTheOfficialBridgeGetsTheSessions() {
        // Built in: no other app is involved.
        assertTrue(FullBackup.bridgeTrusted(builtIn = true, providerPackage = null, certs = null, pinned = pinned))
        // The Bridge's package, signed with the pinned certificate (any letter case).
        assertTrue(FullBackup.bridgeTrusted(false, FullBackup.BRIDGE_PACKAGE, setOf("ab".repeat(32)), pinned))
        // The Bridge's name with another key, or no readable certificate.
        assertFalse(FullBackup.bridgeTrusted(false, FullBackup.BRIDGE_PACKAGE, setOf("CD".repeat(32)), pinned))
        assertFalse(FullBackup.bridgeTrusted(false, FullBackup.BRIDGE_PACKAGE, emptySet(), pinned))
        assertFalse(FullBackup.bridgeTrusted(false, FullBackup.BRIDGE_PACKAGE, null, pinned))
        // Another plugin, even signed with the pinned key, or an authority that resolves to nothing.
        assertFalse(FullBackup.bridgeTrusted(false, "org.example.plugin", pinned, pinned))
        assertFalse(FullBackup.bridgeTrusted(false, null, pinned, pinned))
    }

    @Test fun theReleaseCertificateIsPinned() {
        assertTrue(FullBackup.BRIDGE_SHA256.isNotEmpty())
        assertTrue(FullBackup.BRIDGE_SHA256.all { it.matches(Regex("[0-9A-F]{64}")) })
    }
}
