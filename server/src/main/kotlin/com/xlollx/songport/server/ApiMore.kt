package com.xlollx.songport.server

import com.sun.net.httpserver.HttpExchange
import com.xlollx.songport.data.ConfigBackup
import com.xlollx.songport.model.Schedule
import com.xlollx.songport.model.Track
import com.xlollx.songport.providers.MusicProvider
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.sync.CsvCodec
import com.xlollx.songport.sync.LibraryTools
import com.xlollx.songport.sync.Matcher
import com.xlollx.songport.sync.NamePatterns
import com.xlollx.songport.sync.PlaylistFiles
import com.xlollx.songport.sync.TrackLinks
import com.xlollx.songport.sync.UsageStats
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * The rest of the page through /api/v1: the File playlists (delete, a .zip of several, how names in
 * imported files are read, #49), the set-up file brought back in, the webhook's settings, the
 * notification channels, the settings the page offers, a search on a service, a track carried from
 * one service to another, the Tools (duplicates, export, numbers), the background tasks and, for the
 * admin, the log. Each with the page's own checks and permissions.
 */
internal fun Web.apiMore(ex: HttpExchange, method: String, rest: List<String>, query: Map<String, String>, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit): Boolean {
    fun body(): JsonObject? = apiJson(ex)
    fun badJson() = error(400, "Send JSON (see GET /api/v1/openapi.json).")
    when {
        // ---------------------------------------------------------------- files
        // Before files/<name>: the playlist files all end in .csv, so these names are never one.
        rest == listOf("files", "settings") && method == "GET" -> json(200, fileSettings())
        rest == listOf("files", "settings") && (method == "PUT" || method == "PATCH") -> {
            val b = body() ?: return true.also { badJson() }
            val patterns = (b["patterns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: b.text("patterns")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
            patterns?.forEachIndexed { i, l -> NamePatterns.compile(l).exceptionOrNull()?.let { return true.also { _ -> error(400, "Pattern ${i + 1}: ${it.message}") } } }
            val layout = b.text("layout")?.uppercase()?.let { l ->
                PlaylistFiles.NameLayout.entries.firstOrNull { it.name == l } ?: return true.also { error(400, "\"layout\" is one of ${PlaylistFiles.NameLayout.entries.joinToString(", ") { it.name }}.") }
            }
            if (layout == PlaylistFiles.NameLayout.CUSTOM && (patterns ?: env.store.data.settings.fileNamePatterns).isEmpty()) return true.also { error(400, "CUSTOM needs at least one pattern.") }
            env.store.updateSettings { s -> s.copy(fileNameLayout = layout?.name ?: s.fileNameLayout, fileNamePatterns = patterns ?: s.fileNamePatterns) }
            json(200, fileSettings())
        }
        rest == listOf("files", "try") && method == "POST" -> {
            val b = body() ?: return true.also { badJson() }
            val sample = b.text("sample") ?: return true.also { error(400, "\"sample\" is needed: a line or a path from a playlist file.") }
            val lines = (b["patterns"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                ?: b.text("patterns")?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: env.store.data.settings.fileNamePatterns
            lines.forEachIndexed { i, l -> NamePatterns.compile(l).exceptionOrNull()?.let { return true.also { _ -> error(400, "Pattern ${i + 1}: ${it.message}") } } }
            // Tried only, never saved: PUT files/settings saves.
            val read = NamePatterns.read(sample, NamePatterns.compileAll(lines))
            json(200, buildJsonObject {
                put("matched", read != null)
                read?.let { put("artist", it.artist); put("album", it.album); put("title", it.title) }
            })
        }
        rest == listOf("files", "zip") && method == "POST" -> {
            val b = body() ?: return true.also { badJson() }
            val names = (b["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            val files = csvFiles().filter { names == null || it.name in names || it.nameWithoutExtension in names }
            if (files.isEmpty()) return true.also { error(404, "None of those files is here: see GET /api/v1/files.") }
            ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"Trackhop playlists.zip\"")
            sendBytes(ex, 200, zipOf(files, ExportFormat.of(b.text("format"))), "application/zip")
        }
        rest.size == 2 && rest[0] == "files" && method == "DELETE" -> {
            val f = csvFiles().firstOrNull { it.name == rest[1] || it.nameWithoutExtension == rest[1] } ?: return true.also { error(404, "No such file.") }
            f.delete()
            env.log("files", "deleted ${f.name} through the API")
            json(200, buildJsonObject { put("deleted", f.name) })
        }
        // ---------------------------------------------------------------- set-up
        rest == listOf("config") && method == "POST" -> {
            val bytes = ex.requestBody.readNBytes(20_000_001)
            if (bytes.size > 20_000_000) return true.also { error(413, "Files up to 20 MB.") }
            val text = String(bytes, Charsets.UTF_8)
            val snapshot = runCatching { ConfigBackup.read(text) }.getOrElse { return true.also { _ -> error(400, it.message ?: "Not a Trackhop configuration file.") } }
            val channels = Notify.inBackup(text)
            val s = ConfigBackup.summary(snapshot)
            fun summary(imported: Boolean) = buildJsonObject {
                put("imported", imported)
                put("writtenBy", s.writtenBy); put("writtenAt", s.writtenAt)
                put("syncs", s.syncs); put("matches", s.matches); put("files", s.files); put("runs", s.runs); put("channels", channels.size)
            }
            // A dry run says what the file holds, as the page does before its Import button.
            if (query["dryRun"] == "true") return true.also { json(200, summary(false)) }
            ConfigBackup.import(env, snapshot)
            Notify.restore(env, channels)
            env.log("config", "imported ${s.syncs} syncs, ${s.files} files from ${s.writtenBy} through the API")
            json(200, summary(true))
        }
        // ---------------------------------------------------------------- webhook
        rest == listOf("webhook") && method == "GET" -> json(200, webhookJson())
        rest == listOf("webhook") && (method == "PUT" || method == "PATCH") -> {
            val b = body() ?: return true.also { badJson() }
            val url = b.text("url") ?: if (method == "PATCH") Api.webhook(env) else ""
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) return true.also { error(400, "The address starts with http:// or https://.") }
            val events = (b["events"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            events?.firstOrNull { it !in Api.EVENTS }?.let { return true.also { _ -> error(400, "Unknown event $it: ${Api.EVENTS.joinToString(", ")}.") } }
            Api.setWebhook(env, url, b.text("secret"), events)
            json(200, webhookJson())
        }
        rest == listOf("webhook") && method == "DELETE" -> {
            Api.setWebhook(env, "", null)
            json(200, webhookJson())
        }
        // ---------------------------------------------------------------- notification channels
        rest == listOf("notifications", "kinds") && method == "GET" -> json(200, buildJsonObject {
            put("events", JsonArray(Notify.Event.entries.map { e -> buildJsonObject { put("key", e.key); put("label", e.label) } }))
            put("kinds", JsonArray(Notify.Kind.entries.map { k ->
                buildJsonObject {
                    put("id", k.id); put("name", k.label); put("help", k.help)
                    put("settings", JsonArray(k.fields.map { f ->
                        buildJsonObject { put("key", f.key); put("label", f.label); put("secret", f.secret); put("optional", f.optional); put("default", f.default); put("address", f.url) }
                    }))
                }
            }))
        })
        rest == listOf("notifications") && method == "POST" -> saveChannel(null, body(), json, error)
        rest.size == 2 && rest[0] == "notifications" && method == "PATCH" -> {
            val old = Notify.channel(env, rest[1]) ?: return true.also { error(404, "No notification channel with that id.") }
            saveChannel(old, body(), json, error)
        }
        rest.size == 2 && rest[0] == "notifications" && method == "DELETE" -> {
            val c = Notify.channel(env, rest[1]) ?: return true.also { error(404, "No notification channel with that id.") }
            Notify.remove(env, c.id)
            env.log("notify", "removed ${c.name} (${c.kind.label}) through the API")
            json(200, buildJsonObject { put("deleted", c.id) })
        }
        // ---------------------------------------------------------------- settings
        rest == listOf("settings") && method == "GET" -> json(200, settingsJson())
        rest == listOf("settings") && (method == "PATCH" || method == "PUT") -> {
            val b = body() ?: return true.also { badJson() }
            b.text("theme")?.let { t -> if (Theme.ALL.none { it.first == t }) return true.also { error(400, "\"theme\" is one of ${Theme.ALL.joinToString(", ") { it.first }}.") } }
            val schedule = b.text("backupSchedule")?.uppercase()?.let { s ->
                Schedule.entries.firstOrNull { it.name == s && it in listOf(Schedule.MANUAL, Schedule.DAILY, Schedule.WEEKLY) }
                    ?: return true.also { error(400, "\"backupSchedule\" is manual, daily or weekly.") }
            }
            val folder = if (b.containsKey("backupFolder")) b.text("backupFolder").orEmpty() else null
            folder?.takeIf { it.isNotEmpty() }?.let { f -> backupFolderProblem(f)?.let { return true.also { _ -> error(400, it) } } }
            val updates = b.flag("updatesCheck")
            if (updates != null && !seesUpdates()) return true.also { error(403, "The update check is the admin's.") }
            b.text("theme")?.let { Theme.set(env, it) }
            env.store.updateSettings { s -> s.copy(backupSchedule = schedule ?: s.backupSchedule, backupFolder = folder ?: s.backupFolder) }
            updates?.let { Updates.setEnabled(it) }
            json(200, settingsJson())
        }
        // ---------------------------------------------------------------- services
        rest.size == 2 && rest[0] == "services" && method == "GET" -> {
            val p = Providers.byId(rest[1]) ?: return true.also { error(404, "No service with that id: see GET /api/v1/services.") }
            json(200, serviceJson(p))
        }
        rest.size == 3 && rest[0] == "services" && rest[2] == "search" && method == "GET" -> {
            val p = connected(rest[1], error) ?: return true
            val q = query["q"]?.trim().orEmpty().ifEmpty { return true.also { error(400, "Say what to look for: ?q=Artist - Title (or a link).") } }
            val limit = (query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
            val found = runCatching {
                runBlocking {
                    val asked = ApiPlaylists.asWanted(JsonPrimitive(q)) ?: JsonObject(emptyMap())
                    val src = ApiPlaylists.source(env, asked) ?: Track(id = "", title = q)
                    p.search(env, src).map { it to Matcher.score(src, it) }.sortedByDescending { it.second }.take(limit)
                }
            }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
            json(200, JsonArray(found.map { (t, score) -> buildJsonObject { ApiPlaylists.track(t).forEach { (k, v) -> put(k, v) }; put("score", score) } }))
        }
        rest.size == 6 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "duplicates" && rest[5] == "remove" && method == "POST" -> {
            val p = connected(rest[1], error) ?: return true
            if (!p.canRemoveTracks) return true.also { error(405, "${p.label(env)} does not let apps remove tracks.") }
            val playlist = rest[3]
            val task = Tasks.start("Remove duplicates", "/tools/dedupe?service=${q(p.id)}&playlist=${q(playlist)}", { env.log("dedupe", it) }) { t ->
                val n = LibraryTools.removeDuplicates(env, p, playlist) { pr -> t.progress = "${pr.done}/${pr.total}" }
                env.log("dedupe", "${p.label(env)} / $playlist: $n removed")
                "<div class=\"card ok\">Removed $n extra copies.</div>"
            }
            json(202, buildJsonObject { put("task", task.id) })
        }
        rest.size == 5 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "duplicates" && method == "GET" -> {
            val p = connected(rest[1], error) ?: return true
            val groups = runCatching { runBlocking { LibraryTools.findDuplicates(env, p, rest[3]) } }
                .getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
            json(200, buildJsonObject {
                put("extraCopies", groups.sumOf { it.size - 1 })
                put("canRemove", p.canRemoveTracks)
                put("groups", JsonArray(groups.map { g -> buildJsonObject { put("track", trackRef(g.first())); put("copies", g.size); put("ids", buildJsonArray { g.forEach { add(it.id) } }) } }))
            })
        }
        rest.size == 5 && rest[0] == "services" && rest[2] == "playlists" && rest[4] == "export" && method == "GET" -> {
            val p = connected(rest[1], error) ?: return true
            val format = ExportFormat.of(query["format"])
            val (name, tracks) = runCatching {
                runBlocking {
                    val name = (p.libraryEntries(env, false) + p.playlists(env)).firstOrNull { it.id == rest[3] }?.name ?: rest[3]
                    name to p.tracks(env, rest[3])
                }
            }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
            val file = name.replace(Regex("""[\\/:*?"<>|]"""), "_") + "." + format.ext
            ex.responseHeaders.set("Content-Disposition", "attachment; filename=\"${file.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")}\"")
            send(ex, 200, format.encode(name, tracks), format.mime)
        }
        rest == listOf("convert") && (method == "GET" || method == "POST") -> {
            val b = if (method == "POST") body() ?: return true.also { badJson() } else JsonObject(emptyMap())
            val to = (b.text("to") ?: query["to"]) ?: return true.also { error(400, "Say on which service: \"to\" (see GET /api/v1/services).") }
            val p = connected(to, error) ?: return true
            val asked: List<JsonElement> = (b["tracks"] as? JsonArray)?.toList()
                ?: listOfNotNull((b["track"] ?: query["track"]?.let { JsonPrimitive(it) }))
            if (asked.isEmpty()) return true.also { error(400, "Say which track: ?track=Artist - Title (or a link), or {\"tracks\": […]}.") }
            val answers = runCatching {
                runBlocking {
                    asked.mapNotNull { ApiPlaylists.asWanted(it) }.map { o ->
                        val src = ApiPlaylists.source(env, o)
                        val hit = src?.let { s -> Matcher.bestScored(s, p.search(env, s)) }
                        buildJsonObject {
                            put("asked", ApiPlaylists.describe(o))
                            put("source", src?.let { trackRef(it) } ?: JsonNull)
                            put("match", hit?.let { ApiPlaylists.track(it.track) } ?: JsonNull)
                            put("score", hit?.score?.let { JsonPrimitive(it) } ?: JsonNull)
                        }
                    }
                }
            }.getOrElse { return true.also { _ -> error(502, "${p.label(env)}: ${it.message ?: it.javaClass.simpleName}") } }
            json(200, if (method == "GET" && b["tracks"] == null) answers.first() else JsonArray(answers))
        }
        // ---------------------------------------------------------------- tools and the rest
        rest == listOf("stats") && method == "GET" -> json(200, statsJson())
        rest == listOf("tasks") && method == "GET" -> json(200, JsonArray(Tasks.all().map { t ->
            buildJsonObject {
                put("id", t.id); put("title", t.title); put("done", t.done); put("progress", t.progress)
                put("error", t.error?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }))
        rest == listOf("log") && method == "GET" -> {
            // One log for the whole server: other people's lines are in it, so it is the admin's.
            if (!env.may(Permissions.ADMIN)) return true.also { error(403, "The log is the admin's.") }
            val n = (query["lines"]?.toIntOrNull() ?: 200).coerceIn(1, 2000)
            json(200, JsonArray(Log.recent().takeLast(n).map { JsonPrimitive(it) }))
        }
        else -> return false
    }
    return true
}

private fun Web.connected(id: String, error: (Int, String) -> Unit): MusicProvider? {
    val p = Providers.byId(id) ?: return null.also { error(404, "No service with that id: see GET /api/v1/services.") }
    if (p.requiresAuth && !p.isConnected(env)) return null.also { error(409, "${p.label(env)} is not connected.") }
    return p
}

internal fun Web.serviceJson(p: MusicProvider) = buildJsonObject {
    put("id", p.id); put("name", p.label(env))
    put("serviceId", p.serviceId); put("slot", p.slot)
    put("connected", !p.requiresAuth || p.isConnected(env))
    put("account", p.accountName(env)?.let { JsonPrimitive(it) } ?: JsonNull)
    // What it can do: a script asks before it tries.
    put("canWrite", p.canWrite); put("canCreatePlaylists", p.canCreatePlaylists); put("canRenamePlaylists", p.canRenamePlaylists)
    put("canDeletePlaylists", p.canDeletePlaylists); put("canRemoveTracks", p.canRemoveTracks); put("linkOnly", p.linkOnly)
}

private fun Web.fileSettings() = buildJsonObject {
    put("layout", PlaylistFiles.NameLayout.of(env.store.data.settings.fileNameLayout).name)
    put("layouts", buildJsonArray { PlaylistFiles.NameLayout.entries.forEach { add(it.name) } })
    put("patterns", buildJsonArray { env.store.data.settings.fileNamePatterns.forEach { add(it) } })
}

private fun Web.webhookJson() = buildJsonObject {
    put("url", Api.webhook(env))
    // Whether one is set, never the secret itself.
    put("secret", Api.hasSecret(env))
    put("events", buildJsonArray { Api.EVENTS.filter { it in Api.events(env) }.forEach { add(it) } })
    put("available", buildJsonArray { Api.EVENTS.forEach { add(it) } })
}

private fun Web.settingsJson() = buildJsonObject {
    val s = env.store.data.settings
    put("theme", Theme.of(env))
    put("backupSchedule", s.backupSchedule.name.lowercase())
    if (env.may(Permissions.FOLDERS)) put("backupFolder", s.backupFolder)
    put("fileNameLayout", PlaylistFiles.NameLayout.of(s.fileNameLayout).name)
    put("fileNamePatterns", buildJsonArray { s.fileNamePatterns.forEach { add(it) } })
    if (seesUpdates()) put("updatesCheck", Updates.enabled)
}

/** Why [folder] cannot take the backups' copies, or null when it can (the page's own checks). */
internal fun Web.backupFolderProblem(folder: String): String? {
    if (!java.io.File(folder).isDirectory) return "$folder is not a folder here."
    if (!env.may(Permissions.FOLDERS)) return "Folders on the server are not turned on for you: ask the admin."
    // On a server shared by several people, nobody copies files into the others' part of the data volume.
    if (sharedRoot != null) {
        val f = java.io.File(folder).canonicalFile
        if (f.startsWith(sharedRoot.canonicalFile) && !f.startsWith(env.dataDir.canonicalFile)) return "$folder is part of Trackhop's own data: choose another folder, such as /backups/${account?.name ?: "you"}."
    }
    return null
}

private fun Web.saveChannel(old: Notify.Channel?, b: JsonObject?, json: (Int, JsonElement) -> Unit, error: (Int, String) -> Unit) {
    if (b == null) return error(400, "Send JSON: {\"kind\": \"ntfy\", \"name\": \"…\", \"settings\": {…}} (see GET /api/v1/notifications/kinds).")
    val kind = (b.text("kind")?.let { Notify.Kind.of(it) ?: return error(400, "Unknown kind: see GET /api/v1/notifications/kinds.") }) ?: old?.kind
        ?: return error(400, "\"kind\" is needed: see GET /api/v1/notifications/kinds.")
    val events = (b["events"] as? JsonArray)?.map { e ->
        Notify.Event.of((e as? JsonPrimitive)?.contentOrNull) ?: return error(400, "Unknown event: ${Notify.Event.entries.joinToString(", ") { it.key }}.")
    }?.toSet() ?: old?.events ?: Notify.Event.entries.toSet()
    val given = (b["settings"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
    // A change keeps what it does not name; a secret left out keeps its value (Notify.build).
    val values = kind.fields.associate { f -> f.key to (given[f.key] ?: if (f.secret) "" else old?.takeIf { it.kind == kind }?.value(f.key) ?: f.default) }
    val c = Notify.build(old?.id ?: Notify.newId(), kind, b.text("name") ?: old?.name ?: kind.label, b.flag("enabled") ?: old?.enabled ?: true, events, values, old)
        .getOrElse { return error(400, it.message ?: "Not saved.") }
    Notify.put(env, c)
    env.log("notify", "${if (old == null) "added" else "changed"} ${c.name} (${c.kind.label}) through the API")
    json(if (old == null) 201 else 200, Notify.shown(c))
}

private fun Web.statsJson() = buildJsonObject {
    val s = UsageStats.compute(env.store.data, System.currentTimeMillis(), ZoneId.systemDefault())
    put("runs", s.runs); put("since", s.sinceEpoch); put("historyCapped", s.historyCapped)
    put("added", s.added); put("addedThisMonth", s.addedThisMonth); put("syncs", s.syncs)
    put("secondsSaved", s.secondsSaved)
    put("matchFound", s.matchFound); put("matchSearched", s.matchSearched)
    put("matchPercent", s.matchPercent?.let { JsonPrimitive(it) } ?: JsonNull)
    put("busiestRoute", s.route?.let { r -> buildJsonObject { put("source", r.sourceProvider); put("target", r.targetProvider); put("runs", r.runs); put("added", r.added) } } ?: JsonNull)
    put("biggestPlaylist", s.longest?.let { l -> buildJsonObject { put("syncId", l.jobId); put("name", l.name); put("tracks", l.tracks) } } ?: JsonNull)
    put("topArtists", JsonArray(s.topArtists.map { a -> buildJsonObject { put("name", a.name); put("tracks", a.tracks) } }))
}

/** [files] in one .zip, each in [format], names made unique. */
internal fun zipOf(files: List<java.io.File>, format: ExportFormat): ByteArray {
    val bytes = java.io.ByteArrayOutputStream()
    java.util.zip.ZipOutputStream(bytes).use { zip ->
        val used = HashSet<String>()
        files.forEach { f ->
            val text = if (format == ExportFormat.CSV) f.readText() else format.encode(f.nameWithoutExtension, CsvCodec.parse(f.readText()))
            var entry = "${f.nameWithoutExtension}.${format.ext}"
            var n = 2
            while (!used.add(entry)) entry = "${f.nameWithoutExtension} (${n++}).${format.ext}"
            zip.putNextEntry(java.util.zip.ZipEntry(entry))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}
