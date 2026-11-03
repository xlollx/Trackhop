package com.xlollx.songport.ytmbridge

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A hook for the host app: an interceptor every client of this module adds (the connections log). */
object BridgeNet {
    @Volatile var interceptor: Interceptor? = null
    /** The app's diagnostics log (tag, message); nothing secret goes there, only what happened. */
    @Volatile var logSink: ((String, String) -> Unit)? = null

    /**
     * Where the lines go when no host log is set: the Bridge app's own file, read by Trackhop
     * through the `log` call so a diagnostics report made on the Play build shows what the
     * connectors running in the Bridge did.
     */
    @Volatile private var own: File? = null
    private const val MAX_LINES = 600
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    fun attach(ctx: Context) { if (own == null) own = File(ctx.applicationContext.filesDir, "bridge.log") }

    fun log(tag: String, message: String) {
        val sink = logSink
        if (sink != null) { runCatching { sink(tag, message) }; return }
        val f = own ?: return
        val line = "${fmt.format(Date())} [$tag] ${message.replace('\n', ' ').take(1200)}\n"
        synchronized(lock) {
            runCatching {
                f.appendText(line)
                // Trimmed now and then, not at every line: a file read per line would slow a sync.
                if (f.length() > MAX_LINES * 400L) f.writeText(f.readLines().takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** The last [n] lines of the Bridge's own log, oldest first. */
    fun recent(n: Int): String = synchronized(lock) { runCatching { own?.takeIf { it.exists() }?.readLines()?.takeLast(n)?.joinToString("\n") }.getOrNull().orEmpty() }

    fun OkHttpClient.Builder.withHook(): OkHttpClient.Builder = apply { interceptor?.let { addInterceptor(it) } }
}
