package com.xlollx.songport.server

import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.get
import com.xlollx.songport.net.int
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A GitHub issue made ready, never sent (POST /api/v1/report, `trackhop report`, MCP prepare_issue):
 * its text, with the report and the error's signature hidden in it as `<!-- trackhop-sig: … -->`,
 * and a link that opens it prefilled for the user to read and send. Before suggesting a new one,
 * one public search on GitHub (no account) for that signature: an open issue with it gets a
 * "happens to me too" comment instead; a closed one, an update when a newer version came out after
 * it was closed, else a comment on it. No answer from GitHub: just the new issue.
 */
object Issues {
    const val REPO = "xlollx/Trackhop"
    private const val LINK_BODY = 5_500

    /** GitHub's API (a stand-in in tests). */
    @Volatile internal var github = "https://api.github.com"

    /** An issue the search found: open or closed, and when it was closed ("2026-11-01T10:00:00Z"). */
    data class Found(val number: Int, val title: String, val url: String, val open: Boolean, val closedAt: String?)

    private fun q(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)

    private fun get(url: String): String? = runCatching {
        runBlocking { Http.send("GET", url, mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Trackhop"), maxRateLimitRetries = 0) }
    }.getOrNull()?.takeIf { it.code == 200 }?.body

    /** The issues whose text holds [sig], open ones first; null when GitHub did not answer. */
    fun search(sig: String): List<Found>? {
        val body = get("$github/search/issues?per_page=20&q=" + q("\"$sig\" repo:$REPO is:issue in:body")) ?: return null
        val json = runCatching { parseJson(body) }.getOrNull() ?: return null
        return json["items"].arr.filter { it["body"].str.orEmpty().contains("trackhop-sig: $sig") }.map {
            Found(it["number"].int ?: 0, it["title"].str.orEmpty(), it["html_url"].str.orEmpty(), it["state"].str == "open", it["closed_at"].str)
        }.sortedBy { !it.open }
    }

    /** The newest server release and when it came out, or null when GitHub did not answer. */
    private fun newestRelease(): Pair<String, String>? {
        val json = get("$github/repos/$REPO/releases?per_page=30")?.let { runCatching { parseJson(it) }.getOrNull() } ?: return null
        val newest = Updates.newest(json) ?: return null
        val at = json.arr.firstOrNull { it["tag_name"].str == "server-v${newest.version}" }?.get("published_at")?.str.orEmpty()
        return newest.version to at
    }

    /**
     * [report] cut so that, encoded in a link, it takes at most [max] characters: everything before
     * the log, then its newest lines that still fit (GitHub refuses links of more than about 8 KB).
     */
    internal fun forLink(report: String, max: Int): String {
        if (q(report).length <= max) return report
        val cut = report.indexOf("\nLog:\n")
        val head = (if (cut >= 0) report.substring(0, cut + 6) else report).let { h -> if (q(h).length <= max / 2) h else h.take(max / 6) + "\n  …\n" }
        if (cut < 0) return head
        var room = max - q(head).length - 120
        val kept = ArrayDeque<String>()
        val lines = report.substring(cut + 6).lines()
        for (l in lines.asReversed()) {
            val n = q(l).length + 3
            if (n > room) break
            kept.addFirst(l); room -= n
        }
        val left = lines.size - kept.size
        return head + (if (left > 0) "  ($left older lines left out)\n" else "") + kept.joinToString("\n")
    }

    /**
     * The issue for what went wrong since [sinceMs]: [title] and [description] are the user's (or
     * the assistant's) words, left out for the template's. Searches GitHub when an error was found.
     */
    fun prepare(env: ServerEnv, product: String, version: String, sinceMs: Long, names: Boolean, title: String?, description: String?): JsonObject {
        val number = version.substringBefore(' ')
        val report = Diagnostics.report(env, product, version, sinceMs, names)
        // The signature always from the error without names: the same for everyone it happens to.
        val error = Diagnostics.lastError(env, sinceMs, names = false)
        val sig = error?.let { Diagnostics.signature(it) }
        fun body(r: String) = "**$product $version**\n\n" +
            (description?.trim()?.takeIf { it.isNotEmpty() } ?: "What happened, and what you expected:\n\n") + "\n\n" +
            (error?.let { "Error:\n```\n$it\n```\n\n" } ?: "") +
            "<details><summary>Diagnostics</summary>\n\n```\n$r\n```\n</details>\n" +
            (sig?.let { "\n<!-- trackhop-sig: $it -->\n" } ?: "")
        val issueTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: ("$product $number: " + (error?.take(80) ?: ""))
        val full = body(report)
        val newUrl = "https://github.com/$REPO/issues/new?title=${q(issueTitle)}&labels=${q("server")}&body=${q(body(forLink(report, LINK_BODY)))}"
        val found = sig?.let { search(it) }
        val open = found?.firstOrNull { it.open }
        val closed = found?.firstOrNull { !it.open }
        val comment = "Happens to me too, on $product $number." + (error?.let { "\n\n```\n$it\n```" } ?: "")
        var suggestion = "new"
        var message = "Nothing was sent. Read the text, take out anything you would rather not share, then open the link to send it on GitHub."
        var latest: String? = null
        when {
            open != null -> {
                suggestion = "comment"
                message = "This error is already reported, in #${open.number} (${open.title}), still open: add a comment there instead of a new issue (the text is in \"comment\"). Nothing was sent."
            }
            closed != null -> {
                val rel = newestRelease()
                if (rel != null && Updates.newer(rel.first, number) && rel.second >= closed.closedAt.orEmpty()) {
                    suggestion = "update"; latest = rel.first
                    message = "This error was fixed in #${closed.number} (${closed.title}): update to $product ${rel.first}. If it still happens there, comment on that issue. Nothing was sent."
                } else {
                    suggestion = "comment_closed"
                    message = "This error was reported in #${closed.number} (${closed.title}), closed, but it still happens on this version: comment there with what you see (only its author or the maintainer can reopen it). Nothing was sent."
                }
            }
        }
        val issue = open ?: closed
        return buildJsonObject {
            put("suggestion", suggestion)
            put("message", message)
            put("title", issueTitle)
            put("body", full)
            put("newIssueUrl", newUrl)
            put("signature", sig?.let { JsonPrimitive(it) } ?: JsonNull)
            put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
            // Whether GitHub was asked and answered: false without an error to look for, or without network.
            put("searched", found != null)
            put("issue", issue?.let { i -> buildJsonObject { put("number", i.number); put("title", i.title); put("url", i.url); put("open", i.open) } } ?: JsonNull)
            put("commentUrl", issue?.takeIf { suggestion != "update" }?.let { JsonPrimitive(it.url + "#new_comment_field") } ?: JsonNull)
            put("comment", issue?.takeIf { suggestion != "update" }?.let { JsonPrimitive(comment) } ?: JsonNull)
            put("latest", latest?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }
}
