package com.xlollx.songport.server

import com.xlollx.songport.data.Redact
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.Timing
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The technical report for asking for help, from the API, the command line and the MCP tools: the
 * app's Log › Export log on a server. Version, system, the services and whether each is connected,
 * the syncs, the last runs and the log since a time, without secrets (Redact: tokens, cookies,
 * e-mails, the user's servers and the user name in folder paths) and, unless asked, without the
 * names of syncs, playlists, tracks and artists.
 */
object Diagnostics {
    const val DEFAULT_SINCE = "24h"
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** "24h", "90m", "7d", "2w" (a number alone is hours) in milliseconds; null when it is none of those. */
    fun duration(text: String?): Long? {
        val m = Regex("""(\d{1,5})\s*([mhdw]?)""").matchEntire(text?.trim()?.lowercase() ?: DEFAULT_SINCE) ?: return null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2]) { "m" -> 60_000L; "d" -> 86_400_000L; "w" -> 604_800_000L; else -> 3_600_000L }
    }

    /**
     * The names a report leaves out unless asked: each sync's (as "[sync N]", N its place in the list,
     * so lines about the same sync still read as one), its playlists', and the tracks, artists and
     * albums of the last runs. Whole words only, and never a service's own name.
     */
    class Names(env: ServerEnv) {
        private val map = LinkedHashMap<String, String>()
        private val regex: Regex?

        init {
            val d = env.store.data
            val services = Providers.all().flatMap { listOf(it.id, it.displayName, it.label(env)) }.map { it.lowercase() }.toSet()
            fun add(name: String?, placeholder: String) {
                val n = name?.trim().orEmpty()
                if (n.length >= 3 && n.lowercase() !in services && n.lowercase() !in map) map[n.lowercase()] = placeholder
            }
            d.jobs.forEachIndexed { i, j -> add(j.name, "[sync ${i + 1}]") }
            d.jobs.forEach { j -> add(j.source.playlistName, "[playlist]"); add(j.target.playlistName, "[playlist]") }
            d.reports.take(50).forEach { r ->
                add(r.jobName, "[sync]")
                (r.unmatchedTracks + r.addedTracks + r.removedTracks + r.reviewTracks.flatMap { listOf(it.source, it.chosen) }).forEach { t ->
                    add(t.title, "[track]"); t.artists.forEach { add(it, "[artist]") }; add(t.artistLine, "[artist]"); add(t.album, "[album]")
                }
            }
            regex = map.keys.sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
                ?.joinToString("|", """(?<![\p{L}\p{N}])(?:""", """)(?![\p{L}\p{N}])""") { Regex.escape(it) }
                ?.let { Regex(it, setOf(RegexOption.IGNORE_CASE)) }
        }

        fun hide(text: String): String = regex?.replace(text) { m -> map[m.value.lowercase()] ?: "[name]" } ?: text
    }

    /** The addresses of the user's own servers (Navidrome, Jellyfin, Plex…), hidden wherever they appear. */
    private fun servers(env: ServerEnv): List<String> =
        Providers.all().mapNotNull { p -> runCatching { env.tokens.get(p.id)?.extra?.get("url") }.getOrNull() }

    /** Cleans [text] as a report is: names (unless [names]), then secrets. */
    fun clean(env: ServerEnv, text: String, names: Boolean, hider: Names? = null): String =
        Redact.text(if (names) text else (hider ?: Names(env)).hide(text), servers(env))

    fun report(env: ServerEnv, product: String, version: String, sinceMs: Long, names: Boolean, now: Long = System.currentTimeMillis()): String {
        val d = env.store.data
        val sb = StringBuilder()
        sb.appendLine("$product $version")
        sb.appendLine("${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")}) · Java ${System.getProperty("java.version")} · ${Locale.getDefault()} · ${ZoneId.systemDefault().id}")
        sb.appendLine("Since: ${time.format(Instant.ofEpochMilli(sinceMs))} (${(now - sinceMs) / 3_600_000} h)")
        sb.appendLine()
        sb.appendLine("Connectors:")
        val connectors = Providers.connectors().filter { it.requiresAuth }
        if (connectors.isEmpty()) sb.appendLine("  (none)")
        connectors.forEach { p -> sb.appendLine("  ${p.id}: ${if (runCatching { p.isConnected(env) }.getOrDefault(false)) "connected" else "no"}") }
        sb.appendLine()
        sb.appendLine("Syncs: ${d.jobs.size}")
        d.jobs.forEach { j ->
            sb.appendLine("  ${j.name}: ${j.source.provider} -> ${j.target.provider}, ${Timing.describe(j)}, mirror=${j.mirrorRemovals}, on=${j.enabled}")
        }
        sb.appendLine()
        sb.appendLine("Last runs:")
        d.reports.filter { it.startedEpoch >= sinceMs }.take(15).forEach { r ->
            sb.appendLine("  ${time.format(Instant.ofEpochMilli(r.startedEpoch))} ${r.jobName}: +${r.added} -${r.removed} nf=${r.unmatched.size}" +
                (r.error?.let { " ERROR: ${it.take(700)}" } ?: ""))
        }
        sb.appendLine()
        sb.appendLine("Log:")
        Log.since(sinceMs).forEach { sb.appendLine("  $it") }
        return clean(env, sb.toString(), names)
    }

    /**
     * The error a report is about: the newest failed run's since [sinceMs], else the newest log line
     * that tells of an error. Cleaned as the report is; null when nothing failed.
     */
    fun lastError(env: ServerEnv, sinceMs: Long, names: Boolean): String? {
        val run = env.store.data.reports.firstOrNull { it.startedEpoch >= sinceMs && it.error != null }?.error
        val line = run ?: Log.since(sinceMs).lastOrNull { l -> ERROR_WORDS.containsMatchIn(l) }?.drop(20)
        return line?.let { clean(env, it.take(700), names) }
    }

    private val ERROR_WORDS = Regex("""(?i)\b(error|exception|failed|refused)\b""")

    /**
     * [message] reduced to what stays the same each time the error comes: names, links, ids, numbers,
     * times and quoted words out, spaces and case folded.
     */
    fun normalise(message: String): String = message.lowercase(Locale.ROOT)
        .replace(Regex("""\[(sync|playlist|track|artist|album|name)( \d+)?]"""), "~")
        .replace(Regex("""https?://\S+"""), "~")
        .replace(Regex(""""[^"]*"|'[^']*'|“[^”]*”"""), "~")
        .replace(Regex("""\b[0-9a-f]{8,}\b|\b[a-z0-9_-]*\d[a-z0-9_-]{5,}\b"""), "~")
        .replace(Regex("""\d+"""), "#")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** The error's signature: the first 12 hex digits of the SHA-256 of [normalise]. */
    fun signature(message: String): String =
        MessageDigest.getInstance("SHA-256").digest(normalise(message).toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
