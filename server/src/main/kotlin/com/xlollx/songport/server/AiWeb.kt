package com.xlollx.songport.server

import com.xlollx.songport.ai.AiClient
import com.xlollx.songport.ai.AiMatch
import com.xlollx.songport.ai.ArtistMix
import com.xlollx.songport.ai.ArtistMixSource
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
 * Above it, the generator with no key at all ([ArtistMixSource]): seed artists, typed or taken from
 * a playlist, and the artists and songs MusicBrainz and ListenBrainz relate to them.
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
            "mix" -> return mixGenerate(r)
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
    mixForm(r, body)
    body.append("<h2>${h(env.text("lb_mode_ai"))}</h2>")
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

/** Playlists the keyless generator can start from: any service that lists playlists, File included. */
private fun Web.mixSources() = Providers.connectors()
    .filter { (!it.requiresAuth || it.isConnected(env)) && !it.linkOnly }

/** "No key needed": seed artists (or a playlist of mine, its service chosen first), length, leaning, destination. */
private fun Web.mixForm(r: Web.Req, body: StringBuilder) {
    val sources = mixSources()
    val from = r.query["mixfrom"]?.let { id -> sources.firstOrNull { it.id == id } }
    val playlists = from?.let { s -> runCatching { runBlocking { s.playlists(env) } } }
    playlists?.exceptionOrNull()?.let { body.append("""<div class="card err">${h(from.label(env))}: ${h(it.message)}</div>""") }
    val targets = aiTargets().joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
    val lengths = ArtistMix.LENGTHS.joinToString("") { """<option value="$it"${if (it == 25) " selected" else ""}>$it</option>""" }
    val leanings = listOf(ArtistMix.Leaning.FAMILIAR to "lb_familiar", ArtistMix.Leaning.BALANCED to "lb_balanced", ArtistMix.Leaning.DISCOVERY to "lb_discovery")
        .joinToString("") { (l, k) -> """<option value="${l.name}"${if (l == ArtistMix.Leaning.BALANCED) " selected" else ""}>${h(env.text(k))}</option>""" }
    body.append("<h2>${h(env.text("lb_mode_keyless"))}</h2>")
    body.append("""<form method="post" action="/tools/ai" class="card"><input type="hidden" name="action" value="mix">
<p class="muted">${h(env.text("lb_desc"))}</p>""")
    if (from != null) {
        val opts = playlists?.getOrNull().orEmpty().joinToString("") { p -> """<option value="${h(p.id)}">${h(p.name)}${if (p.trackCount >= 0) " (${p.trackCount})" else ""}</option>""" }
        body.append("""<input type="hidden" name="service" value="${h(from.id)}">
<label>${h(env.text("lb_seed_from_playlist"))}: ${h(from.label(env))}</label><select name="playlist" required>$opts</select>
<p class="muted">${h(env.text("lb_playlist_hint"))} <a href="/tools/ai">${h(env.text("lb_seed_from_artists"))}</a></p>""")
    } else {
        body.append("""<label>${h(env.text("lb_seed_artists"))}</label><textarea name="artists" rows="2" required placeholder="Radiohead, Björk, Massive Attack"></textarea>
<p class="muted">${h(env.text("lb_seed_artists_hint"))}</p>""")
    }
    body.append("""<label>${h(env.text("lb_length"))}</label><select name="count">$lengths</select>
<label>${h(env.text("lb_leaning"))}</label><select name="leaning">$leanings</select>
<p class="muted">${h(env.text("lb_leaning_hint"))}</p>
<label>Name (optional)</label><input type="text" name="name">
<label>Create it on</label><select name="target">$targets</select>
<p><button>${h(env.text("ai_generate"))}</button></p></form>""")
    if (from == null && sources.isNotEmpty()) {
        val opts = sources.joinToString("") { """<option value="${h(it.id)}">${h(it.label(env))}</option>""" }
        body.append("""<form method="get" action="/tools/ai" class="card"><label>${h(env.text("lb_seed_from_playlist"))}</label><select name="mixfrom">$opts</select>
<p><button class="secondary">${h(env.text("lb_playlist_pick"))}</button></p></form>""")
    }
}

/** The keyless generator's run: the mix, searched on the destination, created there (or saved into File). */
private fun Web.mixGenerate(r: Web.Req): Unit {
    val count = r.form["count"]?.toIntOrNull()?.coerceIn(5, 200) ?: 25
    val leaning = ArtistMix.Leaning.of(r.form["leaning"])
    val source = r.form["service"]?.let { id -> mixSources().firstOrNull { it.id == id } }
    val playlistId = r.form["playlist"].orEmpty().takeIf { source != null }.orEmpty()
    val typed = ArtistMix.parseSeeds(r.form["artists"].orEmpty())
    if (playlistId.isEmpty() && typed.isEmpty()) return redirect(r.ex, "/tools/ai?err=${q(env.text("lb_seed_needed"))}")
    val target = Providers.byId(r.form["target"].orEmpty())?.takeIf { it in aiTargets() }
        ?: return redirect(r.ex, "/tools/ai?err=${q("Pick where to create it.")}")
    val typedName = r.form["name"].orEmpty().trim()
    val task = Tasks.start(env.text("ai_title"), "/tools/ai", { env.log("mix", it) }) { t ->
        // Only a playlist the service lists: an id is never taken as typed.
        val exclude = if (source != null && playlistId.isNotEmpty()) {
            val p = source.playlists(env).firstOrNull { it.id == playlistId } ?: error("No such playlist on ${source.label(env)}.")
            source.tracks(env, p.id)
        } else emptyList()
        val seeds = if (source != null && playlistId.isNotEmpty()) ArtistMix.topArtists(exclude) else typed
        if (seeds.isEmpty()) error(env.text("lb_playlist_empty"))
        val mix = ArtistMixSource.generate(env, ArtistMixSource.Request(seeds, count, leaning, exclude)) { step, done, total ->
            t.progress = when (step) {
                ArtistMixSource.Step.ARTISTS -> env.text("lb_step_artists", done, total)
                ArtistMixSource.Step.SIMILAR -> env.text("lb_step_similar", done, total)
                else -> env.text("lb_step_tracks", done, total)
            }
        }
        val label = mix.seeds.take(2).joinToString(", ") { it.name }
        val name = typedName.ifEmpty { env.text("lb_default_name", label) }
        val tracks = if (target is LocalFilesProvider) mix.tracks.take(count)
        else ArtistMixSource.onService(env, target, mix.tracks, count, exclude) { found, want -> t.progress = "Found on ${target.label(env)}: $found of $want" }.found
        if (tracks.isEmpty()) error(env.text("lb_nothing"))
        t.progress = "Creating “$name”…"
        val made = LibraryTools.createWith(env, target, name, env.text("lb_default_name", mix.seeds.joinToString(", ") { it.name }), tracks) { p -> t.progress = "Adding ${p.done}/${p.total}" }
        env.log("mix", "${target.label(env)}: ${made.playlist.name}, ${made.added} tracks")
        buildString {
            append("""<div class="card ok"><b>${h(made.playlist.name)}</b> on ${h(target.label(env))}: ${made.added} tracks.</div>""")
            if (mix.unknown.isNotEmpty()) append("""<div class="card">${h(env.text("lb_unknown_some", mix.unknown.joinToString(", ")))}</div>""")
            if (made.failed.isNotEmpty()) append("""<div class="card err">Not added: ${h(made.failed.take(20).joinToString(", "))}</div>""")
            append("<div class=\"card\"><ol>")
            tracks.take(200).forEach { append("<li>${h(it.artistLine)} — ${h(it.title)}</li>") }
            append("</ol></div>")
            if (target is LocalFilesProvider) append("""<p><a href="/connectors/csv">Download it, or sync it to a service</a></p>""")
        }
    }
    redirect(r.ex, "/tools/task/${q(task.id)}")
}

/** Where an AI playlist can be created: services that make playlists, File first. */
private fun Web.aiTargets() = Providers.connectors()
    .filter { it.canWrite && it.canCreatePlaylists && (!it.requiresAuth || it.isConnected(env)) }
    .sortedBy { if (it is LocalFilesProvider) 0 else 1 }
