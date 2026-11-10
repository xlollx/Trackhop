package com.xlollx.songport.server

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The diagnostic log: each line goes to standard output (what `docker logs` shows), to the last
 * lines kept for the web page, and to `songport.log` in the data folder, rotated at 2 MB.
 */
object Log {
    private const val KEEP = 1000
    private const val MAX_FILE = 2L * 1024 * 1024
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val lines = ArrayDeque<String>()
    @Volatile private var file: File? = null
    /** Several people on one server: whose engine this is, at the start of its lines in the container's output. */
    @Volatile var who: String? = null

    fun init(dataDir: File) { file = File(dataDir, "songport.log") }

    fun add(tag: String, message: String) {
        val line = "${LocalDateTime.now().format(time)} [$tag] $message"
        println(who?.let { "[$it] $line" } ?: line)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > KEEP) lines.removeFirst()
            file?.let { f ->
                runCatching {
                    if (f.length() > MAX_FILE) f.renameTo(File(f.parentFile, f.name + ".1"))
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** The latest lines, oldest first. */
    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * The lines written at or after [epochMs], oldest first, at most [max]: from `songport.log` and the
     * one before it when there is a file, else from those kept in memory. A line without its own time
     * goes with the one before.
     */
    fun since(epochMs: Long, max: Int = 2000): List<String> {
        val all = synchronized(lines) {
            file?.let { f -> listOf(File(f.parentFile, f.name + ".1"), f).filter { it.isFile }.flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) } }
                ?.takeIf { it.isNotEmpty() } ?: lines.toList()
        }
        var keep = false
        return all.filter { l ->
            if (l.isBlank()) return@filter false
            val at = runCatching { LocalDateTime.parse(l.take(19), time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
            if (at != null) keep = at >= epochMs
            keep
        }.takeLast(max)
    }
}
