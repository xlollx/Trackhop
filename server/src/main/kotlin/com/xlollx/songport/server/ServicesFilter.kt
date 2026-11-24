package com.xlollx.songport.server

import com.xlollx.songport.platform.Env
import com.xlollx.songport.providers.MusicProvider

/**
 * The Services page's search and chips, as the app's Accounts screen (AccountsFilter): one chip at a
 * time. Official: the connectors on a service's official API; Web: the unofficial ones (a web
 * player's cookies, a public link); Connected: the signed-in ones. The search looks in the service's
 * name and the account it is connected as, case ignored.
 */
internal object ServicesFilter {
    enum class Chip(val key: String, val title: String) { ALL("all", "All"), OFFICIAL("official", "Official"), WEB("web", "Web"), CONNECTED("connected", "Connected") }

    fun chip(key: String?): Chip? = if (key.isNullOrBlank()) Chip.ALL else Chip.entries.firstOrNull { it.key == key.trim().lowercase() }

    /** An unofficial connector: a web player's cookies or a public link. */
    fun web(p: MusicProvider): Boolean = p is CookieConnector || p.linkOnly

    fun connected(env: Env, p: MusicProvider): Boolean = !p.requiresAuth || p.isConnected(env)

    fun matches(env: Env, p: MusicProvider, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOfNotNull(p.label(env), p.displayName, runCatching { p.accountName(env) }.getOrNull()).any { it.contains(q, ignoreCase = true) }
    }

    fun shown(env: Env, all: List<MusicProvider>, query: String, chip: Chip): List<MusicProvider> = all.filter { p ->
        val kind = when (chip) {
            Chip.ALL -> true
            Chip.OFFICIAL -> !web(p)
            Chip.WEB -> web(p)
            Chip.CONNECTED -> connected(env, p)
        }
        kind && matches(env, p, query)
    }
}

/** The Services page's search field and chips; nothing with fewer than four services and no filter on. */
internal fun Web.servicesFinder(find: String, chip: ServicesFilter.Chip, count: Int): String {
    if (count < 4 && find.isEmpty() && chip == ServicesFilter.Chip.ALL) return ""
    fun link(c: ServicesFilter.Chip) = "/connectors" + listOfNotNull(
        find.takeIf { it.isNotBlank() }?.let { "q=${q(it)}" },
        c.key.takeIf { c != ServicesFilter.Chip.ALL }?.let { "kind=$it" },
    ).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
    val chips = ServicesFilter.Chip.entries.joinToString("") { c ->
        """<a class="chip${if (c == chip) " on" else ""}" href="${Html.h(link(c))}"${if (c == chip) " aria-current=\"true\"" else ""}>${c.title}</a>"""
    }
    val kept = if (chip != ServicesFilter.Chip.ALL) """<input type="hidden" name="kind" value="${chip.key}">""" else ""
    return """<form method="get" action="/connectors" class="finder" role="search">$kept<input type="search" name="q" value="${Html.h(find)}" placeholder="Find a service or an account" aria-label="Find a service"> <button class="secondary">Find</button>${if (find.isNotEmpty() || chip != ServicesFilter.Chip.ALL) """ <a href="/connectors">Clear</a>""" else ""}</form><p class="chips">$chips</p>"""
}
