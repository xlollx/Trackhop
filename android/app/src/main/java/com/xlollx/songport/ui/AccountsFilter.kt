package com.xlollx.songport.ui

/**
 * The chips over the Accounts list: one at a time. Official: the connectors that use a service's
 * official API (every connector that is not a web one); Web: the web sign-in connectors (built in,
 * or through Trackhop Bridge: [com.xlollx.songport.providers.MusicProvider.pluginBased]); Connected:
 * the signed-in ones.
 */
enum class AccountChip { ALL, OFFICIAL, WEB, CONNECTED }

/** What the search and the chips look at in one account of the Accounts list. */
data class AccountEntry(
    val id: String,
    /** The service's name ("Spotify (web)"). */
    val service: String,
    /** The name the user gave the connector (Rename), if any. */
    val customName: String?,
    /** The account it is signed in as ("Connected as …"), if known. */
    val connectedAs: String?,
    /** A web sign-in connector ([com.xlollx.songport.providers.MusicProvider.pluginBased]). */
    val web: Boolean,
    val connected: Boolean,
)

/** The Accounts screen's search and chips, without the screen: what is shown and which chips are offered. */
object AccountsFilter {
    /**
     * The chips offered. A store build ([store]: Google Play's rules) names the official and the web
     * connectors apart only where Trackhop Bridge is installed ([bridgeInstalled]); without it only
     * All and Connected, never a greyed-out chip.
     */
    fun chips(store: Boolean, bridgeInstalled: Boolean): List<AccountChip> =
        if (!store || bridgeInstalled) AccountChip.entries.toList() else listOf(AccountChip.ALL, AccountChip.CONNECTED)

    /** The chip in effect: [chip] when it is offered, All otherwise (a Bridge uninstalled since it was chosen). */
    fun effective(chip: AccountChip, store: Boolean, bridgeInstalled: Boolean): AccountChip =
        chip.takeIf { it in chips(store, bridgeInstalled) } ?: AccountChip.ALL

    /** True when [query] (case ignored) is in the service's name, the account's own name or the name it is connected as. */
    fun matches(a: AccountEntry, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOfNotNull(a.service, a.customName, a.connectedAs).any { it.contains(q, ignoreCase = true) }
    }

    /** The accounts shown for [query] and [chip], in their order. */
    fun shown(accounts: List<AccountEntry>, query: String, chip: AccountChip, store: Boolean, bridgeInstalled: Boolean): List<AccountEntry> {
        val c = effective(chip, store, bridgeInstalled)
        return accounts.filter { a ->
            val kind = when (c) {
                AccountChip.ALL -> true
                AccountChip.OFFICIAL -> !a.web
                AccountChip.WEB -> a.web
                AccountChip.CONNECTED -> a.connected
            }
            kind && matches(a, query)
        }
    }
}
