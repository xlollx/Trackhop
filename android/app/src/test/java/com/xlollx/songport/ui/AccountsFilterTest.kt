package com.xlollx.songport.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Accounts screen's search and chips (AccountsFilter): what is shown, and which chips a build offers. */
class AccountsFilterTest {
    private val accounts = listOf(
        AccountEntry("spotify", "Spotify", null, "anna", web = false, connected = true),
        AccountEntry("spotify_web", "Spotify (web)", "Family", null, web = true, connected = false),
        AccountEntry("deezer@2", "Deezer", "Work", "marco.r", web = false, connected = false),
        AccountEntry("amazon", "Amazon Music", null, "Anna B", web = true, connected = true),
        AccountEntry("csv", "Files", null, null, web = false, connected = false),
    )

    private fun ids(query: String = "", chip: AccountChip = AccountChip.ALL, store: Boolean = false, bridge: Boolean = false) =
        AccountsFilter.shown(accounts, query, chip, store, bridge).map { it.id }

    @Test fun everythingWithoutAFilter() {
        assertEquals(accounts.map { it.id }, ids())
        assertEquals(accounts.map { it.id }, ids("   "))
    }

    @Test fun searchLooksAtServiceOwnNameAndAccountIgnoringCase() {
        assertEquals(listOf("spotify", "spotify_web"), ids("SPOT"))
        assertEquals(listOf("spotify_web"), ids("family"))
        assertEquals(listOf("deezer@2"), ids("MARCO"))
        // "anna" is the account Spotify is connected as, and Amazon's "Anna B".
        assertEquals(listOf("spotify", "amazon"), ids("anna"))
        assertEquals(emptyList<String>(), ids("tidal"))
    }

    @Test fun chipsChooseOneKind() {
        assertEquals(listOf("spotify", "deezer@2", "csv"), ids(chip = AccountChip.OFFICIAL))
        assertEquals(listOf("spotify_web", "amazon"), ids(chip = AccountChip.WEB))
        assertEquals(listOf("spotify", "amazon"), ids(chip = AccountChip.CONNECTED))
        // With a search too: both must hold.
        assertEquals(listOf("spotify"), ids("spotify", AccountChip.CONNECTED))
        assertEquals(listOf("spotify_web"), ids("spotify", AccountChip.WEB))
    }

    @Test fun chipsOfferedPerBuild() {
        val all = listOf(AccountChip.ALL, AccountChip.OFFICIAL, AccountChip.WEB, AccountChip.CONNECTED)
        // GitHub and F-Droid: all four, Bridge or not.
        assertEquals(all, AccountsFilter.chips(store = false, bridgeInstalled = false))
        assertEquals(all, AccountsFilter.chips(store = false, bridgeInstalled = true))
        // Google Play: Official and Web only with Trackhop Bridge installed; never shown greyed out.
        assertEquals(listOf(AccountChip.ALL, AccountChip.CONNECTED), AccountsFilter.chips(store = true, bridgeInstalled = false))
        assertEquals(all, AccountsFilter.chips(store = true, bridgeInstalled = true))
    }

    @Test fun aChipNotOfferedCountsAsAll() {
        // Web chosen, then the Bridge uninstalled on a Play build: everything shows again.
        assertEquals(AccountChip.ALL, AccountsFilter.effective(AccountChip.WEB, store = true, bridgeInstalled = false))
        assertEquals(accounts.map { it.id }, ids(chip = AccountChip.WEB, store = true, bridge = false))
        assertEquals(listOf("spotify_web", "amazon"), ids(chip = AccountChip.WEB, store = true, bridge = true))
        assertEquals(listOf("spotify", "amazon"), ids(chip = AccountChip.CONNECTED, store = true, bridge = false))
    }
}
