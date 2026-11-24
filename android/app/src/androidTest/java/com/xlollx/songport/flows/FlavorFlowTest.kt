package com.xlollx.songport.flows

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.MainActivity
import com.xlollx.songport.R
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.installConnectors
import com.xlollx.songport.platform.env
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What differs between the two builds, on the real connector list of this build (nothing signed
 * in, nothing fetched): the GitHub/F-Droid build ("full") carries the web connectors (Deezer's web
 * route next to its official one, SoundCloud's account) and SoundCloud links; the Google Play build
 * ("play") hides plugin-only services unless a plugin is installed (never naming Amazon Music or
 * SoundCloud, Deezer with its official route only),
 * explains plugins only in Settings (no name, no link anywhere), and says it shows a banner.
 */
@RunWith(AndroidJUnit4::class)
class FlavorFlowTest : FlowTest() {
    override val connectedAtStart = emptyList<String>()
    private val full get() = !BuildConfig.STORE_BUILD

    private fun openServicePicker() {
        installConnectors()
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.connectors_empty_title))
        click(s(R.string.connector_add))
        waitText(s(R.string.connector_pick_title))
    }

    @Test
    fun servicePickerMatchesTheBuild() {
        openServicePicker()
        // Everywhere: the services with an official API, each under its category's chip.
        waitText("Spotify")
        click(s(R.string.services_group_streaming))
        listOf("Spotify", "Deezer", "TIDAL").forEach { waitText(it) }
        click(s(R.string.services_group_servers))
        listOf("Jellyfin", "Plex").forEach { waitText(it) }
        click(s(R.string.chooser_all))
        // Never a greyed-out list of what this build cannot reach: only what it offers.
        assertFalse(exists(s(R.string.services_group_unavailable)))
        click(s(R.string.services_group_streaming))
        waitText("Deezer")
        if (full) {
            assertTrue("Amazon Music (web connector) is offered", exists("Amazon Music"))
            // Spotify can be reached two ways here: the family page lists both routes.
            click("Spotify")
            waitText(s(R.string.connector_pick_hint))
            assertTrue(exists(s(R.string.route_easy)))
            assertTrue(exists(s(R.string.route_official)))
        } else {
            // The services with no official API are not named at all.
            assertFalse(exists("Amazon Music"))
            assertFalse(exists("SoundCloud"))
            // Only the official Spotify route: straight to the name.
            click("Spotify")
            waitText(s(R.string.connector_name_label))
        }
    }

    @Test
    fun deezerAndSoundCloudMatchTheBuild() {
        openServicePicker()
        click(s(R.string.services_group_streaming))
        waitText("Deezer")
        if (full) {
            assertTrue("SoundCloud's account connector is offered", exists("SoundCloud"))
            // Deezer can be reached two ways here: its web player (easy) and its official API.
            click("Deezer")
            waitText(s(R.string.connector_pick_hint))
            assertTrue(exists(s(R.string.route_easy)))
            assertTrue(exists(s(R.string.route_official)))
            // The web route is alpha, the official one stays beta.
            assertTrue("the web route says alpha", exists(s(R.string.alpha)))
            assertTrue("the official route says beta", exists(s(R.string.beta)))
        } else {
            // No plugin on the test device: SoundCloud is not named, and Deezer has one route.
            assertFalse(exists("SoundCloud"))
            assertFalse(exists(s(R.string.services_group_unavailable)))
            click("Deezer")
            waitText(s(R.string.connector_name_label))
        }
    }

    @Test
    fun deezerWebIsAlphaAndSoundCloudAccountIsBeta() {
        installConnectors()
        // The flags themselves are ProviderFlagsTest's (a unit test); here, how each one's card in Accounts shows them.
        store.updateSettings { it.copy(connectors = listOf("deezer_bridge")) }
        store.flush()
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.alpha_note))
        assertTrue("Deezer (web)'s card says alpha", exists(" · " + s(R.string.alpha), substring = true))
        assertFalse(exists(s(R.string.beta_note)))
        store.updateSettings { it.copy(connectors = listOf("soundcloud")) }
        store.flush()
        launch(tabIntent(MainActivity.TAB_ACCOUNTS))
        waitText(s(R.string.beta_note))
        assertFalse("SoundCloud's card has no alpha note", exists(s(R.string.alpha_note)))
    }

    @Test
    fun pluginsSectionOnlyInThePlayBuild() {
        installConnectors()
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_free_note))
        // What a plugin is, as the section's description (1.2.8), and where plugins are published,
        // as its footer (1.2.6): each its own line.
        assertEquals("the neutral plugins section", !full, exists(s(R.string.plugins_desc)))
        assertEquals("where plugins are published", !full, exists(s(R.string.plugins_where)))
        // No plugin on the test device: the Play build says so, and names nothing.
        if (!full) waitText(s(R.string.plugins_none))
    }

    @Test
    fun theAppIsCalledTrackhopInEveryBuild() {
        assertEquals("Trackhop", s(R.string.app_name))
    }

    @Test
    fun soundCloudLinksOnlyInTheFullBuild() {
        installConnectors()
        // One SoundCloud service in both builds: the account connector, which also reads public links.
        assertNotNull(Providers.byId("soundcloud"))
        assertEquals(1, Providers.all().count { it.id == "soundcloud" })
        // A pasted link has somewhere to go only where SoundCloud is read without a plugin.
        val reader = com.xlollx.songport.providers.linkProvider(app, "soundcloud")
        if (full) assertNotNull(reader) else assertNull(reader)
        if (full) assertTrue(reader!!.canRead(app.env, "label/sets/night-drive"))
        assertEquals(full, com.xlollx.songport.providers.BuiltIn.available)
        assertEquals(full, com.xlollx.songport.providers.BridgePlugin.builtIn)
    }

    @Test
    fun aboutSaysTheAppIsFree() {
        installConnectors()
        launch(tabIntent(MainActivity.TAB_SETTINGS))
        waitText(s(R.string.settings_free_note))
    }
}
