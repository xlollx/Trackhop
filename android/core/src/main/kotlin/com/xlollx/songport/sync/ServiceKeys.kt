package com.xlollx.songport.sync

import com.xlollx.songport.data.Tokens
import com.xlollx.songport.platform.Env

/**
 * The user's own keys of Discover's sources (Ticketmaster's Consumer Key, the setlist.fm key): in
 * the token vault with the sign-ins and the Discogs token (encrypted with the Keystore on the
 * phone), never in the settings, so never in an exported set-up file or a backup of the settings.
 * A key an earlier 1.18 build kept in the settings moves into the vault the first time it is read.
 */
internal object ServiceKeys {
    private fun vaultId(id: String) = "__${id}__"

    fun get(env: Env, id: String): String? {
        env.tokens.get(vaultId(id))?.accessToken?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val old = env.store.data.settings.clientIds[id]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        save(env, id, old)
        return old
    }

    /** Saves [key] (blank removes it). */
    fun save(env: Env, id: String, key: String) {
        val k = key.trim()
        if (k.isEmpty()) env.tokens.clear(vaultId(id)) else env.tokens.set(vaultId(id), Tokens(accessToken = k))
        if (id in env.store.data.settings.clientIds) env.store.updateSettings { s -> s.copy(clientIds = s.clientIds - id) }
    }
}
