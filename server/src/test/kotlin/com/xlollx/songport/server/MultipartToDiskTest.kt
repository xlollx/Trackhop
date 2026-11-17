package com.xlollx.songport.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A form read onto disk: text fields stay small all together, not each on its own. */
class MultipartToDiskTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun form(fields: List<Pair<String, String>>): ByteArray = buildString {
        for ((k, v) in fields) append("--b\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        append("--b--\r\n")
    }.toByteArray()

    private fun read(body: ByteArray) = MultipartToDisk.read("multipart/form-data; boundary=b", body.inputStream(), tmp.root, 10_000_000)

    @Test fun smallFieldsAreRead() {
        val parts = read(form(listOf("a" to "one", "b" to "two")))
        assertEquals(mapOf("a" to "one", "b" to "two"), parts.fields)
    }

    @Test fun manyFieldsEachUnderTheCapCannotAddUpPastIt() {
        val quarter = "x".repeat(MultipartToDisk.MAX_FIELD / 4)
        // Each field is well under the cap; four of them reach it, five go past it.
        val within = read(form((1..3).map { "f$it" to quarter }))
        assertEquals(3, within.fields.size)
        val past = runCatching { read(form((1..5).map { "f$it" to quarter })) }.exceptionOrNull()
        assertTrue(past.toString(), past is MultipartToDisk.TooLarge)
    }
}
