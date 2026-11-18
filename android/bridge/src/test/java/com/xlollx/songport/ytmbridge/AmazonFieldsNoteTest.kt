package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page configuration in the log: its field names once per run of the app, then only a count; never a value. */
class AmazonFieldsNoteTest {
    private fun config(json: String) = AmazonClient.Config(parseJson(json) as JsonObject)
    private val full = config("""{"accessToken":"Atna|secret","customerId":"A1SECRET","deviceId":"137","deviceType":"A16Z","sessionId":"262-1",
        "csrf":{"token":"tokSECRET","ts":1,"rnd":"2"},"ageBand":"x","benefits":["prime"]}""")

    @Test fun theNamesTheFirstTimeOnly() {
        val first = AmazonClient.fieldsNote(full, first = true)
        assertTrue(first, first.startsWith("configuration fields: accessToken,ageBand,benefits,csrf,"))
        assertEquals("8 configuration fields, the needed ones present", AmazonClient.fieldsNote(full, first = false))
    }

    @Test fun whatIsMissingIsNamed() {
        val note = AmazonClient.fieldsNote(config("""{"customerId":"A1SECRET","deviceType":"A16Z"}"""), first = false)
        assertEquals("2 configuration fields, missing: deviceId,sessionId,csrf", note)
    }

    @Test fun neverAValue() {
        for (first in listOf(true, false)) {
            val note = AmazonClient.fieldsNote(full, first)
            listOf("secret", "SECRET", "Atna", "prime", "262-1").forEach { assertFalse(note, note.contains(it)) }
        }
    }
}
