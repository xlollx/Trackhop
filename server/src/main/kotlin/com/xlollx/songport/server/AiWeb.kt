package com.xlollx.songport.server

import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiMatch
import com.xlollx.songport.providers.LocalFilesProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.server.Html.h
import com.xlollx.songport.sync.LibraryTools
import kotlinx.coroutines.runBlocking

/**
 * Tools › AI playlist, as the app's: the user's own AI writes a playlist from a description, and
 * Trackhop searches it on a service and creates it there, or saves it into File. No AI of Trackhop's:
 * the key is the user's (free tiers: OpenRouter's ":free" models, Groq, Gemini), or a local model
 * (Ollama, LM Studio) through an OpenAI-compatible address, which needs no key at all.
 */
internal fun Web.aiTool(r: Web.Req) {
    val config = AiClient.config(env)
    if (r.method == "POST") {
        when (r.form["action"]) {
            "settings" -> {
                val vendor = AiClient.Vendor.entries.firstOrNull { it.name == r.form["vendor"] } ?: AiClient.Vendor.OPENROUTER
                // An empty key field keeps the saved one: the page never shows it back.
                val key = r.form["key"].orEmpty().trim().ifEmpty { config?.takeIf { it.vendor == vendor }?.apiKey.orEmpty() }
                val c = AiClient.Config(vendor, key, r.form["model"].orEmpty().trim(), r.form["base"].orEmpty().trim())
                AiClient.save(env, c)
                val problem = if (c.model.isBlank()) null else runCatching { runBlocking { AiClient.check(c) } }.getOrElse { it.message }
                return redirect(r.ex, "/tools/ai?" + if (problem != null) "err=${q(problem)}" else "ok=${q("Saved.")}")
            }
            "forget" -> { AiClient.clear(env); return redirect(r.ex, "/tools/ai?ok=${q("Forgotten.")}") }
            "generate" -> {
                val c = config?.takeIf { it.complete } ?: return redirect(r.ex, "/tools/ai?err=${q("Set up your AI first.")}")
                val description = r.form["description"].orEmpty().trim().ifEmpty { return redirect(r.ex, "/tools/ai?err=${q("Describe the playlist.")}") }
                val count = r.form["count"]?.toIntOrNull()?.coerceIn(5, 200) ?: 30
                val name = r.form["name"].orEmpty().trim().ifEmpty { description.take(60) }
                val target = Providers.byId(r.form["target"].orEmpty())?.takeIf { it in aiTargets() }
                    ?: return redirect(r.ex, "/tools/ai?err=${q("Pick where to create it.")}")
                val prompt = AiClient.Prompt(description, count, emptyList(), "")
                val task = Tasks.start("AI playlist", "/tools/ai", { env.log("ai", it) }) { t ->
                    t.progress = "Asking ${c.vendor.label} (${c.model})…"
                    val tracks = if (target is LocalFilesProvider) AiMatch.fresh(AiClient.generateAll(c, prompt), emptyList())
                    else AiMatch.available(env, c, target, prompt, emptyList()) { found, want -> t.progress = "Found on ${target.label(env)}: $found of $want" }.found
                    if (tracks.isEmpty()) error("The AI gave nothing ${target.label(env)} has: try another description.")
                    t.progress = "Creating “$name”…"
                    val made = LibraryTools.createWith(env, target, name, description, tracks) { p -> t.progress = "Adding ${p.done}/${p.total}" }
                    env.log("ai", "${target.label(env)}: ${made.playlist.name}, ${made.added} tracks")
                    buildString {
                        append("""<div class="card ok"><b>${h(made.playlist.name)}</b> on ${h(target.label(env))}: ${made.added} tracks.</div>""")
                        if (made.failed.isNotEmpty()) append("""<div class="card err">Not added: ${h(made.failed.take(20).joinToString(", "))}</div>""")
                        append("<div class=\"card\"><ol>")
                        tracks.take(200).forEach { append("<li>${h(it.artistLine)} — ${h(it.title)}</li>") }
                        append("</ol></div>")
                        if (target is LocalFilesProvider) append("""<p><a href="/connectors/csv">Download it, or sync it to a service</a></p>""")
                    }
                }
                return redirect(r.ex, "/tools/task/${q(task.id)}")
            }
        }
    }
    val body = StringBuilder(notice(r))
    if (config?.complete == true) {
        val targets = aiTargets().joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append(
            """<form method="post" action="/tools/ai" class="card"><input type="hidden" name="action" value="generate">
<label>Describe the playlist</label><textarea name="description" rows="3" required placeholder="Rainy Sunday morning, acoustic, nothing too sad"></textarea>
<label>How many tracks</label><input type="text" name="count" value="30" inputmode="numeric">
<label>Name (optional)</label><input type="text" name="name">
<label>Create it on</label><select name="target">$targets</select>
<p class="muted">Only your description goes to ${h(config.vendor.label)} (${h(config.host)}). Its answer is searched on the service like any sync, so a track it made up is simply not found.</p>
<p><button>Write the playlist</button></p></form>""",
        )
    }
    val vendors = AiClient.Vendor.entries.joinToString("") { v ->
        """<option value="${v.name}"${if (v == (config?.vendor ?: AiClient.Vendor.OPENROUTER)) " selected" else ""}>${h(v.label)}${if (v.freeTier) " (free tier)" else ""}</option>"""
    }
    body.append(
        """<h2>Your AI</h2><form method="post" action="/tools/ai" class="card"><input type="hidden" name="action" value="settings">
<p class="muted">Trackhop has no AI of its own: use yours. Free ways: an <a href="https://openrouter.ai/keys">OpenRouter</a> key with a <code>:free</code> model, a <a href="https://console.groq.com/keys">Groq</a> key, a <a href="https://aistudio.google.com/apikey">Gemini</a> key; or a model on your own machine (Ollama, LM Studio): choose <i>OpenAI-compatible</i> and its address, no key needed. <a href="https://trackhop.org/guide/docker#ai-playlists">More</a></p>
<label>Service</label><select name="vendor">$vendors</select>
<label>Key${if (config?.apiKey?.isNotBlank() == true) " (saved: leave empty to keep it)" else ""}</label><input type="password" name="key" autocomplete="off">
<label>Model</label><input type="text" name="model" value="${h(config?.model)}" placeholder="for example meta-llama/llama-3.3-70b-instruct:free, llama-3.3-70b-versatile, gemini-2.0-flash, llama3.2">
<label>Address (only for OpenAI-compatible)</label><input type="text" name="base" value="${h(config?.baseUrl)}" placeholder="http://ollama:11434/v1">
<p><button class="secondary">Save and test</button></p></form>""",
    )
    if (config != null) body.append("<p>" + Html.postButton("/tools/ai", "Forget my AI settings", "danger", fields = mapOf("action" to "forget")) + "</p>")
    send(r.ex, 200, page("AI playlist", body.toString()))
}

/** Where an AI playlist can be created: services that make playlists, File first. */
private fun Web.aiTargets() = Providers.connectors()
    .filter { it.canWrite && it.canCreatePlaylists && (!it.requiresAuth || it.isConnected(env)) }
    .sortedBy { if (it is LocalFilesProvider) 0 else 1 }
