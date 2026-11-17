package com.xlollx.songport.server

import java.nio.charset.StandardCharsets

/** The small part of multipart/form-data the pages use: text fields and uploaded files. */
object Multipart {
    class File(val name: String, val bytes: ByteArray)
    class Parts(val fields: Map<String, String>, val files: Map<String, File>)

    fun parse(contentType: String, body: ByteArray): Parts {
        val boundary = contentType.substringAfter("boundary=", "").trim().trim('"').ifEmpty { return Parts(emptyMap(), emptyMap()) }
        // ISO-8859-1 maps every byte to one char and back: splitting the text splits the bytes.
        val text = String(body, StandardCharsets.ISO_8859_1)
        val fields = HashMap<String, String>()
        val files = HashMap<String, File>()
        for (raw in text.split("--$boundary")) {
            val part = raw.removePrefix("\r\n")
            if (part.isBlank() || part.startsWith("--")) continue
            val headerEnd = part.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: continue
            val headers = part.substring(0, headerEnd)
            val content = part.substring(headerEnd + 4).removeSuffix("\r\n")
            val disposition = headers.lineSequence().firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) } ?: continue
            val name = attr(disposition, "name") ?: continue
            val fileName = attr(disposition, "filename")
            val bytes = content.toByteArray(StandardCharsets.ISO_8859_1)
            if (fileName != null) {
                if (bytes.isNotEmpty()) files[name] = File(String(fileName.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8), bytes)
            } else fields[name] = String(bytes, StandardCharsets.UTF_8)
        }
        return Parts(fields, files)
    }

    private fun attr(header: String, key: String): String? =
        Regex("""(?:^|;)\s*$key="([^"]*)"""").find(header)?.groupValues?.get(1)
}

/**
 * A multipart form read straight from the request onto disk (#67): a file part goes to a file in
 * [dir] as it arrives, so its size costs disk, not memory. Fields stay small ([MAX_FIELD]).
 */
object MultipartToDisk {
    class DiskFile(val name: String, val file: java.io.File)
    class Parts(val fields: Map<String, String>, val files: Map<String, DiskFile>)
    class TooLarge : Exception("too large")
    class Incomplete : Exception("the upload was cut short")

    const val MAX_FIELD = 1_000_000

    fun read(contentType: String, input: java.io.InputStream, dir: java.io.File, maxBytes: Long): Parts {
        val boundary = contentType.substringAfter("boundary=", "").substringBefore(';').trim().trim('"')
        require(boundary.isNotEmpty()) { "no boundary" }
        val inp = java.io.BufferedInputStream(input, 64 * 1024)
        val fields = HashMap<String, String>()
        val files = HashMap<String, DiskFile>()
        val first = "--$boundary"
        // The preamble, up to the first boundary line.
        while (true) {
            val line = readLine(inp) ?: return Parts(fields, files)
            if (line == first) break
        }
        val delimiter = "\r\n--$boundary".toByteArray(StandardCharsets.ISO_8859_1)
        val fail = failure(delimiter)
        var budget = maxBytes
        try {
            while (true) {
                val headers = ArrayList<String>()
                while (true) {
                    val line = readLine(inp) ?: return Parts(fields, files)
                    if (line.isEmpty()) break
                    if (headers.size < 20) headers += line
                }
                val disposition = headers.firstOrNull { it.startsWith("Content-Disposition", ignoreCase = true) }.orEmpty()
                val name = attr(disposition, "name")
                val fileName = attr(disposition, "filename")
                val found: Boolean
                if (name != null && fileName != null) {
                    dir.mkdirs()
                    val f = java.io.File.createTempFile("upload-", ".part", dir)
                    val counted = Limited(java.io.BufferedOutputStream(java.io.FileOutputStream(f), 64 * 1024), budget)
                    found = try { counted.use { copyUntil(inp, delimiter, fail, it) } } catch (e: Exception) { f.delete(); throw e }
                    budget -= counted.written
                    // The request ended inside the file: the upload was cut short, nothing of it is used.
                    if (!found) { f.delete(); throw Incomplete() }
                    if (f.length() > 0) files[name] = DiskFile(String(fileName.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8), f) else f.delete()
                } else {
                    val buf = Limited(java.io.ByteArrayOutputStream(), MAX_FIELD.toLong())
                    found = copyUntil(inp, delimiter, fail, buf)
                    if (name != null) fields[name] = String((buf.out as java.io.ByteArrayOutputStream).toByteArray(), StandardCharsets.UTF_8)
                }
                if (!found) return Parts(fields, files)
                // After a boundary: "--" closes the form, a line break opens the next part.
                val a = inp.read(); val b = inp.read()
                if (a == '-'.code && b == '-'.code) return Parts(fields, files)
            }
        } catch (e: Exception) {
            files.values.forEach { it.file.delete() }
            throw e
        }
    }

    private class Limited(val out: java.io.OutputStream, private val max: Long) : java.io.OutputStream() {
        var written = 0L
        override fun write(b: Int) { if (++written > max) throw TooLarge(); out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { written += len; if (written > max) throw TooLarge(); out.write(b, off, len) }
        override fun close() = out.close()
    }

    /** Copies [inp] to [out] up to [d] (not written); false when the stream ends first. */
    private fun copyUntil(inp: java.io.InputStream, d: ByteArray, fail: IntArray, out: java.io.OutputStream): Boolean {
        var k = 0
        while (true) {
            val c = inp.read()
            if (c < 0) { out.write(d, 0, k); return false }
            val b = c.toByte()
            while (k > 0 && b != d[k]) { val nk = fail[k - 1]; out.write(d, 0, k - nk); k = nk }
            if (b == d[k]) { k++; if (k == d.size) return true } else out.write(c)
        }
    }

    private fun failure(d: ByteArray): IntArray {
        val f = IntArray(d.size); var k = 0
        for (i in 1 until d.size) {
            while (k > 0 && d[i] != d[k]) k = f[k - 1]
            if (d[i] == d[k]) k++
            f[i] = k
        }
        return f
    }

    /** A header line (CRLF dropped), at most 8 KB; null at the end of the stream. */
    private fun readLine(inp: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            if (sb.length < 8192) sb.append(c.toChar())
        }
    }

    private fun attr(header: String, key: String): String? =
        Regex("""(?:^|;)\s*$key="([^"]*)"""").find(header)?.groupValues?.get(1)
}
