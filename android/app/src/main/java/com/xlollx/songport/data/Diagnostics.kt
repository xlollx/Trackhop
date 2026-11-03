package com.xlollx.songport.data

import com.xlollx.songport.providers.*
import android.content.Context
import android.os.Build
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.providers.Providers
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro tecnico locale, senza server: le ultime righe di errori e passaggi importanti,
 * piu' un rapporto copiabile per chiedere aiuto. Niente token, niente password, niente
 * indirizzi dei server personali.
 */
object Diagnostics {
    private const val MAX_LINES = 2000
    private const val MAX_CHARS = 1200
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val lock = Any()
    /** The last line written and how many times it came again since, folded into one line. */
    private var last: String? = null
    private var repeats = 0

    private fun file(ctx: Context) = File(ctx.filesDir, "diagnostics.log")

    fun log(ctx: Context, tag: String, message: String) {
        val text = "[$tag] ${message.replace('\n', ' ').take(MAX_CHARS)}"
        synchronized(lock) {
            try {
                if (text == last) { repeats++; return }
                val f = file(ctx)
                val out = StringBuilder()
                if (repeats > 0) out.append("${fmt.format(Date())} (the line above came $repeats more times)\n")
                out.append("${fmt.format(Date())} $text\n")
                last = text; repeats = 0
                f.appendText(out.toString())
                // Trimmed when it grows past the limit, not at every line: a sync writes many.
                if (f.length() > MAX_LINES * 300L) f.writeText(f.readLines().takeLast(MAX_LINES * 3 / 4).joinToString("\n", postfix = "\n"))
            } catch (_: Exception) { }
        }
    }

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    /** Rapporto leggibile: versione, dispositivo, servizi collegati, ultime sync, ultime righe di log. */
    fun report(ctx: Context): String {
        val data = Store.get(ctx).data
        val sb = StringBuilder()
        sb.appendLine("${ctx.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Build: ${BuildConfig.FLAVOR} · Locale: ${Locale.getDefault()} · ${java.util.TimeZone.getDefault().id}")
        runCatching { android.webkit.WebView.getCurrentWebViewPackage() }.getOrNull()?.let { sb.appendLine("WebView: ${it.packageName} ${it.versionName}") }
        val plugin = if (BridgePlugin.builtIn) null else BridgePlugin.find(ctx)
        plugin?.let { p -> runCatching { ctx.packageManager.getPackageInfo(p.packageName, 0) }.getOrNull() }
            ?.let { sb.appendLine("Plugin: ${it.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it)})") }
        sb.appendLine()
        sb.appendLine("Connectors:")
        val connectors = Providers.connectors()
        if (connectors.isEmpty()) sb.appendLine("  (none)")
        connectors.filter { it.requiresAuth }.forEach { p ->
            sb.appendLine("  ${p.id}: ${if (p.isConnected(ctx)) "connected" else "no"}" +
                (if (p.usesOwnCredentials(ctx)) ", own credentials" else "") +
                (if (!p.isConfigured(ctx)) ", not configured" else ""))
        }
        // Credenziali rimaste da connettori tolti: utili per capire una sincronizzazione che si
        // riferisce a un servizio non piu' elencato.
        val leftovers = Providers.all().filter { p -> p.requiresAuth && connectors.none { it.id == p.id } && runCatching { p.isConnected(ctx) }.getOrDefault(false) }
        if (leftovers.isNotEmpty()) sb.appendLine("  leftover sessions: ${leftovers.joinToString { it.id }}")
        // Cosa sta facendo il plugin, se c'e': conteggi e ritmi delle sue chiamate, mai contenuti.
        if (com.xlollx.songport.providers.BridgePlugin.installed(ctx)) {
            val stats = runCatching { com.xlollx.songport.providers.BridgePlugin.call(ctx, "stats", null, null)?.getString("stats") }.getOrNull()
            sb.appendLine("  plugin: ${stats ?: "no stats"}")
        }
        sb.appendLine()
        sb.appendLine("Syncs: ${data.jobs.size} · match cache: ${data.matchCache.size} · miss cache: ${data.missCache.size}")
        data.jobs.forEach { j ->
            sb.appendLine("  ${j.name}: ${j.source.provider} -> ${j.target.provider}, ${j.schedule}, mirror=${j.mirrorRemovals}, on=${j.enabled}")
        }
        sb.appendLine()
        sb.appendLine("Last runs:")
        data.reports.take(15).forEach { r ->
            sb.appendLine("  ${fmt.format(Date(r.startedEpoch))} ${r.jobName}: +${r.added} -${r.removed} nf=${r.unmatched.size}" +
                (r.error?.let { " ERROR: ${it.take(700)}" } ?: ""))
        }
        sb.appendLine()
        sb.appendLine("Log:")
        val f = file(ctx)
        if (f.exists()) f.readLines().takeLast(400).forEach { sb.appendLine("  $it") }
        // On the Play build some connectors run in the plugin: its own log, where it keeps one.
        if (plugin != null) {
            val pluginLog = runCatching { com.xlollx.songport.providers.BridgePlugin.call(ctx, "log", null, android.os.Bundle().apply { putInt("lines", 300) })?.getString("log") }.getOrNull()
            if (!pluginLog.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("Plugin log:")
                pluginLog.lines().forEach { sb.appendLine("  $it") }
            }
        }
        return sb.toString()
    }

    /**
     * [report] cut so that, encoded in a link, it takes at most [max] characters: everything before
     * the log (cut too if it alone is longer), then the newest log lines that still fit, with a line
     * saying how many older ones were left out. GitHub refuses a link of more than about 8 KB, and
     * encoding makes a text two or three times longer (spaces, line ends, letters beyond ASCII).
     */
    fun forLink(report: String, max: Int): String {
        if (encodedLength(report) <= max) return report
        val cut = report.indexOf("\nLog:\n")
        val headFull = if (cut >= 0) report.substring(0, cut + 6) else report
        val head = fit(headFull, max / 2)
        if (cut < 0) return head
        var room = max - encodedLength(head) - 120
        val lines = report.substring(cut + 6).lines()
        val kept = ArrayDeque<String>()
        for (l in lines.asReversed()) {
            val n = encodedLength(l) + 3
            if (n > room) break
            kept.addFirst(l); room -= n
        }
        val left = lines.size - kept.size
        return head + (if (left > 0) "  ($left older lines left out)\n" else "") + kept.joinToString("\n")
    }

    /** The longest start of [text] whose encoded length is at most [max]. */
    private fun fit(text: String, max: Int): String {
        var n = 0
        for ((i, c) in text.withIndex()) {
            n += encodedLength(c.toString())
            if (n > max) return text.substring(0, i)
        }
        return text
    }

    /** How long [text] gets once percent-encoded in a link (as Uri.encode does it). */
    fun encodedLength(text: String): Int {
        var n = 0
        for (c in text) n += when {
            c.isLetterOrDigit() && c.code < 128 -> 1
            c in "-_.!~*'()" -> 1
            c.isSurrogate() -> 6
            else -> 3 * c.toString().toByteArray(Charsets.UTF_8).size
        }
        return n
    }
}
