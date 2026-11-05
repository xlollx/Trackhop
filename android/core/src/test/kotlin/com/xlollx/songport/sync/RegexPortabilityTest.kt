package com.xlollx.songport.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android compiles regexes with ICU, stricter than the JVM these tests run on: a bare "{" or "}"
 * that is not a quantifier (`{2}`, `{1,3}`) or a property (`\p{L}`) passes here and throws there,
 * when the object holding it first loads (1.14.3–1.14.6: every Roast closed the app). This reads
 * every Regex("…") in the sources and fails on such a brace, so it is caught before a release.
 */
class RegexPortabilityTest {
    private val literal = Regex("Regex\\(\\s*(\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\")")

    @Test fun noBareBracesInRegexes() {
        val roots = listOf("src/main", "../app/src", "../bridge/src/main", "../../server/src/main").map(::File).filter { it.isDirectory }
        assertTrue("no sources found from ${File(".").absolutePath}", roots.isNotEmpty())
        val bad = ArrayList<String>()
        for (root in roots) root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            for (m in literal.findAll(text)) {
                val raw = m.groupValues[2].ifEmpty { m.groupValues[3].replace("\\\\", "\\") }
                val rest = raw
                    .replace(Regex("\\\\[{}]"), "")
                    .replace(Regex("\\\\p\\{\\w+\\}", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\{\\d+(,\\d*)?\\}"), "")
                    .replace(Regex("\\$\\{[^}]*\\}"), "")
                    .replace(Regex("\\[(?:\\\\.|[^\\]])*\\]"), "")
                if ('{' in rest || '}' in rest) bad += "${f.path}:${text.substring(0, m.range.first).count { it == '\n' } + 1}: $raw"
            }
        }
        assertTrue("Bare braces in regexes (escape them as \\{ \\}):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
