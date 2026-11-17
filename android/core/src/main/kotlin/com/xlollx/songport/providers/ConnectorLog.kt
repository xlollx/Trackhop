package com.xlollx.songport.providers

import com.xlollx.songport.platform.Env

/**
 * The connectors' lines in the diagnostic log (the technical report on the phone, `docker logs` on
 * the server): how much a read of a list gave, never what it held. No token, password, e-mail,
 * cookie or key is ever written; an account shows as its first letters only.
 */
object ConnectorLog {
    /** An account as the log names it: the first four letters of its name, never the whole name or an e-mail's domain. */
    fun account(name: String?): String {
        val n = name?.trim()?.substringBefore('@').orEmpty()
        return if (n.isEmpty()) "" else n.take(4) + if (n.length > 4) "…" else ""
    }

    /**
     * The line for a read of [what] ("playlists", "tracks"): how many entries were listed, the
     * [total] the service declared (null: it declares none), how many came back [unreadable] (no id,
     * or an empty entry) and were left out.
     */
    fun line(what: String, listed: Int, total: Int? = null, unreadable: Int = 0, account: String? = null): String = buildString {
        append("$what: $listed listed")
        if (total != null) append(", total $total")
        if (unreadable > 0 || total != null) append(", $unreadable unreadable")
        account(account).takeIf { it.isNotEmpty() }?.let { append(" (account $it)") }
    }

    /** Writes [line] under the connector's own tag. Never throws: a log line is not worth a failed read. */
    fun listed(ctx: Env, p: MusicProvider, what: String, listed: Int, total: Int? = null, unreadable: Int = 0) {
        runCatching { ctx.log(p.id, line(what, listed, total, unreadable, p.accountName(ctx))) }
    }

    /** A list as it came back with its count logged: [entries] is how many the answer held, read or not. */
    fun <T> List<T>.logged(ctx: Env, p: MusicProvider, what: String, entries: Int = size, total: Int? = null): List<T> =
        also { listed(ctx, p, what, size, total, (entries - size).coerceAtLeast(0)) }
}
