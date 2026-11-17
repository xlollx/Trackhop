package com.xlollx.songport.sync

import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * A file read a piece at a time (#67): one small buffer, whatever the file's size. Every playlist
 * format is read through it, so the memory taken is the one of the tracks found, never of the file.
 * [progress] gets the characters read so far, about every MB; [cancelled] is asked as often and stops
 * the reading with a [java.util.concurrent.CancellationException].
 */
internal class CharSource(
    private val reader: Reader,
    private val progress: (Long) -> Unit = {},
    private val cancelled: () -> Boolean = { false },
) {
    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var total = 0L
    private var nextReport = REPORT_EVERY
    private var started = false
    private var ended = false
    private val line = StringBuilder()

    private fun fill(): Boolean {
        if (ended) return false
        var n = reader.read(buf)
        // A reader may give nothing without being at its end.
        while (n == 0) n = reader.read(buf)
        if (n < 0) { ended = true; len = 0; pos = 0; return false }
        len = n; pos = 0
        took(n)
        return true
    }

    private fun took(n: Int) {
        total += n
        // A BOM in front of the file is not part of it.
        if (!started) { started = true; if (buf[0] == '﻿') pos = 1 }
        if (total >= nextReport) {
            nextReport = total + REPORT_EVERY
            if (cancelled()) throw java.util.concurrent.CancellationException("reading stopped")
            progress(total)
        }
    }

    /** The next character, or -1 at the end. */
    fun next(): Int = if (pos < len || fill()) buf[pos++].code else -1

    /** The character [next] will give, without taking it. */
    fun peek(): Int = if (pos < len || fill()) buf[pos].code else -1

    /** The start of the file (up to the buffer's size), without taking it: what tells its format. Asked before reading. */
    fun head(): String {
        if (started) return String(buf, pos, len - pos)
        while (len < buf.size) {
            val n = reader.read(buf, len, buf.size - len)
            if (n < 0) break
            len += n
        }
        if (len > 0) took(len) else started = true
        return String(buf, pos, len - pos)
    }

    /** The next line without its line break (\n, \r\n or \r), or null at the end; cut at [MAX_TEXT]. */
    fun line(): String? {
        var c = next()
        if (c < 0) return null
        line.setLength(0)
        while (c >= 0 && c != '\n'.code && c != '\r'.code) {
            if (line.length < MAX_TEXT) line.append(c.toChar())
            c = next()
        }
        if (c == '\r'.code && peek() == '\n'.code) next()
        return line.toString()
    }

    companion object {
        /** No single value (a line, a cell, an element's text, a tag) is kept longer than this. */
        const val MAX_TEXT = 64 * 1024
        private const val REPORT_EVERY = 1L shl 20
    }
}

/**
 * XML read as tags and text, one character buffer at a time: enough for a plist, XSPF and OPML.
 * Text is kept only while [keep] is set, and no more than [CharSource.MAX_TEXT] of it, so no single
 * element can fill the memory; comments and declarations are skipped.
 */
internal class XmlTokens(private val src: CharSource) {
    interface Handler {
        /** An opening tag; [attrs] is what follows its name. A self-closing one gets no [close]. */
        fun open(name: String, attrs: String, selfClosing: Boolean)
        fun close(name: String)
    }

    /** The text since it was last emptied, while [keep] is set. */
    val text = StringBuilder()
    var keep = false

    private val tag = StringBuilder()
    private var root: String? = null
    private var rootClosed = false
    private var cut = false

    /**
     * True when the file ends where its first element does. One cut short (an upload or a copy that
     * stopped half way) ends inside a tag or before that closing tag.
     */
    val whole: Boolean get() = root != null && rootClosed && !cut

    fun run(handler: Handler) {
        while (true) {
            val c = src.next()
            if (c < 0) break
            if (c != '<'.code) {
                if (keep && text.length < CharSource.MAX_TEXT) text.append(c.toChar())
                continue
            }
            val t = src.next()
            if (t == '!'.code || t == '?'.code) { skipDeclaration(t); continue }
            if (!readTag(t)) { cut = true; break }
            val raw = tag.toString().trim()
            val closing = raw.startsWith("/")
            val selfClosing = raw.endsWith("/")
            val body = raw.removePrefix("/")
            val name = body.takeWhile { !it.isWhitespace() && it != '/' }
            if (name.isEmpty()) continue
            if (closing) {
                rootClosed = name.equals(root, ignoreCase = true)
                handler.close(name)
            } else {
                if (root == null) root = name
                rootClosed = selfClosing && name.equals(root, ignoreCase = true)
                handler.open(name, body.substring(name.length), selfClosing)
            }
        }
    }

    /** Reads a tag to its '>' (one inside a quoted attribute is not the end). False when the file ends first. */
    private fun readTag(first: Int): Boolean {
        tag.setLength(0)
        var c = first
        var quote = 0
        while (c >= 0 && (quote != 0 || c != '>'.code)) {
            if (quote != 0) { if (c == quote) quote = 0 } else if (c == '"'.code || c == '\''.code) quote = c
            if (tag.length < CharSource.MAX_TEXT) tag.append(c.toChar())
            c = src.next()
        }
        return c >= 0
    }

    private fun skipDeclaration(first: Int) {
        // <!-- … --> ends at "-->", <!DOCTYPE …> and <?xml …?> at the first '>'.
        var a = src.next()
        if (first == '!'.code && a == '-'.code) {
            var p1 = 0; var p2 = 0
            while (true) {
                val c = src.next(); if (c < 0) { cut = true; return }
                if (c == '>'.code && p1 == '-'.code && p2 == '-'.code) return
                p2 = p1; p1 = c
            }
        }
        while (a >= 0 && a != '>'.code) a = src.next()
        if (a < 0) cut = true
    }

    companion object {
        private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""")

        fun unescape(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }.getOrDefault(m.value)
            }
        }
    }
}

/**
 * A picked file's bytes as text, decoded as they are read: UTF-8 (a BOM is dropped), UTF-16 with a
 * BOM, and otherwise Windows-1252 from the first byte that is not UTF-8 on: what Windows players
 * write in a plain .m3u (where "Beyoncé" is not valid UTF-8). What [PlaylistFiles.decode] does for
 * a file held whole.
 */
internal class DecodingReader(input: InputStream) : Reader() {
    private val input = if (input.markSupported()) input else java.io.BufferedInputStream(input)
    private val bytes = ByteBuffer.allocate(64 * 1024).also { it.limit(0) }
    private var decoder: CharsetDecoder
    private var fallback = false
    private var ended = false
    /** The second half of a character that takes two, when the caller had room for one. */
    private var pending = -1

    init {
        this.input.mark(3)
        val b = ByteArray(3)
        var n = 0
        while (n < 3) { val r = this.input.read(b, n, 3 - n); if (r < 0) break; n += r }
        fun starts(vararg v: Int) = n >= v.size && v.indices.all { b[it] == v[it].toByte() }
        this.input.reset()
        val (skip, charset) = when {
            starts(0xEF, 0xBB, 0xBF) -> 3 to Charsets.UTF_8
            starts(0xFF, 0xFE) -> 2 to Charsets.UTF_16LE
            starts(0xFE, 0xFF) -> 2 to Charsets.UTF_16BE
            else -> 0 to null
        }
        repeat(skip) { this.input.read() }
        // With a BOM the file says what it is; without one, UTF-8 until a byte says otherwise.
        fallback = charset != null
        decoder = (charset ?: Charsets.UTF_8).newDecoder()
            .onMalformedInput(if (charset != null) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
            .onUnmappableCharacter(if (charset != null) CodingErrorAction.REPLACE else CodingErrorAction.REPORT)
    }

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pending >= 0) { cbuf[off] = pending.toChar(); pending = -1; return 1 }
        val out = CharBuffer.wrap(cbuf, off, len)
        while (true) {
            val r = decoder.decode(bytes, out, ended)
            if (r.isError && !fallback) {
                fallback = true
                decoder = charset("windows-1252").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                continue
            }
            if (out.position() > off) return out.position() - off
            if (r.isOverflow) {
                val two = CharBuffer.allocate(2)
                decoder.decode(bytes, two, ended)
                if (two.position() == 2) pending = two.get(1).code
                cbuf[off] = two.get(0)
                return 1
            }
            if (ended) return -1
            // The decoder wants more bytes: what it left (the start of a character) stays in front.
            bytes.compact()
            val n = input.read(bytes.array(), bytes.position(), bytes.remaining())
            if (n < 0) ended = true else bytes.position(bytes.position() + n)
            bytes.flip()
        }
    }

    override fun close() = input.close()
}
