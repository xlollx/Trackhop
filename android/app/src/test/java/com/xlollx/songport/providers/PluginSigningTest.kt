package com.xlollx.songport.providers

import com.xlollx.songport.providers.PluginSigning.Candidate
import com.xlollx.songport.providers.PluginSigning.Signers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A connector plugin is used only when signed with a certificate of Trackhop or Trackhop Bridge:
 * an app that merely declares the plugin action (or takes the Bridge's old package id on a phone
 * without it) is never asked anything. The package manager is faked by a map of package → signers.
 */
class PluginSigningTest {
    private val release = "21EADF268C72F85565961A6045BF4BE5ABAF256D680D1E0D808D93ED22D35032"
    private val play = "410826F07FFC9270445AF76FFCAAC642BBE2C76E19328C83832693423E6BBCC5"
    private val stranger = "AB".repeat(32)
    private val other = "CD".repeat(32)
    private val bridge = "com.xlollx.songport.ytmbridge"

    // The fake package manager: who signed each installed package.
    private fun pm(vararg installed: Pair<String, Signers?>): (String) -> Boolean {
        val map = installed.toMap()
        return { pkg -> PluginSigning.trusted(map[pkg]) }
    }

    @Test fun knownCertificatesAreTrustedAndOthersAreNot() {
        assertTrue(PluginSigning.trusted(Signers(setOf(release))))
        assertTrue(PluginSigning.trusted(Signers(setOf(play))))
        assertTrue("hex case does not matter", PluginSigning.trusted(Signers(setOf(release.lowercase()))))
        assertFalse(PluginSigning.trusted(Signers(setOf(stranger))))
        assertFalse("unreadable signature", PluginSigning.trusted(null))
        assertFalse("no signature", PluginSigning.trusted(Signers(emptySet())))
        assertTrue(PluginSigning.TRUSTED.all { it.matches(Regex("[0-9A-F]{64}")) })
    }

    @Test fun rotatedKeyIsTrustedThroughItsProvenHistory() {
        // The plugin moved from the release key to a new one: Android vouches for the lineage.
        assertTrue(PluginSigning.trusted(Signers(current = setOf(other), past = setOf(release))))
        // A history of strangers proves nothing.
        assertFalse(PluginSigning.trusted(Signers(current = setOf(other), past = setOf(stranger))))
    }

    @Test fun withMultipleSignersEveryOneMustBeKnown() {
        assertTrue(PluginSigning.trusted(Signers(setOf(release, play))))
        assertFalse("a known certificate next to a stranger's", PluginSigning.trusted(Signers(setOf(release, stranger))))
    }

    @Test fun aForkTrustsAPluginSignedExactlyAsItself() {
        val own = Signers(setOf(stranger))
        assertTrue(PluginSigning.trusted(Signers(setOf(stranger)), own))
        assertFalse(PluginSigning.trusted(Signers(setOf(other)), own))
        assertFalse("only a part of this app's signers", PluginSigning.trusted(Signers(setOf(stranger)), Signers(setOf(stranger, other))))
        assertTrue("the known certificates still count", PluginSigning.trusted(Signers(setOf(release)), own))
    }

    @Test fun anImpostorDeclaringTheActionIsIgnored() {
        val declared = listOf(Candidate("org.evil.plugin", "org.evil.provider"))
        assertNull(PluginSigning.choose(declared, legacyInstalled = false, preferred = bridge, trusted = pm("org.evil.plugin" to Signers(setOf(stranger)))))
    }

    @Test fun theSignedPluginIsChosenEvenBehindAnImpostor() {
        val declared = listOf(Candidate("org.evil.plugin", "org.evil.provider"), Candidate(bridge, "$bridge.provider"))
        val got = PluginSigning.choose(declared, false, bridge, pm("org.evil.plugin" to Signers(setOf(stranger)), bridge to Signers(setOf(release))))
        assertEquals(bridge, got?.packageName)
        assertEquals("$bridge.provider", got?.authority)
    }

    @Test fun amongSeveralSignedPluginsTheBridgeComesFirstThenTheSystemsOrder() {
        val a = Candidate("com.xlollx.plugin.a", "a.provider")
        val b = Candidate("com.xlollx.plugin.b", "b.provider")
        val official = Candidate(bridge, null)
        val signed = pm(a.packageName to Signers(setOf(release)), b.packageName to Signers(setOf(play)), bridge to Signers(setOf(release)))
        assertEquals(bridge, PluginSigning.choose(listOf(a, b, official), false, bridge, signed)?.packageName)
        assertEquals(a.packageName, PluginSigning.choose(listOf(a, b), false, bridge, signed)?.packageName)
    }

    @Test fun theLegacyPackageIsCheckedToo() {
        // Installed without the action (an old plugin): used only when signed as the Bridge is.
        val legacy = PluginSigning.choose(emptyList(), legacyInstalled = true, preferred = bridge, trusted = pm(bridge to Signers(setOf(release))))
        assertEquals(bridge, legacy?.packageName)
        assertNull("the legacy authority", legacy?.authority)
        assertNull("the package id taken by someone else", PluginSigning.choose(emptyList(), true, bridge, pm(bridge to Signers(setOf(stranger)))))
        assertNull("not installed", PluginSigning.choose(emptyList(), false, bridge, pm(bridge to Signers(setOf(release)))))
        // An impostor declaring the action does not hide a signed legacy plugin.
        val behind = PluginSigning.choose(listOf(Candidate("org.evil.plugin", "x")), true, bridge, pm("org.evil.plugin" to Signers(setOf(stranger)), bridge to Signers(setOf(release))))
        assertEquals(bridge, behind?.packageName)
    }
}
