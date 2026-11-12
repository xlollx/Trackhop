package com.xlollx.songport

import com.xlollx.songport.data.Settings
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.sync.MemoryService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release switch: the build's half, the device's half, and what each 1.18 offer asks of it. */
class FeaturesTest {
    @After fun reset() {
        Features.build = false
        Features.preview = { false }
    }

    @Test fun offUnlessTheBuildOrTheDeviceSaysSo() {
        assertFalse(Features.v2)
        Features.preview = { true }
        assertTrue(Features.v2)
        Features.preview = { false }
        Features.build = true
        assertTrue(Features.v2)
    }

    @Test fun aPreviewThatCannotBeReadIsOff() {
        Features.preview = { error("no store yet") }
        assertFalse(Features.v2)
    }

    @Test fun theSettingsSayTheDevicesHalf() {
        assertFalse(Settings().preview2)
        assertFalse(Features.v2(Settings()))
        assertTrue(Features.v2(Settings(preview2 = true)))
        Features.build = true
        assertTrue(Features.v2(Settings()))
    }

    /** A service with one list, covers and moves of 1.18, as TIDAL. */
    private class Tidalish : MemoryService("tidalish") {
        override val supportsLikedSongs = true
        override val supportsLikedTarget = true
        override val canReorder = true
        override val reorderIsV2 = true
        override val canSetCover = true
        override val coverIsV2 = true
        override fun libraryIsV2(playlistId: String, asTarget: Boolean) = playlistId == MusicProvider.LIKED_ID
    }

    /** A service whose offers are all older than 1.18. */
    private class Older : MemoryService("older") {
        override val supportsLikedSongs = true
        override val canReorder = true
        override val canSetCover = true
    }

    @Test fun theOffersOf1_18WaitForItAndTheOlderOnesDoNot() {
        val env = com.xlollx.songport.contract.ContractEnv()
        val t = Tidalish(); val o = Older()
        assertTrue(t.libraryEntries(env, asTarget = false).none { it.id == MusicProvider.LIKED_ID })
        assertFalse(Features.coversOn(t))
        assertFalse(Features.reorderOn(t))
        // What a sync already uses keeps working.
        assertTrue(t.supportsLibrary(MusicProvider.LIKED_ID, asTarget = true))
        assertEquals(listOf(MusicProvider.LIKED_ID), o.libraryEntries(env, asTarget = false).map { it.id })
        assertTrue(Features.coversOn(o))
        assertTrue(Features.reorderOn(o))

        Features.preview = { true }
        assertEquals(listOf(MusicProvider.LIKED_ID), t.libraryEntries(env, asTarget = false).map { it.id })
        assertTrue(Features.coversOn(t))
        assertTrue(Features.reorderOn(t))
    }

    @Test fun aServiceThatTakesNoCoverOrMoveNeverGetsOne() {
        Features.build = true
        val none = MemoryService("none")
        assertFalse(Features.coversOn(none))
        assertFalse(Features.reorderOn(none))
    }

    @Test fun theDevicesPreviewStaysOnTheDevice() {
        val env = com.xlollx.songport.contract.ContractEnv()
        env.store.updateSettings { it.copy(preview2 = true) }
        val file = com.xlollx.songport.data.ConfigBackup.export(env, "test")
        assertFalse(file, Regex("\"preview2\"\\s*:\\s*true").containsMatchIn(file))
        // Brought into another device, which keeps its own.
        val other = com.xlollx.songport.contract.ContractEnv()
        com.xlollx.songport.data.ConfigBackup.import(other, com.xlollx.songport.data.ConfigBackup.read(file))
        assertFalse(other.store.data.settings.preview2)
    }

    /** Only the code's hash is kept; case and spaces around it do not count, a near miss does not open. */
    @Test fun thePreviewCodeIsCheckedByItsHash() {
        val keep = PreviewCode.hash
        try {
            PreviewCode.hash = java.security.MessageDigest.getInstance("SHA-256").digest("trackhop-preview:code-for-tests".toByteArray()).joinToString("") { "%02x".format(it) }
            assertTrue(PreviewCode.matches("code-for-tests"))
            assertTrue(PreviewCode.matches("  Code-For-Tests "))
            assertFalse(PreviewCode.matches("code-for-test"))
            assertFalse(PreviewCode.matches(""))
        } finally { PreviewCode.hash = keep }
    }
}
