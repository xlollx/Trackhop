package com.xlollx.songport.ai

import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AiAnswerTest {
    private val cands = listOf(Track("1", "A", listOf("X")), Track("2", "B", listOf("X")))
    private val json = """{"match": 2, "queries": ["a b"], "note": "same song {remaster}"}"""

    private fun run(vararg answers: String): Pair<AiClient.Suggestion, Int> {
        var n = 0
        val s = runBlocking { AiClient.suggest(cands, "sys", "usr") { _, _ -> answers[n++.coerceAtMost(answers.size - 1)] } }
        return s to n
    }

    @Test fun plainJson() { assertEquals(2, run(json).first.match) }

    @Test fun textBeforeAndAfter() {
        val (s, calls) = run("Let's think step by step. The second one fits.\n$json\nHope this helps!")
        assertEquals(2, s.match); assertEquals(listOf("a b"), s.queries); assertEquals("same song {remaster}", s.note); assertEquals(1, calls)
    }

    @Test fun codeFenceAndBracesInProse() {
        assertEquals(2, run("Sure {here it is}:\n```json\n$json\n```").first.match)
    }

    @Test fun stricterSecondTryWhenFirstHasNoJson() {
        val (s, calls) = run("Let's think step by step... I would say the second.", json)
        assertEquals(2, s.match); assertEquals(2, calls)
    }

    @Test fun clearErrorWhenStillNoJson() {
        val e = assertThrows(ProviderException::class.java) { run("no json", "still none") }
        assert(e.message!!.contains("did not answer"))
    }

    @Test fun firstJsonFindsArraysAndSkipsBrokenStarts() {
        assertEquals(2, (AiClient.firstJson("Here: [ {\"a\":1}, {\"a\":2} ] done", '[') as kotlinx.serialization.json.JsonArray).size)
        assertNull(AiClient.firstJson("nothing {broken here", '{'))
    }
}
